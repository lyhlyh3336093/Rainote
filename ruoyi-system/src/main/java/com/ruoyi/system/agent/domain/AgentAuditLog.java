package com.ruoyi.system.agent.domain;

import com.ruoyi.common.core.domain.BaseEntity;

import java.util.Date;
import java.util.List;

/**
 * Agent 审计日志对象 agent_audit_log
 * <p>
 * 每次计划执行创建一条主记录，status 从 executing → completed/interrupted。
 * 崩溃恢复时通过 {@link #status} = executing 识别未完成计划（路径 A 恢复）。
 * <p>
 * F19 简化决策：INSERT + UPDATE 模式，无 SHA-256 链式哈希。
 * 审计降级标记 {@link #degraded}：审计写入失败时不阻塞业务，标记后路径 A 恢复提示"审计不完整"。
 *
 * @author ruoyi
 */
public class AgentAuditLog extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 审计日志ID */
    private Long id;

    /** 用户ID */
    private Long userId;

    /** 会话ID（前端会话标识，用于跨请求关联） */
    private String sessionId;

    /** 用户输入（PII 脱敏后） */
    private String userInput;

    /** 计划 JSON（PII 脱敏后，含 steps 数组） */
    private String planJson;

    /** 状态：executing / completed / interrupted */
    private String status;

    /** 审计降级标记（true 表示审计写入曾失败，路径 A 恢复需提示用户） */
    private Boolean degraded;

    /** 创建时间（计划开始执行时间） */
    private Date createdAt;

    /** 完成时间（全部步骤完成或中断时间） */
    private Date completedAt;

    /** 步骤日志列表（非持久化，查询时关联填充） */
    private transient List<AgentAuditStepLog> stepLogs;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getUserInput() { return userInput; }
    public void setUserInput(String userInput) { this.userInput = userInput; }

    public String getPlanJson() { return planJson; }
    public void setPlanJson(String planJson) { this.planJson = planJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Boolean getDegraded() { return degraded; }
    public void setDegraded(Boolean degraded) { this.degraded = degraded; }

    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }

    public Date getCompletedAt() { return completedAt; }
    public void setCompletedAt(Date completedAt) { this.completedAt = completedAt; }

    public List<AgentAuditStepLog> getStepLogs() { return stepLogs; }
    public void setStepLogs(List<AgentAuditStepLog> stepLogs) { this.stepLogs = stepLogs; }
}
