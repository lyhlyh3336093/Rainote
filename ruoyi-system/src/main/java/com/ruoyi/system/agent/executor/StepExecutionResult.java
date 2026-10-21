package com.ruoyi.system.agent.executor;

import com.alibaba.fastjson2.JSON;

/**
 * 步骤执行结果。
 * <p>
 * 由 {@link AgentPlanExecutor} 在每步执行后产出，通过 {@link ProgressCallback} 回调前端。
 *
 * @see ProgressCallback
 */
public class StepExecutionResult
{
    /** 结果摘要最大长度（字符），防止大对象（如列表）撑爆 SSE 事件与审计存储 */
    private static final int RESULT_SUMMARY_MAX_LENGTH = 2000;

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

    /**
     * 获取本步骤执行结果的摘要（SSE step_completed 事件 resultSummary 字段与审计 result 落库共用）。
     */
    public String getResultSummary()
    {
        return summarizeResult(result);
    }

    /**
     * 将步骤执行结果转为摘要字符串：String 直接使用，其他类型 fastjson 序列化，
     * 统一截断至 {@link #RESULT_SUMMARY_MAX_LENGTH} 字符兜底（防止列表等大对象撑爆
     * SSE 事件与审计存储）；序列化失败（如循环引用）返回 null，结果缺失不阻塞业务。
     *
     * @param result 步骤执行结果（Service 方法返回值），可为 null
     * @return 摘要字符串，result 为 null 或序列化失败时返回 null
     */
    public static String summarizeResult(Object result)
    {
        if (result == null)
        {
            return null;
        }
        String summary;
        try
        {
            summary = result instanceof String ? (String) result : JSON.toJSONString(result);
        }
        catch (Exception e)
        {
            return null;
        }
        return summary.length() > RESULT_SUMMARY_MAX_LENGTH
                ? summary.substring(0, RESULT_SUMMARY_MAX_LENGTH) : summary;
    }
}
