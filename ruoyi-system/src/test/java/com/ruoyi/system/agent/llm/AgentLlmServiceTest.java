package com.ruoyi.system.agent.llm;

import com.ruoyi.system.agent.annotation.AgentOperation;
import com.ruoyi.system.agent.annotation.AgentParam;
import com.ruoyi.system.agent.audit.PiiRedactor;
import com.ruoyi.system.agent.model.AgentContext;
import com.ruoyi.system.agent.model.AgentPlan;
import com.ruoyi.system.agent.model.AgentStep;
import com.ruoyi.system.agent.registry.AgentOperationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentLlmService 相关单元测试。
 * <p>
 * 覆盖 12 个测试场景：Happy path、多步计划、清单外操作拒绝（R10/AE3）、
 * 参数 schema 校验失败（R27）、LLM 返回非 JSON、只读查询路径（F1）、
 * 澄清问题（F3/R11）、PiiRedactor 脱敏、空输入处理、LLM 调用失败、
 * 无效依赖、空响应。
 */
class AgentLlmServiceTest
{
    private final PlanResponseParser parser = new PlanResponseParser();
    private final PiiRedactor piiRedactor = new PiiRedactor();

    // ===== 测试用 Service（提供操作清单） =====

    @Component
    static class TestOperationService
    {
        @AgentOperation(name = "dwtable.create", description = "创建多维表")
        public int createDwtable(@AgentParam(value = "dwtable", type = "object",
                objectType = "TestEntity", allowedFields = {"name", "noteId"},
                description = "多维表信息") TestEntity dwtable)
        { return 1; }

        @AgentOperation(name = "dwtable.delete", destructive = true,
                permissionKey = "system:dwtable:remove", description = "删除多维表")
        public int deleteDwtable(@AgentParam(value = "id", type = "long",
                description = "多维表ID") Long id)
        { return 1; }

        @AgentOperation(name = "column.create", description = "创建列")
        public int createColumn(@AgentParam(value = "column", type = "object",
                objectType = "TestEntity", allowedFields = {"name", "type", "dwtableId"},
                description = "列配置") TestEntity column)
        { return 1; }

        @AgentOperation(name = "record.list", description = "查询记录列表")
        public List<Object> listRecords(@AgentParam(value = "filter", type = "object",
                objectType = "TestEntity", allowedFields = {"name"},
                description = "筛选条件") TestEntity filter, Long userId)
        { return Collections.emptyList(); }

        @AgentOperation(name = "dwtable.batchDelete", destructive = true,
                permissionKey = "system:dwtable:remove", description = "批量删除多维表")
        public int batchDeleteDwtable(@AgentParam(value = "ids", type = "list",
                description = "多维表ID列表") String[] ids)
        { return ids.length; }
    }

    public static class TestEntity
    {
        private String name;
        private Long id;
        private String type;
        private Long dwtableId;
        private Long noteId;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Long getDwtableId() { return dwtableId; }
        public void setDwtableId(Long dwtableId) { this.dwtableId = dwtableId; }
        public Long getNoteId() { return noteId; }
        public void setNoteId(Long noteId) { this.noteId = noteId; }
    }

    private AgentOperationRegistry createRegistry()
    {
        AnnotationConfigApplicationContext ctx =
                new AnnotationConfigApplicationContext(TestOperationService.class,
                        AgentOperationRegistry.class);
        return ctx.getBean(AgentOperationRegistry.class);
    }

    // ===== 场景1: Happy path — 单步创建计划 =====

    @Test
    void test_happyPath_singleStepCreatePlan()
    {
        AgentOperationRegistry registry = createRegistry();
        String llmResponse = "{\"type\":\"PLAN\",\"steps\":[{\"stepId\":\"s1\","
                + "\"operationName\":\"dwtable.create\","
                + "\"params\":{\"dwtable\":{\"name\":\"客户表\"}},"
                + "\"operationType\":\"create\",\"bulkGroupId\":null,\"dependsOn\":[]}]}";

        AgentPlan plan = parser.parse(llmResponse, registry);

        assertTrue(plan.isPlan(), "应为 PLAN 类型");
        assertEquals(1, plan.getSteps().size(), "应有 1 个步骤");

        AgentStep step = plan.getSteps().get(0);
        assertEquals("dwtable.create", step.getOperationName());
        assertEquals("create", step.getOperationType());
        assertFalse(step.isDestructive(), "create 非破坏性");
        assertNotNull(step.getParams().get("dwtable"), "应包含 dwtable 参数");
    }

