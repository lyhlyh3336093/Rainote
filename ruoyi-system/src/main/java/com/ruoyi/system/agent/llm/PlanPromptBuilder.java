package com.ruoyi.system.agent.llm;

import com.ruoyi.system.agent.model.AgentContext;
import com.ruoyi.system.agent.registry.AgentOperationRegistry;
import com.ruoyi.system.agent.registry.AgentOperationSpec;
import com.ruoyi.system.agent.registry.AgentParamSpec;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 构造 GLM system prompt，注入操作清单 + 上下文 ID + 输出格式约束。
 * <p>
 * R10：仅暴露操作清单中的操作名+描述+参数 schema，LLM 只能引用清单内操作。
 * R27：不传笔记正文/记录正文字段值，仅传上下文 ID。
 *
 * @see AgentOperationRegistry#exportSchemaForLlm()
 */
@Component
public class PlanPromptBuilder
{
    /** 计划超过此步数触发汇总确认（R9） */
    public static final int SUMMARY_THRESHOLD = 20;

    /**
     * 构建 system prompt。
     *
     * @param registry 操作清单注册中心
     * @param context  当前上下文（可为 null 或 empty）
     * @return 完整 system prompt 字符串
     */
    public String buildSystemPrompt(AgentOperationRegistry registry, AgentContext context)
    {
        StringBuilder sb = new StringBuilder(4096);

        sb.append("你是雨滴笔记的 AI 助手，负责理解用户的自然语言意图并生成结构化操作计划。\n\n");

        sb.append("## 可用操作清单\n");
        sb.append("你只能使用以下操作，不得编造清单外的操作名。\n\n");

        List<Map<String, Object>> schema = registry.exportSchemaForLlm();
        for (Map<String, Object> op : schema)
        {
            sb.append("- **").append(op.get("name")).append("**");
            if (Boolean.TRUE.equals(op.get("destructive")))
            {
                sb.append(" [破坏性]");
            }
            sb.append("：").append(op.get("description")).append("\n");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> params = (List<Map<String, Object>>) op.get("params");
            if (params != null && !params.isEmpty())
            {
                for (Map<String, Object> param : params)
                {
                    sb.append("  - ").append(param.get("name"))
                            .append(" (").append(param.get("type")).append(")");
                    if (Boolean.TRUE.equals(param.get("required")))
                    {
                        sb.append(" [必填]");
                    }
                    String desc = (String) param.get("description");
                    if (desc != null && !desc.isEmpty())
                    {
                        sb.append("：").append(desc);
                    }
                    // type=object 时输出允许的字段名，让 LLM 知道嵌套对象内部结构
                    @SuppressWarnings("unchecked")
                    java.util.List<String> allowedFields = (java.util.List<String>) param.get("allowedFields");
                    if (allowedFields != null && !allowedFields.isEmpty())
                    {
                        sb.append("（字段：").append(String.join(", ", allowedFields)).append("）");
                    }
                    sb.append("\n");
                }
            }
        }

        sb.append("\n## 当前上下文\n");
        if (context != null && context.hasContext())
        {
            if (context.getCurrentNoteId() != null)
            {
                sb.append("- 当前笔记 ID: ").append(context.getCurrentNoteId()).append("\n");
            }
            if (context.getCurrentDwtableId() != null)
            {
                sb.append("- 当前多维表 ID: ").append(context.getCurrentDwtableId()).append("\n");
            }
            if (context.getCurrentColumnId() != null)
            {
                sb.append("- 当前列 ID: ").append(context.getCurrentColumnId()).append("\n");
            }
            if (context.getCurrentRecordId() != null)
            {
                sb.append("- 当前记录 ID: ").append(context.getCurrentRecordId()).append("\n");
            }
        }
        else
        {
            sb.append("- 无特定上下文（全局操作）\n");
        }

        sb.append("\n## 输出格式\n");
        sb.append("返回 JSON 对象，type 字段决定响应类型：\n\n");
        sb.append("### 1. 操作计划 (type=\"PLAN\")\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"type\": \"PLAN\",\n");
        sb.append("  \"steps\": [\n");
        sb.append("    {\n");
        sb.append("      \"stepId\": \"s1\",\n");
        sb.append("      \"operationName\": \"dwtable.create\",\n");
        sb.append("      \"params\": {\"dwtable\": {\"name\": \"客户表\"}},\n");
        sb.append("      \"operationType\": \"create\",\n");
        sb.append("      \"bulkGroupId\": null,\n");
        sb.append("      \"dependsOn\": []\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n");
        sb.append("```\n\n");
        sb.append("### 2. 只读查询 (type=\"QUERY\")\n");
        sb.append("用户意图仅为查询数据时使用，直接返回查询描述，无需用户确认。\n");
        sb.append("```json\n");
        sb.append("{\"type\": \"QUERY\", \"queryDescription\": \"查询该表的记录数\"}\n");
        sb.append("```\n\n");
        sb.append("### 3. 澄清问题 (type=\"CLARIFY\")\n");
        sb.append("意图不明确时使用，返回一个澄清问题。\n");
        sb.append("```json\n");
        sb.append("{\"type\": \"CLARIFY\", \"question\": \"您想在哪个多维表中添加列？\"}\n");
        sb.append("```\n\n");

        sb.append("## 规则\n");
        sb.append("1. operationName 必须来自上方操作清单\n");
        sb.append("2. operationType 取值：query / create / update / delete\n");
        sb.append("3. dependsOn 引用前序步骤的 stepId，无依赖时为空数组\n");
        sb.append("4. bulkGroupId 用于关联同组批量操作（如同时创建多个列）\n");
        sb.append("5. 参数必须符合操作清单声明的 schema\n");
        sb.append("6. 破坏性操作（delete/batchDelete）需特别标注\n");
        sb.append("7. 只读查询（getById/list）使用 QUERY 类型，不生成 PLAN\n");

        sb.append("\n## 硬性约束（必须遵守，不可违反）\n");
        sb.append("8. 上下文优先：如果上下文（当前笔记/多维表/列/记录 ID 或用户输入）与你的预训练知识冲突，");
        sb.append("一律以上下文为准，不得用预训练知识覆盖或纠正上下文信息\n");
        sb.append("9. 禁止模糊表达：严禁使用\"可能\"、\"大概\"、\"通常来说\"、\"一般来说\"、\"也许\"、\"或许\"等模糊词汇；");
        sb.append("所有判断与参数填充必须基于上下文或操作清单做出明确结论\n");
        sb.append("10. 承认无知：若上下文不足以决定操作目标或参数（如缺失 dwtableId、用户意图不明确），");
        sb.append("必须返回 type=\"CLARIFY\" 并直接说明\"我不知道/无法确定\"具体缺什么，禁止凭猜测填充参数\n");

        return sb.toString();
    }

    /**
     * 构建用户消息（已脱敏）。
     *
     * @param userInput 用户原始输入（已由 PiiRedactor 脱敏）
     */
    public String buildUserMessage(String userInput)
    {
        return userInput;
    }
}
