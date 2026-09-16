package com.ruoyi.system.agent.service;

import com.ruoyi.system.agent.audit.PiiRedactor;
import com.ruoyi.system.agent.domain.AgentAuditLog;
import com.ruoyi.system.agent.domain.AgentAuditStepLog;
import com.ruoyi.system.agent.mapper.AgentAuditMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AgentAuditService 单元测试（U4）。
 * <p>
 * 覆盖 6 个场景：即时写入、PII 脱敏、路径 A 恢复、留存期限、跨会话查询、审计降级。
 * <p>
 * 使用 Mockito 模拟 Mapper 和 TransactionManager，TransactionTemplate 回调直接执行。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentAuditServiceTest
{
    @Mock
    private AgentAuditMapper agentAuditMapper;

    private final PiiRedactor piiRedactor = new PiiRedactor();

    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private AgentAuditService agentAuditService;

    @BeforeEach
    void setUp()
    {
        // 注入 PiiRedactor（非 Mock，测试真实脱敏逻辑）
        agentAuditService = new AgentAuditService();
        injectField(agentAuditService, "agentAuditMapper", agentAuditMapper);
        injectField(agentAuditService, "piiRedactor", piiRedactor);
        injectField(agentAuditService, "transactionManager", transactionManager);
        agentAuditService.init();

        // 模拟 TransactionTemplate：直接执行回调，不做真实事务管理
        // TransactionTemplate 在 execute() 中调用 transactionManager.getTransaction() → 执行回调 → commit()
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    // ===== 场景1: 即时写入 — 计划开始创建审计记录，每步写入步骤记录 =====

    @Test
    void test_createAuditLog_insertsMainRecord_statusExecuting()
    {
        // 模拟 useGeneratedKeys：insertAuditLog 时设置 id
        doAnswer(invocation ->
        {
            AgentAuditLog log = invocation.getArgument(0);
            log.setId(100L);
            return 1;
        }).when(agentAuditMapper).insertAuditLog(any(AgentAuditLog.class));

        Long auditLogId = agentAuditService.createAuditLog(1L, "sess-001",
                "创建一个多维表", "{\"type\":\"PLAN\",\"steps\":[]}");

        assertNotNull(auditLogId, "审计日志ID不应为 null");
        assertEquals(100L, auditLogId);

        // 验证 insertAuditLog 被调用，且 status=executing
        ArgumentCaptor<AgentAuditLog> captor = ArgumentCaptor.forClass(AgentAuditLog.class);
        verify(agentAuditMapper).insertAuditLog(captor.capture());
        AgentAuditLog inserted = captor.getValue();
        assertEquals("executing", inserted.getStatus(), "初始状态应为 executing");
        assertEquals(1L, inserted.getUserId());
        assertEquals("sess-001", inserted.getSessionId());
        assertFalse(inserted.getDegraded(), "初始不应为降级");
    }

    @Test
    void test_insertStepLog_insertsStepRecord_statusExecuting()
    {
        // 步骤前 INSERT status=executing
        doAnswer(invocation ->
        {
            AgentAuditStepLog step = invocation.getArgument(0);
            step.setId(200L);
            return 1;
        }).when(agentAuditMapper).insertStepLog(any(AgentAuditStepLog.class));

        Long stepLogId = agentAuditService.insertStepLog(100L, 0, "s1",
                "dwtable.create", "params", "req-001");

        assertNotNull(stepLogId, "步骤日志ID不应为 null");
        assertEquals(200L, stepLogId);

        ArgumentCaptor<AgentAuditStepLog> captor = ArgumentCaptor.forClass(AgentAuditStepLog.class);
        verify(agentAuditMapper).insertStepLog(captor.capture());
        AgentAuditStepLog inserted = captor.getValue();
        assertEquals("executing", inserted.getStatus(), "步骤初始状态应为 executing");
        assertEquals("s1", inserted.getStepId());
        assertEquals("dwtable.create", inserted.getOperationName());
        assertEquals("req-001", inserted.getStepRequestId(), "应保存幂等键");
    }

    // ===== 场景2: PII 脱敏 — 用户输入含手机号 → 审计记录中已脱敏 =====

    @Test
    void test_piiRedaction_userInputAndPlanJson_redacted()
    {
        doAnswer(invocation ->
        {
            AgentAuditLog log = invocation.getArgument(0);
            log.setId(1L);
            return 1;
        }).when(agentAuditMapper).insertAuditLog(any(AgentAuditLog.class));

        // userInput 含手机号
        String userInput = "删除手机号13812345678的记录";
        // planJson 含嵌套手机号
        String planJson = "{\"steps\":[{\"operationName\":\"record.delete\","
                + "\"params\":{\"reason\":\"联系13812345678\"}}]}";

        agentAuditService.createAuditLog(1L, "sess-002", userInput, planJson);

        ArgumentCaptor<AgentAuditLog> captor = ArgumentCaptor.forClass(AgentAuditLog.class);
        verify(agentAuditMapper).insertAuditLog(captor.capture());
        AgentAuditLog inserted = captor.getValue();

        // userInput 纯文本脱敏
        assertFalse(inserted.getUserInput().contains("13812345678"),
                "userInput 中手机号应被脱敏");
        assertTrue(inserted.getUserInput().contains("138****5678"),
                "userInput 中手机号应保留前3后4");

        // planJson 递归 JSON 脱敏
        assertFalse(inserted.getPlanJson().contains("13812345678"),
                "planJson 中嵌套手机号应被脱敏");
        assertTrue(inserted.getPlanJson().contains("138****5678"),
                "planJson 中嵌套手机号应保留前3后4");
    }

    @Test
    void test_piiRedaction_paramsJson_redacted()
    {
        doAnswer(invocation ->
        {
            AgentAuditStepLog step = invocation.getArgument(0);
            step.setId(1L);
            return 1;
        }).when(agentAuditMapper).insertStepLog(any(AgentAuditStepLog.class));

        // params 对象含手机号
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("phone", "13812345678");
        params.put("name", "测试");

        agentAuditService.insertStepLog(1L, 0, "s1", "record.delete", params, "req-001");

        ArgumentCaptor<AgentAuditStepLog> captor = ArgumentCaptor.forClass(AgentAuditStepLog.class);
        verify(agentAuditMapper).insertStepLog(captor.capture());
        AgentAuditStepLog inserted = captor.getValue();

        assertFalse(inserted.getParamsJson().contains("13812345678"),
                "paramsJson 中手机号应被脱敏");
        assertTrue(inserted.getParamsJson().contains("138****5678"),
                "paramsJson 中手机号应保留前3后4");
    }

    // ===== 场景3: 路径 A 恢复 — 查询未完成审计记录含步骤状态 =====

    @Test
    void test_getIncompleteAuditLog_returnsRecordsWithStepStatus()
    {
        // 模拟未完成审计记录
        AgentAuditLog incompleteLog = new AgentAuditLog();
        incompleteLog.setId(50L);
        incompleteLog.setUserId(1L);
        incompleteLog.setStatus("executing");
        incompleteLog.setUserInput("创建多维表并加列");
        incompleteLog.setPlanJson("{\"steps\":[]}");

        when(agentAuditMapper.selectIncompleteAuditLog(eq(1L), anyInt()))
                .thenReturn(Collections.singletonList(incompleteLog));

        // 模拟步骤日志
        AgentAuditStepLog step1 = new AgentAuditStepLog();
        step1.setId(1L);
        step1.setAuditLogId(50L);
        step1.setStepIndex(0);
        step1.setStepId("s1");
        step1.setOperationName("dwtable.create");
        step1.setStatus("success");

        AgentAuditStepLog step2 = new AgentAuditStepLog();
        step2.setId(2L);
        step2.setAuditLogId(50L);
        step2.setStepIndex(1);
        step2.setStepId("s2");
        step2.setOperationName("column.create");
        step2.setStatus("executing"); // 崩溃时不确定状态

        // 批量查询步骤日志（服务层已优化为 IN 批量查询，步骤记录需设置 auditLogId 以便分组）
        when(agentAuditMapper.selectStepLogsByAuditLogIds(anyList()))
                .thenReturn(Arrays.asList(step1, step2));

        List<AgentAuditLog> result = agentAuditService.getIncompleteAuditLog(1L);

        assertNotNull(result);
        assertEquals(1, result.size(), "应返回 1 条未完成记录");
        AgentAuditLog log = result.get(0);
        assertEquals("executing", log.getStatus());
        assertNotNull(log.getStepLogs(), "步骤日志应被填充");
        assertEquals(2, log.getStepLogs().size());
        assertEquals("success", log.getStepLogs().get(0).getStatus(),
                "步骤 1 已完成");
        assertEquals("executing", log.getStepLogs().get(1).getStatus(),
                "步骤 2 处于 executing（不确定状态）");
    }

    // ===== 场景3b: errorMessage PII 脱敏 =====

    @Test
    void test_updateStepStatus_errorMessagePiiRedacted()
    {
        // Service 异常消息可能含 PII（如 echo 用户输入）
        String errorMsg = "记录 13812345678 不存在";

        agentAuditService.updateStepStatus(1L, "failed", errorMsg);

        ArgumentCaptor<AgentAuditStepLog> captor = ArgumentCaptor.forClass(AgentAuditStepLog.class);
        verify(agentAuditMapper).updateStepLogStatus(captor.capture());
        AgentAuditStepLog updated = captor.getValue();
        assertFalse(updated.getErrorMessage().contains("13812345678"),
                "errorMessage 中手机号应被脱敏");
        assertTrue(updated.getErrorMessage().contains("138****5678"),
                "errorMessage 中手机号应保留前3后4");
    }

    // ===== 场景4: 留存期限 — 91 天前的记录不返回（验证 Mapper 被正确调用） =====

    @Test
    void test_retentionPeriod_mapperCalledWithUserId()
    {
        // 留存期限过滤在 SQL 层（created_at >= NOW() - 90 DAY），
        // 单元测试验证 Mapper 方法被调用，SQL 层过滤由集成测试覆盖
        when(agentAuditMapper.selectRecentHistory(eq(1L), anyInt()))
                .thenReturn(Collections.emptyList());

        List<AgentAuditLog> result = agentAuditService.getRecentHistory(1L);

        assertNotNull(result);
        verify(agentAuditMapper).selectRecentHistory(eq(1L), anyInt());
    }

    // ===== 场景5: 跨会话查询 — getRecentHistory 返回最近 50 条 =====

    @Test
    void test_getRecentHistory_returnsAllSessions()
    {
        // 模拟跨会话的 2 条记录
        AgentAuditLog log1 = new AgentAuditLog();
        log1.setId(1L);
        log1.setSessionId("sess-a");
        log1.setStatus("completed");

        AgentAuditLog log2 = new AgentAuditLog();
        log2.setId(2L);
        log2.setSessionId("sess-b");
        log2.setStatus("completed");

        when(agentAuditMapper.selectRecentHistory(eq(1L), anyInt()))
                .thenReturn(Arrays.asList(log1, log2));

        List<AgentAuditLog> result = agentAuditService.getRecentHistory(1L);

        assertEquals(2, result.size(), "应返回 2 条记录");
        // 验证来自不同会话
        assertNotEquals(result.get(0).getSessionId(), result.get(1).getSessionId(),
                "应包含跨会话记录");
    }

    // ===== 场景6: 审计降级 — 审计写入失败不阻塞业务 =====

    @Test
    void test_auditWriteFailure_doesNotThrow_returnsNull()
    {
        // 模拟 mapper 抛异常（数据库故障）
        doThrow(new RuntimeException("DB connection failed"))
                .when(agentAuditMapper).insertAuditLog(any(AgentAuditLog.class));

        // 审计写入失败不应抛异常
        Long auditLogId = agentAuditService.createAuditLog(1L, "sess-003",
                "创建多维表", "{}");

        assertNull(auditLogId, "审计降级时应返回 null");
    }

    @Test
    void test_stepAuditWriteFailure_doesNotThrow_returnsNull()
    {
        doThrow(new RuntimeException("DB connection failed"))
                .when(agentAuditMapper).insertStepLog(any(AgentAuditStepLog.class));

        Long stepLogId = agentAuditService.insertStepLog(1L, 0, "s1",
                "dwtable.create", "params", "req-001");

        assertNull(stepLogId, "步骤审计降级时应返回 null");
    }

    @Test
    void test_markDegraded_updatesAuditLog()
    {
        agentAuditService.markDegraded(100L);

        ArgumentCaptor<AgentAuditLog> captor = ArgumentCaptor.forClass(AgentAuditLog.class);
        verify(agentAuditMapper).updateAuditLogStatus(captor.capture());
        assertTrue(captor.getValue().getDegraded(), "应标记为降级");
        assertEquals(100L, captor.getValue().getId());
    }

    @Test
    void test_completeAuditLog_updatesStatusAndCompletedAt()
    {
        agentAuditService.completeAuditLog(100L, "completed");

        ArgumentCaptor<AgentAuditLog> captor = ArgumentCaptor.forClass(AgentAuditLog.class);
        verify(agentAuditMapper).updateAuditLogStatus(captor.capture());
        AgentAuditLog updated = captor.getValue();
        assertEquals("completed", updated.getStatus());
        assertNotNull(updated.getCompletedAt(), "应设置完成时间");
    }

    // ===== 场景7: 按幂等键查询步骤（路径 A 恢复判断 create 是否已提交） =====

    @Test
    void test_getStepLogByRequestId_returnsStepLog()
    {
        AgentAuditStepLog stepLog = new AgentAuditStepLog();
        stepLog.setId(1L);
        stepLog.setStepRequestId("req-001");
        stepLog.setStatus("success");

        when(agentAuditMapper.selectStepLogByStepRequestId("req-001"))
                .thenReturn(stepLog);

        AgentAuditStepLog result = agentAuditService.getStepLogByRequestId("req-001");

        assertNotNull(result, "应返回步骤日志");
        assertEquals("success", result.getStatus());
    }

    @Test
    void test_getStepLogByRequestId_nullInput_returnsNull()
    {
        assertNull(agentAuditService.getStepLogByRequestId(null),
                "null 幂等键应返回 null");
        assertNull(agentAuditService.getStepLogByRequestId(""),
                "空幂等键应返回 null");
    }

    // ===== 辅助方法 =====

    private void injectField(Object target, String fieldName, Object value)
    {
        try
        {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        }
        catch (Exception e)
        {
            throw new RuntimeException("注入失败: " + fieldName, e);
        }
    }
}