    // ===== 场景2: 多步计划 + bulkGroupId 关联 =====

    @Test
    void test_multiStepPlan_withBulkGroupId()
    {
        AgentOperationRegistry registry = createRegistry();
        String llmResponse = "{\"type\":\"PLAN\",\"steps\":["
                // s1: 建表
                + "{\"stepId\":\"s1\",\"operationName\":\"dwtable.create\","
                + "\"params\":{\"dwtable\":{\"name\":\"客户表\"}},"
                + "\"operationType\":\"create\",\"dependsOn\":[]},"
                // s2: 建列-姓名（bulkGroupId=cols，依赖 s1）
                + "{\"stepId\":\"s2\",\"operationName\":\"column.create\","
                + "\"params\":{\"column\":{\"name\":\"姓名\",\"type\":\"text\",\"dwtableId\":1}},"
                + "\"operationType\":\"create\",\"bulkGroupId\":\"cols\",\"dependsOn\":[\"s1\"]},"
                // s3: 建列-电话
                + "{\"stepId\":\"s3\",\"operationName\":\"column.create\","
                + "\"params\":{\"column\":{\"name\":\"电话\",\"type\":\"text\",\"dwtableId\":1}},"
                + "\"operationType\":\"create\",\"bulkGroupId\":\"cols\",\"dependsOn\":[\"s1\"]},"
                // s4: 建列-公司
                + "{\"stepId\":\"s4\",\"operationName\":\"column.create\","
                + "\"params\":{\"column\":{\"name\":\"公司\",\"type\":\"text\",\"dwtableId\":1}},"
                + "\"operationType\":\"create\",\"bulkGroupId\":\"cols\",\"dependsOn\":[\"s1\"]},"
                // s5: 删旧表
                + "{\"stepId\":\"s5\",\"operationName\":\"dwtable.delete\","
                + "\"params\":{\"id\":999},"
                + "\"operationType\":\"delete\",\"dependsOn\":[]}"
                + "]}";

        AgentPlan plan = parser.parse(llmResponse, registry);

        assertTrue(plan.isPlan(), "应为 PLAN 类型");
        assertEquals(5, plan.getSteps().size(), "应有 5 个步骤");

        // F2: 步骤 2/3/4 共享同一 bulkGroupId
        assertEquals("cols", plan.getSteps().get(1).getBulkGroupId());
        assertEquals("cols", plan.getSteps().get(2).getBulkGroupId());
        assertEquals("cols", plan.getSteps().get(3).getBulkGroupId());

        // 步骤 5 为破坏性删除
        assertTrue(plan.getSteps().get(4).isDestructive(), "dwtable.delete 应为破坏性");
        assertEquals("delete", plan.getSteps().get(4).getOperationType());

        // 依赖关系校验
        assertEquals(Collections.singletonList("s1"), plan.getSteps().get(1).getDependsOn());
    }

    // ===== 场景3: 清单外操作拒绝（R10/AE3） =====

    @Test
    void test_outOfRegistryOperation_rejected_r10()
    {
        AgentOperationRegistry registry = createRegistry();
        String llmResponse = "{\"type\":\"PLAN\",\"steps\":[{\"stepId\":\"s1\","
                + "\"operationName\":\"export.excel\","
                + "\"params\":{},\"operationType\":\"query\",\"dependsOn\":[]}]}";

        AgentPlan plan = parser.parse(llmResponse, registry);

        assertTrue(plan.isError(), "清单外操作应返回 ERROR");
        assertTrue(plan.getErrorMessage().contains("解析失败"), "错误消息应提示解析失败");
    }

