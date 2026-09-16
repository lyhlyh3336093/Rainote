package com.ruoyi.system.agent.executor;

/**
 * 执行进度回调接口，由 Controller 实现，通过 SSE 实时反馈前端。
 * <p>
 * 所有方法均有默认空实现，实现方按需覆盖。Executor 在每个关键节点回调对应方法。
 */
public interface ProgressCallback
{
    /** 步骤开始执行 */
    default void onStepStarted(String stepId, String operationName) {}

    /** 步骤执行完成（成功/失败/跳过/阻塞） */
    default void onStepCompleted(String stepId, StepExecutionResult result) {}

    /** 步骤被阻塞（依赖步骤失败） */
    default void onStepBlocked(String stepId, String reason) {}

    /** 破坏性步骤等待用户确认 */
    default void onStepPendingConfirmation(String stepId, String operationName) {}

    /** 计划全部完成 */
    default void onPlanCompleted() {}

    /** 计划中断（异常或用户取消） */
    default void onPlanInterrupted(String reason) {}
}
