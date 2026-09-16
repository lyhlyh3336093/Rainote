package com.ruoyi.web.controller.agent;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.agent.domain.AgentAuditLog;
import com.ruoyi.system.agent.domain.AgentAuditStepLog;
import com.ruoyi.system.agent.executor.AgentPlanExecutor;
import com.ruoyi.system.agent.lock.AgentExecutionLock;
import com.ruoyi.system.agent.lock.AgentRateLimiter;
import com.ruoyi.system.agent.llm.AgentLlmService;
import com.ruoyi.system.agent.model.AgentContext;
import com.ruoyi.system.agent.model.AgentPlan;
import com.ruoyi.system.agent.model.AgentStep;
import com.ruoyi.system.agent.registry.AgentExecutionContext;
import com.ruoyi.system.agent.service.AgentAuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link AgentController} 单元测试（U6）。
 * <p>
 * 聚焦 Controller 层自身的安全拦截与参数校验逻辑，不深入 executor/llm 内部实现：
 * <ul>
 *   <li><b>速率限制</b>：/agent/chat 超限返回 429；input 为空返回错误；LLM 失败返回错误；正常返回计划</li>
 *   <li><b>锁管理</b>：acquire / heartbeat / release 的成功与冲突分支</li>
 *   <li><b>IDOR 防护</b>：审计详情、SSE 订阅、路径 A 恢复访问他人记录 → 403/错误</li>
 *   <li><b>执行锁校验</b>：confirm / step 控制端点 / cancel / resume 未持有锁 → 409</li>
 *   <li><b>参数校验</b>：confirm 无效/空计划 → 错误</li>
 *   <li><b>SSE 连接</b>：订阅不存在的 emitter → 错误 emitter；正常订阅 → 返回 emitter</li>
 *   <li><b>会话校验</b>：步骤控制端点在会话不存在时 → 错误</li>
 * </ul>
 * {@link SecurityUtils#isAdmin(Long)} 判定 userId == 1L，故管理员 ID 固定为 1L。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentControllerTest
{
    private static final Long ADMIN_ID = 1L;
    private static final Long USER_A = 2L;
    private static final Long USER_B = 3L;
    private static final Long AUDIT_LOG_ID = 100L;
    private static final String SESSION_A = "session-A";
    private static final String SESSION_B = "session-B";

    @Mock private AgentLlmService llmService;
    @Mock private AgentPlanExecutor executor;
    @Mock private AgentAuditService auditService;
    @Mock private AgentExecutionLock executionLock;
    @Mock private AgentRateLimiter rateLimiter;
    @Mock private SseEmitterManager sseManager;
    @Mock private PlanSessionManager sessionManager;

    /** 被测对象，依赖通过反射注入 */
    private AgentController controller;

    @BeforeEach
    void setUp()
    {
        controller = new AgentController();
        injectField("llmService", llmService);
        injectField("executor", executor);
        injectField("auditService", auditService);
        injectField("executionLock", executionLock);
        injectField("rateLimiter", rateLimiter);
        injectField("sseManager", sseManager);
        injectField("sessionManager", sessionManager);

        // 速率限制默认放行，个别测试覆盖
        when(rateLimiter.tryAcquire(any())).thenReturn(true);
        when(rateLimiter.getRateLimitInfo(any()))
                .thenReturn(mock(AgentRateLimiter.RateLimitInfo.class));
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
    }

    // ==================== 速率限制 ====================

    @Test
    void chat_rateLimited_returns429()
    {
        loginAs(USER_A);
        when(rateLimiter.tryAcquire(USER_A)).thenReturn(false);
        when(rateLimiter.getRateLimitInfo(USER_A))
                .thenReturn(mock(AgentRateLimiter.RateLimitInfo.class));

        JSONObject body = new JSONObject();
        body.put("input", "创建一个笔记");

        AjaxResult result = controller.chat(body);

        assertEquals(429, code(result));
        verify(llmService, never()).generatePlan(any(), any());
    }

    @Test
    void chat_inputEmpty_returnsError()
    {
        loginAs(USER_A);

        JSONObject body = new JSONObject();
        body.put("input", "");

        AjaxResult result = controller.chat(body);

        assertEquals(HttpStatus.ERROR, code(result));
        verify(llmService, never()).generatePlan(any(), any());
    }

    @Test
    void chat_inputNull_returnsError()
    {
        loginAs(USER_A);

        JSONObject body = new JSONObject();

        AjaxResult result = controller.chat(body);

        assertEquals(HttpStatus.ERROR, code(result));
        verify(llmService, never()).generatePlan(any(), any());
    }

    @Test
    void chat_llmReturnsNull_returnsError()
    {
        loginAs(USER_A);
        when(llmService.generatePlan(any(), any())).thenReturn(null);

        JSONObject body = new JSONObject();
        body.put("input", "创建一个笔记");

        AjaxResult result = controller.chat(body);

        assertEquals(HttpStatus.ERROR, code(result));
    }

    @Test
    void chat_success_returnsPlan()
    {
        loginAs(USER_A);
        AgentPlan plan = AgentPlan.ofQuery("查询结果");
        when(llmService.generatePlan(any(), any())).thenReturn(plan);

        JSONObject body = new JSONObject();
        body.put("input", "查看我的笔记");
        JSONObject ctx = new JSONObject();
        ctx.put("noteId", 10L);
        body.put("context", ctx);

        AjaxResult result = controller.chat(body);

        assertEquals(HttpStatus.SUCCESS, code(result));
        assertEquals("QUERY", ((java.util.Map<?, ?>) result.get(AjaxResult.DATA_TAG)).get("type"));
    }

    // ==================== 锁管理 ====================

    @Test
    void acquireLock_success()
    {
        loginAs(USER_A);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Agent-Session", SESSION_A);
        when(executionLock.acquireLock(USER_A, SESSION_A)).thenReturn(true);

        AjaxResult result = controller.acquireLock(request);

        assertEquals(HttpStatus.SUCCESS, code(result));
        java.util.Map<?, ?> data = (java.util.Map<?, ?>) result.get(AjaxResult.DATA_TAG);
        assertEquals(SESSION_A, data.get("sessionId"));
    }

    @Test
    void acquireLock_heldByOther_returns409()
    {
        loginAs(USER_A);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Agent-Session", SESSION_B);
        when(executionLock.acquireLock(USER_A, SESSION_B)).thenReturn(false);
        when(executionLock.getLockHolderSessionId(USER_A)).thenReturn(SESSION_A);

        AjaxResult result = controller.acquireLock(request);

        assertEquals(409, code(result));
    }

    @Test
    void heartbeat_success()
    {
        loginAs(USER_A);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Agent-Session", SESSION_A);
        when(executionLock.heartbeat(USER_A, SESSION_A)).thenReturn(true);

        AjaxResult result = controller.heartbeat(request);

        assertEquals(HttpStatus.SUCCESS, code(result));
    }

    @Test
    void heartbeat_lockInvalid_returns409()
    {
        loginAs(USER_A);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Agent-Session", SESSION_A);
        when(executionLock.heartbeat(USER_A, SESSION_A)).thenReturn(false);

        AjaxResult result = controller.heartbeat(request);

        assertEquals(409, code(result));
    }

    @Test
    void releaseLock_success()
    {
        loginAs(USER_A);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Agent-Session", SESSION_A);

        AjaxResult result = controller.releaseLock(request);

        assertEquals(HttpStatus.SUCCESS, code(result));
        verify(executionLock).releaseLock(USER_A, SESSION_A);
    }

    // ==================== IDOR 防护：审计详情 ====================

    @Test
    void auditDetail_notFound_returnsError()
    {
        loginAs(USER_A);
        when(auditService.getAuditLogById(AUDIT_LOG_ID)).thenReturn(null);

        AjaxResult result = controller.auditDetail(AUDIT_LOG_ID);

        assertEquals(HttpStatus.ERROR, code(result));
    }

    @Test
    void auditDetail_othersRecord_returns403()
    {
        loginAs(USER_A);
        AgentAuditLog log = newAuditLog(AUDIT_LOG_ID, USER_B);
        when(auditService.getAuditLogById(AUDIT_LOG_ID)).thenReturn(log);

        AjaxResult result = controller.auditDetail(AUDIT_LOG_ID);

        assertEquals(403, code(result));
    }

    @Test
    void auditDetail_ownRecord_success()
    {
        loginAs(USER_A);
        AgentAuditLog log = newAuditLog(AUDIT_LOG_ID, USER_A);
        when(auditService.getAuditLogById(AUDIT_LOG_ID)).thenReturn(log);

        AjaxResult result = controller.auditDetail(AUDIT_LOG_ID);

        assertEquals(HttpStatus.SUCCESS, code(result));
    }

    @Test
    void auditDetail_adminAccessOthers_success()
    {
        loginAs(ADMIN_ID);
        AgentAuditLog log = newAuditLog(AUDIT_LOG_ID, USER_B);
        when(auditService.getAuditLogById(AUDIT_LOG_ID)).thenReturn(log);

        AjaxResult result = controller.auditDetail(AUDIT_LOG_ID);

        assertEquals(HttpStatus.SUCCESS, code(result));
    }

    // ==================== IDOR 防护：SSE 订阅 ====================

    @Test
    void stream_othersRecord_returnsErrorEmitter()
    {
        loginAs(USER_A);
        AgentAuditLog log = newAuditLog(AUDIT_LOG_ID, USER_B);
        when(auditService.getAuditLogById(AUDIT_LOG_ID)).thenReturn(log);

        SseEmitter emitter = controller.stream(AUDIT_LOG_ID);

        assertNotNull(emitter, "即使鉴权失败也应返回 emitter（含 error 事件）");
        verify(sseManager, never()).subscribe(AUDIT_LOG_ID);
    }

    @Test
    void stream_notFound_returnsErrorEmitter()
    {
        loginAs(USER_A);
        when(auditService.getAuditLogById(AUDIT_LOG_ID)).thenReturn(null);

        SseEmitter emitter = controller.stream(AUDIT_LOG_ID);

        assertNotNull(emitter);
        verify(sseManager, never()).subscribe(AUDIT_LOG_ID);
    }

    @Test
    void stream_noEmitter_returnsErrorEmitter()
    {
        loginAs(USER_A);
        // IDOR 通过（自己的记录），但 emitter 不存在（计划已完成或未创建）
        AgentAuditLog log = newAuditLog(AUDIT_LOG_ID, USER_A);
        when(auditService.getAuditLogById(AUDIT_LOG_ID)).thenReturn(log);
        when(sseManager.subscribe(AUDIT_LOG_ID)).thenReturn(null);

        SseEmitter emitter = controller.stream(AUDIT_LOG_ID);

        assertNotNull(emitter, "emitter 不存在时应返回含 error 事件的 emitter");
        verify(sseManager).subscribe(AUDIT_LOG_ID);
    }

    @Test
    void stream_success_returnsEmitter()
    {
        loginAs(USER_A);
        AgentAuditLog log = newAuditLog(AUDIT_LOG_ID, USER_A);
        when(auditService.getAuditLogById(AUDIT_LOG_ID)).thenReturn(log);
        SseEmitter mockEmitter = new SseEmitter();
        when(sseManager.subscribe(AUDIT_LOG_ID)).thenReturn(mockEmitter);

        SseEmitter emitter = controller.stream(AUDIT_LOG_ID);

        assertSame(mockEmitter, emitter, "应返回 sseManager 提供的 emitter");
    }

    @Test
    void stream_adminAccessOthers_success()
    {
        loginAs(ADMIN_ID);
        AgentAuditLog log = newAuditLog(AUDIT_LOG_ID, USER_B);
        when(auditService.getAuditLogById(AUDIT_LOG_ID)).thenReturn(log);
        SseEmitter mockEmitter = new SseEmitter();
        when(sseManager.subscribe(AUDIT_LOG_ID)).thenReturn(mockEmitter);

        SseEmitter emitter = controller.stream(AUDIT_LOG_ID);

        assertSame(mockEmitter, emitter);
    }

    // ==================== IDOR 防护：路径 A 恢复 ====================

    @Test
    void resumeFromAudit_notFound_returnsError()
    {
        loginAs(USER_A);
        MockHttpServletRequest request = newSessionRequest(SESSION_A);
        when(auditService.getAuditLogById(AUDIT_LOG_ID)).thenReturn(null);

        AjaxResult result = controller.resumeFromAudit(AUDIT_LOG_ID, request);

        assertEquals(HttpStatus.ERROR, code(result));
    }

    @Test
    void resumeFromAudit_othersRecord_returns403()
    {
        loginAs(USER_A);
        MockHttpServletRequest request = newSessionRequest(SESSION_A);
        AgentAuditLog log = newAuditLog(AUDIT_LOG_ID, USER_B);
        when(auditService.getAuditLogById(AUDIT_LOG_ID)).thenReturn(log);

        AjaxResult result = controller.resumeFromAudit(AUDIT_LOG_ID, request);

        assertEquals(403, code(result));
        verify(executionLock, never()).isLockHeld(any(), any());
    }

    @Test
    void resumeFromAudit_noLock_returns409()
    {
        loginAs(USER_A);
        MockHttpServletRequest request = newSessionRequest(SESSION_A);
        AgentAuditLog log = newAuditLog(AUDIT_LOG_ID, USER_A);
        log.setPlanJson("{\"type\":\"PLAN\"}");
        when(auditService.getAuditLogById(AUDIT_LOG_ID)).thenReturn(log);
        when(executionLock.isLockHeld(USER_A, SESSION_A)).thenReturn(false);

        AjaxResult result = controller.resumeFromAudit(AUDIT_LOG_ID, request);

        assertEquals(409, code(result));
    }

    // ==================== 执行锁校验 ====================

    @Test
    void confirm_noLock_returns409()
    {
        loginAs(USER_A);
        MockHttpServletRequest request = newSessionRequest(SESSION_A);
        when(executionLock.isLockHeld(USER_A, SESSION_A)).thenReturn(false);

        JSONObject body = new JSONObject();
        body.put("plan", "{}");
        body.put("userInput", "创建笔记");

        AjaxResult result = controller.confirm(body, request);

        assertEquals(409, code(result));
        verify(executor, never()).startExecution(any(), any(), any(), any(), any());
    }

    @Test
    void confirmStep_noLock_returns409()
    {
        loginAs(USER_A);
        MockHttpServletRequest request = newSessionRequest(SESSION_A);
        AgentAuditLog log = newAuditLog(AUDIT_LOG_ID, USER_A);
        when(auditService.getAuditLogById(AUDIT_LOG_ID)).thenReturn(log);
        when(executionLock.isLockHeld(USER_A, SESSION_A)).thenReturn(false);

        AjaxResult result = controller.confirmStep(AUDIT_LOG_ID, "s1", request);

        assertEquals(409, code(result));
        verify(executor, never()).confirmStep(any(), any(), any(), any(), any());
    }

    @Test
    void confirmStep_idor_returns403()
    {
        loginAs(USER_A);
        MockHttpServletRequest request = newSessionRequest(SESSION_A);
        AgentAuditLog log = newAuditLog(AUDIT_LOG_ID, USER_B);
        when(auditService.getAuditLogById(AUDIT_LOG_ID)).thenReturn(log);

        AjaxResult result = controller.confirmStep(AUDIT_LOG_ID, "s1", request);

        assertEquals(403, code(result));
        verify(executionLock, never()).isLockHeld(any(), any());
        verify(executor, never()).confirmStep(any(), any(), any(), any(), any());
    }

    @Test
    void skipStep_noLock_returns409()
    {
        loginAs(USER_A);
        MockHttpServletRequest request = newSessionRequest(SESSION_A);
        AgentAuditLog log = newAuditLog(AUDIT_LOG_ID, USER_A);
        when(auditService.getAuditLogById(AUDIT_LOG_ID)).thenReturn(log);
        when(executionLock.isLockHeld(USER_A, SESSION_A)).thenReturn(false);

        AjaxResult result = controller.skipStep(AUDIT_LOG_ID, "s1", request);

        assertEquals(409, code(result));
        verify(executor, never()).skipStep(any(), any(), any(), any());
    }

    @Test
    void retryStep_noLock_returns409()
    {
        loginAs(USER_A);
        MockHttpServletRequest request = newSessionRequest(SESSION_A);
        AgentAuditLog log = newAuditLog(AUDIT_LOG_ID, USER_A);
        when(auditService.getAuditLogById(AUDIT_LOG_ID)).thenReturn(log);
        when(executionLock.isLockHeld(USER_A, SESSION_A)).thenReturn(false);

        AjaxResult result = controller.retryStep(AUDIT_LOG_ID, "s1", request);

        assertEquals(409, code(result));
        verify(executor, never()).retryStep(any(), any(), any(), any(), any());
    }

    @Test
    void cancel_noLock_returns409()
    {
        loginAs(USER_A);
        MockHttpServletRequest request = newSessionRequest(SESSION_A);
        AgentAuditLog log = newAuditLog(AUDIT_LOG_ID, USER_A);
        when(auditService.getAuditLogById(AUDIT_LOG_ID)).thenReturn(log);
        when(executionLock.isLockHeld(USER_A, SESSION_A)).thenReturn(false);

        AjaxResult result = controller.cancel(AUDIT_LOG_ID, request);

        assertEquals(409, code(result));
        verify(executor, never()).cancelPlan(any(), any(), any());
    }

    @Test
    void pause_noLock_returns409()
    {
        loginAs(USER_A);
        MockHttpServletRequest request = newSessionRequest(SESSION_A);
        AgentAuditLog log = newAuditLog(AUDIT_LOG_ID, USER_A);
        when(auditService.getAuditLogById(AUDIT_LOG_ID)).thenReturn(log);
        when(executionLock.isLockHeld(USER_A, SESSION_A)).thenReturn(false);

        AjaxResult result = controller.pause(AUDIT_LOG_ID, request);

        assertEquals(409, code(result));
        verify(sseManager, never()).send(any(), any(), any());
    }

    // ==================== 参数校验 ====================

    @Test
    void confirm_emptyPlan_returnsError()
    {
        loginAs(USER_A);
        MockHttpServletRequest request = newSessionRequest(SESSION_A);
        when(executionLock.isLockHeld(USER_A, SESSION_A)).thenReturn(true);

        JSONObject body = new JSONObject();
        body.put("plan", "");
        body.put("userInput", "创建笔记");

        AjaxResult result = controller.confirm(body, request);

        assertEquals(HttpStatus.ERROR, code(result));
        verify(executor, never()).startExecution(any(), any(), any(), any(), any());
    }

    @Test
    void confirm_nullPlan_returnsError()
    {
        loginAs(USER_A);
        MockHttpServletRequest request = newSessionRequest(SESSION_A);
        when(executionLock.isLockHeld(USER_A, SESSION_A)).thenReturn(true);

        JSONObject body = new JSONObject();
        body.put("userInput", "创建笔记");

        AjaxResult result = controller.confirm(body, request);

        assertEquals(HttpStatus.ERROR, code(result));
        verify(executor, never()).startExecution(any(), any(), any(), any(), any());
    }

    @Test
    void confirm_invalidPlanJson_returnsError()
    {
        loginAs(USER_A);
        MockHttpServletRequest request = newSessionRequest(SESSION_A);
        when(executionLock.isLockHeld(USER_A, SESSION_A)).thenReturn(true);

        JSONObject body = new JSONObject();
        body.put("plan", "{这不是合法JSON");
        body.put("userInput", "创建笔记");

        AjaxResult result = controller.confirm(body, request);

        assertEquals(HttpStatus.ERROR, code(result));
        verify(executor, never()).startExecution(any(), any(), any(), any(), any());
    }

    // ==================== 会话校验（步骤控制端点） ====================

    @Test
    void confirmStep_sessionNotFound_returnsError()
    {
        loginAs(USER_A);
        MockHttpServletRequest request = newSessionRequest(SESSION_A);
        AgentAuditLog log = newAuditLog(AUDIT_LOG_ID, USER_A);
        when(auditService.getAuditLogById(AUDIT_LOG_ID)).thenReturn(log);
        when(executionLock.isLockHeld(USER_A, SESSION_A)).thenReturn(true);
        when(sessionManager.get(AUDIT_LOG_ID)).thenReturn(null);

        AjaxResult result = controller.confirmStep(AUDIT_LOG_ID, "s1", request);

        assertEquals(HttpStatus.ERROR, code(result));
        verify(executor, never()).confirmStep(any(), any(), any(), any(), any());
    }

    @Test
    void skipStep_sessionNotFound_returnsError()
    {
        loginAs(USER_A);
        MockHttpServletRequest request = newSessionRequest(SESSION_A);
        AgentAuditLog log = newAuditLog(AUDIT_LOG_ID, USER_A);
        when(auditService.getAuditLogById(AUDIT_LOG_ID)).thenReturn(log);
        when(executionLock.isLockHeld(USER_A, SESSION_A)).thenReturn(true);
        when(sessionManager.get(AUDIT_LOG_ID)).thenReturn(null);

        AjaxResult result = controller.skipStep(AUDIT_LOG_ID, "s1", request);

        assertEquals(HttpStatus.ERROR, code(result));
    }

    @Test
    void cancel_sessionNotFound_returnsError()
    {
        loginAs(USER_A);
        MockHttpServletRequest request = newSessionRequest(SESSION_A);
        AgentAuditLog log = newAuditLog(AUDIT_LOG_ID, USER_A);
        when(auditService.getAuditLogById(AUDIT_LOG_ID)).thenReturn(log);
        when(executionLock.isLockHeld(USER_A, SESSION_A)).thenReturn(true);
        when(sessionManager.get(AUDIT_LOG_ID)).thenReturn(null);

        AjaxResult result = controller.cancel(AUDIT_LOG_ID, request);

        assertEquals(HttpStatus.ERROR, code(result));
        verify(executor, never()).cancelPlan(any(), any(), any());
    }

    // ==================== 审计查询 ====================

    @Test
    void recentHistory_success()
    {
        loginAs(USER_A);
        List<AgentAuditLog> history = new ArrayList<>();
        history.add(newAuditLog(1L, USER_A));
        history.add(newAuditLog(2L, USER_A));
        when(auditService.getRecentHistory(USER_A)).thenReturn(history);

        AjaxResult result = controller.recentHistory();

        assertEquals(HttpStatus.SUCCESS, code(result));
        verify(auditService).getRecentHistory(USER_A);
    }

    @Test
    void recentHistory_empty_success()
    {
        loginAs(USER_A);
        when(auditService.getRecentHistory(USER_A)).thenReturn(Collections.emptyList());

        AjaxResult result = controller.recentHistory();

        assertEquals(HttpStatus.SUCCESS, code(result));
    }

    // ==================== 辅助方法 ====================

    /**
     * 模拟指定用户登录，设置 SecurityContext。
     * {@link SecurityUtils#getUserId()} 与 {@link SecurityUtils#getLoginUser()}
     * 均从 SecurityContext 获取当前用户。
     */
    private void loginAs(Long userId)
    {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(userId);
        Set<String> permissions = new HashSet<>();
        // 管理员赋予全部权限；普通用户空权限集（权限校验由 executor 层负责，Controller 测试不涉及）
        if (ADMIN_ID.equals(userId))
        {
            permissions.add("*:*:*");
        }
        loginUser.setPermissions(permissions);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * 构造带 X-Agent-Session header 的请求。
     */
    private MockHttpServletRequest newSessionRequest(String sessionId)
    {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Agent-Session", sessionId);
        return request;
    }

    /**
     * 构造审计日志对象。
     */
    private AgentAuditLog newAuditLog(Long id, Long userId)
    {
        AgentAuditLog log = new AgentAuditLog();
        log.setId(id);
        log.setUserId(userId);
        log.setStatus("executing");
        log.setDegraded(false);
        return log;
    }

    /**
     * 提取 AjaxResult 的 code 字段。
     */
    private int code(AjaxResult result)
    {
        Object c = result.get(AjaxResult.CODE_TAG);
        return c instanceof Number ? ((Number) c).intValue() : -1;
    }

    /**
     * 通过反射向 controller 注入 mock 依赖。
     */
    private void injectField(String fieldName, Object value)
    {
        try
        {
            java.lang.reflect.Field field = AgentController.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(controller, value);
        }
        catch (Exception e)
        {
            throw new RuntimeException("注入失败: " + fieldName, e);
        }
    }
}