    // ===== 场景4: 参数 schema 校验失败（R27） =====

    @Test
    void test_missingRequiredParam_rejected_r27()
    {
        AgentOperationRegistry registry = createRegistry();
        String llmResponse = "{\"type\":\"PLAN\",\"steps\":[{\"stepId\":\"s1\","
                + "\"operationName\":\"dwtable.create\","
                + "\"params\":{},\"operationType\":\"create\",\"dependsOn\":[]}]}";

        AgentPlan plan = parser.parse(llmResponse, registry);

        assertTrue(plan.isError(), "缺少必填参数应返回 ERROR");
    }

    // ===== 场景5: LLM 返回非 JSON =====

    @Test
    void test_nonJsonResponse_returnsError()
    {
        AgentOperationRegistry registry = createRegistry();
        String llmResponse = "这不是一个JSON格式的回复";

        AgentPlan plan = parser.parse(llmResponse, registry);

        assertTrue(plan.isError(), "非 JSON 应返回 ERROR");
        assertTrue(plan.getErrorMessage().contains("格式错误"), "应提示格式错误");
    }

    // ===== 场景6: 只读查询路径（F1） =====

    @Test
    void test_readOnlyQuery_returnsQueryType_f1()
    {
        AgentOperationRegistry registry = createRegistry();
        String llmResponse = "{\"type\":\"QUERY\",\"queryDescription\":\"查询该表的记录数\"}";

        AgentPlan plan = parser.parse(llmResponse, registry);

        assertTrue(plan.isQuery(), "应为 QUERY 类型");
        assertEquals("查询该表的记录数", plan.getQueryResult());
    }

    // ===== 场景7: 澄清问题（F3/R11） =====

    @Test
    void test_unclearIntent_returnsClarify_f3()
    {
        AgentOperationRegistry registry = createRegistry();
        String llmResponse = "{\"type\":\"CLARIFY\",\"question\":\"您想在哪个多维表中添加列？\"}";

        AgentPlan plan = parser.parse(llmResponse, registry);

        assertTrue(plan.isClarify(), "应为 CLARIFY 类型");
        assertEquals("您想在哪个多维表中添加列？", plan.getClarifyingQuestion());
    }

    // ===== 场景8: PiiRedactor 脱敏 =====

    @Test
    void test_piiRedactor_masksPhoneEmailIdCard()
    {
        // 手机号脱敏
        String phoneInput = "我的手机号是13812345678";
        String phoneResult = piiRedactor.redact(phoneInput);
        assertTrue(phoneResult.contains("138****5678"), "手机号应被脱敏");
        assertFalse(phoneResult.contains("13812345678"), "完整手机号不应残留");

        // 邮箱脱敏
        String emailInput = "联系我：testuser@example.com";
        String emailResult = piiRedactor.redact(emailInput);
        assertTrue(emailResult.contains("*"), "邮箱用户名应被脱敏");
        assertFalse(emailResult.contains("testuser@"), "完整邮箱用户名不应残留");

        // 身份证脱敏
        String idInput = "身份证号：110102199001011234";
        String idResult = piiRedactor.redact(idInput);
        assertTrue(idResult.contains("*"), "身份证号应被脱敏");
        assertFalse(idResult.contains("19900101"), "出生日期部分不应残留");

        // null 输入
        assertNull(piiRedactor.redact(null), "null 输入应返回 null");
        assertEquals("", piiRedactor.redact(""), "空字符串应返回空");
    }

    // ===== 场景8b: PiiRedactor JSON 递归脱敏 =====

