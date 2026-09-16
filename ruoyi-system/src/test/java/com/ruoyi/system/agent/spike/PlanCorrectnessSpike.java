package com.ruoyi.system.agent.spike;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.agent.audit.PiiRedactor;
import com.ruoyi.system.agent.llm.AgentLlmService;
import com.ruoyi.system.agent.llm.PlanPromptBuilder;
import com.ruoyi.system.agent.llm.PlanResponseParser;
import com.ruoyi.system.agent.model.AgentContext;
import com.ruoyi.system.agent.model.AgentPlan;
import com.ruoyi.system.agent.model.AgentStep;
import com.ruoyi.system.agent.registry.AgentOperationRegistry;
import com.ruoyi.system.agent.registry.AgentOperationSpec;
import com.ruoyi.system.config.GlmConfig;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GLM 计划正确率 Spike 验证（U8）。
 * <p>
 * 加载 {@code spike-cases.json}（50 个用例），对每个用例调用真实 GLM API 生成计划，
 * 评估计划正确率（类型匹配 + 操作名集合匹配），门槛 ≥80%。
 * <p>
 * <b>默认禁用</b>：依赖真实 GLM API 调用（网络+费用），按需启用：
 * <pre>
 * mvn test -pl ruoyi-system -am -Dtest=PlanCorrectnessSpike \
 *   -Dglm.api-key=你的API密钥 \
 *   -Dglm.api-url=https://open.bigmodel.cn/api/paas/v4/chat/completions \
 *   -Dglm.model=glm-4
 * </pre>
 * <p>
 * 评估规则：
 * <ul>
 *   <li><b>PLAN</b>：实际类型为 PLAN 且操作名集合（去重、忽略顺序）与期望一致</li>
 *   <li><b>QUERY</b>：实际类型为 QUERY</li>
 *   <li><b>CLARIFY</b>：实际类型为 CLARIFY</li>
 *   <li><b>ERROR</b>：实际类型为 ERROR（清单外操作应被拒绝）</li>
 * </ul>
 * <p>
 * <b>详细日志</b>：每个用例打印输入→脱敏→prompt→GLM 原始响应→解析结果→匹配判定的完整链路，
 * 便于排查通过率低于门槛时的根因。汇总阶段按类别与失败原因统计。
 *
 * @see AgentLlmService#generatePlan(String, AgentContext)
 */
@Disabled("需真实 GLM API，按需启用：-Dglm.api-key=xxx -Dtest=PlanCorrectnessSpike")
class PlanCorrectnessSpike
{
    /** 正确率门槛（80%） */
    private static final double THRESHOLD = 0.80;

    /** Spike 用例文件路径（classpath） */
    private static final String CASES_FILE = "/agent/spike-cases.json";

    /** 日志截断长度（避免控制台刷屏） */
    private static final int LOG_TRUNCATE = 500;

