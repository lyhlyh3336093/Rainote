-- =============================================
-- VIP 会员系统 DDL + 初始化数据（U1）
-- 表：vip_membership（会员身份）
--      vip_recharge_order（充值申请订单）
--      vip_tier（时长档位）
--      sys_upload_record（上传容量记录）
-- 变更：note_note 新增 delete_time（回收站保留期）
-- 配置：sys_config 限额参数 / sys_job 回收站清理任务
-- =============================================

-- ---------------------------------------------
-- 会员身份表（R1：每用户一行，到期时间惰性比较）
-- ---------------------------------------------
CREATE TABLE `vip_membership` (
    `user_id`     BIGINT       NOT NULL                 COMMENT '用户ID（sys_user.user_id）',
    `expire_time` DATETIME     NOT NULL                 COMMENT 'VIP到期时间',
    `update_by`   VARCHAR(64)  DEFAULT ''               COMMENT '更新者',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='VIP会员身份表';

-- ---------------------------------------------
-- 充值申请订单表（R7：0待审核/1已开通/2已驳回）
-- pending 唯一待审标记（KTD11）：待审核=1，已审=NULL
-- MySQL 唯一索引不去重 NULL，实现"同一用户仅一条待审核"
-- ---------------------------------------------
CREATE TABLE `vip_recharge_order` (
    `id`           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '订单ID',
    `user_id`      BIGINT        NOT NULL                COMMENT '申请用户ID',
    `tier_id`      BIGINT        NOT NULL                COMMENT '档位ID（vip_tier.id）',
    `amount`       DECIMAL(10,2) NOT NULL                COMMENT '支付金额（下单时档位价格快照）',
    `pay_channel`  VARCHAR(20)   DEFAULT NULL            COMMENT '支付渠道（wechat/alipay）',
    `proof_url`    VARCHAR(255)  NOT NULL                COMMENT '支付凭证图片路径（私有目录相对路径）',
    `user_remark`  VARCHAR(500)  DEFAULT NULL            COMMENT '用户备注（付款尾号/时间等）',
    `status`       CHAR(1)       NOT NULL DEFAULT '0'    COMMENT '状态（0待审核 1已开通 2已驳回）',
    `pending`      TINYINT       DEFAULT NULL            COMMENT '唯一待审标记（待审核=1，已审=NULL）',
    `admin_remark` VARCHAR(500)  DEFAULT NULL            COMMENT '管理员留言（驳回原因）',
    `audited_by`   VARCHAR(64)   DEFAULT NULL            COMMENT '审核人',
    `audit_time`   DATETIME      DEFAULT NULL            COMMENT '审核时间',
    `create_time`  DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_pending` (`user_id`, `pending`),
    KEY `idx_user_status` (`user_id`, `status`),
    KEY `idx_status_create` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='VIP充值申请订单表';

-- ---------------------------------------------
-- 时长档位表（R4：单档VIP按时长定价，管理端CRUD）
-- ---------------------------------------------
CREATE TABLE `vip_tier` (
    `id`     BIGINT        NOT NULL AUTO_INCREMENT COMMENT '档位ID',
    `name`   VARCHAR(50)   NOT NULL                COMMENT '档位名称（如月卡/季卡/年卡）',
    `days`   INT           NOT NULL                COMMENT '时长（天）',
    `price`  DECIMAL(10,2) NOT NULL                COMMENT '价格（元）',
    `sort`   INT           DEFAULT 0               COMMENT '显示顺序',
    `status` CHAR(1)       DEFAULT '0'             COMMENT '状态（0启用 1停用）',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='VIP时长档位表';

-- 默认档位（价格上线后管理员可在参数设置调整）
INSERT INTO vip_tier (name, days, price, sort, status) VALUES
('月卡', 30,  19.90, 1, '0'),
('季卡', 90,  49.90, 2, '0'),
('年卡', 365, 168.00, 3, '0');

-- ---------------------------------------------
-- 上传容量记录表（R9/KTD3：容量=SUM(file_size)）
-- 存量已上传文件不回填，容量自上线起算
-- ---------------------------------------------
CREATE TABLE `sys_upload_record` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '记录ID',
    `user_id`     BIGINT       NOT NULL                COMMENT '上传用户ID',
    `file_name`   VARCHAR(255) DEFAULT ''              COMMENT '原始文件名',
    `file_path`   VARCHAR(255) NOT NULL                COMMENT '落盘相对路径',
    `file_size`   BIGINT       NOT NULL                COMMENT '文件大小（字节）',
    `biz_type`    VARCHAR(20)  DEFAULT 'common'        COMMENT '业务类型（common/avatar）',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户上传容量记录表';

-- ---------------------------------------------
-- note_note 加列：回收站保留期（R13/KTD6）
-- ---------------------------------------------
ALTER TABLE `note_note` ADD COLUMN `delete_time` DATETIME DEFAULT NULL COMMENT '移入回收站时间（保留期清理依据）';

-- 存量垃圾笔记回填：保留期从上线起算（对存量用户最宽容）
UPDATE `note_note` SET `delete_time` = NOW() WHERE `delFlag` = 1 AND `delete_time` IS NULL;

-- ---------------------------------------------
-- sys_config 限额参数（KTD4：在线可调，即时生效）
-- ---------------------------------------------
INSERT INTO sys_config VALUES(100, 'VIP-普通用户单文件上传上限(MB)',   'vip.upload.file.max.mb.normal',   '50',    'Y', 'admin', sysdate(), '', null, '普通用户单文件上传上限，单位MB');
INSERT INTO sys_config VALUES(101, 'VIP-VIP单文件上传上限(MB)',        'vip.upload.file.max.mb.vip',      '512',   'Y', 'admin', sysdate(), '', null, 'VIP单文件上传上限，单位MB');
INSERT INTO sys_config VALUES(102, 'VIP-普通用户总容量上限(MB)',       'vip.upload.total.max.mb.normal',  '1024',  'Y', 'admin', sysdate(), '', null, '普通用户附件总容量上限，单位MB');
INSERT INTO sys_config VALUES(103, 'VIP-VIP总容量上限(MB)',            'vip.upload.total.max.mb.vip',     '10240', 'Y', 'admin', sysdate(), '', null, 'VIP附件总容量上限，单位MB');
INSERT INTO sys_config VALUES(104, 'VIP-普通用户笔记数上限',           'vip.note.limit.normal',           '20',    'Y', 'admin', sysdate(), '', null, '普通用户可创建笔记数上限（不含文件夹与模板）');
INSERT INTO sys_config VALUES(105, 'VIP-普通用户AI对话每日次数',       'vip.ai.daily.normal',             '10',    'Y', 'admin', sysdate(), '', null, '普通用户AI对话每日次数上限');
INSERT INTO sys_config VALUES(106, 'VIP-VIP AI对话每日次数',           'vip.ai.daily.vip',                '100',   'Y', 'admin', sysdate(), '', null, 'VIP AI对话每日次数上限');
INSERT INTO sys_config VALUES(107, 'VIP-普通用户回收站保留天数',       'vip.recycle.days.normal',         '7',     'Y', 'admin', sysdate(), '', null, '普通用户回收站保留天数');
INSERT INTO sys_config VALUES(108, 'VIP-VIP回收站保留天数',            'vip.recycle.days.vip',            '30',    'Y', 'admin', sysdate(), '', null, 'VIP回收站保留天数');
INSERT INTO sys_config VALUES(109, 'VIP-凭证上传每日次数',             'vip.proof.daily.limit',           '10',    'Y', 'admin', sysdate(), '', null, '每用户每日支付凭证上传次数上限');
INSERT INTO sys_config VALUES(110, 'VIP-微信收款码图片URL',            'vip.pay.wechat.qr',               '',      'Y', 'admin', sysdate(), '', null, '管理员上传的微信收款码图片地址');
INSERT INTO sys_config VALUES(111, 'VIP-支付宝收款码图片URL',          'vip.pay.alipay.qr',               '',      'Y', 'admin', sysdate(), '', null, '管理员上传的支付宝收款码图片地址');

-- ---------------------------------------------
-- sys_job 回收站清理任务（R13：每日凌晨3点）
-- ---------------------------------------------
INSERT INTO sys_job VALUES(100, 'VIP回收站保留期清理', 'DEFAULT', 'vipCleanTask.cleanExpiredGarbage()', '0 0 3 * * ?', '3', '1', '0', 'admin', sysdate(), '', null, '按用户VIP状态对应的保留期物理清理过期回收站笔记');