    @Test
    void test_piiRedactor_redactJson_recursive()
    {
        // 嵌套 JSON：对象 + 数组 + 嵌套字符串值含 PII
        String json = "{\"userInput\":\"删除手机号13812345678的记录\","
                + "\"steps\":["
                + "{\"operationName\":\"record.delete\",\"params\":{\"reason\":\"联系人test@example.com\"}},"
                + "{\"operationName\":\"record.list\",\"params\":{\"filter\":{\"phone\":\"13987654321\"}}}"
                + "],"
                + "\"meta\":{\"count\":3,\"id\":12345}}";

        String result = piiRedactor.redactJson(json);

        // 手机号应被脱敏（对象层 + 嵌套数组层）
        assertFalse(result.contains("13812345678"), "对象层手机号应被脱敏");
        assertTrue(result.contains("138****5678"), "对象层手机号应保留前3后4");
        assertFalse(result.contains("13987654321"), "嵌套数组层手机号应被脱敏");
        assertTrue(result.contains("139****4321"), "嵌套数组层手机号应保留前3后4");

        // 邮箱应被脱敏
        assertFalse(result.contains("test@example.com"), "邮箱应被脱敏");
        assertTrue(result.contains("*"), "邮箱应包含掩码字符");

        // 非字符串类型（Number）应原样保留
        assertTrue(result.contains("12345"), "数字 ID 应原样保留");
        assertTrue(result.contains("3"), "数字 count 应原样保留");

        // JSON 结构应保持完整（仍是合法 JSON）
        com.alibaba.fastjson2.JSON.parse(result);
    }

    @Test
    void test_piiRedactor_redactJson_nullAndInvalid()
    {
        // null 输入
        assertNull(piiRedactor.redactJson(null), "null 输入应返回 null");
        assertEquals("", piiRedactor.redactJson(""), "空字符串应返回空");

        // 非 JSON 字符串 → 回退到纯文本脱敏
        String nonJson = "我的手机号是13812345678";
        String result = piiRedactor.redactJson(nonJson);
        assertTrue(result.contains("138****5678"), "非 JSON 应回退到纯文本脱敏");
        assertFalse(result.contains("13812345678"), "手机号不应残留");
    }

    // ===== 场景9: 空输入处理 =====

    @Test
    void test_emptyInput_returnsError()
    {
        AgentLlmService service = new AgentLlmService();
        injectDependencies(service);

        AgentPlan plan = service.generatePlan(null, AgentContext.empty());
        assertTrue(plan.isError(), "null 输入应返回 ERROR");

        plan = service.generatePlan("  ", AgentContext.empty());
        assertTrue(plan.isError(), "空白输入应返回 ERROR");
    }

    // ===== 场景10: LLM 调用失败返回友好错误（R20a） =====

    @Test
    void test_llmCallFailure_returnsFriendlyError_r20a()
    {
        // 子类覆盖 callGlmWithRetry 模拟全部重试失败
        AgentLlmService service = new AgentLlmService()
        {
            @Override
            String callGlmWithRetry(String systemPrompt, String userMessage)
            {
                return null;
            }
        };
        injectDependencies(service);

        AgentPlan plan = service.generatePlan("创建一个多维表", AgentContext.empty());

        assertTrue(plan.isError(), "LLM 调用失败应返回 ERROR");
        assertTrue(plan.getErrorMessage().contains("AI 暂时无法处理"),
                "应返回友好错误消息");
    }

    // ===== 场景11: dependsOn 引用无效步骤 =====

    @Test
    void test_invalidDependsOn_rejected()
    {
        AgentOperationRegistry registry = createRegistry();
        String llmResponse = "{\"type\":\"PLAN\",\"steps\":[{\"stepId\":\"s1\","
                + "\"operationName\":\"column.create\","
                + "\"params\":{\"column\":{\"name\":\"姓名\"}},"
                + "\"operationType\":\"create\",\"dependsOn\":[\"s99\"]}]}";

        AgentPlan plan = parser.parse(llmResponse, registry);

        assertTrue(plan.isError(), "无效依赖应返回 ERROR");
        assertTrue(plan.getErrorMessage().contains("依赖"), "错误消息应提示依赖问题");
    }

    // ===== 场景12: 空响应处理 =====

