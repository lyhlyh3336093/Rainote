package com.ruoyi.system.agent.executor;

/**
 * 步骤执行结果。
 * <p>
 * 由 {@link AgentPlanExecutor} 在每步执行后产出，通过 {@link ProgressCallback} 回调前端。
 *
 * @see ProgressCallback
 */
public class StepExecutionResult
{
    public enum Status
    {
        /** 执行成功 */
        SUCCESS,
        /** 执行失败（Service 抛异常或校验不通过） */
        FAILED,
        /** 用户跳过 */
        SKIPPED,
        /** 依赖步骤失败/跳过，本步骤被级联阻塞 */
        BLOCKED,
        /** 破坏性步骤，等待用户确认 */
        PENDING_CONFIRMATION
    }

    private final Status status;
    private final Object result;
    private final String errorMessage;

    private StepExecutionResult(Status status, Object result, String errorMessage)
    {
        this.status = status;
        this.result = result;
        this.errorMessage = errorMessage;
    }

    public static StepExecutionResult success(Object result)
    {
        return new StepExecutionResult(Status.SUCCESS, result, null);
    }

    public static StepExecutionResult failed(String errorMessage)
    {
        return new StepExecutionResult(Status.FAILED, null, errorMessage);
    }

    public static StepExecutionResult skipped()
    {
        return new StepExecutionResult(Status.SKIPPED, null, null);
    }

    public static StepExecutionResult blocked(String reason)
    {
        return new StepExecutionResult(Status.BLOCKED, null, reason);
    }

    public static StepExecutionResult pendingConfirmation()
    {
        return new StepExecutionResult(Status.PENDING_CONFIRMATION, null, null);
    }

    public Status getStatus() { return status; }
    public Object getResult() { return result; }
    public String getErrorMessage() { return errorMessage; }

    public boolean isSuccess() { return status == Status.SUCCESS; }
    public boolean isFailed() { return status == Status.FAILED; }
    public boolean isSkipped() { return status == Status.SKIPPED; }
    public boolean isBlocked() { return status == Status.BLOCKED; }
    public boolean isPendingConfirmation() { return status == Status.PENDING_CONFIRMATION; }
}
