package com.ruoyi.system.agent.service;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.system.agent.audit.PiiRedactor;
import com.ruoyi.system.agent.domain.AgentAuditLog;
import com.ruoyi.system.agent.domain.AgentAuditStepLog;
import com.ruoyi.system.agent.mapper.AgentAuditMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 审计服务。
 * <p>
 * 提供计划级与步骤级审计日志的写入、查询能力，支持路径 A 恢复（R20b）与历史查询（R23）。
 * <p>
 * 关键设计：
 * <ul>
 *   <li><b>REQUIRES_NEW 事务</b>：所有审计写入通过 {@link TransactionTemplate} 独立事务执行，
 *       不干扰业务步骤的事务边界</li>
 *   <li><b>审计降级</b>：所有写入与查询方法均 catch 异常不阻塞业务——
 *       写入失败时 catch + log.error + 标记 degraded，查询失败时返回 null/空列表，
 *       路径 A 恢复对 degraded 记录提示"审计不完整"</li>
 *   <li><b>PII 脱敏</b>：userInput 用 {@link PiiRedactor#redact(String)}，
 *       planJson/paramsJson 用 {@link PiiRedactor#redactJson(String)} 递归脱敏</li>
 *   <li><b>竞态防护</b>：步骤执行前 INSERT status=executing，崩溃恢复时识别为"不确定"</li>
 *   <li><b>留存期限</b>：查询过滤 90 天前记录（R23）</li>
 * </ul>
 *
 * @see AgentAuditLog
 * @see AgentAuditStepLog
 * @see PiiRedactor
 */
@Service
public class AgentAuditService
{
    private static final Logger log = LoggerFactory.getLogger(AgentAuditService.class);

    /** 审计留存期限（天） */
    private static final int RETENTION_DAYS = 90;

    @Autowired
    private AgentAuditMapper agentAuditMapper;

    @Autowired
    private PiiRedactor piiRedactor;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** REQUIRES_NEW 事务模板，用于独立审计写入 */
    private TransactionTemplate requiresNewTx;

    @PostConstruct
    public void init()
    {
        requiresNewTx = new TransactionTemplate(transactionManager);
        requiresNewTx.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ===== 计划级审计 =====

    /**
     * 创建审计日志主记录（计划开始执行时调用）。
     * <p>
     * 对 userInput 应用纯文本脱敏，对 planJson 应用 JSON 递归脱敏。
     * 审计写入失败不抛异常，返回 null 表示审计降级。
     *
     * @param userId     用户ID
     * @param sessionId  会话ID
     * @param userInput  用户原始输入
     * @param planJson   计划 JSON（含 steps 数组）
     * @return 审计日志ID，审计降级时返回 null
     */
    public Long createAuditLog(Long userId, String sessionId, String userInput, String planJson)
    {
        AgentAuditLog auditLog = new AgentAuditLog();
        auditLog.setUserId(userId);
        auditLog.setSessionId(sessionId);
        auditLog.setStatus("executing");
        auditLog.setDegraded(false);
        auditLog.setCreatedAt(new Date());

        try
        {
            // PII 脱敏：userInput 纯文本，planJson 递归 JSON（移入 try 防止脱敏异常阻塞业务）
            auditLog.setUserInput(piiRedactor.redact(userInput));
            auditLog.setPlanJson(piiRedactor.redactJson(planJson));

            requiresNewTx.execute(status ->
            {
                agentAuditMapper.insertAuditLog(auditLog);
                return null;
            });
            log.debug("审计日志已创建: id={}, userId={}", auditLog.getId(), userId);
            return auditLog.getId();
        }
        catch (Exception e)
        {
            log.error("审计日志创建失败（降级）: userId={}, sessionId={}", userId, sessionId, e);
            return null;
        }
    }

    /**
     * 标记审计日志降级（审计写入曾失败时调用）。
     */
    public void markDegraded(Long auditLogId)
    {
        if (auditLogId == null) return;
        try
        {
            AgentAuditLog update = new AgentAuditLog();
            update.setId(auditLogId);
            update.setDegraded(true);
            requiresNewTx.execute(status ->
            {
                agentAuditMapper.updateAuditLogStatus(update);
                return null;
            });
        }
        catch (Exception e)
        {
            log.error("审计降级标记失败: auditLogId={}", auditLogId, e);
        }
    }

    /**
     * 完成审计日志（全部步骤完成或中断时调用）。
     *
     * @param auditLogId  审计日志ID
     * @param status      最终状态：completed / interrupted
     */
    public void completeAuditLog(Long auditLogId, String status)
    {
        if (auditLogId == null) return;
        try
        {
            AgentAuditLog update = new AgentAuditLog();
            update.setId(auditLogId);
            update.setStatus(status);
            update.setCompletedAt(new Date());
            requiresNewTx.execute(s ->
            {
                agentAuditMapper.updateAuditLogStatus(update);
                return null;
            });
            log.debug("审计日志已完成: id={}, status={}", auditLogId, status);
        }
        catch (Exception e)
        {
            log.error("审计日志完成标记失败: auditLogId={}", auditLogId, e);
        }
    }

    /**
     * 查询用户未完成的审计记录（路径 A 恢复，R20b）。
     * <p>
     * 返回 status=executing 的记录（90 天内），并关联填充步骤日志。
     *
     * @param userId 用户ID
     * @return 未完成审计记录列表（含步骤状态），按创建时间倒序
     */
    public List<AgentAuditLog> getIncompleteAuditLog(Long userId)
    {
        try
        {
            List<AgentAuditLog> logs = agentAuditMapper.selectIncompleteAuditLog(userId, RETENTION_DAYS);
            if (logs != null && !logs.isEmpty())
            {
                // 批量查询所有步骤日志（避免 N+1 查询，2 次 SQL 替代 N+1 次）
                List<Long> auditLogIds = new ArrayList<>(logs.size());
                for (AgentAuditLog logEntry : logs)
                {
                    auditLogIds.add(logEntry.getId());
                }
                List<AgentAuditStepLog> allStepLogs =
                        agentAuditMapper.selectStepLogsByAuditLogIds(auditLogIds);

                // 按 auditLogId 分组后设置到对应的审计日志
                Map<Long, List<AgentAuditStepLog>> stepLogMap = new HashMap<>();
                if (allStepLogs != null)
                {
                    for (AgentAuditStepLog stepLog : allStepLogs)
                    {
                        stepLogMap.computeIfAbsent(stepLog.getAuditLogId(), k -> new ArrayList<>())
                                .add(stepLog);
                    }
                }
                for (AgentAuditLog logEntry : logs)
                {
                    List<AgentAuditStepLog> stepLogs = stepLogMap.get(logEntry.getId());
                    logEntry.setStepLogs(stepLogs != null ? stepLogs : Collections.emptyList());
                }
            }
            return logs;
        }
        catch (Exception e)
        {
            log.error("未完成审计记录查询失败（降级）: userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 查询用户最近 50 条审计记录（R23 历史查询）。
     */
    public List<AgentAuditLog> getRecentHistory(Long userId)
    {
        try
        {
            return agentAuditMapper.selectRecentHistory(userId, RETENTION_DAYS);
        }
        catch (Exception e)
        {
            log.error("历史审计记录查询失败（降级）: userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 按 ID 查询审计日志（含步骤日志）。
     */
    public AgentAuditLog getAuditLogById(Long id)
    {
        try
        {
            AgentAuditLog logEntry = agentAuditMapper.selectAuditLogById(id);
            if (logEntry != null)
            {
                logEntry.setStepLogs(agentAuditMapper.selectStepLogsByAuditLogId(id));
            }
            return logEntry;
        }
        catch (Exception e)
        {
            log.error("审计记录查询失败（降级）: id={}", id, e);
            return null;
        }
    }

    // ===== 步骤级审计 =====

    /**
     * 新增步骤审计记录（步骤执行前调用，status=executing）。
     * <p>
     * 对 paramsJson 应用 JSON 递归脱敏。
     * 审计写入失败不抛异常，返回 null，调用方应标记降级。
     *
     * @param auditLogId     审计日志ID
     * @param stepIndex      步骤序号
     * @param stepId         步骤唯一标识
     * @param operationName  操作名
     * @param params         参数对象（将转为 JSON 并脱敏）
     * @param stepRequestId  幂等键（create 操作防重复）
     * @return 步骤日志ID，审计降级时返回 null
     */
    public Long insertStepLog(Long auditLogId, int stepIndex, String stepId,
                              String operationName, Object params, String stepRequestId)
    {
        if (auditLogId == null) return null;

        AgentAuditStepLog stepLog = new AgentAuditStepLog();
        stepLog.setAuditLogId(auditLogId);
        stepLog.setStepIndex(stepIndex);
        stepLog.setStepId(stepId);
        stepLog.setOperationName(operationName);
        stepLog.setStatus("executing");
        stepLog.setStepRequestId(stepRequestId);
        stepLog.setExecutedAt(new Date());

        try
        {
            // params 转 JSON 并 PII 脱敏（移入 try 防止序列化/脱敏异常阻塞业务，
            // 如 params 存在循环引用导致 JSON.toJSONString 抛异常）
            String paramsJson = params != null ? JSON.toJSONString(params) : null;
            stepLog.setParamsJson(piiRedactor.redactJson(paramsJson));

            requiresNewTx.execute(status ->
            {
                agentAuditMapper.insertStepLog(stepLog);
                return null;
            });
            log.debug("步骤审计已创建: auditLogId={}, stepId={}, operation={}",
                    auditLogId, stepId, operationName);
            return stepLog.getId();
        }
        catch (Exception e)
        {
            log.error("步骤审计创建失败（降级）: auditLogId={}, stepId={}", auditLogId, stepId, e);
            return null;
        }
    }

    /**
     * 更新步骤状态（步骤执行完成后调用）。
     * <p>
     * 对 errorMessage 应用 PII 纯文本脱敏，防止 Service 异常消息中残留用户输入的 PII。
     *
     * @param stepLogId    步骤日志ID
     * @param status       最终状态：success / failed / skipped / blocked
     * @param errorMessage 错误信息（failed 时填充，将自动 PII 脱敏）
     */
    public void updateStepStatus(Long stepLogId, String status, String errorMessage)
    {
        if (stepLogId == null) return;
        try
        {
            AgentAuditStepLog update = new AgentAuditStepLog();
            update.setId(stepLogId);
            update.setStatus(status);
            update.setErrorMessage(piiRedactor.redact(errorMessage));
            requiresNewTx.execute(s ->
            {
                agentAuditMapper.updateStepLogStatus(update);
                return null;
            });
        }
        catch (Exception e)
        {
            log.error("步骤状态更新失败: stepLogId={}", stepLogId, e);
        }
    }

    /**
     * 按幂等键查询步骤记录（路径 A 恢复时判断 create 是否已提交）。
     *
     * @param stepRequestId 幂等键
     * @return 步骤日志，不存在返回 null
     */
    public AgentAuditStepLog getStepLogByRequestId(String stepRequestId)
    {
        if (stepRequestId == null || stepRequestId.isEmpty()) return null;
        try
        {
            return agentAuditMapper.selectStepLogByStepRequestId(stepRequestId);
        }
        catch (Exception e)
        {
            log.error("步骤审计查询失败（降级）: stepRequestId={}", stepRequestId, e);
            return null;
        }
    }

    /**
     * 按审计日志ID查询步骤列表。
     */
    public List<AgentAuditStepLog> getStepLogs(Long auditLogId)
    {
        try
        {
            return agentAuditMapper.selectStepLogsByAuditLogId(auditLogId);
        }
        catch (Exception e)
        {
            log.error("步骤列表查询失败（降级）: auditLogId={}", auditLogId, e);
            return Collections.emptyList();
        }
    }
}
