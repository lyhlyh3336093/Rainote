package com.ruoyi.system.agent.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Agent 速率限制器（JVM 内存滑动窗口）。
 * <p>
 * 限制维度：
 * <ul>
 *   <li>每用户每分钟最多 {@value #MAX_PER_MINUTE} 次 /agent/chat</li>
 *   <li>每用户每日最多 {@value #MAX_PER_DAY} 次 /agent/chat</li>
 * </ul>
 * 超限返回 429。MVP 单实例下内存计数准确；多实例部署时需升级为 Redis 滑动窗口。
 */
@Component
public class AgentRateLimiter
{
    private static final Logger log = LoggerFactory.getLogger(AgentRateLimiter.class);

    /** 每用户每分钟最大请求数 */
    public static final int MAX_PER_MINUTE = 10;

    /** 每用户每日最大请求数 */
    public static final int MAX_PER_DAY = 200;

    /** 滑动窗口长度（毫秒） */
    private static final long WINDOW_MS = 60_000;

    /** 每用户请求时间戳队列（分钟窗口） */
    private final ConcurrentHashMap<Long, Deque<Long>> minuteWindows = new ConcurrentHashMap<>();

    /** 每用户日计数器 */
    private final ConcurrentHashMap<Long, DailyCounter> dailyCounters = new ConcurrentHashMap<>();

    private ScheduledExecutorService cleanupExecutor;

    @PostConstruct
    public void init()
    {
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r ->
        {
            Thread t = new Thread(r, "agent-ratelimit-cleanup");
            t.setDaemon(true);
            return t;
        });
        // 每 5 分钟清理一次过期的分钟窗口条目
        cleanupExecutor.scheduleAtFixedRate(this::cleanupStaleEntries,
                5, 5, TimeUnit.MINUTES);
    }

    /**
     * 尝试获取请求许可。
     *
     * @param userId 用户 ID
     * @return true=允许请求，false=超限（应返回 429）
     */
    public boolean tryAcquire(Long userId)
    {
        if (userId == null) return false;

        long now = System.currentTimeMillis();

        // 1. 检查分钟窗口
        Deque<Long> window = minuteWindows.computeIfAbsent(userId, k -> new ConcurrentLinkedDeque<>());
        // 清除过期时间戳（超过 1 分钟）
        while (!window.isEmpty() && now - window.peekFirst() > WINDOW_MS)
        {
            window.pollFirst();
        }
        if (window.size() >= MAX_PER_MINUTE)
        {
            log.warn("速率限制（分钟）: userId={}, 当前窗口请求数={}", userId, window.size());
            return false;
        }

        // 2. 检查日限制
        DailyCounter counter = dailyCounters.computeIfAbsent(userId, k -> new DailyCounter());
        if (!counter.canIncrement(now, MAX_PER_DAY))
        {
            log.warn("速率限制（日）: userId={}, 今日请求数={}", userId, counter.getCount(now));
            return false;
        }

        // 3. 获取许可
        window.addLast(now);
        counter.increment(now);
        return true;
    }

    /**
     * 获取用户当前剩余配额信息（供前端展示）。
     */
    public RateLimitInfo getRateLimitInfo(Long userId)
    {
        if (userId == null) return new RateLimitInfo(0, MAX_PER_MINUTE, 0, MAX_PER_DAY);

        long now = System.currentTimeMillis();
        Deque<Long> window = minuteWindows.get(userId);
        int minuteUsed = 0;
        if (window != null)
        {
            Iterator<Long> it = window.iterator();
            while (it.hasNext())
            {
                if (now - it.next() <= WINDOW_MS) minuteUsed++;
            }
        }

        DailyCounter counter = dailyCounters.get(userId);
        int dayUsed = counter != null ? counter.getCount(now) : 0;

        return new RateLimitInfo(minuteUsed, MAX_PER_MINUTE, dayUsed, MAX_PER_DAY);
    }

    /**
     * 清理过期的分钟窗口和日计数器条目，防止内存泄漏。
     */
    private void cleanupStaleEntries()
    {
        try
        {
            long now = System.currentTimeMillis();
            // 清理空窗口
            minuteWindows.entrySet().removeIf(e ->
            {
                Deque<Long> w = e.getValue();
                while (!w.isEmpty() && now - w.peekFirst() > WINDOW_MS) w.pollFirst();
                return w.isEmpty();
            });
            // 清理非今日的日计数器
            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            dailyCounters.entrySet().removeIf(e ->
                    e.getValue().getDate().isBefore(today));
        }
        catch (Exception e)
        {
            log.error("速率限制器清理异常", e);
        }
    }

    /**
     * 日计数器（按自然日重置）。
     */
    static class DailyCounter
    {
        private volatile LocalDate date;
        private volatile int count;

        synchronized boolean canIncrement(long timestamp, int maxPerDay)
        {
            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            if (date == null || !today.equals(date))
            {
                date = today;
                count = 0;
            }
            return count < maxPerDay;
        }

        synchronized void increment(long timestamp)
        {
            count++;
        }

        synchronized int getCount(long timestamp)
        {
            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            if (date == null || !today.equals(date)) return 0;
            return count;
        }

        LocalDate getDate()
        {
            return date;
        }
    }

    /**
     * 速率限制信息（供前端展示剩余配额）。
     */
    public static class RateLimitInfo
    {
        private final int minuteUsed;
        private final int minuteLimit;
        private final int dayUsed;
        private final int dayLimit;

        RateLimitInfo(int minuteUsed, int minuteLimit, int dayUsed, int dayLimit)
        {
            this.minuteUsed = minuteUsed;
            this.minuteLimit = minuteLimit;
            this.dayUsed = dayUsed;
            this.dayLimit = dayLimit;
        }

        public int getMinuteUsed() { return minuteUsed; }
        public int getMinuteLimit() { return minuteLimit; }
        public int getDayUsed() { return dayUsed; }
        public int getDayLimit() { return dayLimit; }
        public int getMinuteRemaining() { return Math.max(0, minuteLimit - minuteUsed); }
        public int getDayRemaining() { return Math.max(0, dayLimit - dayUsed); }
    }
}
