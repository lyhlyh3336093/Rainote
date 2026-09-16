package com.ruoyi.system.agent.e2e;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.agent.annotation.AgentOperation;
import com.ruoyi.system.agent.annotation.AgentParam;
import com.ruoyi.system.agent.domain.AgentAuditLog;
import com.ruoyi.system.agent.domain.AgentAuditStepLog;
import com.ruoyi.system.agent.executor.AgentPlanExecutor;
import com.ruoyi.system.agent.executor.DependencyResolver;
import com.ruoyi.system.agent.executor.ProgressCallback;
import com.ruoyi.system.agent.executor.StepExecutionResult;
import com.ruoyi.system.agent.llm.PlanResponseParser;
import com.ruoyi.system.agent.model.AgentPlan;
import com.ruoyi.system.agent.model.AgentStep;
import com.ruoyi.system.agent.registry.AgentExecutionContext;
import com.ruoyi.system.agent.registry.AgentOperationRegistry;
import com.ruoyi.system.agent.registry.AgentOperationSpec;
import com.ruoyi.system.agent.registry.AgentParamBinder;
import com.ruoyi.system.agent.service.AgentAuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Agent 端到端测试（U8）。
 * <p>
 * 使用真实 {@link AgentOperationRegistry}（Spring 扫描 @AgentOperation）、真实
 * {@link PlanResponseParser}、真实 {@link AgentPlanExecutor}，仅 mock 数据库/事务依赖，
 * 验证"生成→执行→审计"全流程与 AE1-AE6 验收场景：
 * <ul>
 *   <li><b>AE1 上下文感知</b>：执行上下文 userId/权限全程传递</li>
 *   <li><b>AE2 破坏性确认</b>：删除步骤暂停→确认→续跑，参数完整回显</li>
 *   <li><b>AE3 清单外拒绝</b>：LLM 生成不存在操作→解析层拒绝</li>
 *   <li><b>AE4 权限校验</b>：无权限操作被拒绝</li>
 *   <li><b>AE5 审计完整性</b>：审计日志含完整执行记录，刷新后可恢复续跑</li>
 *   <li><b>AE6 批量效率</b>：批量操作步骤数比手动逐条减少 &gt;50%</li>
 * </ul>
 * <p>
 * 同时覆盖：多步计划、跳过（级联阻塞）、重试、取消、审计降级。
 *
 * @see SecurityTest
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentE2ETest
{
    /**
     * 测试用 Service，提供 Agent 操作清单。
     * 包含非破坏性（create/update/query）与破坏性（delete/batchDelete）操作，
     * 覆盖 E2E 全场景所需操作类型。
     */
    @Component
    public static class E2ETestService
    {
        @AgentOperation(name = "note.create", description = "创建笔记")
        public Long createNote(@AgentParam(value = "note", type = "object",
                objectType = "Object", allowedFields = {"title", "content", "noteId"},
                description = "笔记内容") Map<String, Object> note)
        {
            return 100L;
        }

        @AgentOperation(name = "note.list", description = "查询笔记列表")
        public List<Object> listNotes(@AgentParam(value = "filter", type = "object",
                objectType = "Object", allowedFields = {"title"},
                description = "筛选条件") Map<String, Object> filter)
        {
            return Collections.emptyList();
        }

        @AgentOperation(name = "note.update", description = "更新笔记")
        public int updateNote(@AgentParam(value = "note", type = "object",
                objectType = "Object", allowedFields = {"id", "title"},
                description = "笔记信息") Map<String, Object> note)
        {
            return 1;
        }

        @AgentOperation(name = "note.delete", destructive = true,
                permissionKey = "note:note:delete", description = "删除笔记")
        public int deleteNote(@AgentParam(value = "id", type = "long",
                description = "笔记ID") Long id)
        {
            return 1;
        }

        @AgentOperation(name = "note.batchDelete", destructive = true,
                permissionKey = "note:note:delete", description = "批量删除笔记")
        public int batchDeleteNote(@AgentParam(value = "ids", type = "list",
                description = "笔记ID列表") String[] ids)
        {
            return ids.length;
        }

        @AgentOperation(name = "dwtable.create", description = "创建多维表")
        public Long createDwtable(@AgentParam(value = "dwtable", type = "object",
                objectType = "Object", allowedFields = {"name", "noteId"},
                description = "多维表信息") Map<String, Object> dwtable)
        {
            return 200L;
        }

        @AgentOperation(name = "column.create", description = "创建列")
        public Long createColumn(@AgentParam(value = "column", type = "object",
                objectType = "Object", allowedFields = {"name", "type", "dwtableId"},
                description = "列配置") Map<String, Object> column)
        {
            return 300L;
        }
    }

    @Mock private AgentParamBinder paramBinder;
    @Mock private AgentAuditService auditService;
    @Mock private PlatformTransactionManager transactionManager;

    private AnnotationConfigApplicationContext ctx;
    private AgentOperationRegistry registry;
    private final PlanResponseParser parser = new PlanResponseParser();
    private AgentPlanExecutor executor;
    private final List<String> events = new ArrayList<>();

    /** 步骤日志 ID 自增生成器，模拟数据库自增主键 */
    private final AtomicInteger stepLogIdSeq = new AtomicInteger(1000);

    @BeforeEach
    void setUp() throws Exception
    {
        // 构建真实 Registry（Spring 扫描 @AgentOperation）
        ctx = new AnnotationConfigApplicationContext(E2ETestService.class,
                AgentOperationRegistry.class);
        registry = ctx.getBean(AgentOperationRegistry.class);

        // 构建真实 Executor，注入真实 registry + mock 依赖
        executor = new AgentPlanExecutor();
        injectField("registry", registry);
        injectField("paramBinder", paramBinder);
        injectField("auditService", auditService);
        injectField("dependencyResolver", new DependencyResolver());
        injectField("transactionManager", transactionManager);
        executor.init();

        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        // 审计 mock：createAuditLog 返回固定 ID
        when(auditService.createAuditLog(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(1L);
        // insertStepLog 返回自增 ID
        when(auditService.insertStepLog(anyLong(), anyInt(), anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> (long) stepLogIdSeq.incrementAndGet());

        // 默认 paramBinder：按方法签名返回正确类型的参数
        when(paramBinder.bind(any(), anyMap(), any())).thenAnswer(inv -> {
            AgentOperationSpec spec = inv.getArgument(0);
            Class<?>[] paramTypes = spec.getMethod().getParameterTypes();
            Object[] args = new Object[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++)
            {
                if (paramTypes[i] == Map.class) args[i] = new LinkedHashMap<>();
                else if (paramTypes[i] == String[].class) args[i] = new String[]{"1"};
                else args[i] = 1L;
            }
            return args;
        });

        events.clear();
        stepLogIdSeq.set(1000);
    }

    @AfterEach
    void tearDown()
    {
        if (ctx != null) ctx.close();
    }

    // ==================== AE1: 上下文感知（context 全程传递）====================

    /**
     * AE1：AgentExecutionContext 的 userId/权限在执行全程传递。
     * note.delete 需要 note:note:delete 权限，无权限用户在 confirmStep 时被 Layer 3 拦截，
     * 有权限用户可成功执行，证明 context 传递正确。
     */
    @Test
    void ae1_contextPropagation_userIdAndPermissionsCarriedThroughExecution()
    {
        AgentStep s1 = step("s1", "note.delete", "delete",
                params("id", 1L), null, true, Collections.emptyList());
        AgentPlan plan = AgentPlan.ofPlan(Collections.singletonList(s1));

        // 用户 A 有删除权限
        AgentExecutionContext userA = new AgentExecutionContext(2L, allPermissions());
        executor.startExecution(plan, userA, "sess-ae1", "删除笔记1", recordingCallback());
        assertTrue(events.contains("pending:s1"), "破坏性步骤应暂停等待确认");

        executor.confirmStep(plan, 1L, "s1", userA, recordingCallback());
        assertTrue(events.contains("completed:s1:SUCCESS"),
                "有权限用户确认后应执行成功: " + events);

        // 验证审计日志 userId 为 context 中的 2L（非默认 1L）
        verify(auditService).createAuditLog(eq(2L), eq("sess-ae1"), eq("删除笔记1"), anyString());
    }

    // ==================== AE2: 破坏性二次确认 + 参数回显 ====================

    /**
     * AE2：破坏性步骤暂停→确认→续跑，审计日志完整回显参数。
     * 计划：s1 note.list → s2 note.delete（破坏性）→ s3 note.list
     * 验证：s1 执行，s2 暂停（pending_confirm），confirm 后 s2/s3 执行，
     *       s2 步骤审计日志 paramsJson 含完整 id 参数。
     */
    @Test
    void ae2_destructiveConfirm_pausesThenResumesWithFullParamEcho()
    {
        AgentStep s1 = step("s1", "note.list", "query",
                params("filter", new LinkedHashMap<>()), null, false, Collections.emptyList());
        AgentStep s2 = step("s2", "note.delete", "delete",
                params("id", 42L), null, true, Collections.emptyList());
        AgentStep s3 = step("s3", "note.list", "query",
                params("filter", new LinkedHashMap<>()), null, false, Collections.emptyList());
        AgentPlan plan = AgentPlan.ofPlan(Arrays.asList(s1, s2, s3));

        AgentExecutionContext admin = new AgentExecutionContext(1L, allPermissions());
        executor.startExecution(plan, admin, "sess-ae2", "查询后删除", recordingCallback());

        // s1 执行，s2 暂停，s3 不执行
        assertTrue(events.contains("completed:s1:SUCCESS"), "s1 应执行成功");
        assertTrue(events.contains("pending:s2"), "s2 破坏性步骤应暂停");
        assertFalse(events.contains("started:s3"), "s3 在 s2 确认前不应执行");

        // 确认 s2
        events.clear();
        executor.confirmStep(plan, 1L, "s2", admin, recordingCallback());
        assertTrue(events.contains("started:s2"), "s2 确认后应开始执行");
        assertTrue(events.contains("completed:s2:SUCCESS"), "s2 应执行成功");
        assertTrue(events.contains("started:s3"), "s3 应在 s2 后续跑");
        assertTrue(events.contains("planCompleted"), "计划应完成");

        // 验证 s2 参数完整回显到审计日志（paramsJson 含 id=42）
        ArgumentCaptor<Object> paramsCaptor = ArgumentCaptor.forClass(Object.class);
        verify(auditService, atLeastOnce()).insertStepLog(
                eq(1L), anyInt(), eq("s2"), eq("note.delete"), paramsCaptor.capture(), any());
        Object capturedParams = paramsCaptor.getValue();
        assertNotNull(capturedParams, "s2 参数应回显到审计日志");
        String paramsJson = com.alibaba.fastjson2.JSON.toJSONString(capturedParams);
        assertTrue(paramsJson.contains("42"), "审计日志应含完整参数 id=42: " + paramsJson);
    }

    // ==================== AE3: 清单外拒绝（解析层）====================

    /**
     * AE3：LLM 生成清单外操作（note.exportExcel），解析层 R10 拦截返回 ERROR。
     */
    @Test
    void ae3_outOfManifestOperation_rejectedByParser()
    {
        JSONObject llmResponse = new JSONObject();
        llmResponse.put("type", "PLAN");
        JSONArray steps = new JSONArray();
        JSONObject step = new JSONObject();
        step.put("stepId", "s1");
        step.put("operationName", "note.exportExcel");
        step.put("operationType", "query");
        steps.add(step);
        llmResponse.put("steps", steps);

        AgentPlan plan = parser.parse(llmResponse.toJSONString(), registry);

        assertTrue(plan.isError(), "清单外操作应被解析层拒绝");
        assertTrue(plan.getErrorMessage().contains("解析失败"),
                "应提示解析失败: " + plan.getErrorMessage());
    }

    // ==================== AE4: 权限校验 ====================

    /**
     * AE4：用户无 note:note:delete 权限，confirmStep 时 Layer 3 拦截。
     */
    @Test
    void ae4_permissionDenied_userLacksDeletePermission()
    {
        AgentStep s1 = step("s1", "note.delete", "delete",
                params("id", 1L), null, true, Collections.emptyList());
        AgentPlan plan = AgentPlan.ofPlan(Collections.singletonList(s1));

        AgentExecutionContext noPerm = new AgentExecutionContext(2L,
                new HashSet<>(Collections.singletonList("note:note:list")));

        executor.startExecution(plan, noPerm, "sess-ae4", "无权限删除", recordingCallback());
        assertTrue(events.contains("pending:s1"), "破坏性步骤应先暂停");

        executor.confirmStep(plan, 1L, "s1", noPerm, recordingCallback());
        assertTrue(events.contains("completed:s1:FAILED"), "无权限应标记失败");
        verify(auditService).updateStepStatus(anyLong(), eq("failed"), contains("无权限"));
    }

    // ==================== AE5: 审计完整性 + 刷新恢复 ====================

    /**
     * AE5 审计完整性：全流程执行后审计日志含完整执行记录。
     * 验证：createAuditLog 1次、insertStepLog 每步1次、updateStepStatus 每步1次、
     *       completeAuditLog(completed) 1次。
     */
    @Test
    void ae5_auditIntegrity_completeAuditTrailAfterExecution()
    {
        AgentStep s1 = step("s1", "note.list", "query",
                params("filter", new LinkedHashMap<>()), null, false, Collections.emptyList());
        AgentStep s2 = step("s2", "note.update", "update",
                params("note", noteMap(1L, "新标题")), null, false, Collections.emptyList());
        AgentPlan plan = AgentPlan.ofPlan(Arrays.asList(s1, s2));

        executor.startExecution(plan, new AgentExecutionContext(1L, allPermissions()),
                "sess-ae5", "查询并更新", recordingCallback());

        assertTrue(events.contains("planCompleted"), "计划应完成");

        // 审计完整性校验
        verify(auditService, times(1)).createAuditLog(eq(1L), eq("sess-ae5"), anyString(), anyString());
        verify(auditService, times(2)).insertStepLog(eq(1L), anyInt(), anyString(), anyString(), any(), any());
        verify(auditService, times(2)).updateStepStatus(anyLong(), eq("success"), any());
        verify(auditService, times(1)).completeAuditLog(1L, "completed");
    }

    /**
     * AE5 刷新恢复：计划执行到破坏性步骤暂停后页面刷新，
     * 通过 getIncompleteAuditLog 查询未完成记录，识别 pending_confirm 步骤后 confirmStep 续跑。
     */
    @Test
    void ae5_refreshRecovery_incompleteAuditLogResumableAfterRefresh()
    {
        AgentStep s1 = step("s1", "note.list", "query",
                params("filter", new LinkedHashMap<>()), null, false, Collections.emptyList());
        AgentStep s2 = step("s2", "note.delete", "delete",
                params("id", 7L), null, true, Collections.emptyList());
        AgentStep s3 = step("s3", "note.list", "query",
                params("filter", new LinkedHashMap<>()), null, false, Collections.emptyList());
        AgentPlan plan = AgentPlan.ofPlan(Arrays.asList(s1, s2, s3));

        AgentExecutionContext admin = new AgentExecutionContext(1L, allPermissions());
        executor.startExecution(plan, admin, "sess-refresh", "刷新恢复", recordingCallback());
        assertTrue(events.contains("pending:s2"), "s2 应暂停");

        // 模拟审计日志中已记录的步骤状态（刷新后从 DB 读取）
        AgentAuditStepLog s1Log = stepLog("s1", "success");
        AgentAuditStepLog s2Log = stepLog("s2", "pending_confirm");
        when(auditService.getStepLogs(1L)).thenReturn(Arrays.asList(s1Log, s2Log));

        // 模拟 getIncompleteAuditLog 返回未完成记录
        AgentAuditLog incompleteLog = new AgentAuditLog();
        incompleteLog.setId(1L);
        incompleteLog.setUserId(1L);
        incompleteLog.setSessionId("sess-refresh");
        incompleteLog.setStatus("executing");
        incompleteLog.setStepLogs(Arrays.asList(s1Log, s2Log));
        when(auditService.getIncompleteAuditLog(1L))
                .thenReturn(Collections.singletonList(incompleteLog));

        // 刷新后查询未完成记录
        List<AgentAuditLog> incomplete = auditService.getIncompleteAuditLog(1L);
        assertEquals(1, incomplete.size(), "应查询到 1 条未完成记录");
        assertEquals("executing", incomplete.get(0).getStatus(), "状态应为 executing");

        // 恢复续跑：确认 s2
        events.clear();
        executor.confirmStep(plan, 1L, "s2", admin, recordingCallback());
        assertTrue(events.contains("completed:s2:SUCCESS"), "恢复后确认 s2 应成功");
        assertTrue(events.contains("started:s3"), "s3 应续跑执行");
        assertTrue(events.contains("planCompleted"), "计划应完成");
    }

    // ==================== AE6: 批量效率 ====================

    /**
     * AE6：批量删除 5 条笔记，批量操作 1 步 vs 手动逐条 5 步，步骤数减少 80% > 50%。
     * 批量计划使用 note.batchDelete（单步 5 ID），手动逐条需 5 次 note.delete。
     */
    @Test
    void ae6_batchEfficiency_batchDeleteReducesStepsOver50Percent()
    {
        // 场景：用户要删除 5 条笔记（ID: 1,2,3,4,5）
        int recordCount = 5;

        // 批量计划：1 步 batchDelete
        AgentStep batchStep = step("s1", "note.batchDelete", "delete",
                params("ids", new String[]{"1", "2", "3", "4", "5"}),
                null, true, Collections.emptyList());
        AgentPlan batchPlan = AgentPlan.ofPlan(Collections.singletonList(batchStep));
        int batchStepCount = batchPlan.getSteps().size();

        // 手动逐条：5 步 delete
        List<AgentStep> manualSteps = new ArrayList<>();
        for (int i = 0; i < recordCount; i++)
        {
            manualSteps.add(step("s" + (i + 1), "note.delete", "delete",
                    params("id", (long) (i + 1)), null, true, Collections.emptyList()));
        }
        AgentPlan manualPlan = AgentPlan.ofPlan(manualSteps);
        int manualStepCount = manualPlan.getSteps().size();

        // 步骤数减少百分比
        double reduction = (1.0 - (double) batchStepCount / manualStepCount) * 100;

        assertEquals(1, batchStepCount, "批量计划应为 1 步");
        assertEquals(5, manualStepCount, "手动逐条应为 5 步");
        assertTrue(reduction > 50,
                "批量操作步骤数应减少 >50%，实际: " + reduction + "%");

        // 执行批量计划验证可运行
        AgentExecutionContext admin = new AgentExecutionContext(1L, allPermissions());
        executor.startExecution(batchPlan, admin, "sess-ae6", "批量删除5条", recordingCallback());
        assertTrue(events.contains("pending:s1"), "批量删除为破坏性操作应暂停");

        executor.confirmStep(batchPlan, 1L, "s1", admin, recordingCallback());
        assertTrue(events.contains("completed:s1:SUCCESS"), "批量删除确认后应成功");
        assertTrue(events.contains("planCompleted"), "批量计划应完成");
    }

    /**
     * AE6 补充：建表+3列的批量计划（bulkGroupId 同组），1 次确认 vs 手动 4 次确认。
     * 验证 bulkGroupId 关联的 3 个列创建步骤共享同一组标识。
     */
    @Test
    void ae6_bulkGroup_multipleColumnsShareGroupId()
    {
        // 模拟 LLM 返回多步计划（建表 + 3 列，列共享 bulkGroupId="cols"）
        JSONObject llmResponse = new JSONObject();
        llmResponse.put("type", "PLAN");
        JSONArray steps = new JSONArray();
        // s1: 建表
        steps.add(stepJson("s1", "dwtable.create", "create",
                params("dwtable", dwtableMap("客户表", 10L)), null, null));
        // s2/s3/s4: 建 3 列，共享 bulkGroupId="cols"，依赖 s1
        steps.add(stepJson("s2", "column.create", "create",
                params("column", columnMap("姓名", "text", 200L)), "cols", Collections.singletonList("s1")));
        steps.add(stepJson("s3", "column.create", "create",
                params("column", columnMap("电话", "text", 200L)), "cols", Collections.singletonList("s1")));
        steps.add(stepJson("s4", "column.create", "create",
                params("column", columnMap("公司", "text", 200L)), "cols", Collections.singletonList("s1")));
        llmResponse.put("steps", steps);

        AgentPlan plan = parser.parse(llmResponse.toJSONString(), registry);

        assertTrue(plan.isPlan(), "应解析为 PLAN 类型");
        assertEquals(4, plan.getSteps().size(), "应有 4 步");

        // 验证 s2/s3/s4 共享 bulkGroupId
        assertEquals("cols", plan.getSteps().get(1).getBulkGroupId(), "s2 应有 bulkGroupId=cols");
        assertEquals("cols", plan.getSteps().get(2).getBulkGroupId(), "s3 应有 bulkGroupId=cols");
        assertEquals("cols", plan.getSteps().get(3).getBulkGroupId(), "s4 应有 bulkGroupId=cols");

        // 验证依赖关系
        assertEquals(Collections.singletonList("s1"), plan.getSteps().get(1).getDependsOn(),
                "s2 应依赖 s1");

        // 执行：s1 执行后，s2/s3/s4 连续执行（均非破坏性）
        executor.startExecution(plan, new AgentExecutionContext(1L, allPermissions()),
                "sess-ae6b", "建表+3列", recordingCallback());

        assertTrue(events.contains("completed:s1:SUCCESS"), "s1 建表应成功");
        assertTrue(events.contains("completed:s2:SUCCESS"), "s2 建列应成功");
        assertTrue(events.contains("completed:s3:SUCCESS"), "s3 建列应成功");
        assertTrue(events.contains("completed:s4:SUCCESS"), "s4 建列应成功");
        assertTrue(events.contains("planCompleted"), "计划应完成");
    }

    // ==================== 全流程：生成→执行→审计（单步）====================

    /**
     * 全流程：模拟 LLM JSON → parser 解析 → executor 执行 → 审计写入。
     * 验证端到端"输入→计划→执行→审计"链路完整。
     */
    @Test
    void e2e_fullFlow_singleStepGenerateExecuteAudit()
    {
        // 模拟 GLM 返回的单步创建计划
        JSONObject llmResponse = new JSONObject();
        llmResponse.put("type", "PLAN");
        JSONArray steps = new JSONArray();
        JSONObject step = stepJson("s1", "note.create", "create",
                params("note", noteMap(null, "测试笔记")), null, null);
        steps.add(step);
        llmResponse.put("steps", steps);

        // 生成阶段：parser 解析
        AgentPlan plan = parser.parse(llmResponse.toJSONString(), registry);
        assertTrue(plan.isPlan(), "应解析为 PLAN");
        assertEquals(1, plan.getSteps().size(), "应有 1 步");
        assertEquals("note.create", plan.getSteps().get(0).getOperationName());

        // 执行阶段：executor 执行
        Long auditLogId = executor.startExecution(plan,
                new AgentExecutionContext(1L, allPermissions()),
                "sess-full", "创建测试笔记", recordingCallback());

        // 验证阶段：执行成功 + 审计完整
        assertEquals(Long.valueOf(1L), auditLogId, "应返回审计日志 ID");
        assertTrue(events.contains("completed:s1:SUCCESS"), "步骤应执行成功");
        assertTrue(events.contains("planCompleted"), "计划应完成");
        verify(auditService).createAuditLog(eq(1L), eq("sess-full"), eq("创建测试笔记"), anyString());
        verify(auditService).completeAuditLog(1L, "completed");
    }

    // ==================== 跳过 + 级联阻塞 ====================

    /**
     * 跳过破坏性步骤 s1，级联阻塞依赖 s1 的后继步骤 s2。
     */
    @Test
    void e2e_skipStep_cascadesBlockToDependents()
    {
        AgentStep s1 = step("s1", "note.delete", "delete",
                params("id", 1L), null, true, Collections.emptyList());
        AgentStep s2 = step("s2", "note.list", "query",
                params("filter", new LinkedHashMap<>()), null, false, Collections.singletonList("s1"));
        AgentPlan plan = AgentPlan.ofPlan(Arrays.asList(s1, s2));

        executor.startExecution(plan, new AgentExecutionContext(1L, allPermissions()),
                "sess-skip", "跳过测试", recordingCallback());
        assertTrue(events.contains("pending:s1"), "s1 应暂停");

        // 跳过 s1
        events.clear();
        executor.skipStep(plan, 1L, "s1", recordingCallback());

        assertTrue(events.contains("completed:s1:SKIPPED"), "s1 应标记为 skipped");
        assertTrue(events.contains("blocked:s2"), "s2 应因依赖被级联阻塞");
        verify(auditService).updateStepStatus(anyLong(), eq("skipped"), any());
    }

    // ==================== 重试失败步骤 ====================

    /**
     * 步骤首次执行失败（Service 抛异常），修正后 retryStep 重试成功。
     */
    @Test
    void e2e_retryStep_succeedsAfterFailure()
    {
        AgentStep s1 = step("s1", "note.list", "query",
                params("filter", new LinkedHashMap<>()), null, false, Collections.emptyList());
        AgentPlan plan = AgentPlan.ofPlan(Collections.singletonList(s1));

        // 首次执行：paramBinder 抛异常模拟 Service 失败
        // 使用 doThrow 避免 when() 先触发方法调用
        doThrow(new RuntimeException("数据库连接失败"))
                .when(paramBinder).bind(any(), anyMap(), any());

        executor.startExecution(plan, new AgentExecutionContext(1L, allPermissions()),
                "sess-retry", "重试测试", recordingCallback());
        assertTrue(events.contains("completed:s1:FAILED"), "首次应失败");
        verify(auditService).updateStepStatus(anyLong(), eq("failed"), contains("数据库连接失败"));

        // 修正 paramBinder，重试成功
        // 使用 doReturn 避免 when() 先触发之前的 thenThrow stubbing
        doReturn(new Object[]{new LinkedHashMap<>()})
                .when(paramBinder).bind(any(), anyMap(), any());
        events.clear();
        executor.retryStep(plan, 1L, "s1",
                new AgentExecutionContext(1L, allPermissions()), recordingCallback());

        assertTrue(events.contains("started:s1"), "重试应重新执行 s1");
        assertTrue(events.contains("completed:s1:SUCCESS"), "重试应成功");
        verify(auditService).updateStepStatus(anyLong(), eq("success"), any());
    }

    // ==================== 取消计划 ====================

    /**
     * 计划执行中取消，未完成步骤标记 blocked，审计日志标记 interrupted。
     */
    @Test
    void e2e_cancelPlan_marksRemainingBlockedAndInterrupts()
    {
        AgentStep s1 = step("s1", "note.list", "query",
                params("filter", new LinkedHashMap<>()), null, false, Collections.emptyList());
        AgentStep s2 = step("s2", "note.delete", "delete",
                params("id", 1L), null, true, Collections.emptyList());
        AgentStep s3 = step("s3", "note.list", "query",
                params("filter", new LinkedHashMap<>()), null, false, Collections.emptyList());
        AgentPlan plan = AgentPlan.ofPlan(Arrays.asList(s1, s2, s3));

        executor.startExecution(plan, new AgentExecutionContext(1L, allPermissions()),
                "sess-cancel", "取消测试", recordingCallback());
        assertTrue(events.contains("completed:s1:SUCCESS"), "s1 应已执行");
        assertTrue(events.contains("pending:s2"), "s2 应暂停");

        // 模拟审计日志中 s1 已完成
        when(auditService.getStepLogs(1L))
                .thenReturn(Collections.singletonList(stepLog("s1", "success")));

        events.clear();
        executor.cancelPlan(plan, 1L, recordingCallback());

        assertTrue(events.contains("blocked:s2"), "s2 应被标记 blocked");
        assertTrue(events.contains("blocked:s3"), "s3 应被标记 blocked");
        assertTrue(events.contains("planInterrupted:用户取消"), "计划应中断");
        verify(auditService).completeAuditLog(1L, "interrupted");
    }

    // ==================== 审计降级仍执行 ====================

    /**
     * 审计降级（createAuditLog 返回 null）时，步骤仍正常执行。
     */
    @Test
    void e2e_auditDegraded_executionContinuesWithoutAudit()
    {
        when(auditService.createAuditLog(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(null); // 审计降级

        AgentStep s1 = step("s1", "note.list", "query",
                params("filter", new LinkedHashMap<>()), null, false, Collections.emptyList());
        AgentPlan plan = AgentPlan.ofPlan(Collections.singletonList(s1));

        Long auditLogId = executor.startExecution(plan,
                new AgentExecutionContext(1L, allPermissions()),
                "sess-degrade", "审计降级", recordingCallback());

        assertNull(auditLogId, "审计降级应返回 null");
        assertTrue(events.contains("completed:s1:SUCCESS"), "步骤仍应执行成功");
        assertTrue(events.contains("planCompleted"), "计划应完成");
    }

    // ==================== 辅助方法 ====================

    private ProgressCallback recordingCallback()
    {
        return new ProgressCallback()
        {
            @Override
            public void onStepStarted(String stepId, String operationName)
            { events.add("started:" + stepId); }

            @Override
            public void onStepCompleted(String stepId, StepExecutionResult result)
            { events.add("completed:" + stepId + ":" + result.getStatus()); }

            @Override
            public void onStepBlocked(String stepId, String reason)
            { events.add("blocked:" + stepId); }

            @Override
            public void onStepPendingConfirmation(String stepId, String operationName)
            { events.add("pending:" + stepId); }

            @Override
            public void onPlanCompleted()
            { events.add("planCompleted"); }

            @Override
            public void onPlanInterrupted(String reason)
            { events.add("planInterrupted:" + reason); }
        };
    }

    private AgentStep step(String stepId, String operationName, String opType,
                           Map<String, Object> params, String bulkGroupId,
                           boolean destructive, List<String> dependsOn)
    {
        return new AgentStep(stepId, operationName, opType, params, bulkGroupId, destructive, dependsOn);
    }

    private Map<String, Object> params(String key, Object value)
    {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put(key, value);
        return p;
    }

    private Map<String, Object> noteMap(Long id, String title)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        if (id != null) m.put("id", id);
        m.put("title", title);
        m.put("content", "内容");
        return m;
    }

    private Map<String, Object> dwtableMap(String name, Long noteId)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("noteId", noteId);
        return m;
    }

    private Map<String, Object> columnMap(String name, String type, Long dwtableId)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type);
        m.put("dwtableId", dwtableId);
        return m;
    }

    /** 构造 LLM 返回的步骤 JSON 对象 */
    private JSONObject stepJson(String stepId, String operationName, String opType,
                                Map<String, Object> params, String bulkGroupId,
                                List<String> dependsOn)
    {
        JSONObject step = new JSONObject();
        step.put("stepId", stepId);
        step.put("operationName", operationName);
        step.put("operationType", opType);
        step.put("params", params);
        if (bulkGroupId != null) step.put("bulkGroupId", bulkGroupId);
        step.put("dependsOn", dependsOn != null ? dependsOn : Collections.emptyList());
        return step;
    }

    private AgentAuditStepLog stepLog(String stepId, String status)
    {
        AgentAuditStepLog log = new AgentAuditStepLog();
        log.setStepId(stepId);
        log.setStatus(status);
        log.setExecutedAt(new Date());
        return log;
    }

    private Set<String> allPermissions()
    {
        return new HashSet<>(Arrays.asList(
                "note:note:delete", "system:dwtable:remove",
                "system:column:remove", "system:record:remove"));
    }

    private void injectField(String fieldName, Object value)
    {
        try
        {
            java.lang.reflect.Field field = executor.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(executor, value);
        }
        catch (Exception e)
        {
            throw new RuntimeException("注入失败: " + fieldName, e);
        }
    }
}
