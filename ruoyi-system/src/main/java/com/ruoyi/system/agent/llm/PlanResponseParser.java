package com.ruoyi.system.agent.llm;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.agent.model.AgentPlan;
import com.ruoyi.system.agent.model.AgentStep;
import com.ruoyi.system.agent.registry.AgentOperationRegistry;
import com.ruoyi.system.agent.registry.AgentOperationSpec;
import com.ruoyi.system.agent.registry.AgentParamSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 解析 GLM 返回 JSON 为 {@link AgentPlan}，执行 R10/R27 校验。
 * <p>
 * 校验规则：
 * <ul>
 *   <li>R10：每步 operationName 必须在 Registry 中存在</li>
 *   <li>R27：每步必填参数必须存在</li>
 *   <li>stepId 唯一、dependsOn 引用有效</li>
 *   <li>破坏性标记从 Registry 元数据获取（非 LLM 自填）</li>
 * </ul>
 * 解析失败返回 {@link AgentPlan#ofError(String)}，不抛异常。
 */
@Component
public class PlanResponseParser
{
    private static final Logger log = LoggerFactory.getLogger(PlanResponseParser.class);

    /** 单计划最大步骤数 */
    private static final int MAX_PLAN_STEPS = 50;

    /** 单计划内同类实体删除总数上限 */
    private static final int MAX_DELETE_PER_ENTITY = 10;

    /** batchDelete 单步 ID 列表长度上限 */
    private static final int MAX_BATCH_DELETE_IDS = 20;

    /**
     * 解析 GLM 响应为 AgentPlan。
     *
     * @param llmResponse GLM 返回的原始文本
     * @param registry    操作清单注册中心
     * @return 解析后的 AgentPlan（含 ERROR 类型表示解析失败）
     */
    public AgentPlan parse(String llmResponse, AgentOperationRegistry registry)
    {
        if (llmResponse == null || llmResponse.trim().isEmpty())
        {
            return AgentPlan.ofError("AI 返回为空");
        }

        JSONObject json;
        try
        {
            json = JSON.parseObject(llmResponse);
        }
        catch (Exception e)
        {
            log.warn("GLM 返回非 JSON: {}", truncate(llmResponse, 200));
            return AgentPlan.ofError("AI 返回格式错误，无法解析");
        }

        String type = json.getString("type");
        if (type == null)
        {
            return AgentPlan.ofError("AI 返回缺少 type 字段");
        }

        switch (type.toUpperCase())
        {
            case "PLAN":
                return parsePlan(json, registry);
            case "QUERY":
                return parseQuery(json);
            case "CLARIFY":
                return parseClarify(json);
            default:
                return AgentPlan.ofError("未知的响应类型: " + type);
        }
    }

    /**
     * 解析 PLAN 类型响应。
     */
    private AgentPlan parsePlan(JSONObject json, AgentOperationRegistry registry)
    {
        JSONArray stepsArray = json.getJSONArray("steps");
        if (stepsArray == null || stepsArray.isEmpty())
        {
            return AgentPlan.ofError("计划无步骤");
        }

        // 步骤数上限校验
        if (stepsArray.size() > MAX_PLAN_STEPS)
        {
            log.warn("计划步骤数 {} 超过上限 {}", stepsArray.size(), MAX_PLAN_STEPS);
            return AgentPlan.ofError("计划步骤数超过上限 " + MAX_PLAN_STEPS);
        }

        List<AgentStep> steps = new ArrayList<>();
        Set<String> stepIds = new HashSet<>();

        for (int i = 0; i < stepsArray.size(); i++)
        {
            JSONObject stepJson = stepsArray.getJSONObject(i);
            AgentStep step = parseStep(stepJson, registry, i, stepIds);
            if (step == null)
            {
                // parseStep 已记录错误，返回 ERROR 计划
                return AgentPlan.ofError("步骤 " + (i + 1) + " 解析失败");
            }
            steps.add(step);
        }

        // 校验 dependsOn 引用的 stepId 存在
        for (AgentStep step : steps)
        {
            for (String depId : step.getDependsOn())
            {
                if (!stepIds.contains(depId))
                {
                    return AgentPlan.ofError("步骤 " + step.getStepId()
                            + " 依赖不存在的步骤: " + depId);
                }
            }
        }

        // 批量删除阈值校验
        String thresholdError = validateBatchDeleteThreshold(steps);
        if (thresholdError != null)
        {
            log.warn("批量删除阈值拦截: {}", thresholdError);
            return AgentPlan.ofError(thresholdError);
        }

        return AgentPlan.ofPlan(steps);
    }

    /**
     * 解析单个步骤，执行 R10/R27 校验。
     *
     * @return 解析成功的 AgentStep，失败返回 null
     */
    @SuppressWarnings("unchecked")
    private AgentStep parseStep(JSONObject stepJson, AgentOperationRegistry registry,
                                int index, Set<String> existingStepIds)
    {
        String stepId = stepJson.getString("stepId");
        if (stepId == null || stepId.isEmpty())
        {
            stepId = "s" + (index + 1);
        }
        if (!existingStepIds.add(stepId))
        {
            log.warn("步骤 stepId 重复: {}", stepId);
            return null;
        }

        String operationName = stepJson.getString("operationName");
        if (operationName == null || operationName.isEmpty())
        {
            log.warn("步骤 {} 缺少 operationName", stepId);
            return null;
        }

        // R10: 操作名必须在 Registry 中存在
        AgentOperationSpec spec = registry.getOperation(operationName);
        if (spec == null)
        {
            log.warn("R10 拒绝: 操作 '{}' 不在清单中", operationName);
            return null;
        }

        String operationType = stepJson.getString("operationType");
        if (operationType == null || operationType.isEmpty())
        {
            // 从操作名推断类型
            operationType = inferOperationType(operationName);
        }

        // 解析参数
        Map<String, Object> params = (Map<String, Object>) stepJson.get("params");
        if (params == null)
        {
            params = new java.util.LinkedHashMap<>();
        }

        // R27: 校验必填参数存在
        for (AgentParamSpec paramSpec : spec.getParams())
        {
            if (paramSpec.isFrameworkInjected())
            {
                continue; // 框架注入参数（如 userId）不需要 LLM 提供
            }
            if (paramSpec.isRequired() && !params.containsKey(paramSpec.getName()))
            {
                log.warn("R27 拒绝: 操作 '{}' 缺少必填参数 '{}'", operationName, paramSpec.getName());
                return null;
            }
        }

        String bulkGroupId = stepJson.getString("bulkGroupId");
        if (bulkGroupId != null && bulkGroupId.isEmpty())
        {
            bulkGroupId = null;
        }

        // 破坏性标记从 Registry 元数据获取（非 LLM 自填）
        boolean destructive = spec.isDestructive();

        // 依赖步骤
        List<String> dependsOn = new ArrayList<>();
        JSONArray depsArray = stepJson.getJSONArray("dependsOn");
        if (depsArray != null)
        {
            for (int j = 0; j < depsArray.size(); j++)
            {
                dependsOn.add(depsArray.getString(j));
            }
        }

        return new AgentStep(stepId, operationName, operationType, params,
                bulkGroupId, destructive, dependsOn);
    }

    /** 从操作名推断操作类型 */
    private String inferOperationType(String operationName)
    {
        if (operationName.contains("delete") || operationName.contains("batchDelete"))
        {
            return "delete";
        }
        if (operationName.contains("create") || operationName.contains("insert"))
        {
            return "create";
        }
        if (operationName.contains("update"))
        {
            return "update";
        }
        return "query";
    }

    /**
     * 批量删除阈值校验。
     * <p>
     * 规则：
     * <ul>
     *   <li>batchDelete 单步 ID 列表长度 ≤ {@value #MAX_BATCH_DELETE_IDS}</li>
     *   <li>单计划内同类实体的 delete/batchDelete 涉及实体总数 ≤ {@value #MAX_DELETE_PER_ENTITY}</li>
     * </ul>
     *
     * @param steps 计划步骤列表
     * @return 错误消息，null 表示通过
     */
    private String validateBatchDeleteThreshold(List<AgentStep> steps)
    {
        // 按实体类型累计删除数量
        Map<String, Integer> deleteCountByEntity = new java.util.HashMap<>();

        for (AgentStep step : steps)
        {
            String opName = step.getOperationName();
            String opNameLower = opName.toLowerCase();
            if (!opNameLower.contains("delete"))
            {
                continue;
            }

            // 提取实体类型（操作名第一个点之前的部分，如 "note.batchDelete" → "note"）
            String entityType = opName.contains(".")
                    ? opName.substring(0, opName.indexOf('.')) : opName;

            boolean isBatch = opNameLower.contains("batchdelete");
            int count;

            if (isBatch)
            {
                // batchDelete：从 params.ids 计算
                Object idsObj = step.getParams().get("ids");
                count = countIds(idsObj);
                if (count > MAX_BATCH_DELETE_IDS)
                {
                    return "批量删除步骤 " + step.getStepId()
                            + " 的 ID 列表长度 " + count + " 超过上限 " + MAX_BATCH_DELETE_IDS;
                }
            }
            else
            {
                // 单个删除：计 1
                count = 1;
            }

            int total = deleteCountByEntity.getOrDefault(entityType, 0) + count;
            deleteCountByEntity.put(entityType, total);

            if (total > MAX_DELETE_PER_ENTITY)
            {
                return "实体类型 '" + entityType + "' 的删除总数 " + total
                        + " 超过单计划上限 " + MAX_DELETE_PER_ENTITY;
            }
        }

        return null;
    }

    /**
     * 计算 IDs 列表中的元素数量。
     */
    private int countIds(Object idsObj)
    {
        if (idsObj == null) return 0;
        if (idsObj instanceof List) return ((List<?>) idsObj).size();
        if (idsObj instanceof Object[]) return ((Object[]) idsObj).length;
        if (idsObj instanceof JSONArray) return ((JSONArray) idsObj).size();
        return 1;
    }

    private AgentPlan parseQuery(JSONObject json)
    {
        String desc = json.getString("queryDescription");
        if (desc == null || desc.isEmpty())
        {
            desc = json.getString("queryResult");
        }
        if (desc == null)
        {
            desc = "查询操作";
        }
        return AgentPlan.ofQuery(desc);
    }

    private AgentPlan parseClarify(JSONObject json)
    {
        String question = json.getString("question");
        if (question == null || question.isEmpty())
        {
            question = json.getString("clarifyingQuestion");
        }
        if (question == null || question.isEmpty())
        {
            return AgentPlan.ofError("澄清问题为空");
        }
        return AgentPlan.ofClarify(question);
    }

    private String truncate(String s, int max)
    {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
