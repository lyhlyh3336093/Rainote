package com.ruoyi.system.agent.lock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentExecutionLock 单元测试。
 * <p>
 * 覆盖 R17a 多标签页锁的全部场景：
 * <ul>
 *   <li>基本获取/释放</li>
 *   <li>同会话重复获取</li>
 *   <li>不同会话互斥</li>
 *   <li>心跳续期</li>
 *   <li>超时接管</li>
 *   <li>释放后重新获取</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentExecutionLockTest
{
    private AgentExecutionLock lock;

    private static final Long USER_A = 1L;
    private static final Long USER_B = 2L;
    private static final String SESSION_1 = "session-tab-1";
    private static final String SESSION_2 = "session-tab-2";

    @BeforeEach
    void setUp()
    {
        lock = new AgentExecutionLock();
        lock.init();
    }

    @AfterEach
    void tearDown()
    {
        lock.destroy();
    }

    // ===== 基本获取/释放 =====

    @Test
    void test_acquireLock_success()
    {
        assertTrue(lock.acquireLock(USER_A, SESSION_1), "首次获取锁应成功");
    }

    @Test
    void test_releaseLock_thenReacquire()
    {
        assertTrue(lock.acquireLock(USER_A, SESSION_1));
        lock.releaseLock(USER_A, SESSION_1);
        assertTrue(lock.acquireLock(USER_A, SESSION_1), "释放后应可重新获取");
    }

    // ===== 同会话重复获取 =====

    @Test
    void test_acquireLock_sameSession_reacquire_success()
    {
        assertTrue(lock.acquireLock(USER_A, SESSION_1));
        assertTrue(lock.acquireLock(USER_A, SESSION_1), "同会话重复获取应成功（幂等）");
    }

    // ===== 不同会话互斥 =====

    @Test
    void test_acquireLock_differentSession_blocked()
    {
        assertTrue(lock.acquireLock(USER_A, SESSION_1));
        assertFalse(lock.acquireLock(USER_A, SESSION_2),
                "不同会话获取应被拒绝（互斥）");
    }

    @Test
    void test_differentUsers_independent()
    {
        assertTrue(lock.acquireLock(USER_A, SESSION_1));
        assertTrue(lock.acquireLock(USER_B, SESSION_1),
                "不同用户的锁应独立，互不影响");
    }

    // ===== 心跳续期 =====

    @Test
    void test_heartbeat_validSession_success()
    {
        lock.acquireLock(USER_A, SESSION_1);
        assertTrue(lock.heartbeat(USER_A, SESSION_1), "已持有锁的会话心跳应成功");
    }

    @Test
    void test_heartbeat_noLock_failure()
    {
        assertFalse(lock.heartbeat(USER_A, SESSION_1), "无锁时心跳应失败");
    }

    @Test
    void test_heartbeat_wrongSession_failure()
    {
        lock.acquireLock(USER_A, SESSION_1);
        assertFalse(lock.heartbeat(USER_A, SESSION_2), "非持有会话心跳应失败");
    }

    // ===== 锁状态查询 =====

    @Test
    void test_isLockHeld_correctSession_true()
    {
        lock.acquireLock(USER_A, SESSION_1);
        assertTrue(lock.isLockHeld(USER_A, SESSION_1));
    }

    @Test
    void test_isLockHeld_wrongSession_false()
    {
        lock.acquireLock(USER_A, SESSION_1);
        assertFalse(lock.isLockHeld(USER_A, SESSION_2));
    }

    @Test
    void test_isLockHeld_noLock_false()
    {
        assertFalse(lock.isLockHeld(USER_A, SESSION_1));
    }

    @Test
    void test_isLockedByOther_otherSession_true()
    {
        lock.acquireLock(USER_A, SESSION_1);
        assertTrue(lock.isLockedByOther(USER_A, SESSION_2), "被其他会话持有时应返回 true");
    }

    @Test
    void test_isLockedByOther_sameSession_false()
    {
        lock.acquireLock(USER_A, SESSION_1);
        assertFalse(lock.isLockedByOther(USER_A, SESSION_1), "被自己持有时应返回 false");
    }

    // ===== 释放校验 =====

    @Test
    void test_releaseLock_wrongSession_noEffect()
    {
        lock.acquireLock(USER_A, SESSION_1);
        lock.releaseLock(USER_A, SESSION_2); // 非持有者释放应无效
        assertTrue(lock.isLockHeld(USER_A, SESSION_1), "非持有者释放不应影响锁状态");
    }

    // ===== 超时接管 =====

    @Test
    void test_expiredLock_takeover() throws Exception
    {
        // 获取锁
        assertTrue(lock.acquireLock(USER_A, SESSION_1));

        // 等待超时（LOCK_TIMEOUT_MS = 15000ms，测试中等待略超过）
        // 为避免测试过慢，使用反射直接修改 lastHeartbeat 模拟超时
        forceExpireLock(USER_A);

        // 超时后另一会话应能接管
        assertTrue(lock.acquireLock(USER_A, SESSION_2), "超时后另一会话应能接管");
        assertTrue(lock.isLockHeld(USER_A, SESSION_2));
        assertFalse(lock.isLockHeld(USER_A, SESSION_1));
    }

    @Test
    void test_expiredLock_isLockHeld_false() throws Exception
    {
        lock.acquireLock(USER_A, SESSION_1);
        forceExpireLock(USER_A);
        assertFalse(lock.isLockHeld(USER_A, SESSION_1), "超时后 isLockHeld 应返回 false");
    }

    @Test
    void test_expiredLock_isLockedByOther_false() throws Exception
    {
        lock.acquireLock(USER_A, SESSION_1);
        forceExpireLock(USER_A);
        assertFalse(lock.isLockedByOther(USER_A, SESSION_2), "超时后 isLockedByOther 应返回 false");
    }

    @Test
    void test_heartbeat_preventsExpiry() throws Exception
    {
        lock.acquireLock(USER_A, SESSION_1);

        // 模拟部分时间过去但未超时
        forcePartialExpiry(USER_A, AgentExecutionLock.LOCK_TIMEOUT_MS - 1000);

        // 心跳续期
        assertTrue(lock.heartbeat(USER_A, SESSION_1));

        // 仍应持有锁
        assertTrue(lock.isLockHeld(USER_A, SESSION_1));
    }

    // ===== 边界场景 =====

    @Test
    void test_acquireLock_nullUserId_failure()
    {
        assertFalse(lock.acquireLock(null, SESSION_1));
    }

    @Test
    void test_acquireLock_nullSessionId_failure()
    {
        assertFalse(lock.acquireLock(USER_A, null));
    }

    @Test
    void test_getLockHolderSessionId_valid()
    {
        lock.acquireLock(USER_A, SESSION_1);
        assertEquals(SESSION_1, lock.getLockHolderSessionId(USER_A));
    }

    @Test
    void test_getLockHolderSessionId_noLock_null()
    {
        assertNull(lock.getLockHolderSessionId(USER_A));
    }

    @Test
    void test_getLockHolderSessionId_expired_null() throws Exception
    {
        lock.acquireLock(USER_A, SESSION_1);
        forceExpireLock(USER_A);
        assertNull(lock.getLockHolderSessionId(USER_A));
    }

    // ===== 辅助方法 =====

    /**
     * 通过反射强制使锁过期（模拟 15s 无心跳）。
     */
    private void forceExpireLock(Long userId) throws Exception
    {
        java.lang.reflect.Field locksField = AgentExecutionLock.class
                .getDeclaredField("locks");
        locksField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<Long, Object> locks =
                (java.util.concurrent.ConcurrentHashMap<Long, Object>) locksField.get(lock);

        Object entry = locks.get(userId);
        if (entry == null) return;

        java.lang.reflect.Field heartbeatField = entry.getClass()
                .getDeclaredField("lastHeartbeat");
        heartbeatField.setAccessible(true);
        // 设置为超时前的时间
        heartbeatField.setLong(entry, System.currentTimeMillis()
                - AgentExecutionLock.LOCK_TIMEOUT_MS - 1000);
    }

    /**
     * 通过反射模拟部分时间过去（未超时）。
     */
    private void forcePartialExpiry(Long userId, long elapsedMs) throws Exception
    {
        java.lang.reflect.Field locksField = AgentExecutionLock.class
                .getDeclaredField("locks");
        locksField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<Long, Object> locks =
                (java.util.concurrent.ConcurrentHashMap<Long, Object>) locksField.get(lock);

        Object entry = locks.get(userId);
        if (entry == null) return;

        java.lang.reflect.Field heartbeatField = entry.getClass()
                .getDeclaredField("lastHeartbeat");
        heartbeatField.setAccessible(true);
        heartbeatField.setLong(entry, System.currentTimeMillis() - elapsedMs);
    }
}