    @Test
    void test_emptyResponse_returnsError()
    {
        AgentOperationRegistry registry = createRegistry();

        assertTrue(parser.parse(null, registry).isError(), "null 响应应返回 ERROR");
        assertTrue(parser.parse("", registry).isError(), "空字符串响应应返回 ERROR");
        assertTrue(parser.parse("   ", registry).isError(), "空白响应应返回 ERROR");
    }

    // ===== 场景13: 批量删除阈值 — batchDelete 单步 ID 列表 ≤ 20 =====

    @Test
    void test_batchDelete_exceedsMaxIdsPerStep_returnsError()
    {
        AgentOperationRegistry registry = createRegistry();

        // 构造 21 个 ID 的 batchDelete 步骤
        StringBuilder idsArray = new StringBuilder("[");
        for (int i = 1; i <= 21; i++)
        {
            if (i > 1) idsArray.append(",");
            idsArray.append("\"").append(i).append("\"");
        }
        idsArray.append("]");

        String response = "{\"type\":\"PLAN\",\"steps\":[{\"stepId\":\"s1\","
                + "\"operationName\":\"dwtable.batchDelete\","
                + "\"params\":{\"ids\":" + idsArray + "}}]}";

        AgentPlan plan = parser.parse(response, registry);
        assertTrue(plan.isError(), "batchDelete 单步 ID 超过 20 应返回 ERROR");
        assertTrue(plan.getErrorMessage().contains("超过上限"), "错误消息应包含阈值信息");
    }

    // ===== 场景14: 批量删除阈值 — 同类实体删除总数 ≤ 10 =====

    @Test
    void test_batchDelete_exceedsMaxPerEntity_returnsError()
    {
        AgentOperationRegistry registry = createRegistry();

        // 11 个单删除步骤（dwtable.delete），超过同类实体上限 10
        StringBuilder steps = new StringBuilder("[");
        for (int i = 1; i <= 11; i++)
        {
            if (i > 1) steps.append(",");
            steps.append("{\"stepId\":\"s").append(i).append("\",")
                    .append("\"operationName\":\"dwtable.delete\",")
                    .append("\"params\":{\"id\":").append(i).append("}}");
        }
        steps.append("]");

        String response = "{\"type\":\"PLAN\",\"steps\":" + steps + "}";

        AgentPlan plan = parser.parse(response, registry);
        assertTrue(plan.isError(), "同类实体删除总数超过 10 应返回 ERROR");
        assertTrue(plan.getErrorMessage().contains("dwtable"), "错误消息应包含实体类型");
    }

    // ===== 场景15: 步骤数上限 — 单计划 ≤ 50 步 =====

    @Test
    void test_planExceedsMaxSteps_returnsError()
    {
        AgentOperationRegistry registry = createRegistry();

        // 构造 51 个步骤
        StringBuilder steps = new StringBuilder("[");
        for (int i = 1; i <= 51; i++)
        {
            if (i > 1) steps.append(",");
            steps.append("{\"stepId\":\"s").append(i).append("\",")
                    .append("\"operationName\":\"record.list\",")
                    .append("\"params\":{\"filter\":{\"name\":\"test\"}}}");
        }
        steps.append("]");

        String response = "{\"type\":\"PLAN\",\"steps\":" + steps + "}";

        AgentPlan plan = parser.parse(response, registry);
        assertTrue(plan.isError(), "步骤数超过 50 应返回 ERROR");
        assertTrue(plan.getErrorMessage().contains("50"), "错误消息应包含上限值");
    }

    // ===== 辅助方法 =====

    /** 通过反射注入 AgentLlmService 的依赖（测试用，不走 Spring） */
    private void injectDependencies(AgentLlmService service)
    {
        try
        {
            setField(service, "piiRedactor", new PiiRedactor());
            setField(service, "responseParser", new PlanResponseParser());
            setField(service, "registry", createRegistry());
            setField(service, "promptBuilder", new PlanPromptBuilder());
        }
        catch (Exception e)
        {
            fail("反射注入失败: " + e.getMessage());
        }
    }

    private void setField(Object target, String fieldName, Object value) throws Exception
    {
        java.lang.reflect.Field field = AgentLlmService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
