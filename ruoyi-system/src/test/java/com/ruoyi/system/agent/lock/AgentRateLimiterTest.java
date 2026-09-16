package com.ruoyi.system.agent.lock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentRateLimiter 单元测试。
 * <p>
 * 覆盖速率限制核心逻辑：
 * <ul>
 *   <li>分钟窗口限制（10 次/分钟）</li>
 *   <li>日限制（200 次/日）</li>
 *   <li>不同用户独立计数</li>
 *   <li>配额信息查询</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentRateLimiterTest
{
    private AgentRateLimiter limiter;

    private static final Long USER_A = 1L;
    private static final Long USER_B = 2L;

    @BeforeEach
    void setUp()
    {
        limiter = new AgentRateLimiter();
        limiter.init();
    }

    // ===== 分钟窗口限制 =====

    @Test
    void test_withinMinuteLimit_allowed()
    {
        for (int i = 0; i < AgentRateLimiter.MAX_PER_MINUTE; i++)
        {
            assertTrue(limiter.tryAcquire(USER_A), "第 " + (i + 1) + " 次请求应被允许");
        }
    }

    @Test
    void test_exceedMinuteLimit_blocked()
    {
        // 消耗完配额
        for (int i = 0; i < AgentRateLimiter.MAX_PER_MINUTE; i++)
        {
            limiter.tryAcquire(USER_A);
        }
        // 超限请求应被拒绝
        assertFalse(limiter.tryAcquire(USER_A), "超过分钟限制的请求应被拒绝");
    }

    @Test
    void test_minuteLimit_isPerUser()
    {
        // 用户 A 消耗完配额
        for (int i = 0; i < AgentRateLimiter.MAX_PER_MINUTE; i++)
        {
            limiter.tryAcquire(USER_A);
        }
        // 用户 B 不受影响
        assertTrue(limiter.tryAcquire(USER_B), "不同用户的限制应独立");
    }

    // ===== 日限制 =====

    @Test
    void test_dayLimit_notExceededInNormalUse()
    {
        // 正常使用量远低于日限制
        for (int i = 0; i < AgentRateLimiter.MAX_PER_MINUTE; i++)
        {
            assertTrue(limiter.tryAcquire(USER_A));
        }
        AgentRateLimiter.RateLimitInfo info = limiter.getRateLimitInfo(USER_A);
        assertEquals(AgentRateLimiter.MAX_PER_DAY - AgentRateLimiter.MAX_PER_MINUTE,
                info.getDayRemaining(), "日剩余配额应正确");
    }

    // ===== 配额信息查询 =====

    @Test
    void test_getRateLimitInfo_initial()
    {
        AgentRateLimiter.RateLimitInfo info = limiter.getRateLimitInfo(USER_A);
        assertEquals(0, info.getMinuteUsed(), "初始分钟已用应为 0");
        assertEquals(AgentRateLimiter.MAX_PER_MINUTE, info.getMinuteLimit());
        assertEquals(0, info.getDayUsed(), "初始日已用应为 0");
        assertEquals(AgentRateLimiter.MAX_PER_DAY, info.getDayLimit());
        assertEquals(AgentRateLimiter.MAX_PER_MINUTE, info.getMinuteRemaining());
        assertEquals(AgentRateLimiter.MAX_PER_DAY, info.getDayRemaining());
    }

    @Test
    void test_getRateLimitInfo_afterRequests()
    {
        limiter.tryAcquire(USER_A);
        limiter.tryAcquire(USER_A);
        limiter.tryAcquire(USER_A);

        AgentRateLimiter.RateLimitInfo info = limiter.getRateLimitInfo(USER_A);
        assertEquals(3, info.getMinuteUsed(), "3 次请求后分钟已用应为 3");
        assertEquals(3, info.getDayUsed(), "3 次请求后日已用应为 3");
        assertEquals(AgentRateLimiter.MAX_PER_MINUTE - 3, info.getMinuteRemaining());
        assertEquals(AgentRateLimiter.MAX_PER_DAY - 3, info.getDayRemaining());
    }

    @Test
    void test_getRateLimitInfo_differentUsers()
    {
        limiter.tryAcquire(USER_A);
        limiter.tryAcquire(USER_A);
        limiter.tryAcquire(USER_B);

        AgentRateLimiter.RateLimitInfo infoA = limiter.getRateLimitInfo(USER_A);
        AgentRateLimiter.RateLimitInfo infoB = limiter.getRateLimitInfo(USER_B);

        assertEquals(2, infoA.getMinuteUsed(), "用户 A 应有 2 次");
        assertEquals(1, infoB.getMinuteUsed(), "用户 B 应有 1 次");
    }

    // ===== 边界场景 =====

    @Test
    void test_tryAcquire_nullUserId_failure()
    {
        assertFalse(limiter.tryAcquire(null), "null userId 应返回 false");
    }

    @Test
    void test_getRateLimitInfo_nullUserId()
    {
        AgentRateLimiter.RateLimitInfo info = limiter.getRateLimitInfo(null);
        assertNotNull(info, "null userId 不应抛异常");
        assertEquals(0, info.getMinuteUsed());
    }

    // ===== 分钟窗口滑动验证 =====

    @Test
    void test_minuteWindow_resetsAfterExpiry() throws Exception
    {
        // 消耗完配额
        for (int i = 0; i < AgentRateLimiter.MAX_PER_MINUTE; i++)
        {
            limiter.tryAcquire(USER_A);
        }
        assertFalse(limiter.tryAcquire(USER_A), "配额用完应被拒绝");

        // 模拟窗口过期（通过反射清除旧时间戳）
        forceClearMinuteWindow(USER_A);

        // 窗口过期后应可再次获取
        assertTrue(limiter.tryAcquire(USER_A), "窗口过期后应可重新获取");
    }

    // ===== 辅助方法 =====

    /**
     * 通过反射清除分钟窗口中的旧时间戳，模拟窗口过期。
     */
    private void forceClearMinuteWindow(Long userId) throws Exception
    {
        java.lang.reflect.Field windowsField = AgentRateLimiter.class
                .getDeclaredField("minuteWindows");
        windowsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<Long, java.util.Deque<Long>> windows =
                (java.util.concurrent.ConcurrentHashMap<Long, java.util.Deque<Long>>)
                        windowsField.get(limiter);

        java.util.Deque<Long> window = windows.get(userId);
        if (window != null)
        {
            window.clear();
        }
    }
}