    @Test
    void spike_planCorrectness_meetsThreshold() throws Exception
    {
        // ===== 阶段0: 前置条件——GLM API 配置 =====
        String apiKey = System.getProperty("glm.api-key");
        String apiUrl = System.getProperty("glm.api-url",
                "https://open.bigmodel.cn/api/paas/v4/chat/completions");
        String model = System.getProperty("glm.model", "glm-4");
        assertTrue(apiKey != null && !apiKey.trim().isEmpty(),
                "请通过 -Dglm.api-key=xxx 提供 GLM API 密钥");

        println("╔══════════════════════════════════════════════════════════════╗");
        println("║          GLM 计划正确率 Spike 验证（U8）                     ║");
        println("╚══════════════════════════════════════════════════════════════╝");
        println("[配置] 模型: " + model);
        println("[配置] API URL: " + apiUrl);
        println("[配置] API Key: " + maskKey(apiKey));
        println("[配置] 门槛: " + (THRESHOLD * 100) + "%");

        // 构建 GLM 配置
        GlmConfig glmConfig = new GlmConfig();
        glmConfig.setApiKey(apiKey);
        glmConfig.setApiUrl(apiUrl);
        glmConfig.setModel(model);
        glmConfig.setMaxTokens(4096);

        // ===== 阶段1: 构建 Spring 上下文 + 注册操作清单 =====
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(
                             AgentOperationRegistry.class,
                             PlanPromptBuilder.class,
                             PlanResponseParser.class,
                             PiiRedactor.class))
        {
            AgentOperationRegistry registry = ctx.getBean(AgentOperationRegistry.class);
            PlanPromptBuilder promptBuilder = ctx.getBean(PlanPromptBuilder.class);
            PlanResponseParser parser = ctx.getBean(PlanResponseParser.class);
            PiiRedactor piiRedactor = ctx.getBean(PiiRedactor.class);

            // 打印注册的操作清单（LLM 可见的操作名）
            printOperationManifest(registry);

            // 构建 AgentLlmService 并注入依赖
            AgentLlmService llmService = new AgentLlmService();
            inject(llmService, "glmConfig", glmConfig);
            inject(llmService, "registry", registry);
            inject(llmService, "promptBuilder", promptBuilder);
            inject(llmService, "responseParser", parser);
            inject(llmService, "piiRedactor", piiRedactor);

            // ===== 阶段2: 加载 Spike 用例 =====
            List<SpikeCase> cases = loadCases();
            assertTrue(cases.size() >= 50, "应加载至少 50 个用例，实际: " + cases.size());
            printCaseDistribution(cases);

            println("");
            println("═══════════════════════════════════════════════════════════════");
            println("开始逐个评估（共 " + cases.size() + " 个用例）");
            println("═══════════════════════════════════════════════════════════════");

            // ===== 阶段3: 逐个评估 =====
            int passed = 0;
            List<SpikeResult> results = new ArrayList<>();
            for (int i = 0; i < cases.size(); i++)
            {
                SpikeCase testCase = cases.get(i);
                println("");
                println("┌─────────────────────────────────────────────────────────────┐");
                println(String.format("│ 用例 %d/%d  [%s] 类别=%s",
                        i + 1, cases.size(), testCase.id, testCase.category));
                println("└─────────────────────────────────────────────────────────────┘");

                SpikeResult result = evaluate(llmService, registry, promptBuilder,
                        piiRedactor, parser, testCase);
                results.add(result);
                if (result.passed) passed++;

                println(result.detail);
                if (result.passed)
                {
                    println("  >>> 结果: PASS");
                }
                else
                {
                    println("  >>> 结果: FAIL — " + result.reason);
                }
            }

            // ===== 阶段4: 汇总报告 =====
            double rate = (double) passed / cases.size();
            println("");
            println("═══════════════════════════════════════════════════════════════");
            println("                      汇总报告");
            println("═══════════════════════════════════════════════════════════════");
            println(String.format("总通过率: %d/%d (%.1f%%)  门槛: %.0f%%  %s",
                    passed, cases.size(), rate * 100, THRESHOLD * 100,
                    rate >= THRESHOLD ? "✓ 达标" : "✗ 未达标"));

            // 按类别统计通过率
            printCategorySummary(results);

            // 按失败原因分类统计
            printFailureReasonSummary(results);

            // 失败用例速查表
            printFailureList(results);

            println("═══════════════════════════════════════════════════════════════");

            assertTrue(rate >= THRESHOLD,
                    "GLM 计划正确率 " + String.format("%.1f%%", rate * 100)
                            + " 低于门槛 " + (THRESHOLD * 100) + "%，详见上方日志");
        }
    }

    // ==================== 核心：评估单个用例（含详细日志）====================

    /**
     * 评估单个用例：手动拆解 generatePlan 的各步骤，在每个关键节点打印详细日志。
     * <p>
     * 步骤链路：脱敏 → 构造 prompt → 调用 GLM → 解析响应 → 匹配判定。
     *
     * @param llmService   LLM 服务（用于反射调用 callGlmWithRetry）
     * @param registry     操作清单注册中心
     * @param promptBuilder prompt 构造器
     * @param piiRedactor  PII 脱敏器
     * @param parser       响应解析器
     * @param testCase     测试用例
     * @return 评估结果
     */
    private SpikeResult evaluate(AgentLlmService llmService, AgentOperationRegistry registry,
                                 PlanPromptBuilder promptBuilder, PiiRedactor piiRedactor,
                                 PlanResponseParser parser, SpikeCase testCase)
    {
        // ----- 节点1: 用例基本信息 -----
        AgentContext context = buildContext(testCase.context);
        println("  [1] 用例输入: \"" + testCase.userInput + "\"");
        println("  [1] 上下文: " + formatContext(context));
        println("  [1] 期望: 类型=" + testCase.expectedType
                + (testCase.expectedOperations.isEmpty()
                        ? "" : " 操作集合=" + testCase.expectedOperations));

        // ----- 节点2: PII 脱敏 -----
        String redactedInput = piiRedactor.redact(testCase.userInput);
        if (!redactedInput.equals(testCase.userInput))
        {
            println("  [2] PII 脱敏: \"" + testCase.userInput + "\" → \"" + redactedInput + "\"");
        }
        else
        {
            println("  [2] PII 脱敏: 无变化（输入不含 PII）");
        }

        // ----- 节点3: 构造 prompt -----
        String systemPrompt = promptBuilder.buildSystemPrompt(registry, context);
        String userMessage = promptBuilder.buildUserMessage(redactedInput);
        println("  [3] System Prompt 摘要 (" + systemPrompt.length() + " 字符): "
                + truncate(systemPrompt.replace("\n", " "), 200));
        println("  [3] User Message: " + truncate(userMessage, 200));

        // ----- 节点4: 调用 GLM（通过反射获取原始响应）-----
        String llmResponse;
        try
        {
            Method callMethod = AgentLlmService.class.getDeclaredMethod(
                    "callGlmWithRetry", String.class, String.class);
            callMethod.setAccessible(true);
            long startMs = System.currentTimeMillis();
            llmResponse = (String) callMethod.invoke(llmService, systemPrompt, userMessage);
            long elapsed = System.currentTimeMillis() - startMs;
            println("  [4] GLM 调用耗时: " + elapsed + "ms");
        }
        catch (Exception e)
        {
            String msg = "GLM 调用反射失败: " + e.getMessage();
            println("  [4] ERROR " + msg);
            return SpikeResult.fail(testCase, "ERROR", "[]", msg,
                    formatFail(testCase, "ERROR", "[]", "GLM反射异常: " + e.getMessage()));
        }

        if (llmResponse == null)
        {
            String msg = "GLM 调用失败（全部重试后返回 null），请检查 API Key/网络/配额";
            println("  [4] ERROR " + msg);
            return SpikeResult.fail(testCase, "ERROR", "[]", msg,
                    formatFail(testCase, "ERROR", "[]", msg));
        }
        println("  [4] GLM 原始响应 (" + llmResponse.length() + " 字符): "
                + truncate(llmResponse, LOG_TRUNCATE));

        // ----- 节点5: 解析响应 -----
        AgentPlan plan;
        try
        {
            plan = parser.parse(llmResponse, registry);
        }
        catch (Exception e)
        {
            String msg = "解析异常: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            println("  [5] ERROR " + msg);
            return SpikeResult.fail(testCase, "ERROR", "[]", msg,
                    formatFail(testCase, "ERROR", "[]", msg));
        }

        if (plan == null)
        {
            String msg = "解析返回 null";
            println("  [5] ERROR " + msg);
            return SpikeResult.fail(testCase, "null", "[]", msg,
                    formatFail(testCase, "null", "[]", msg));
        }

        // 打印解析后的计划详情
        String actualType = plan.getType().name();
        List<String> actualOps = extractOperations(plan);
        printParsedPlanDetail(plan, actualType, actualOps);

        // ----- 节点6: 匹配判定 -----
        String actualOpsStr = actualOps.toString();
        return matchAndJudge(testCase, actualType, actualOps, actualOpsStr, plan);
    }

    /**
     * 节点6：匹配判定，打印类型匹配与操作集合差异详情。
     */
    private SpikeResult matchAndJudge(SpikeCase testCase, String actualType,
                                       List<String> actualOps, String actualOpsStr,
                                       AgentPlan plan)
    {
        // 6a. 类型匹配
        if (!testCase.expectedType.equals(actualType))
        {
            String reason = "类型不匹配: 期望 " + testCase.expectedType + " 实际 " + actualType;
            println("  [6] 类型不匹配: 期望=" + testCase.expectedType + " 实际=" + actualType);
            if (plan.isError())
            {
                println("  [6] ERROR 计划错误消息: " + plan.getErrorMessage());
            }
            else if (plan.isClarify())
            {
                println("  [6] CLARIFY 澄清问题: " + plan.getClarifyingQuestion());
            }
            else if (plan.isQuery())
            {
                println("  [6] QUERY 查询结果: " + truncate(plan.getQueryResult(), 200));
            }
            return SpikeResult.fail(testCase, actualType, actualOpsStr, reason,
                    formatFail(testCase, actualType, actualOpsStr, reason));
        }
        println("  [6] 类型匹配 ✓ (" + actualType + ")");

        // 6b. PLAN 类型需校验操作名集合
        if ("PLAN".equals(testCase.expectedType))
        {
            Set<String> expectedSet = new HashSet<>(testCase.expectedOperations);
            Set<String> actualSet = new HashSet<>(actualOps);

            if (!expectedSet.equals(actualSet))
            {
                // 计算差集，打印具体差异
                Set<String> expectedOnly = new HashSet<>(expectedSet);
                expectedOnly.removeAll(actualSet);
                Set<String> actualOnly = new HashSet<>(actualSet);
                actualOnly.removeAll(expectedSet);

                StringBuilder reason = new StringBuilder("操作名集合不匹配");
                if (!expectedOnly.isEmpty())
                {
                    reason.append(" | 期望有但实际缺失: ").append(expectedOnly);
                }
                if (!actualOnly.isEmpty())
                {
                    reason.append(" | 实际多出: ").append(actualOnly);
                }

                println("  [6] 操作集合不匹配 ✗");
                println("  [6]   期望集合: " + expectedSet);
                println("  [6]   实际集合: " + actualSet);
                if (!expectedOnly.isEmpty())
                {
                    println("  [6]   期望有但实际缺失: " + expectedOnly);
                }
                if (!actualOnly.isEmpty())
                {
                    println("  [6]   实际多出（清单外或多余操作）: " + actualOnly);
                }
                return SpikeResult.fail(testCase, actualType, actualOpsStr, reason.toString(),
                        formatFail(testCase, actualType, actualOpsStr, reason.toString()));
            }
            println("  [6] 操作集合匹配 ✓ (" + actualSet + ")");
        }

        return SpikeResult.ok(testCase, actualType, actualOpsStr,
                formatPass(testCase, actualType, actualOpsStr));
    }

    // ==================== 日志打印辅助方法 ====================

    /** 打印注册的操作清单（LLM 可见的操作名列表） */
    private void printOperationManifest(AgentOperationRegistry registry)
    {
        Map<String, AgentOperationSpec> ops = registry.getOperations();
        println("");
        println("[操作清单] 已注册 " + ops.size() + " 个操作（LLM 仅能从这些操作中选择）:");
        for (AgentOperationSpec spec : ops.values())
        {
            String destructive = spec.isDestructive() ? " [破坏性]" : "";
            String perm = spec.getPermissionKey();
            perm = (perm != null && !perm.isEmpty()) ? " 权限=" + perm : "";
            println("  - " + spec.getName() + destructive + perm
                    + " : " + truncate(spec.getDescription(), 60));
        }
    }

    /** 打印用例类别分布 */
    private void printCaseDistribution(List<SpikeCase> cases)
    {
        Map<String, Integer> dist = new TreeMap<>();
        for (SpikeCase c : cases)
        {
            dist.merge(c.category, 1, Integer::sum);
        }
        println("");
        println("[用例分布] 共 " + cases.size() + " 个用例:");
        for (Map.Entry<String, Integer> e : dist.entrySet())
        {
            println("  " + e.getKey() + ": " + e.getValue() + " 个");
        }
    }

    /** 打印解析后的计划详情 */
    private void printParsedPlanDetail(AgentPlan plan, String actualType, List<String> actualOps)
    {
        println("  [5] 解析结果: 类型=" + actualType
                + (plan.isPlan() ? " 步骤数=" + plan.getSteps().size() : ""));
        if (plan.isError())
        {
            println("  [5]   错误消息: " + plan.getErrorMessage());
        }
        else if (plan.isClarify())
        {
            println("  [5]   澄清问题: " + plan.getClarifyingQuestion());
        }
        else if (plan.isQuery())
        {
            println("  [5]   查询结果: " + truncate(plan.getQueryResult(), 200));
        }
        else if (plan.isPlan())
        {
            for (int i = 0; i < plan.getSteps().size(); i++)
            {
                AgentStep s = plan.getSteps().get(i);
                println("  [5]   步骤" + (i + 1) + ": " + s.getStepId()
                        + " " + s.getOperationName()
                        + " (" + s.getOperationType()
                        + (s.isDestructive() ? ", 破坏性" : "")
                        + (s.getBulkGroupId() != null ? ", bulk=" + s.getBulkGroupId() : "")
                        + ")");
                println("  [5]           参数: " + truncate(JSON.toJSONString(s.getParams()), 200));
            }
            println("  [5]   实际操作名集合: " + new HashSet<>(actualOps));
        }
    }

    /** 按类别统计通过率 */
    private void printCategorySummary(List<SpikeResult> results)
    {
        Map<String, int[]> stats = new TreeMap<>(); // category -> [passed, total]
        for (SpikeResult r : results)
        {
            int[] s = stats.computeIfAbsent(r.category, k -> new int[2]);
            s[1]++;
            if (r.passed) s[0]++;
        }
        println("");
        println("── 按类别通过率 ──");
        for (Map.Entry<String, int[]> e : stats.entrySet())
        {
            int[] s = e.getValue();
            double rate = s[1] == 0 ? 0 : (double) s[0] / s[1] * 100;
            String bar = barChart(s[0], s[1]);
            println(String.format("  %-20s %d/%d (%5.1f%%) %s",
                    e.getKey(), s[0], s[1], rate, bar));
        }
    }

    /** 按失败原因分类统计 */
    private void printFailureReasonSummary(List<SpikeResult> results)
    {
        Map<String, Integer> reasonStats = new TreeMap<>();
        for (SpikeResult r : results)
        {
            if (!r.passed)
            {
                // 归类失败原因
                String category = classifyFailureReason(r.reason);
                reasonStats.merge(category, 1, Integer::sum);
            }
        }
        if (reasonStats.isEmpty()) return;
        println("");
        println("── 失败原因分类 ──");
        for (Map.Entry<String, Integer> e : reasonStats.entrySet())
        {
            println("  " + e.getKey() + ": " + e.getValue() + " 个");
        }
    }

    /** 失败用例速查表 */
    private void printFailureList(List<SpikeResult> results)
    {
        List<SpikeResult> failures = new ArrayList<>();
        for (SpikeResult r : results)
        {
            if (!r.passed) failures.add(r);
        }
        if (failures.isEmpty())
        {
            println("");
            println("── 失败用例: 无 ──");
            return;
        }
        println("");
        println("── 失败用例速查（" + failures.size() + " 个）──");
        for (SpikeResult r : failures)
        {
            println("  [" + r.id + "] " + r.category + " | 输入: \"" + r.userInput + "\"");
            println("        期望: " + r.expectedType + " " + r.expectedOperations);
            println("        实际: " + r.actualType + " " + r.actualOperations);
            println("        原因: " + r.reason);
        }
    }

    // ==================== 工具方法 ====================

    /** 将失败原因归类为大类 */
    private String classifyFailureReason(String reason)
    {
        if (reason == null) return "未知";
        if (reason.contains("类型不匹配")) return "类型不匹配（如期望 PLAN 实际 CLARIFY）";
        if (reason.contains("期望有但实际缺失")) return "操作缺失（期望的操作未生成）";
        if (reason.contains("实际多出")) return "操作多余（生成了清单外/多余操作）";
        if (reason.contains("操作名集合不匹配")) return "操作集合不匹配";
        if (reason.contains("GLM")) return "GLM 调用失败";
        if (reason.contains("解析")) return "解析异常";
        if (reason.contains("null")) return "返回 null";
        return "其他";
    }

    /** 文本进度条 */
    private String barChart(int passed, int total)
    {
        int len = 20;
        int filled = total == 0 ? 0 : (int) Math.round((double) passed / total * len);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < len; i++) sb.append(i < filled ? "█" : "░");
        sb.append("]");
        return sb.toString();
    }

    /** 格式化上下文 */
    private String formatContext(AgentContext ctx)
    {
        if (!ctx.hasContext()) return "空（全局意图）";
        List<String> parts = new ArrayList<>();
        if (ctx.getCurrentNoteId() != null) parts.add("noteId=" + ctx.getCurrentNoteId());
        if (ctx.getCurrentDwtableId() != null) parts.add("dwtableId=" + ctx.getCurrentDwtableId());
        if (ctx.getCurrentColumnId() != null) parts.add("columnId=" + ctx.getCurrentColumnId());
        if (ctx.getCurrentRecordId() != null) parts.add("recordId=" + ctx.getCurrentRecordId());
        return String.join(", ", parts);
    }

    private String formatPass(SpikeCase tc, String actualType, String actualOps)
    {
        return String.format("[%s] PASS %s | \"%s\" → %s %s",
                tc.id, tc.category, tc.userInput, actualType, actualOps);
    }

    private String formatFail(SpikeCase tc, String actualType, String actualOps, String reason)
    {
        return String.format("[%s] FAIL %s | \"%s\" | 期望 %s %s | 实际 %s %s | %s",
                tc.id, tc.category, tc.userInput,
                tc.expectedType, tc.expectedOperations,
                actualType, actualOps, reason);
    }

    private String maskKey(String key)
    {
        if (key == null || key.length() <= 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    private String truncate(String s, int max)
    {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...(" + s.length() + "字符)";
    }

    private void println(String s)
    {
        System.out.println(s);
    }

    /** 从计划中提取操作名列表 */
    private List<String> extractOperations(AgentPlan plan)
    {
        List<String> ops = new ArrayList<>();
        if (plan.getSteps() != null)
        {
            for (AgentStep step : plan.getSteps())
            {
                ops.add(step.getOperationName());
            }
        }
        return ops;
    }

    /** 从用例 context 字段构建 AgentContext */
    private AgentContext buildContext(JSONObject contextJson)
    {
        if (contextJson == null || contextJson.isEmpty())
        {
            return AgentContext.empty();
        }
        Long noteId = contextJson.getLong("currentNoteId");
        Long dwtableId = contextJson.getLong("currentDwtableId");
        Long columnId = contextJson.getLong("currentColumnId");
        Long recordId = contextJson.getLong("currentRecordId");
        return new AgentContext(noteId, dwtableId, columnId, recordId);
    }

    /** 加载 classpath 下的 spike-cases.json */
    private List<SpikeCase> loadCases() throws Exception
    {
        try (InputStream is = PlanCorrectnessSpike.class.getResourceAsStream(CASES_FILE))
        {
            assertTrue(is != null, "无法加载 Spike 用例文件: " + CASES_FILE);
            String content = new String(readAll(is), StandardCharsets.UTF_8);
            JSONObject root = JSON.parseObject(content);
            JSONArray cases = root.getJSONArray("cases");
            List<SpikeCase> result = new ArrayList<>(cases.size());
            for (int i = 0; i < cases.size(); i++)
            {
                JSONObject c = cases.getJSONObject(i);
                SpikeCase sc = new SpikeCase();
                sc.id = c.getString("id");
                sc.category = c.getString("category");
                sc.userInput = c.getString("userInput");
                sc.context = c.getJSONObject("context");
                sc.expectedType = c.getString("expectedType");
                JSONArray ops = c.getJSONArray("expectedOperations");
                sc.expectedOperations = new ArrayList<>();
                if (ops != null)
                {
                    for (int j = 0; j < ops.size(); j++)
                    {
                        sc.expectedOperations.add(ops.getString(j));
                    }
                }
                sc.description = c.getString("description");
                result.add(sc);
            }
            return result;
        }
    }

    private byte[] readAll(InputStream is) throws Exception
    {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1)
        {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private void inject(Object target, String fieldName, Object value) throws Exception
    {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** Spike 用例 POJO */
    private static class SpikeCase
    {
        String id;
        String category;
        String userInput;
        JSONObject context;
        String expectedType;
        List<String> expectedOperations;
        String description;
    }

    /** 单个用例评估结果（含用例上下文，便于汇总统计） */
    private static class SpikeResult
    {
        boolean passed;
        String id;
        String category;
        String userInput;
        String expectedType;
        List<String> expectedOperations;
        String actualType;
        String actualOperations;
        String reason;
        String detail;

        static SpikeResult ok(SpikeCase tc, String actualType, String actualOps, String detail)
        {
            SpikeResult r = base(tc, actualType, actualOps);
            r.passed = true;
            r.detail = detail;
            return r;
        }

        static SpikeResult fail(SpikeCase tc, String actualType, String actualOps,
                                String reason, String detail)
        {
            SpikeResult r = base(tc, actualType, actualOps);
            r.passed = false;
            r.reason = reason;
            r.detail = detail;
            return r;
        }

        private static SpikeResult base(SpikeCase tc, String actualType, String actualOps)
        {
            SpikeResult r = new SpikeResult();
            r.id = tc.id;
            r.category = tc.category;
            r.userInput = tc.userInput;
            r.expectedType = tc.expectedType;
            r.expectedOperations = tc.expectedOperations;
            r.actualType = actualType;
            r.actualOperations = actualOps;
            return r;
        }
    }
}
