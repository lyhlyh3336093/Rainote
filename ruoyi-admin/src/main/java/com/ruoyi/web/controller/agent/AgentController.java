package com.ruoyi.web.controller.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.agent.executor.AgentPlanExecutor;
import com.ruoyi.system.agent.executor.ProgressCallback;
import com.ruoyi.system.agent.executor.StepExecutionResult;
import com.ruoyi.system.agent.lock.AgentExecutionLock;
import com.ruoyi.system.agent.lock.AgentRateLimiter;
import com.ruoyi.system.agent.llm.AgentLlmService;
import com.ruoyi.system.agent.model.AgentContext;
import com.ruoyi.system.agent.model.AgentPlan;
import com.ruoyi.system.agent.model.AgentStep;
import com.ruoyi.system.agent.registry.AgentExecutionContext;
import com.ruoyi.system.agent.service.AgentAuditService;
import com.ruoyi.system.agent.domain.AgentAuditLog;
import com.ruoyi.system.agent.domain.AgentAuditStepLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.HttpServletRequest;
import java.util.*;

/**
 * Agent API Controller。
 * <p>
 * 提供 agent 前端所需的全部 API 端点（U6）：
 * <ul>
 *   <li>意图解析与计划生成：POST /agent/chat</li>
 *   <li>计划确认与执行：POST /agent/confirm</li>
 *   <li>SSE 实时进度：GET /agent/plan/{id}/stream</li>
 *   <li>步骤控制：confirm / skip / retry</li>
 *   <li>计划控制：pause / resume / cancel</li>
 *   <li>审计查询：recent / detail / resume</li>
 *   <li>多标签页锁：acquire / heartbeat / release</li>
 * </ul>
 * <p>
 * 安全措施：
 * <ul>
 *   <li>IDOR 防护：所有按 ID 访问的端点校验 auditLog.userId == 当前用户（或管理员）</li>
 *   <li>执行锁校验：执行类端点入口校验当前会话持有 AgentExecutionLock</li>
 *   <li>速率限制：/agent/chat 每用户每分钟 10 次、每日 200 次</li>
 *   <li>SSE 鉴权：双轨方案（event-source-polyfill header + query 参数 token 兜底）</li>
 * </ul>
 */
@RestController
@RequestMapping("/agent")
public class AgentController
{
    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    @Autowired private AgentLlmService llmService;
    @Autowired private AgentPlanExecutor executor;
    @Autowired private AgentAuditService auditService;
    @Autowired private AgentExecutionLock executionLock;
    @Autowired private AgentRateLimiter rateLimiter;
    @Autowired private SseEmitterManager sseManager;
    @Autowired private PlanSessionManager sessionManager;

    // ==================== 意图解析 ====================

    /**
     * POST /agent/chat — 接收 {input, context}，返回计划/查询结果/澄清问题/错误。
     * <p>
     * R8/R11/R20a：调用 GLM 解析意图，支持澄清（最多 3 轮）与重试（2 次指数退避）。
     * 速率限制：每用户每分钟 10 次、每日 200 次。
     */
    @PostMapping("/chat")
    public AjaxResult chat(@RequestBody JSONObject body)
    {
        Long userId = SecurityUtils.getUserId();

        // 速率限制
        if (!rateLimiter.tryAcquire(userId))
        {
            AgentRateLimiter.RateLimitInfo info = rateLimiter.getRateLimitInfo(userId);
            return AjaxResult.error(HttpStatus.TOO_MANY_REQUESTS.value(),
                    "请求过于频繁，请稍后再试（剩余：" + info.getMinuteRemaining() + "次/分钟，"
                            + info.getDayRemaining() + "次/今日）");
        }

        String input = body.getString("input");
        if (input == null || input.trim().isEmpty())
        {
            return AjaxResult.error("输入不能为空");
        }

        // 构建上下文
        AgentContext context = parseContext(body.getJSONObject("context"));

        // 调用 LLM 生成计划
        AgentPlan plan = llmService.generatePlan(input, context);
        if (plan == null)
        {
            return AjaxResult.error("AI 处理失败，请稍后重试");
        }

        // 返回计划（含类型标识，前端按类型渲染）
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", plan.getType().name());
        result.put("plan", plan);
        result.put("queryResult", plan.getQueryResult());
        result.put("clarifyingQuestion", plan.getClarifyingQuestion());
        result.put("errorMessage", plan.getErrorMessage());
        result.put("needsSummary", plan.isNeedsSummary());

        // 附带速率限制信息
        AgentRateLimiter.RateLimitInfo rlInfo = rateLimiter.getRateLimitInfo(userId);
        result.put("rateLimit", rlInfo);

        return AjaxResult.success(result);
    }

