package com.ruoyi.system.agent.executor;

import com.ruoyi.system.agent.domain.AgentAuditStepLog;
import com.ruoyi.system.agent.model.AgentPlan;
import com.ruoyi.system.agent.model.AgentStep;
import com.ruoyi.system.agent.registry.AgentExecutionContext;
import com.ruoyi.system.agent.registry.AgentOperationRegistry;
import com.ruoyi.system.agent.registry.AgentOperationSpec;
import com.ruoyi.system.agent.registry.AgentParamBinder;
import com.ruoyi.system.agent.registry.AgentParamSpec;
import com.ruoyi.system.agent.service.AgentAuditService;
import com.ruoyi.system.domain.NoteNote;
import com.ruoyi.system.domain.NoteRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Agent 计划执行引擎。
 * <p>
 * 接收用户确认后的 {@link AgentPlan}，逐步执行，每步经过四层校验后通过反射调用 Service 方法。
 * 采用事件驱动状态机模型：API 调用触发步骤推进，非破坏性步骤连续执行，破坏性步骤暂停等待确认。
 * <p>
 * 关键设计：
 * <ul>
 *   <li><b>四层校验</b>：(1)操作名在 Registry (2)参数 schema (3)权限校验 (4)前置条件</li>
 *   <li><b>独立事务</b>：每步 Service 调用通过 TransactionTemplate（REQUIRES_NEW）包裹，
 *       审计写入由 U4 独立事务提交，互不干扰</li>
 *   <li><b>事件驱动</b>：非破坏性步骤连续执行，破坏性步骤暂停并持久化 pending_confirm 状态，
 *       /agent/step/confirm 触发继续</li>
 *   <li><b>依赖解析</b>：步骤失败时通过 {@link DependencyResolver} 级联阻塞后继</li>
 *   <li><b>幂等键</b>：create 操作生成 stepRequestId，路径 A 恢复时判断是否已提交</li>
 *   <li><b>安全上下文</b>：userId/权限从 {@link AgentExecutionContext} 显式传递，不依赖 ThreadLocal</li>
 * </ul>
 *
 * @see AgentOperationRegistry
 * @see AgentParamBinder
 * @see AgentAuditService
 * @see DependencyResolver
 */
@Service
public class AgentPlanExecutor
{
    private static final Logger log = LoggerFactory.getLogger(AgentPlanExecutor.class);

    /** 步骤审计状态常量 */
    private static final String STATUS_EXECUTING = "executing";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_SKIPPED = "skipped";
    private static final String STATUS_BLOCKED = "blocked";
    private static final String STATUS_PENDING_CONFIRM = "pending_confirm";

    /** 操作类型常量 */
    private static final String OP_CREATE = "create";

    /** 笔记创建操作名（auth 字段需强制注入当前用户） */
    private static final String OP_NOTE_CREATE = "note.create";

    /** 记录创建操作名（dwtableId 字段需在 LLM 未填时从执行上下文注入） */
    private static final String OP_RECORD_CREATE = "record.create";

    @Autowired
    private AgentOperationRegistry registry;

    @Autowired
    private AgentParamBinder paramBinder;

    @Autowired
    private AgentAuditService auditService;

    @Autowired
    private DependencyResolver dependencyResolver;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate requiresNewTx;

