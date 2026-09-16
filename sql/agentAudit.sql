-- =============================================
-- Agent 审计日志 DDL（U4）
-- 表：agent_audit_log（主审计记录）
--      agent_audit_step_log（步骤级审计记录）
-- =============================================

-- ---------------------------------------------
-- 主审计记录表
-- ---------------------------------------------
DROP TABLE IF EXISTS `agent_audit_log`;
CREATE TABLE `agent_audit_log` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '审计日志ID',
    `user_id`       BIGINT       NOT NULL                 COMMENT '用户ID',
    `session_id`    VARCHAR(64)  DEFAULT NULL              COMMENT '会话ID（前端会话标识）',
    `user_input`    TEXT         DEFAULT NULL              COMMENT '用户输入（PII 脱敏后）',
    `plan_json`     TEXT         DEFAULT NULL              COMMENT '计划JSON（PII 脱敏后）',
    `status`        VARCHAR(20)  NOT NULL DEFAULT 'executing' COMMENT '状态：executing/completed/interrupted',
    `degraded`      TINYINT(1)   NOT NULL DEFAULT 0       COMMENT '审计降级标记（0=正常 1=降级）',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `completed_at`  DATETIME     DEFAULT NULL              COMMENT '完成时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_status` (`user_id`, `status`),
    KEY `idx_user_created` (`user_id`, `created_at`),
    KEY `idx_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 审计日志主表';

-- ---------------------------------------------
-- 步骤审计记录表
-- ---------------------------------------------
DROP TABLE IF EXISTS `agent_audit_step_log`;
CREATE TABLE `agent_audit_step_log` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '步骤日志ID',
    `audit_log_id`    BIGINT       NOT NULL                 COMMENT '审计日志ID（外键 agent_audit_log.id）',
    `step_index`      INT          NOT NULL                 COMMENT '步骤序号（0-based）',
    `step_id`         VARCHAR(20)  DEFAULT NULL              COMMENT '步骤唯一标识（如 s1/s2）',
    `operation_name`  VARCHAR(100) NOT NULL                 COMMENT '操作名（如 dwtable.create）',
    `params_json`     TEXT         DEFAULT NULL              COMMENT '参数JSON（PII 脱敏后）',
    `status`          VARCHAR(20)  NOT NULL DEFAULT 'executing' COMMENT '状态：executing/success/failed/skipped/blocked',
    `error_message`   TEXT         DEFAULT NULL              COMMENT '错误信息（status=failed 时填充）',
    `step_request_id` VARCHAR(64)  DEFAULT NULL              COMMENT '幂等键（create 防重复，路径 A 恢复判断是否已提交）',
    `executed_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '执行时间',
    PRIMARY KEY (`id`),
    KEY `idx_audit_log_id` (`audit_log_id`),
    UNIQUE KEY `uk_step_request_id` (`step_request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 审计步骤日志表';
