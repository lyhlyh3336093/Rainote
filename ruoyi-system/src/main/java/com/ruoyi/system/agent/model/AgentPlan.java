package com.ruoyi.system.agent.model;

import java.util.Collections;
import java.util.List;

/**
 * Agent 计划，由 GLM 解析意图后产出。
 * <p>
 * 四种类型：
 * <ul>
 *   <li>{@link PlanType#PLAN} — 多步操作计划，需用户确认后执行（R8/R9）</li>
 *   <li>{@link PlanType#QUERY} — 只读查询，直接返回结果不经确认（F1）</li>
 *   <li>{@link PlanType#CLARIFY} — 意图不明确，返回澄清问题（R11/F3，最多 3 轮）</li>
 *   <li>{@link PlanType#ERROR} — LLM 调用失败或解析错误，返回友好错误（R20a）</li>
 * </ul>
 *
 * @see AgentStep
 */
public class AgentPlan
{
    /** 计划类型 */
    public enum PlanType { PLAN, QUERY, CLARIFY, ERROR }

    private final PlanType type;
    /** PLAN 类型的步骤列表 */
    private final List<AgentStep> steps;
    /** QUERY 类型的查询结果 */
    private final String queryResult;
    /** CLARIFY 类型的澄清问题 */
    private final String clarifyingQuestion;
    /** ERROR 类型的错误消息 */
    private final String errorMessage;
    /** 步骤数超过 20 时为 true，前端触发汇总确认（R9） */
    private final boolean needsSummary;

    private AgentPlan(PlanType type, List<AgentStep> steps, String queryResult,
                      String clarifyingQuestion, String errorMessage, boolean needsSummary)
    {
        this.type = type;
        this.steps = steps != null ? Collections.unmodifiableList(steps) : Collections.emptyList();
        this.queryResult = queryResult;
        this.clarifyingQuestion = clarifyingQuestion;
        this.errorMessage = errorMessage;
        this.needsSummary = needsSummary;
    }

    /**
     * 构建多步操作计划（R8/R9）。
     * 步骤数超过 20 时 needsSummary=true（R9）。
     */
    public static AgentPlan ofPlan(List<AgentStep> steps)
    {
        return new AgentPlan(PlanType.PLAN, steps, null, null, null, steps != null && steps.size() > 20);
    }

    /** 构建只读查询结果（F1） */
    public static AgentPlan ofQuery(String queryResult)
    {
        return new AgentPlan(PlanType.QUERY, null, queryResult, null, null, false);
    }

    /** 构建澄清问题（R11/F3） */
    public static AgentPlan ofClarify(String question)
    {
        return new AgentPlan(PlanType.CLARIFY, null, null, question, null, false);
    }

    /** 构建 LLM 错误（R20a） */
    public static AgentPlan ofError(String errorMessage)
    {
        return new AgentPlan(PlanType.ERROR, null, null, null, errorMessage, false);
    }

    public PlanType getType() { return type; }
    public List<AgentStep> getSteps() { return steps; }
    public String getQueryResult() { return queryResult; }
    public String getClarifyingQuestion() { return clarifyingQuestion; }
    public String getErrorMessage() { return errorMessage; }
    public boolean isNeedsSummary() { return needsSummary; }

    public boolean isPlan() { return type == PlanType.PLAN; }
    public boolean isQuery() { return type == PlanType.QUERY; }
    public boolean isClarify() { return type == PlanType.CLARIFY; }
    public boolean isError() { return type == PlanType.ERROR; }
}