    // ==================== 计划确认与执行 ====================

    /**
     * POST /agent/confirm — 接收 {plan, userInput, context}，触发执行，返回 auditLogId。
     * <p>
     * 同步执行非破坏性步骤，遇破坏性步骤暂停（pending_confirm）。
     * 事件缓冲在 SseEmitterManager 中，前端订阅 SSE 后 flush。
     */
    @PostMapping("/confirm")
    public AjaxResult confirm(@RequestBody JSONObject body, HttpServletRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        String sessionId = resolveSessionId(request);

        // 执行锁校验
        if (!executionLock.isLockHeld(userId, sessionId))
        {
            return AjaxResult.error(HttpStatus.CONFLICT.value(),
                    "未持有 agent 执行锁，请先打开 agent 面板");
        }

        // 解析计划
        AgentPlan plan = parsePlan(body.getString("plan"));
        if (plan == null || !plan.isPlan() || plan.getSteps() == null || plan.getSteps().isEmpty())
        {
            return AjaxResult.error("无效的计划，无法执行");
        }

        String userInput = body.getString("userInput");
        AgentContext agentContext = parseContext(body.getJSONObject("context"));

        // 构建执行上下文（透传页面 ID，供 record.create 等操作的 dwtableId 注入）
        AgentExecutionContext execContext = buildExecutionContext(agentContext);

        // 创建 SSE emitter（执行前创建，事件将缓冲）
        // auditLogId 尚未生成，先用临时 key，执行后更新
        // 改为：先创建 audit log 再执行——但 executor.startExecution 内部创建 audit log
        // 采用方案：先执行（同步），拿到 auditLogId 后注册 emitter 并补发缓冲事件

        // 创建回调（使用临时 auditLogId=null，执行后更新）
        // 问题：callback 需要 auditLogId 来路由 SSE 事件
        // 解决：使用两阶段——先创建 emitter 用临时 key，执行后将 emitter 迁移到 auditLogId

        // 更简洁的方案：executor.startExecution 返回 auditLogId，
        // 在执行期间 callback 不发送 SSE（因为还没有 auditLogId），
        // 执行后根据审计日志判断当前状态，返回给前端。
        // SSE 仅用于后续的 step confirm/skip/retry 的实时推送。

        // 执行计划
        SseProgressCallback callback = new SseProgressCallback(null);
        Long auditLogId = executor.startExecution(plan, execContext, sessionId, userInput, callback);

        if (auditLogId == null)
        {
            return AjaxResult.error("审计系统降级，无法跟踪执行进度");
        }

        // 注册 SSE emitter
        sseManager.create(auditLogId);
        callback.setAuditLogId(auditLogId);

        // 补发执行期间缓冲的事件
        callback.flushBufferedEvents();

        // 存储计划会话（供后续 step confirm/skip/retry 使用）
        sessionManager.put(auditLogId, plan, execContext, sessionId, userInput);

        // 检查执行状态
        List<AgentAuditStepLog> stepLogs = auditService.getStepLogs(auditLogId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("auditLogId", auditLogId);
        result.put("stepLogs", stepLogs);
        result.put("hasPendingDestructive", callback.hasPendingDestructive());
        result.put("isCompleted", callback.isCompleted());

        return AjaxResult.success(result);
    }

    // ==================== SSE 进度推送 ====================

    /**
     * GET /agent/plan/{auditLogId}/stream — SSE 实时进度推送。
     * <p>
     * SSE 鉴权双轨方案：(1) event-source-polyfill 注入 Authorization header；
     * (2) TokenService 支持 query 参数 ?token=xxx 兜底（仅对 SSE 路径生效）。
     */
    @GetMapping("/plan/{auditLogId}/stream")
    public SseEmitter stream(@PathVariable Long auditLogId)
    {
        // IDOR 防护
        AjaxResult idorCheck = verifyOwnership(auditLogId);
        if (idorCheck != null)
        {
            // 返回错误的 SseEmitter（立即完成）
            SseEmitter errorEmitter = new SseEmitter();
            try
            {
                errorEmitter.send(SseEmitter.event().name("error")
                        .data(JSON.toJSONString(idorCheck)));
                errorEmitter.complete();
            }
            catch (Exception ignored) { }
            return errorEmitter;
        }

        SseEmitter emitter = sseManager.subscribe(auditLogId);
        if (emitter == null)
        {
            // emitter 不存在（计划已完成或 auditLogId 无效）
            emitter = new SseEmitter();
            try
            {
                emitter.send(SseEmitter.event().name("error")
                        .data("{\"message\":\"计划不存在或已完成\"}"));
                emitter.complete();
            }
            catch (Exception ignored) { }
        }
        return emitter;
    }

    // ==================== 步骤控制 ====================

    /**
     * POST /agent/step/{auditLogId}/{stepId}/confirm — 破坏性步骤二次确认。
     */
    @PostMapping("/step/{auditLogId}/{stepId}/confirm")
    public AjaxResult confirmStep(@PathVariable Long auditLogId,
                                   @PathVariable String stepId,
                                   HttpServletRequest request)
    {
        AjaxResult error = verifyExecutionAccess(auditLogId, request);
        if (error != null) return error;

        PlanSessionManager.PlanSession session = sessionManager.get(auditLogId);
        if (session == null) return AjaxResult.error("计划会话不存在，可能已过期");

        SseProgressCallback callback = new SseProgressCallback(auditLogId);
        executor.confirmStep(session.getPlan(), auditLogId, stepId,
                session.getContext(), callback);

        return buildStepResult(callback);
    }

    /**
     * POST /agent/step/{auditLogId}/{stepId}/skip — 跳过步骤。
     */
    @PostMapping("/step/{auditLogId}/{stepId}/skip")
    public AjaxResult skipStep(@PathVariable Long auditLogId,
                                @PathVariable String stepId,
                                HttpServletRequest request)
    {
        AjaxResult error = verifyExecutionAccess(auditLogId, request);
        if (error != null) return error;

        PlanSessionManager.PlanSession session = sessionManager.get(auditLogId);
        if (session == null) return AjaxResult.error("计划会话不存在，可能已过期");

        SseProgressCallback callback = new SseProgressCallback(auditLogId);
        executor.skipStep(session.getPlan(), auditLogId, stepId, callback);

        return buildStepResult(callback);
    }

    /**
     * POST /agent/step/{auditLogId}/{stepId}/retry — 重试步骤。
     */
    @PostMapping("/step/{auditLogId}/{stepId}/retry")
    public AjaxResult retryStep(@PathVariable Long auditLogId,
                                 @PathVariable String stepId,
                                 HttpServletRequest request)
    {
        AjaxResult error = verifyExecutionAccess(auditLogId, request);
        if (error != null) return error;

        PlanSessionManager.PlanSession session = sessionManager.get(auditLogId);
        if (session == null) return AjaxResult.error("计划会话不存在，可能已过期");

        SseProgressCallback callback = new SseProgressCallback(auditLogId);
        executor.retryStep(session.getPlan(), auditLogId, stepId,
                session.getContext(), callback);

        return buildStepResult(callback);
    }

    // ==================== 计划控制 ====================

    /**
     * POST /agent/plan/{auditLogId}/pause — 暂停执行。
     * <p>
     * 事件驱动模型下，暂停 = 不调用 confirm/continue，执行自然停止。
     * 此端点标记暂停状态供前端 UI 使用。
     */
    @PostMapping("/plan/{auditLogId}/pause")
    public AjaxResult pause(@PathVariable Long auditLogId, HttpServletRequest request)
    {
        AjaxResult error = verifyExecutionAccess(auditLogId, request);
        if (error != null) return error;

        // 事件驱动模型：暂停是自然状态（不调用 confirm 即暂停）
        // 仅发一个 SSE 事件通知前端
        sseManager.send(auditLogId, "plan_paused", "{\"auditLogId\":" + auditLogId + "}");
        return AjaxResult.success("已暂停");
    }

    /**
     * POST /agent/plan/{auditLogId}/resume — 继续执行。
     * <p>
     * 恢复 = 继续执行非破坏性步骤（跳过已完成的）。
     */
    @PostMapping("/plan/{auditLogId}/resume")
    public AjaxResult resume(@PathVariable Long auditLogId, HttpServletRequest request)
    {
        AjaxResult error = verifyExecutionAccess(auditLogId, request);
        if (error != null) return error;

        PlanSessionManager.PlanSession session = sessionManager.get(auditLogId);
        if (session == null) return AjaxResult.error("计划会话不存在，可能已过期");

        SseProgressCallback callback = new SseProgressCallback(auditLogId);
        // 恢复执行：重新触发连续步骤执行（executor 内部会跳过已完成步骤）
        // 由于 executor 没有单独的 resume 方法，使用 confirmStep 的模式
        // 找到第一个 pending_confirm 或未完成的步骤继续执行
        // MVP 简化：通过 SSE 通知前端，前端根据审计日志状态决定下一步操作
        sseManager.send(auditLogId, "plan_resumed", "{\"auditLogId\":" + auditLogId + "}");

        return AjaxResult.success("已恢复");
    }

    /**
     * POST /agent/plan/{auditLogId}/cancel — 取消剩余计划。
     */
    @PostMapping("/plan/{auditLogId}/cancel")
    public AjaxResult cancel(@PathVariable Long auditLogId, HttpServletRequest request)
    {
        AjaxResult error = verifyExecutionAccess(auditLogId, request);
        if (error != null) return error;

        PlanSessionManager.PlanSession session = sessionManager.get(auditLogId);
        if (session == null) return AjaxResult.error("计划会话不存在，可能已过期");

        SseProgressCallback callback = new SseProgressCallback(auditLogId);
        executor.cancelPlan(session.getPlan(), auditLogId, callback);

        // 清理资源
        sseManager.complete(auditLogId);
        sessionManager.remove(auditLogId);

        return AjaxResult.success("计划已取消");
    }

    // ==================== 审计查询 ====================

    /**
     * GET /agent/audit/recent — 查询最近 50 条执行历史（R23）。
     */
    @GetMapping("/audit/recent")
    public AjaxResult recentHistory()
    {
        Long userId = SecurityUtils.getUserId();
        List<AgentAuditLog> history = auditService.getRecentHistory(userId);
        return AjaxResult.success(history);
    }

    /**
     * GET /agent/audit/{id} — 查询审计详情（含步骤日志）。
     */
    @GetMapping("/audit/{id}")
    public AjaxResult auditDetail(@PathVariable Long id)
    {
        // IDOR 防护
        AgentAuditLog auditLog = auditService.getAuditLogById(id);
        if (auditLog == null)
        {
            return AjaxResult.error("审计记录不存在");
        }

        Long userId = SecurityUtils.getUserId();
        if (!userId.equals(auditLog.getUserId()) && !SecurityUtils.isAdmin(userId))
        {
            return AjaxResult.error(HttpStatus.FORBIDDEN.value(), "无权访问此审计记录");
        }

        return AjaxResult.success(auditLog);
    }

    /**
     * POST /agent/audit/{id}/resume — 路径 A 恢复（R20b）。
     * <p>
     * 从审计记录恢复未完成的计划，不重新调用 LLM，已执行步骤不重复执行。
     */
    @PostMapping("/audit/{id}/resume")
    public AjaxResult resumeFromAudit(@PathVariable Long id, HttpServletRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        String sessionId = resolveSessionId(request);

        // 获取审计记录
        AgentAuditLog auditLog = auditService.getAuditLogById(id);
        if (auditLog == null)
        {
            return AjaxResult.error("审计记录不存在");
        }

        // IDOR 防护
        if (!userId.equals(auditLog.getUserId()) && !SecurityUtils.isAdmin(userId))
        {
            return AjaxResult.error(HttpStatus.FORBIDDEN.value(), "无权恢复此审计记录");
        }

        // 执行锁校验
        if (!executionLock.isLockHeld(userId, sessionId))
        {
            return AjaxResult.error(HttpStatus.CONFLICT.value(),
                    "未持有 agent 执行锁，请先打开 agent 面板");
        }

        // 从 planJson 重建计划
        AgentPlan plan = reconstructPlan(auditLog.getPlanJson());
        if (plan == null || !plan.isPlan())
        {
            return AjaxResult.error("无法从审计记录恢复计划（计划数据损坏）");
        }

        // 构建执行上下文
        AgentExecutionContext execContext = buildExecutionContext();

        // 注册 SSE emitter
        sseManager.create(id);

        // 存储计划会话
        sessionManager.put(id, plan, execContext, sessionId, auditLog.getUserInput());

        // 获取已完成的步骤状态（供前端展示）
        List<AgentAuditStepLog> stepLogs = auditService.getStepLogs(id);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("auditLogId", id);
        result.put("plan", plan);
        result.put("stepLogs", stepLogs);
        result.put("degraded", auditLog.getDegraded());

        if (auditLog.getDegraded() != null && auditLog.getDegraded())
        {
            result.put("warning", "审计记录不完整，部分步骤状态可能不准确，请手动确认");
        }

        return AjaxResult.success(result);
    }

    // ==================== 多标签页锁 ====================

    /**
     * POST /agent/lock/acquire — 获取 agent 面板锁（R17a）。
     */
    @PostMapping("/lock/acquire")
    public AjaxResult acquireLock(HttpServletRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        String sessionId = resolveSessionId(request);

        boolean acquired = executionLock.acquireLock(userId, sessionId);
        if (!acquired)
        {
            String holderSession = executionLock.getLockHolderSessionId(userId);
            return AjaxResult.error(HttpStatus.CONFLICT.value(),
                    "Agent 已在另一标签页打开" + (holderSession != null ? "" : ""));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("heartbeatInterval", AgentExecutionLock.HEARTBEAT_INTERVAL_MS);
        result.put("lockTimeout", AgentExecutionLock.LOCK_TIMEOUT_MS);
        return AjaxResult.success(result);
    }

    /**
     * POST /agent/lock/heartbeat — 心跳续期。
     */
    @PostMapping("/lock/heartbeat")
    public AjaxResult heartbeat(HttpServletRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        String sessionId = resolveSessionId(request);

        if (!executionLock.heartbeat(userId, sessionId))
        {
            return AjaxResult.error(HttpStatus.CONFLICT.value(), "锁已失效，请重新打开 agent 面板");
        }

        return AjaxResult.success("ok");
    }

    /**
     * POST /agent/lock/release — 释放锁。
     */
    @PostMapping("/lock/release")
    public AjaxResult releaseLock(HttpServletRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        String sessionId = resolveSessionId(request);
        executionLock.releaseLock(userId, sessionId);
        return AjaxResult.success("ok");
    }

    // ==================== 辅助方法 ====================

    /**
     * 解析 AgentContext（前端传入的页面上下文）。
     */
    private AgentContext parseContext(JSONObject ctx)
    {
        if (ctx == null) return AgentContext.empty();
        return new AgentContext(
                ctx.getLong("noteId"),
                ctx.getLong("dwtableId"),
                ctx.getLong("columnId"),
                ctx.getLong("recordId"));
    }

    /**
     * 从 JSON 字符串解析 AgentPlan。
     */
    private AgentPlan parsePlan(String planJson)
    {
        if (planJson == null || planJson.trim().isEmpty()) return null;
        try
        {
            return JSON.parseObject(planJson, AgentPlan.class);
        }
        catch (Exception e)
        {
            log.warn("计划解析失败", e);
            return null;
        }
    }

    /**
     * 从审计日志的 planJson 重建 AgentPlan（路径 A 恢复用）。
     */
    private AgentPlan reconstructPlan(String planJson)
    {
        return parsePlan(planJson);
    }

    /**
     * 构建执行上下文（从 SecurityUtils 获取 userId 与权限键）。
     */
    private AgentExecutionContext buildExecutionContext()
    {
        return buildExecutionContext(null);
    }

    /**
     * 构建执行上下文，并将规划期 AgentContext 的页面 ID 透传到执行期。
     * <p>
     * record.create 等操作在 LLM 未填入 dwtableId 时，由执行器从执行上下文注入，
     * 避免 record 永远收到 null（修复 NullPointerException 根因）。
     *
     * @param agentContext 规划期上下文（可为 null，表示无页面上下文）
     */
    private AgentExecutionContext buildExecutionContext(AgentContext agentContext)
    {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        Long userId = loginUser.getUserId();
        Set<String> permissions = loginUser.getPermissions();
        if (agentContext == null)
        {
            return new AgentExecutionContext(userId, permissions);
        }
        return new AgentExecutionContext(userId, permissions,
                agentContext.getCurrentNoteId(),
                agentContext.getCurrentDwtableId(),
                agentContext.getCurrentColumnId(),
                agentContext.getCurrentRecordId());
    }

    /**
     * 解析会话 ID（从请求头 X-Agent-Session，不存在则生成）。
     */
    private String resolveSessionId(HttpServletRequest request)
    {
        String sessionId = request.getHeader("X-Agent-Session");
        if (sessionId == null || sessionId.trim().isEmpty())
        {
            sessionId = UUID.randomUUID().toString();
        }
        return sessionId;
    }

    /**
     * IDOR 校验：验证审计记录归属当前用户。
     *
     * @return null=校验通过，非 null=校验失败的 AjaxResult
     */
    private AjaxResult verifyOwnership(Long auditLogId)
    {
        AgentAuditLog auditLog = auditService.getAuditLogById(auditLogId);
        if (auditLog == null)
        {
            return AjaxResult.error(HttpStatus.NOT_FOUND.value(), "审计记录不存在");
        }
        Long userId = SecurityUtils.getUserId();
        if (!userId.equals(auditLog.getUserId()) && !SecurityUtils.isAdmin(userId))
        {
            return AjaxResult.error(HttpStatus.FORBIDDEN.value(), "无权操作此审计记录");
        }
        return null;
    }

    /**
     * 执行类端点统一校验：IDOR + 执行锁。
     *
     * @return null=校验通过，非 null=校验失败的 AjaxResult
     */
    private AjaxResult verifyExecutionAccess(Long auditLogId, HttpServletRequest request)
    {
        // IDOR 校验
        AjaxResult idorError = verifyOwnership(auditLogId);
        if (idorError != null) return idorError;

        // 执行锁校验
        Long userId = SecurityUtils.getUserId();
        String sessionId = resolveSessionId(request);
        if (!executionLock.isLockHeld(userId, sessionId))
        {
            return AjaxResult.error(HttpStatus.CONFLICT.value(),
                    "未持有 agent 执行锁，请重新打开 agent 面板");
        }

        return null;
    }

    /**
     * 构建步骤操作结果。
     */
    private AjaxResult buildStepResult(SseProgressCallback callback)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hasPendingDestructive", callback.hasPendingDestructive());
        result.put("isCompleted", callback.isCompleted());
        result.put("isInterrupted", callback.isInterrupted());
        return AjaxResult.success(result);
    }

    // ==================== SSE 回调实现 ====================

    /**
     * 基于 SseEmitterManager 的 ProgressCallback 实现。
     * <p>
     * auditLogId 可能为 null（confirm 阶段执行开始前），此时事件缓冲在内部列表，
     * auditLogId 设置后通过 {@link #flushBufferedEvents} 补发。
     */
    private class SseProgressCallback implements ProgressCallback
    {
        private Long auditLogId;
        private final List<BufferedCallbackEvent> bufferedEvents = new ArrayList<>();
        private volatile boolean hasPendingDestructive = false;
        private volatile boolean completed = false;
        private volatile boolean interrupted = false;

        SseProgressCallback(Long auditLogId)
        {
            this.auditLogId = auditLogId;
        }

        void setAuditLogId(Long auditLogId)
        {
            this.auditLogId = auditLogId;
        }

        boolean hasPendingDestructive() { return hasPendingDestructive; }
        boolean isCompleted() { return completed; }
        boolean isInterrupted() { return interrupted; }

        @Override
        public void onStepStarted(String stepId, String operationName)
        {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("stepId", stepId);
            data.put("operation", operationName);
            sendOrBuffer("step_started", data);
        }

        @Override
        public void onStepCompleted(String stepId, StepExecutionResult result)
        {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("stepId", stepId);
            data.put("status", result.getStatus().name());
            data.put("success", result.isSuccess());
            data.put("errorMessage", result.getErrorMessage());
            sendOrBuffer("step_completed", data);
        }

        @Override
        public void onStepBlocked(String stepId, String reason)
        {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("stepId", stepId);
            data.put("reason", reason);
            sendOrBuffer("step_blocked", data);
        }

        @Override
        public void onStepPendingConfirmation(String stepId, String operationName)
        {
            hasPendingDestructive = true;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("stepId", stepId);
            data.put("operation", operationName);
            sendOrBuffer("step_pending", data);
        }

        @Override
        public void onPlanCompleted()
        {
            completed = true;
            sendOrBuffer("plan_completed", new LinkedHashMap<>());
            if (auditLogId != null) sseManager.complete(auditLogId);
        }

        @Override
        public void onPlanInterrupted(String reason)
        {
            interrupted = true;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reason", reason);
            sendOrBuffer("plan_interrupted", data);
            if (auditLogId != null) sseManager.complete(auditLogId);
        }

        private void sendOrBuffer(String eventName, Object data)
        {
            if (auditLogId != null)
            {
                sseManager.send(auditLogId, eventName, data);
            }
            else
            {
                bufferedEvents.add(new BufferedCallbackEvent(eventName, data));
            }
        }

        /**
         * 补发缓冲事件（auditLogId 设置后调用）。
         */
        void flushBufferedEvents()
        {
            if (auditLogId == null) return;
            for (BufferedCallbackEvent e : bufferedEvents)
            {
                sseManager.send(auditLogId, e.name, e.data);
            }
            bufferedEvents.clear();
        }
    }

    /** 缓冲回调事件（auditLogId 未确定时使用） */
    private static class BufferedCallbackEvent
    {
        final String name;
        final Object data;

        BufferedCallbackEvent(String name, Object data)
        {
            this.name = name;
            this.data = data;
        }
    }
}
