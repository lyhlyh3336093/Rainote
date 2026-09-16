package com.ruoyi.system.agent.mapper;

import com.ruoyi.system.agent.domain.AgentAuditLog;
import com.ruoyi.system.agent.domain.AgentAuditStepLog;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Agent 审计日志 Mapper 接口。
 * <p>
 * 支持主记录 CRUD、步骤记录 CRUD、未完成记录查询（路径 A 恢复）、
 * 最近历史查询（R23）、按幂等键查询（create 防重复）。
 *
 * @author ruoyi
 */
public interface AgentAuditMapper
{
    // ===== 主审计记录 =====

    /**
     * 新增审计日志主记录（status=executing）。
     */
    public int insertAuditLog(AgentAuditLog auditLog);

    /**
     * 更新审计日志状态（completed/interrupted）及降级标记。
     */
    public int updateAuditLogStatus(AgentAuditLog auditLog);

    /**
     * 按 ID 查询审计日志（含步骤列表）。
     */
    public AgentAuditLog selectAuditLogById(Long id);

    /**
     * 查询用户最近的未完成审计记录（status=executing）。
     * 用于路径 A 恢复（R20b）。
     *
     * @param userId        用户ID
     * @param retentionDays 留存期限（天），仅返回此天数内的记录
     * @return 未完成审计记录列表（按创建时间倒序）
     */
    public List<AgentAuditLog> selectIncompleteAuditLog(@Param("userId") Long userId,
                                                        @Param("retentionDays") int retentionDays);

    /**
     * 查询用户最近 50 条审计记录（R23）。
     * 仅返回 retentionDays 天内记录，按创建时间倒序。
     *
     * @param userId        用户ID
     * @param retentionDays 留存期限（天），仅返回此天数内的记录
     * @return 审计记录列表
     */
    public List<AgentAuditLog> selectRecentHistory(@Param("userId") Long userId,
                                                   @Param("retentionDays") int retentionDays);

    // ===== 步骤审计记录 =====

    /**
     * 新增步骤审计记录（status=executing，步骤执行前 INSERT）。
     */
    public int insertStepLog(AgentAuditStepLog stepLog);

    /**
     * 更新步骤状态（success/failed/skipped）及错误信息。
     */
    public int updateStepLogStatus(AgentAuditStepLog stepLog);

    /**
     * 按审计日志ID查询步骤列表（路径 A 恢复时获取步骤状态）。
     */
    public List<AgentAuditStepLog> selectStepLogsByAuditLogId(Long auditLogId);

    /**
     * 按审计日志ID列表批量查询步骤记录（优化 N+1 查询）。
     * <p>
     * 返回的每条记录包含 auditLogId 字段，调用方可据此分组。
     *
     * @param auditLogIds 审计日志ID列表，空列表时返回空结果
     * @return 步骤记录列表（按 audit_log_id, step_index 排序）
     */
    public List<AgentAuditStepLog> selectStepLogsByAuditLogIds(List<Long> auditLogIds);

    /**
     * 按幂等键查询步骤记录（路径 A 恢复时判断 create 是否已提交）。
     */
    public AgentAuditStepLog selectStepLogByStepRequestId(String stepRequestId);
}
