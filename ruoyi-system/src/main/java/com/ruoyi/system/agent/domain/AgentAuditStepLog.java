package com.ruoyi.system.agent.domain;

import com.ruoyi.common.core.domain.BaseEntity;

import java.util.Date;

/**
 * Agent 审计步骤日志对象 agent_audit_step_log
 * <p>
 * 每个步骤执行前 INSERT（status=executing），执行后 UPDATE 为最终状态。
 * 崩溃恢复时 status=executing 表示"不确定"，强制用户手工核对。
 * <p>
 * 幂等键 {@link #stepRequestId}：create 操作防重复提交，路径 A 恢复时判断是否已提交。
 *
 * @author ruoyi
 */
public class AgentAuditStepLog extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 步骤日志ID */
    private Long id;

    /** 审计日志ID（外键关联 agent_audit_log.id） */
    private Long auditLogId;

    /** 步骤序号（0-based，在计划中的位置） */
    private Integer stepIndex;

    /** 步骤唯一标识（如 "s1"、"s2"，计划内唯一） */
    private String stepId;

    /** 操作名（如 "dwtable.create"） */
    private String operationName;

    /** 参数 JSON（PII 脱敏后） */
    private String paramsJson;

    /** 状态：executing / success / failed / skipped / blocked */
    private String status;

    /** 错误信息（status=failed 时填充） */
    private String errorMessage;

    /** 幂等键（create 操作防重复，路径 A 恢复判断是否已提交） */
    private String stepRequestId;

    /** 执行时间（步骤开始执行时间） */
    private Date executedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getAuditLogId() { return auditLogId; }
    public void setAuditLogId(Long auditLogId) { this.auditLogId = auditLogId; }

    public Integer getStepIndex() { return stepIndex; }
    public void setStepIndex(Integer stepIndex) { this.stepIndex = stepIndex; }

    public String getStepId() { return stepId; }
    public void setStepId(String stepId) { this.stepId = stepId; }

    public String getOperationName() { return operationName; }
    public void setOperationName(String operationName) { this.operationName = operationName; }

    public String getParamsJson() { return paramsJson; }
    public void setParamsJson(String paramsJson) { this.paramsJson = paramsJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getStepRequestId() { return stepRequestId; }
    public void setStepRequestId(String stepRequestId) { this.stepRequestId = stepRequestId; }

    public Date getExecutedAt() { return executedAt; }
    public void setExecutedAt(Date executedAt) { this.executedAt = executedAt; }
}
