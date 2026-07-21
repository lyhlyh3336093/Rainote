-- AI Chat tables DDL
-- Run this script against the target database before starting the application

CREATE TABLE IF NOT EXISTS ai_chat_session (
    id              BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '会话ID',
    userId          BIGINT       NOT NULL                 COMMENT '用户ID (逻辑关联 sys_user.user_id)',
    title           VARCHAR(200) DEFAULT NULL              COMMENT '会话标题 (首条用户消息自动派生)',
    lastSelectedAt  DATETIME     DEFAULT NULL              COMMENT '最后选择时间 (R10 恢复用)',
    delFlag         TINYINT      DEFAULT 0                COMMENT '删除标识 (0=正常 1=已删除)',
    createTime      DATETIME     DEFAULT NULL             COMMENT '创建时间',
    createBy        VARCHAR(64)  DEFAULT ''               COMMENT '创建者',
    updateTime      DATETIME     DEFAULT NULL             COMMENT '更新时间',
    updateBy        VARCHAR(64)  DEFAULT ''               COMMENT '更新者',
    PRIMARY KEY (id),
    KEY idx_session_user (userId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI聊天会话表';

CREATE TABLE IF NOT EXISTS ai_chat_message (
    id          BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '消息ID',
    sessionId   BIGINT       NOT NULL                 COMMENT '会话ID (逻辑关联 ai_chat_session.id)',
    role        VARCHAR(20)  NOT NULL                 COMMENT '角色 (user/assistant)',
    content     TEXT         NOT NULL                 COMMENT '消息内容',
    createTime  DATETIME     DEFAULT NULL             COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_message_session (sessionId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI聊天消息表';
