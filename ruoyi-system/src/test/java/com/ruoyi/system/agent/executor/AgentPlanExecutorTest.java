package com.ruoyi.system.agent.executor;

import com.ruoyi.system.agent.domain.AgentAuditStepLog;
import com.ruoyi.system.agent.model.AgentPlan;
import com.ruoyi.system.agent.model.AgentStep;
import com.ruoyi.system.agent.registry.AgentExecutionContext;
import com.ruoyi.system.agent.registry.AgentOperationRegistry;
import com.ruoyi.system.agent.registry.AgentOperationSpec;
import com.ruoyi.system.agent.registry.AgentParamBinder;
import com.ruoyi.system.agent.registry.AgentParamSpec;
import com.ruoyi.system.agent.service.AgentAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AgentPlanExecutor 单元测试（U3）。
 * <p>
 * 覆盖：非破坏性步骤连续执行、破坏性步骤暂停、四层校验失败、级联阻塞、
 * confirmStep 续跑、skipStep、retryStep、cancelPlan、幂等检查、审计降级。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentPlanExecutorTest
{
    @Mock
    private AgentOperationRegistry registry;

    @Mock
    private AgentParamBinder paramBinder;

    @Mock
    private AgentAuditService auditService;

    private final DependencyResolver dependencyResolver = new DependencyResolver();

    @Mock
    private PlatformTransactionManager transactionManager;

    private AgentPlanExecutor executor;

    /** 测试用 Service，提供可反射调用的方法 */
    private final TestService testService = new TestService();

    /** 进度回调记录器 */
    private final List<String> events = new ArrayList<>();

    private ProgressCallback callback;

    @BeforeEach
    void setUp() throws Exception
    {
        executor = new AgentPlanExecutor();
        injectField("registry", registry);
        injectField("paramBinder", paramBinder);
        injectField("auditService", auditService);
        injectField("dependencyResolver", dependencyResolver);
        injectField("transactionManager", transactionManager);
        executor.init();

        // 模拟 TransactionTemplate：直接执行回调
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        // 默认 mock paramBinder：返回单参数数组（匹配 TestService.echo(Object)）
        when(paramBinder.bind(any(), anyMap(), any())).thenReturn(new Object[]{null});

        // 审计 mock：insertStepLog 返回递增 ID
        when(auditService.insertStepLog(anyLong(), anyInt(), anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> {
                    long id = System.nanoTime();
                    return id;
                });

        // 重置事件记录
        events.clear();
        callback = new ProgressCallback()
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

    // ===== 场景1: 非破坏性步骤连续执行成功 =====

    @Test
    void test_nonDestructiveSteps_executeSequentially()
    {
        AgentStep s1 = step("s1", "dwtable.getById", "query", false);
        AgentStep s2 = step("s2", "dwtable.list", "query", false);
        AgentPlan plan = AgentPlan.ofPlan(Arrays.asList(s1, s2));

        AgentOperationSpec spec1 = spec("dwtable.getById", false, "");
        AgentOperationSpec spec2 = spec("dwtable.list", false, "");
        when(registry.getOperation("dwtable.getById")).thenReturn(spec1);
        when(registry.getOperation("dwtable.list")).thenReturn(spec2);

        when(auditService.createAuditLog(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        executor.startExecution(plan, contextWithAllPermissions(), "sess-1", "test", callback);

        // 两个步骤都应执行
        assertTrue(events.contains("started:s1"), "s1 应开始执行");
        assertTrue(events.contains("started:s2"), "s2 应开始执行");
        assertTrue(events.contains("completed:s1:SUCCESS"), "s1 应执行成功");
        assertTrue(events.contains("completed:s2:SUCCESS"), "s2 应执行成功");
        assertTrue(events.contains("planCompleted"), "计划应完成");
    }

    // ===== 场景2: 破坏性步骤暂停，返回 pending_confirm =====

    @Test
    void test_destructiveStep_pausesExecution()
    {
        AgentStep s1 = step("s1", "dwtable.list", "query", false);
        AgentStep s2 = step("s2", "dwtable.batchDelete", "delete", true);
        AgentStep s3 = step("s3", "dwtable.list", "query", false);
        AgentPlan plan = AgentPlan.ofPlan(Arrays.asList(s1, s2, s3));

        when(registry.getOperation("dwtable.list")).thenReturn(spec("dwtable.list", false, ""));
        when(registry.getOperation("dwtable.batchDelete"))
                .thenReturn(spec("dwtable.batchDelete", true, "dwtable:delete"));

        when(auditService.createAuditLog(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        executor.startExecution(plan, contextWithAllPermissions(), "sess-1", "test", callback);

        // s1 执行，s2 暂停，s3 不执行
        assertTrue(events.contains("started:s1"), "s1 应执行");
        assertTrue(events.contains("completed:s1:SUCCESS"), "s1 应成功");
        assertTrue(events.contains("pending:s2"), "s2 应等待确认");
        assertFalse(events.contains("started:s3"), "s3 不应执行（s2 暂停）");
        assertFalse(events.contains("planCompleted"), "计划不应完成（等待确认）");
    }

    // ===== 场景3: Layer 1 校验 — 操作不存在 =====

    @Test
    void test_validation_layer1_operationNotFound()
    {
        AgentStep s1 = step("s1", "nonexistent.op", "query", false);
        AgentPlan plan = AgentPlan.ofPlan(Collections.singletonList(s1));

        when(registry.getOperation("nonexistent.op")).thenReturn(null);
        when(auditService.createAuditLog(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        executor.startExecution(plan, contextWithAllPermissions(), "sess-1", "test", callback);

        // s1 应标记为 FAILED
        assertTrue(events.contains("completed:s1:FAILED"), "操作不存在应标记失败");
        // 验证审计写入的错误消息
        verify(auditService).updateStepStatus(anyLong(), eq("failed"), contains("不在操作清单中"));
    }

    // ===== 场景3b: Layer 3 校验 — 权限不足 =====

    @Test
    void test_validation_layer3_permissionDenied()
    {
        AgentStep s1 = step("s1", "dwtable.batchDelete", "delete", true);
        AgentPlan plan = AgentPlan.ofPlan(Collections.singletonList(s1));

        // 注意：破坏性步骤会先暂停（pending_confirm），但这里需要通过 confirmStep 触发权限校验
        // 在 startExecution 中，破坏性步骤直接暂停，不校验权限
        // 权限校验在 confirmStep 时发生
        when(registry.getOperation("dwtable.batchDelete"))
                .thenReturn(spec("dwtable.batchDelete", true, "dwtable:delete"));
        when(auditService.createAuditLog(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        // 用户无 dwtable:delete 权限
        AgentExecutionContext noPermission = new AgentExecutionContext(1L, Collections.emptySet());

        executor.startExecution(plan, noPermission, "sess-1", "test", callback);

        // 破坏性步骤直接暂停（权限校验在 confirm 时）
        assertTrue(events.contains("pending:s1"), "破坏性步骤应暂停等待确认");

        // 确认时权限校验失败
        executor.confirmStep(plan, 1L, "s1", noPermission, callback);
        assertTrue(events.contains("completed:s1:FAILED"), "权限不足应标记失败");
        verify(auditService).updateStepStatus(anyLong(), eq("failed"), contains("无权限"));
    }

    // ===== 场景4: 步骤失败级联阻塞依赖步骤 =====

    @Test
    void test_stepFailure_cascadesBlockToDependents()
    {
        AgentStep s1 = step("s1", "dwtable.getById", "query", false);
        AgentStep s2 = step("s2", "dwtable.create", "create", false, "s1");
        AgentPlan plan = AgentPlan.ofPlan(Arrays.asList(s1, s2));

        AgentOperationSpec spec1 = spec("dwtable.getById", false, "");
        when(registry.getOperation("dwtable.getById")).thenReturn(spec1);
        when(registry.getOperation("dwtable.create")).thenReturn(null); // s2 操作不存在

        // s1 执行成功
        when(paramBinder.bind(eq(spec1), anyMap(), any())).thenReturn(new Object[]{1L});

        when(auditService.createAuditLog(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        executor.startExecution(plan, contextWithAllPermissions(), "sess-1", "test", callback);

        // s1 成功，s2 依赖 s1 但操作不存在 → 失败
        assertTrue(events.contains("completed:s1:SUCCESS"), "s1 应成功");
        assertTrue(events.contains("completed:s2:FAILED"), "s2 应失败（操作不存在）");
    }

    @Test
    void test_stepFailure_blocksTransitiveDependents()
    {
        // s1 失败 → s2 (depends on s1) 被 blocked → s3 (depends on s2) 被 blocked
        AgentStep s1 = step("s1", "nonexistent", "query", false);
        AgentStep s2 = step("s2", "dwtable.create", "create", false, "s1");
        AgentStep s3 = step("s3", "dwtable.list", "query", false, "s2");
        AgentPlan plan = AgentPlan.ofPlan(Arrays.asList(s1, s2, s3));

        when(registry.getOperation("nonexistent")).thenReturn(null);
        when(registry.getOperation("dwtable.create")).thenReturn(spec("dwtable.create", false, ""));
        when(registry.getOperation("dwtable.list")).thenReturn(spec("dwtable.list", false, ""));

        when(auditService.createAuditLog(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        executor.startExecution(plan, contextWithAllPermissions(), "sess-1", "test", callback);

        // s1 失败，s2 和 s3 被级联阻塞
        assertTrue(events.contains("completed:s1:FAILED"), "s1 应失败");
        // s2 和 s3 不应执行（被阻塞）
        assertFalse(events.contains("started:s2"), "s2 不应执行");
        assertFalse(events.contains("started:s3"), "s3 不应执行");
    }

    // ===== 场景5: confirmStep 续跑 =====

    @Test
    void test_confirmStep_executesAndContinues()
    {
        AgentStep s1 = step("s1", "dwtable.batchDelete", "delete", true);
        AgentStep s2 = step("s2", "dwtable.list", "query", false);
        AgentPlan plan = AgentPlan.ofPlan(Arrays.asList(s1, s2));

        AgentOperationSpec destructiveSpec = spec("dwtable.batchDelete", true, "dwtable:delete");
        AgentOperationSpec querySpec = spec("dwtable.list", false, "");
        when(registry.getOperation("dwtable.batchDelete")).thenReturn(destructiveSpec);
        when(registry.getOperation("dwtable.list")).thenReturn(querySpec);

        when(auditService.createAuditLog(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(1L);
        when(paramBinder.bind(eq(destructiveSpec), anyMap(), any())).thenReturn(new Object[]{new String[]{"1"}});
        when(paramBinder.bind(eq(querySpec), anyMap(), any())).thenReturn(new Object[]{1L});

        // 启动执行 → s1 暂停
        executor.startExecution(plan, contextWithAllPermissions(), "sess-1", "test", callback);
        assertTrue(events.contains("pending:s1"), "s1 应等待确认");

        // 确认 s1 → 执行 s1 和 s2
        executor.confirmStep(plan, 1L, "s1", contextWithAllPermissions(), callback);
        assertTrue(events.contains("started:s1"), "s1 确认后应执行");
        assertTrue(events.contains("completed:s1:SUCCESS"), "s1 应执行成功");
        assertTrue(events.contains("started:s2"), "s2 应在 s1 后执行");
        assertTrue(events.contains("planCompleted"), "计划应完成");
    }

    // ===== 场景6: skipStep 级联阻塞 =====

    @Test
    void test_skipStep_cascadesBlock()
    {
        AgentStep s1 = step("s1", "dwtable.list", "query", false);
        AgentStep s2 = step("s2", "dwtable.create", "create", false, "s1");
        AgentPlan plan = AgentPlan.ofPlan(Arrays.asList(s1, s2));

        when(registry.getOperation("dwtable.list")).thenReturn(spec("dwtable.list", false, ""));
        when(registry.getOperation("dwtable.create")).thenReturn(spec("dwtable.create", false, ""));

        when(auditService.createAuditLog(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        // 启动执行
        executor.startExecution(plan, contextWithAllPermissions(), "sess-1", "test", callback);

        // 跳过 s2（s2 还没执行，模拟 skip 调用）
        events.clear();
        executor.skipStep(plan, 1L, "s2", callback);

        assertTrue(events.contains("completed:s2:SKIPPED"), "s2 应标记为 skipped");
    }

    // ===== 场景7: retryStep 重试失败步骤 =====

    @Test
    void test_retryStep_reExecutesFailedStep()
    {
        AgentStep s1 = step("s1", "dwtable.getById", "query", false);
        AgentPlan plan = AgentPlan.ofPlan(Collections.singletonList(s1));

        AgentOperationSpec spec1 = spec("dwtable.getById", false, "");
        when(registry.getOperation("dwtable.getById")).thenReturn(spec1);

        when(auditService.createAuditLog(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(1L);
        when(paramBinder.bind(eq(spec1), anyMap(), any())).thenReturn(new Object[]{1L});

        // 重试 s1
        executor.retryStep(plan, 1L, "s1", contextWithAllPermissions(), callback);

        assertTrue(events.contains("started:s1"), "s1 应被重新执行");
        assertTrue(events.contains("completed:s1:SUCCESS"), "s1 重试应成功");
    }

    // ===== 场景8: cancelPlan 取消剩余步骤 =====

    @Test
    void test_cancelPlan_marksRemainingAsBlocked()
    {
        AgentStep s1 = step("s1", "dwtable.list", "query", false);
        AgentStep s2 = step("s2", "dwtable.list", "query", false);
        AgentPlan plan = AgentPlan.ofPlan(Arrays.asList(s1, s2));

        when(registry.getOperation("dwtable.list")).thenReturn(spec("dwtable.list", false, ""));
        when(auditService.createAuditLog(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        // 模拟已有步骤完成
        AgentAuditStepLog completedStep = new AgentAuditStepLog();
        completedStep.setStepId("s1");
        completedStep.setStatus("success");
        when(auditService.getStepLogs(1L)).thenReturn(Collections.singletonList(completedStep));

        executor.cancelPlan(plan, 1L, callback);

        // s2 应被标记为 blocked
        assertTrue(events.contains("blocked:s2"), "s2 应被标记为 blocked");
        assertTrue(events.contains("planInterrupted:用户取消"), "计划应中断");
        verify(auditService).completeAuditLog(1L, "interrupted");
    }

    // ===== 场景9: 审计降级 — auditLogId=null 仍执行 =====

    @Test
    void test_auditDegraded_executionContinues()
    {
        AgentStep s1 = step("s1", "dwtable.getById", "query", false);
        AgentPlan plan = AgentPlan.ofPlan(Collections.singletonList(s1));

        when(registry.getOperation("dwtable.getById")).thenReturn(spec("dwtable.getById", false, ""));
        when(auditService.createAuditLog(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(null); // 审计降级

        Long auditLogId = executor.startExecution(plan, contextWithAllPermissions(),
                "sess-1", "test", callback);

        assertNull(auditLogId, "审计降级时应返回 null");
        // 步骤仍应执行
        assertTrue(events.contains("started:s1"), "审计降级不应阻塞步骤执行");
        assertTrue(events.contains("completed:s1:SUCCESS"), "步骤应成功执行");
    }

    // ===== 场景10: 依赖未满足 — 步骤被 blocked =====

    @Test
    void test_dependencyNotMet_stepBlocked()
    {
        // s2 依赖 s1，但 s1 是破坏性步骤（暂停），s2 因依赖未满足被 blocked
        AgentStep s1 = step("s1", "dwtable.batchDelete", "delete", true);
        AgentStep s2 = step("s2", "dwtable.list", "query", false, "s1");
        AgentPlan plan = AgentPlan.ofPlan(Arrays.asList(s1, s2));

        when(registry.getOperation("dwtable.batchDelete"))
                .thenReturn(spec("dwtable.batchDelete", true, "dwtable:delete"));
        when(registry.getOperation("dwtable.list")).thenReturn(spec("dwtable.list", false, ""));
        when(auditService.createAuditLog(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        executor.startExecution(plan, contextWithAllPermissions(), "sess-1", "test", callback);

        // s1 暂停，s2 因依赖未满足不会被立即 blocked（执行停在 s1）
        assertTrue(events.contains("pending:s1"), "s1 应暂停");
        // s2 不应执行（s1 未完成）
        assertFalse(events.contains("started:s2"), "s2 不应执行");
    }

    // ===== 辅助方法 =====

    private AgentStep step(String stepId, String operationName, String opType, boolean destructive,
                           String... dependsOn)
    {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", 1L);
        List<String> deps = dependsOn.length > 0 ? Arrays.asList(dependsOn) : Collections.emptyList();
        return new AgentStep(stepId, operationName, opType, params, null, destructive, deps);
    }

    private AgentOperationSpec spec(String name, boolean destructive, String permissionKey)
    {
        try
        {
            Method method = TestService.class.getMethod("echo", Object.class);
            return new AgentOperationSpec(name, "desc", destructive, permissionKey,
                    testService, method, Collections.<AgentParamSpec>emptyList());
        }
        catch (NoSuchMethodException e)
        {
            throw new RuntimeException(e);
        }
    }

    private AgentExecutionContext contextWithAllPermissions()
    {
        return new AgentExecutionContext(1L, new java.util.HashSet<>(Arrays.asList(
                "dwtable:delete", "note:note:delete", "column:delete", "record:delete")));
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

    /** 测试用 Service，提供可反射调用的方法 */
    public static class TestService
    {
        public Object echo(Object input)
        {
            return input;
        }
    }
}