    @PostConstruct
    public void init()
    {
        requiresNewTx = new TransactionTemplate(transactionManager);
        requiresNewTx.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ===== 公共 API =====

    /**
     * 启动计划执行。
     * <p>
     * 创建审计日志，执行非破坏性步骤，遇破坏性步骤暂停并持久化 pending_confirm 状态。
     *
     * @param plan      用户确认后的计划
     * @param context   执行上下文（userId + 权限键）
     * @param sessionId 会话 ID
     * @param userInput 用户原始输入（审计存储时 PII 脱敏）
     * @param callback  进度回调（SSE）
     * @return 审计日志 ID，null 表示审计降级
     */
    public Long startExecution(AgentPlan plan, AgentExecutionContext context,
                               String sessionId, String userInput,
                               ProgressCallback callback)
    {
        // 创建审计日志
        Long auditLogId = auditService.createAuditLog(
                context.getUserId(), sessionId, userInput, planToJson(plan));

        if (auditLogId == null)
        {
            log.warn("审计降级: userId={}, 继续执行但不记录审计", context.getUserId());
        }

        // 执行非破坏性步骤
        Set<String> completedStepIds = new HashSet<>();
        executeContinuousSteps(plan.getSteps(), auditLogId, context, callback, completedStepIds);

        return auditLogId;
    }

    /**
     * 确认并执行破坏性步骤，然后继续执行后续非破坏性步骤。
     *
     * @param plan       原始计划（用于获取步骤信息）
     * @param auditLogId 审计日志 ID
     * @param stepId     待确认的步骤 ID
     * @param context    执行上下文
     * @param callback   进度回调
     */
    public void confirmStep(AgentPlan plan, Long auditLogId, String stepId,
                            AgentExecutionContext context, ProgressCallback callback)
    {
        List<AgentStep> steps = plan.getSteps();
        Set<String> completedStepIds = getCompletedStepIds(auditLogId);

        int stepIndex = dependencyResolver.getStepIndex(steps, stepId);
        if (stepIndex < 0)
        {
            log.error("步骤不存在: stepId={}, auditLogId={}", stepId, auditLogId);
            return;
        }

        AgentStep step = steps.get(stepIndex);
        AgentOperationSpec spec = registry.getOperation(step.getOperationName());

        // 四层校验
        StepValidationResult validation = validateStep(step, spec, context);
        if (!validation.isPassed())
        {
            Long stepLogId = insertStepLogWithDegradation(auditLogId, stepIndex,
                    step.getStepId(), step.getOperationName(), step.getParams(), null);
            auditService.updateStepStatus(stepLogId, STATUS_FAILED, validation.getErrorMessage());
            callback.onStepCompleted(stepId, StepExecutionResult.failed(validation.getErrorMessage()));
            blockDependents(steps, stepId, auditLogId, callback, completedStepIds);
            // 继续执行后续非破坏性步骤
            executeContinuousSteps(steps, auditLogId, context, callback, completedStepIds, stepIndex + 1);
            return;
        }

        // 执行破坏性步骤
        callback.onStepStarted(stepId, step.getOperationName());
        StepExecutionResult result = executeStep(step, spec, context, auditLogId, steps);
        callback.onStepCompleted(stepId, result);

        if (result.isSuccess())
        {
            completedStepIds.add(stepId);
            // 继续执行后续非破坏性步骤
            executeContinuousSteps(steps, auditLogId, context, callback, completedStepIds, stepIndex + 1);
        }
        else if (result.isFailed())
        {
            blockDependents(steps, stepId, auditLogId, callback, completedStepIds);
            executeContinuousSteps(steps, auditLogId, context, callback, completedStepIds, stepIndex + 1);
        }
    }

    /**
     * 跳过步骤，级联阻塞所有传递依赖该步骤的后继。
     *
     * @param plan       原始计划
     * @param auditLogId 审计日志 ID
     * @param stepId     待跳过的步骤 ID
     * @param callback   进度回调
     */
    public void skipStep(AgentPlan plan, Long auditLogId, String stepId,
                         ProgressCallback callback)
    {
        List<AgentStep> steps = plan.getSteps();
        int stepIndex = dependencyResolver.getStepIndex(steps, stepId);
        if (stepIndex < 0) return;

        AgentStep step = steps.get(stepIndex);
        Set<String> completedStepIds = getCompletedStepIds(auditLogId);

        // 标记当前步骤为 skipped
        Long stepLogId = insertStepLogWithDegradation(auditLogId, stepIndex,
                step.getStepId(), step.getOperationName(), step.getParams(), null);
        auditService.updateStepStatus(stepLogId, STATUS_SKIPPED, null);
        callback.onStepCompleted(stepId, StepExecutionResult.skipped());

        // 级联阻塞后继
        blockDependents(steps, stepId, auditLogId, callback, completedStepIds);
    }

    /**
     * 重试失败的步骤。
     *
     * @param plan       原始计划
     * @param auditLogId 审计日志 ID
     * @param stepId     待重试的步骤 ID
     * @param context    执行上下文
     * @param callback   进度回调
     */
    public void retryStep(AgentPlan plan, Long auditLogId, String stepId,
                          AgentExecutionContext context, ProgressCallback callback)
    {
        List<AgentStep> steps = plan.getSteps();
        int stepIndex = dependencyResolver.getStepIndex(steps, stepId);
        if (stepIndex < 0) return;

        AgentStep step = steps.get(stepIndex);
        AgentOperationSpec spec = registry.getOperation(step.getOperationName());

        // 重新校验
        StepValidationResult validation = validateStep(step, spec, context);
        if (!validation.isPassed())
        {
            callback.onStepCompleted(stepId, StepExecutionResult.failed(validation.getErrorMessage()));
            return;
        }

        // 重新执行
        callback.onStepStarted(stepId, step.getOperationName());
        StepExecutionResult result = executeStep(step, spec, context, auditLogId, steps);
        callback.onStepCompleted(stepId, result);

        if (result.isSuccess())
        {
            Set<String> completedStepIds = getCompletedStepIds(auditLogId);
            completedStepIds.add(stepId);
            executeContinuousSteps(steps, auditLogId, context, callback, completedStepIds, stepIndex + 1);
        }
    }

    /**
     * 取消计划，标记所有未完成步骤为 blocked，完成审计日志。
     *
     * @param plan       原始计划
     * @param auditLogId 审计日志 ID
     * @param callback   进度回调
     */
    public void cancelPlan(AgentPlan plan, Long auditLogId, ProgressCallback callback)
    {
        Set<String> completedStepIds = getCompletedStepIds(auditLogId);
        for (AgentStep step : plan.getSteps())
        {
            if (!completedStepIds.contains(step.getStepId()))
            {
                int idx = dependencyResolver.getStepIndex(plan.getSteps(), step.getStepId());
                Long stepLogId = insertStepLogWithDegradation(auditLogId, idx,
                        step.getStepId(), step.getOperationName(), step.getParams(), null);
                auditService.updateStepStatus(stepLogId, STATUS_BLOCKED, "计划已取消");
                callback.onStepBlocked(step.getStepId(), "计划已取消");
            }
        }
        auditService.completeAuditLog(auditLogId, "interrupted");
        callback.onPlanInterrupted("用户取消");
    }

    // ===== 核心执行逻辑 =====

    /**
     * 连续执行非破坏性步骤，遇破坏性步骤暂停。
     * <p>
     * 从 startIndex 开始遍历步骤列表：
     * <ul>
     *   <li>已完成步骤 → 跳过</li>
     *   <li>依赖未满足 → 标记 blocked</li>
     *   <li>破坏性步骤 → 标记 pending_confirm，暂停执行</li>
     *   <li>校验失败 → 标记 failed，级联阻塞后继</li>
     *   <li>执行成功 → 加入 completedStepIds，继续</li>
     * </ul>
     *
     * @param steps            计划步骤列表
     * @param auditLogId       审计日志 ID
     * @param context          执行上下文
     * @param callback         进度回调
     * @param completedStepIds 已完成步骤 ID 集合（可变，执行中更新）
     * @param startIndex       开始执行的步骤索引
     */
    private void executeContinuousSteps(List<AgentStep> steps, Long auditLogId,
                                        AgentExecutionContext context,
                                        ProgressCallback callback,
                                        Set<String> completedStepIds,
                                        int startIndex)
    {
        for (int i = startIndex; i < steps.size(); i++)
        {
            AgentStep step = steps.get(i);

            // 跳过已完成步骤
            if (completedStepIds.contains(step.getStepId()))
            {
                continue;
            }

            // 依赖检查
            if (!dependencyResolver.canExecute(step, completedStepIds))
            {
                Long stepLogId = insertStepLogWithDegradation(auditLogId, i,
                        step.getStepId(), step.getOperationName(), step.getParams(), null);
                auditService.updateStepStatus(stepLogId, STATUS_BLOCKED, "依赖步骤未完成");
                callback.onStepBlocked(step.getStepId(), "依赖步骤未完成");
                continue;
            }

            AgentOperationSpec spec = registry.getOperation(step.getOperationName());

            // 破坏性步骤 → 暂停等待确认
            if (spec != null && spec.isDestructive())
            {
                Long stepLogId = insertStepLogWithDegradation(auditLogId, i,
                        step.getStepId(), step.getOperationName(), step.getParams(), null);
                auditService.updateStepStatus(stepLogId, STATUS_PENDING_CONFIRM, null);
                callback.onStepPendingConfirmation(step.getStepId(), step.getOperationName());
                return; // 暂停执行，等待 /agent/step/confirm
            }

            // 四层校验
            StepValidationResult validation = validateStep(step, spec, context);
            if (!validation.isPassed())
            {
                Long stepLogId = insertStepLogWithDegradation(auditLogId, i,
                        step.getStepId(), step.getOperationName(), step.getParams(), null);
                auditService.updateStepStatus(stepLogId, STATUS_FAILED, validation.getErrorMessage());
                callback.onStepCompleted(step.getStepId(),
                        StepExecutionResult.failed(validation.getErrorMessage()));
                blockDependents(steps, step.getStepId(), auditLogId, callback, completedStepIds);
                continue;
            }

            // 执行步骤
            callback.onStepStarted(step.getStepId(), step.getOperationName());
            StepExecutionResult result = executeStep(step, spec, context, auditLogId, steps);
            callback.onStepCompleted(step.getStepId(), result);

            if (result.isSuccess())
            {
                completedStepIds.add(step.getStepId());
            }
            else if (result.isFailed())
            {
                blockDependents(steps, step.getStepId(), auditLogId, callback, completedStepIds);
            }
        }

        // 全部步骤完成
        if (auditLogId != null)
        {
            auditService.completeAuditLog(auditLogId, "completed");
        }
        callback.onPlanCompleted();
    }

    /** 重载：从索引 0 开始执行 */
    private void executeContinuousSteps(List<AgentStep> steps, Long auditLogId,
                                        AgentExecutionContext context,
                                        ProgressCallback callback,
                                        Set<String> completedStepIds)
    {
        executeContinuousSteps(steps, auditLogId, context, callback, completedStepIds, 0);
    }

    /**
     * 执行单个步骤（含幂等检查、审计写入、反射调用）。
     *
     * @param step       步骤
     * @param spec       操作规格
     * @param context    执行上下文
     * @param auditLogId 审计日志 ID
     * @param allSteps   全部步骤（用于获取索引）
     * @return 执行结果
     */
    private StepExecutionResult executeStep(AgentStep step, AgentOperationSpec spec,
                                            AgentExecutionContext context,
                                            Long auditLogId, List<AgentStep> allSteps)
    {
        int stepIndex = dependencyResolver.getStepIndex(allSteps, step.getStepId());

        // create 操作生成幂等键
        String stepRequestId = OP_CREATE.equals(step.getOperationType())
                ? UUID.randomUUID().toString() : null;

        // 幂等检查：路径 A 恢复时判断 create 是否已提交
        if (stepRequestId != null && auditLogId != null)
        {
            AgentAuditStepLog existing = auditService.getStepLogByRequestId(stepRequestId);
            if (existing != null && STATUS_SUCCESS.equals(existing.getStatus()))
            {
                log.info("步骤已执行（幂等跳过）: stepId={}, stepRequestId={}",
                        step.getStepId(), stepRequestId);
                return StepExecutionResult.success(null);
            }
        }

        // 审计前置写入（status=executing），auditLogId 为 null 时辅助方法内部直接返回 null
        Long stepLogId = insertStepLogWithDegradation(auditLogId, stepIndex,
                step.getStepId(), step.getOperationName(), step.getParams(), stepRequestId);

        try
        {
            // 参数绑定
            Object[] args = paramBinder.bind(spec, step.getParams(), context);

            // auth 字段强制注入：note.create 时将 NoteNote.auth 设为当前用户 ID，
            // 覆盖 NoteNoteServiceImpl.insertNoteNote 中 auth==null → setAuth(1L) 的缺省行为
            if (OP_NOTE_CREATE.equals(step.getOperationName()))
            {
                for (Object arg : args)
                {
                    if (arg instanceof NoteNote)
                    {
                        ((NoteNote) arg).setAuth(context.getUserId());
                        break;
                    }
                }
            }

            // dwtableId 上下文注入：record.create 时若 LLM 未填 dwtableId，
            // 从执行上下文的 currentDwtableId 注入，避免 Service 收到 null 抛错。
            // 仅在用户处于数据表页面（context.currentDwtableId != null）时生效；
            // 若用户不在数据表页面，仍由 NoteRecordServiceImpl 抛出明确 ServiceException。
            if (OP_RECORD_CREATE.equals(step.getOperationName())
                    && context.getCurrentDwtableId() != null)
            {
                for (Object arg : args)
                {
                    if (arg instanceof NoteRecord)
                    {
                        NoteRecord rec = (NoteRecord) arg;
                        if (rec.getDwtableId() == null)
                        {
                            rec.setDwtableId(context.getCurrentDwtableId());
                        }
                        break;
                    }
                }
            }

            // REQUIRES_NEW 事务中执行 Service 调用
            Object result = requiresNewTx.execute(status ->
                    invokeMethod(spec, args));

            // 审计更新（success）
            if (stepLogId != null)
            {
                auditService.updateStepStatus(stepLogId, STATUS_SUCCESS, null);
            }
            return StepExecutionResult.success(result);
        }
        catch (Exception e)
        {
            String errorMsg = extractErrorMessage(e);
            log.error("步骤执行失败: stepId={}, operation={}", step.getStepId(),
                    step.getOperationName(), e);
            if (stepLogId != null)
            {
                auditService.updateStepStatus(stepLogId, STATUS_FAILED, errorMsg);
            }
            return StepExecutionResult.failed(errorMsg);
        }
    }

    // ===== 四层校验 =====

    /**
     * 四层校验：操作名 → 参数 schema → 权限 → 前置条件。
     *
     * @param step    步骤
     * @param spec    操作规格（可为 null，Layer 1 校验）
     * @param context 执行上下文
     * @return 校验结果
     */
    private StepValidationResult validateStep(AgentStep step, AgentOperationSpec spec,
                                              AgentExecutionContext context)
    {
        // Layer 1: 操作名在 Registry 中
        if (spec == null)
        {
            return StepValidationResult.fail(
                    StepValidationResult.ErrorType.OPERATION_NOT_FOUND,
                    "操作 '" + step.getOperationName() + "' 不在操作清单中");
        }

        // Layer 2: 参数 schema（检查必填且非框架注入的参数）
        for (AgentParamSpec paramSpec : spec.getParams())
        {
            if (paramSpec.isRequired() && !paramSpec.isFrameworkInjected()
                    && !step.getParams().containsKey(paramSpec.getName()))
            {
                return StepValidationResult.fail(
                        StepValidationResult.ErrorType.PARAM_SCHEMA_VIOLATION,
                        "缺少必填参数: " + paramSpec.getName());
            }
        }

        // Layer 3: 权限校验
        if (!context.hasPermission(spec.getPermissionKey()))
        {
            return StepValidationResult.fail(
                    StepValidationResult.ErrorType.PERMISSION_DENIED,
                    "无权限执行操作: " + step.getOperationName()
                            + "（需要权限: " + spec.getPermissionKey() + "）");
        }

        // Layer 4: 前置条件（MVP 阶段由 Service 自行校验实体存在性）
        // TODO: 后续可在此添加实体存在性预检

        return StepValidationResult.ok();
    }

    // ===== 辅助方法 =====

    /**
     * 插入步骤审计日志，若写入失败则自动标记审计降级。
     * <p>
     * 统一封装 {@link AgentAuditService#insertStepLog} 的降级处理：
     * 当 auditLogId 为 null（审计已降级）时直接返回 null；
     * 当 insertStepLog 返回 null（本次写入失败）时调用 {@link AgentAuditService#markDegraded}
     * 标记审计日志为 degraded，路径 A 恢复时可据此提示"审计不完整"。
     *
     * @param auditLogId     审计日志ID，null 表示已降级
     * @param stepIndex      步骤序号
     * @param stepId         步骤唯一标识
     * @param operationName  操作名
     * @param params         参数对象（将转为 JSON 并脱敏）
     * @param stepRequestId  幂等键（create 操作防重复）
     * @return 步骤日志ID，审计降级时返回 null
     */
    private Long insertStepLogWithDegradation(Long auditLogId, int stepIndex, String stepId,
                                              String operationName, Object params, String stepRequestId)
    {
        if (auditLogId == null) return null;
        Long stepLogId = auditService.insertStepLog(auditLogId, stepIndex, stepId,
                operationName, params, stepRequestId);
        if (stepLogId == null)
        {
            auditService.markDegraded(auditLogId);
        }
        return stepLogId;
    }

    /**
     * 级联阻塞所有传递依赖 failedStepId 的后继步骤。
     */
    private void blockDependents(List<AgentStep> allSteps, String failedStepId,
                                 Long auditLogId, ProgressCallback callback,
                                 Set<String> completedStepIds)
    {
        Set<String> dependentIds = dependencyResolver.getTransitiveDependents(failedStepId, allSteps);
        for (String depId : dependentIds)
        {
            if (completedStepIds.contains(depId)) continue;
            AgentStep depStep = findStep(allSteps, depId);
            if (depStep == null) continue;
            int idx = dependencyResolver.getStepIndex(allSteps, depId);
            Long stepLogId = insertStepLogWithDegradation(auditLogId, idx,
                    depId, depStep.getOperationName(), depStep.getParams(), null);
            auditService.updateStepStatus(stepLogId, STATUS_BLOCKED,
                    "依赖步骤 " + failedStepId + " 失败/跳过");
            callback.onStepBlocked(depId, "依赖步骤 " + failedStepId + " 失败/跳过");
        }
    }

    /**
     * 从审计日志读取已成功完成的步骤 ID 集合。
     */
    private Set<String> getCompletedStepIds(Long auditLogId)
    {
        Set<String> completed = new HashSet<>();
        if (auditLogId == null) return completed;
        List<AgentAuditStepLog> stepLogs = auditService.getStepLogs(auditLogId);
        if (stepLogs == null) return completed;
        for (AgentAuditStepLog stepLog : stepLogs)
        {
            if (STATUS_SUCCESS.equals(stepLog.getStatus()))
            {
                completed.add(stepLog.getStepId());
            }
        }
        return completed;
    }

    /**
     * 反射调用 Service 方法，将受检异常包装为 RuntimeException。
     * <p>
     * TransactionCallback 不允许抛出受检异常，故在此包装。
     * {@link #extractErrorMessage(Exception)} 会解包获取根因。
     */
    private Object invokeMethod(AgentOperationSpec spec, Object[] args)
    {
        try
        {
            return spec.getMethod().invoke(spec.getBean(), args);
        }
        catch (InvocationTargetException | IllegalAccessException e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * 从异常链中提取根因错误消息。
     */
    private String extractErrorMessage(Exception e)
    {
        Throwable cause = e;
        // 解包 TransactionSystemException → InvocationTargetException → 实际异常
        while (cause.getCause() != null && cause.getCause() != cause)
        {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        return msg != null ? msg : cause.getClass().getSimpleName();
    }

    /**
     * 在步骤列表中查找指定 ID 的步骤。
     */
    private AgentStep findStep(List<AgentStep> steps, String stepId)
    {
        for (AgentStep s : steps)
        {
            if (s.getStepId().equals(stepId)) return s;
        }
        return null;
    }

    /**
     * 将 AgentPlan 序列化为 JSON（用于审计存储）。
     */
    private String planToJson(AgentPlan plan)
    {
        try
        {
            return com.alibaba.fastjson2.JSON.toJSONString(plan);
        }
        catch (Exception e)
        {
            log.warn("计划序列化失败", e);
            return null;
        }
    }
}
