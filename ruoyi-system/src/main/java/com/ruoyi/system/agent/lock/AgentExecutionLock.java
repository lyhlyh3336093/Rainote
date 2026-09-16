package com.ruoyi.system.agent.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Agent 执行锁（JVM 内存实现）。
 * <p>
 * R17a：禁止多标签页同时打开 agent 面板。userId 维度互斥 + TTL 过期。
 * <p>
 * 语义与 Redis SET NX EX 一致，MVP 单实例部署不引入 Redis 强制依赖；
 * 多实例部署时替换为 Redis 实现即可，接口不变。
 * <ul>
 *   <li>用户打开面板时 {@link #acquireLock} 获取锁</li>
 *   <li>前端心跳每 5s 调用 {@link #heartbeat} 更新时间戳</li>
 *   <li>超时 15s（无心跳）视为崩溃，新面板可接管</li>
 *   <li>执行期间持有锁，暂停期间不释放</li>
 * </ul>
 */
@Component
public class AgentExecutionLock
{
    private static final Logger log = LoggerFactory.getLogger(AgentExecutionLock.class);

    /** 心跳间隔（毫秒），前端每 5s 发送一次心跳 */
    public static final long HEARTBEAT_INTERVAL_MS = 5_000;

    /** 锁超时时间（毫秒），15s 无心跳视为崩溃 */
    public static final long LOCK_TIMEOUT_MS = 15_000;

    /** 清理周期（毫秒） */
    private static final long CLEANUP_INTERVAL_MS = 5_000;

    private final ConcurrentHashMap<Long, LockEntry> locks = new ConcurrentHashMap<>();

    private ScheduledExecutorService cleanupExecutor;

    @PostConstruct
    public void init()
    {
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r ->
        {
            Thread t = new Thread(r, "agent-lock-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredLocks,
                CLEANUP_INTERVAL_MS, CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("AgentExecutionLock 已初始化，超时={}ms，清理周期={}ms", LOCK_TIMEOUT_MS, CLEANUP_INTERVAL_MS);
    }

    @PreDestroy
    public void destroy()
    {
        if (cleanupExecutor != null)
        {
            cleanupExecutor.shutdownNow();
        }
    }

    /**
     * 尝试获取锁。
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID（区分同用户不同标签页）
     * @return true=获取成功（新获取或同会话重复获取），false=锁被其他会话持有且未过期
     */
    public boolean acquireLock(Long userId, String sessionId)
    {
        if (userId == null || sessionId == null) return false;

        long now = System.currentTimeMillis();
        LockEntry newEntry = new LockEntry(userId, sessionId, now);

        // CAS：如果无锁直接获取
        LockEntry existing = locks.putIfAbsent(userId, newEntry);
        if (existing == null)
        {
            log.debug("锁获取成功（新）: userId={}, sessionId={}", userId, sessionId);
            return true;
        }

        // 已有锁是否过期
        if (isExpired(existing, now))
        {
            // 尝试替换过期锁
            if (locks.replace(userId, existing, newEntry))
            {
                log.info("锁接管成功（前持有者超时）: userId={}, sessionId={}, 前sessionId={}",
                        userId, sessionId, existing.sessionId);
                return true;
            }
            // CAS 失败（其他线程已替换），递归重试
            return acquireLock(userId, sessionId);
        }

        // 锁未过期，检查是否同一会话
        if (existing.sessionId.equals(sessionId))
        {
            existing.lastHeartbeat = now; // 同会话续期
            return true;
        }

        // 锁被其他会话持有且未过期
        log.debug("锁获取失败（被其他会话持有）: userId={}, 当前sessionId={}, 请求sessionId={}",
                userId, existing.sessionId, sessionId);
        return false;
    }

    /**
     * 心跳续期。
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @return true=续期成功，false=锁不存在或不属于该会话
     */
    public boolean heartbeat(Long userId, String sessionId)
    {
        if (userId == null || sessionId == null) return false;
        LockEntry entry = locks.get(userId);
        if (entry == null || !entry.sessionId.equals(sessionId)) return false;
        entry.lastHeartbeat = System.currentTimeMillis();
        return true;
    }

    /**
     * 释放锁（仅持有者可释放）。
     */
    public void releaseLock(Long userId, String sessionId)
    {
        if (userId == null || sessionId == null) return;
        locks.computeIfPresent(userId, (k, entry) ->
                entry.sessionId.equals(sessionId) ? null : entry);
        log.debug("锁已释放: userId={}, sessionId={}", userId, sessionId);
    }

    /**
     * 检查指定会话是否持有锁。
     */
    public boolean isLockHeld(Long userId, String sessionId)
    {
        if (userId == null || sessionId == null) return false;
        LockEntry entry = locks.get(userId);
        if (entry == null || isExpired(entry, System.currentTimeMillis())) return false;
        return entry.sessionId.equals(sessionId);
    }

    /**
     * 检查锁是否被其他会话持有（用于面板入口检测）。
     */
    public boolean isLockedByOther(Long userId, String sessionId)
    {
        if (userId == null) return false;
        LockEntry entry = locks.get(userId);
        if (entry == null || isExpired(entry, System.currentTimeMillis())) return false;
        return !entry.sessionId.equals(sessionId);
    }

    /**
     * 获取当前持有锁的会话 ID（用于前端显示"已在另一标签页打开"）。
     *
     * @return 会话 ID，无锁或已过期返回 null
     */
    public String getLockHolderSessionId(Long userId)
    {
        if (userId == null) return null;
        LockEntry entry = locks.get(userId);
        if (entry == null || isExpired(entry, System.currentTimeMillis())) return null;
        return entry.sessionId;
    }

    private boolean isExpired(LockEntry entry, long now)
    {
        return now - entry.lastHeartbeat > LOCK_TIMEOUT_MS;
    }

    /**
     * 后台线程：定期清理过期锁，防止内存泄漏。
     */
    private void cleanupExpiredLocks()
    {
        try
        {
            long now = System.currentTimeMillis();
            int removed = 0;
            Iterator<Map.Entry<Long, LockEntry>> it = locks.entrySet().iterator();
            while (it.hasNext())
            {
                Map.Entry<Long, LockEntry> e = it.next();
                if (now - e.getValue().lastHeartbeat > LOCK_TIMEOUT_MS)
                {
                    it.remove();
                    removed++;
                }
            }
            if (removed > 0)
            {
                log.info("清理过期 agent 锁: {} 个", removed);
            }
        }
        catch (Exception e)
        {
            log.error("锁清理异常", e);
        }
    }

    /**
     * 锁条目。
     */
    static class LockEntry
    {
        final Long userId;
        final String sessionId;
        volatile long lastHeartbeat;

        LockEntry(Long userId, String sessionId, long lastHeartbeat)
        {
            this.userId = userId;
            this.sessionId = sessionId;
            this.lastHeartbeat = lastHeartbeat;
        }
    }
}
