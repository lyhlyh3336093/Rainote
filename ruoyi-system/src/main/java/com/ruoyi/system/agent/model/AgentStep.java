package com.ruoyi.system.agent.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 计划中的单个步骤。
 * <p>
 * 由 {@code PlanResponseParser} 从 GLM 返回的 JSON 解析构建，经 R10（操作名在清单中）
 * 与 R27（参数 schema 校验）后产出。{@code AgentPlanExecutor} 逐步执行。
 *
 * @see AgentPlan
 */
public class AgentStep
{
    /** 步骤唯一标识（计划内唯一，如 "s1"、"s2"），用于依赖引用 */
    private final String stepId;
    /** 操作名，必须在 AgentOperationRegistry 中存在（R10） */
    private final String operationName;
    /** 操作类型：query / create / update / delete */
    private final String operationType;
    /** LLM 生成的参数，经 schema 校验后填充（R27） */
    private final Map<String, Object> params;
    /** 批量组 ID（可选）：同组步骤可并行执行或汇总展示（F2） */
    private final String bulkGroupId;
    /** 是否破坏性操作（从 Registry 元数据获取，非 LLM 自填） */
    private final boolean destructive;
    /** 依赖步骤 ID 列表：本步骤须在依赖步骤完成后执行 */
    private final List<String> dependsOn;

    public AgentStep(String stepId, String operationName, String operationType,
                     Map<String, Object> params, String bulkGroupId,
                     boolean destructive, List<String> dependsOn)
    {
        this.stepId = stepId;
        this.operationName = operationName;
        this.operationType = operationType;
        this.params = params != null ? Collections.unmodifiableMap(new LinkedHashMap<>(params))
                : Collections.emptyMap();
        this.bulkGroupId = bulkGroupId;
        this.destructive = destructive;
        this.dependsOn = dependsOn != null ? Collections.unmodifiableList(dependsOn)
                : Collections.emptyList();
    }

    public String getStepId() { return stepId; }
    public String getOperationName() { return operationName; }
    public String getOperationType() { return operationType; }
    public Map<String, Object> getParams() { return params; }
    public String getBulkGroupId() { return bulkGroupId; }
    public boolean isDestructive() { return destructive; }
    public List<String> getDependsOn() { return dependsOn; }

    @Override
    public String toString()
    {
        return "AgentStep{" + stepId + " " + operationName + " (" + operationType
                + (destructive ? ", destructive" : "")
                + (bulkGroupId != null ? ", bulk=" + bulkGroupId : "")
                + (dependsOn.isEmpty() ? "" : ", depends=" + dependsOn)
                + ")}";
    }
}
