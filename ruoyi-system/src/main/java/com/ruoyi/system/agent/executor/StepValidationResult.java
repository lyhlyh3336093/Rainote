package com.ruoyi.system.agent.executor;

/**
 * 步骤四层校验结果。
 * <p>
 * 四层校验依次执行，任一层失败即终止校验：
 * <ol>
 *   <li>{@link ErrorType#OPERATION_NOT_FOUND} — 操作名不在 Registry 中</li>
 *   <li>{@link ErrorType#PARAM_SCHEMA_VIOLATION} — 参数不符合 schema（缺必填/类型不匹配）</li>
 *   <li>{@link ErrorType#PERMISSION_DENIED} — 用户无权限执行此操作</li>
 *   <li>{@link ErrorType#PRECONDITION_FAILED} — 前置条件不满足（实体不存在等）</li>
 * </ol>
 *
 * @see AgentPlanExecutor
 */
public class StepValidationResult
{
    public enum ErrorType
    {
        OK,
        OPERATION_NOT_FOUND,
        PARAM_SCHEMA_VIOLATION,
        PERMISSION_DENIED,
        PRECONDITION_FAILED
    }

    private final ErrorType errorType;
    private final String errorMessage;

    private StepValidationResult(ErrorType errorType, String errorMessage)
    {
        this.errorType = errorType;
        this.errorMessage = errorMessage;
    }

    public static StepValidationResult ok()
    {
        return new StepValidationResult(ErrorType.OK, null);
    }

    public static StepValidationResult fail(ErrorType type, String message)
    {
        return new StepValidationResult(type, message);
    }

    public boolean isPassed() { return errorType == ErrorType.OK; }

    public ErrorType getErrorType() { return errorType; }

    public String getErrorMessage() { return errorMessage; }

    @Override
    public String toString()
    {
        return errorType == ErrorType.OK ? "OK" : errorType + ": " + errorMessage;
    }
}
