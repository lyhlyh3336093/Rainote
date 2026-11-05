-- =============================================
-- 历史功能表/视图结构补齐脚本（全新初始化必执行）
--
-- 背景：这 9 个对象的结构演进长期只落在 dev 库（手工改库），
-- 建表 SQL 从未沉淀进本目录，导致全新部署后登录/主界面连环报错、
-- 整机还原 manifest 指纹校验失败（2026-11-03 实测踩坑，
-- 详见 docs/solutions/database-issues/init-script-schema-drift-restore-fingerprint.md）。
--
-- 执行时机：全新初始化时，在 note_schema.sql 之后执行
-- （末尾 3 个视图依赖 note_friend/note_note/note_role_menu/note_user_role/
--   cloud_app/sys_user 等表）。
-- 数据来源：2026-11-04 从 dev 库导出（结构权威基线）。
-- =============================================

-- ---------------------------------------------
-- 数据库连接管理（历史功能）
-- ---------------------------------------------
DROP TABLE IF EXISTS `sys_database_connection`;
CREATE TABLE `sys_database_connection` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '连接ID',
  `name` varchar(100) NOT NULL COMMENT '连接名称',
  `db_type` varchar(20) NOT NULL COMMENT '数据库类型(mysql, postgresql, oracle, sqlserver)',
  `host` varchar(100) NOT NULL COMMENT '主机地址',
  `port` int NOT NULL COMMENT '端口号',
  `database_name` varchar(100) NOT NULL COMMENT '数据库名',
  `username` varchar(100) NOT NULL COMMENT '用户名',
  `password` varchar(255) NOT NULL COMMENT '密码(加密存储)',
  `connection_string` varchar(500) DEFAULT NULL COMMENT '完整连接字符串',
  `description` varchar(500) DEFAULT NULL COMMENT '连接描述',
  `group_id` bigint DEFAULT NULL COMMENT '分组ID',
  `is_shared` tinyint(1) DEFAULT '0' COMMENT '是否共享(0-私有,1-共享)',
  `shared_token` varchar(100) DEFAULT NULL COMMENT '共享令牌',
  `timeout` int DEFAULT '30' COMMENT '连接超时时间(秒)',
  `max_connections` int DEFAULT '5' COMMENT '最大连接数',
  `is_active` tinyint(1) DEFAULT '1' COMMENT '是否激活(0-禁用,1-启用)',
  `last_connection_time` datetime DEFAULT NULL COMMENT '最后连接时间',
  `last_connection_status` tinyint(1) DEFAULT NULL COMMENT '最后连接状态(0-失败,1-成功)',
  `last_error_message` text COMMENT '最后错误信息',
  `connection_count` int DEFAULT '0' COMMENT '连接次数统计',
  `user_id` bigint NOT NULL COMMENT '创建用户ID',
  `dept_id` bigint DEFAULT NULL COMMENT '部门ID',
  `create_by` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `del_flag` tinyint(1) DEFAULT '0' COMMENT '删除标志(0-存在,1-删除)',
  `version` int DEFAULT '0' COMMENT '版本号(用于乐观锁)',
  `extended_properties` json DEFAULT NULL COMMENT '扩展属性(JSON格式)',
  `tags` varchar(255) DEFAULT NULL COMMENT '标签(逗号分隔)',
  `encryption_type` varchar(20) DEFAULT 'AES' COMMENT '加密类型',
  `ssl_enabled` tinyint(1) DEFAULT '0' COMMENT '是否启用SSL',
  `ssl_certificate` text COMMENT 'SSL证书',
  `ssh_tunnel_enabled` tinyint(1) DEFAULT '0' COMMENT '是否启用SSH隧道',
  `ssh_host` varchar(100) DEFAULT NULL COMMENT 'SSH主机',
  `ssh_port` int DEFAULT '22' COMMENT 'SSH端口',
  `ssh_username` varchar(100) DEFAULT NULL COMMENT 'SSH用户名',
  `ssh_password` varchar(255) DEFAULT NULL COMMENT 'SSH密码',
  `ssh_private_key` text COMMENT 'SSH私钥',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_group_id` (`group_id`),
  KEY `idx_db_type` (`db_type`),
  KEY `idx_is_shared` (`is_shared`),
  KEY `idx_is_active` (`is_active`),
  KEY `idx_tags` (`tags`(191)),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据库连接表';

-- ---------------------------------------------
-- 视图管理（历史功能）
-- ---------------------------------------------
DROP TABLE IF EXISTS `sys_view_management`;
CREATE TABLE `sys_view_management` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '视图ID',
  `connection_id` bigint NOT NULL COMMENT '连接ID',
  `view_name` varchar(200) NOT NULL COMMENT '视图名称',
  `view_definition` text NOT NULL COMMENT '视图定义SQL',
  `description` varchar(500) DEFAULT NULL COMMENT '视图描述',
  `category_id` bigint DEFAULT NULL COMMENT '分类ID',
  `is_valid` tinyint(1) DEFAULT '1' COMMENT '是否有效(0-无效,1-有效)',
  `last_validated` datetime DEFAULT NULL COMMENT '最后验证时间',
  `validation_error` text COMMENT '验证错误信息',
  `dependencies` json DEFAULT NULL COMMENT '依赖关系',
  `version` int DEFAULT '1' COMMENT '版本号',
  `is_system` tinyint(1) DEFAULT '0' COMMENT '是否系统视图(0-用户,1-系统)',
  `tags` varchar(255) DEFAULT NULL COMMENT '标签(逗号分隔)',
  `access_level` tinyint(1) DEFAULT '0' COMMENT '访问级别(0-私有,1-部门,2-公开)',
  `user_id` bigint NOT NULL COMMENT '创建用户ID',
  `dept_id` bigint DEFAULT NULL COMMENT '部门ID',
  `create_by` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `del_flag` tinyint(1) DEFAULT '0' COMMENT '删除标志(0-存在,1-删除)',
  `extended_properties` json DEFAULT NULL COMMENT '扩展属性(JSON格式)',
  `usage_count` int DEFAULT '0' COMMENT '使用次数统计',
  `last_used` datetime DEFAULT NULL COMMENT '最后使用时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_connection_view` (`connection_id`,`view_name`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_category_id` (`category_id`),
  KEY `idx_access_level` (`access_level`),
  KEY `idx_is_valid` (`is_valid`),
  KEY `idx_tags` (`tags`(191)),
  KEY `idx_view_name` (`view_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='视图管理表';

-- ---------------------------------------------
-- 个人信息（userinfo 菜单功能）
-- ---------------------------------------------
DROP TABLE IF EXISTS `user_info`;
CREATE TABLE `user_info` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '姓名',
  `age` int DEFAULT NULL COMMENT '年龄',
  `threed_data_path` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '3D扫描信息地址',
  `skill` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '人物技能证书',
  `tag` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '标签',
  `knowledge_map` varchar(1023) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '知识图谱',
  `todolist` varchar(1023) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '待办事项',
  `item` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '物品',
  `directory` varchar(1023) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '通讯录',
  `remark` varchar(1023) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------------------------------------------
-- 云应用快捷方式（app 菜单功能）
-- ---------------------------------------------
DROP TABLE IF EXISTS `cloud_app`;
CREATE TABLE `cloud_app` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(255) DEFAULT NULL COMMENT '应用名称',
  `link` varchar(255) DEFAULT NULL COMMENT '域名',
  `description` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci DEFAULT NULL COMMENT '描述',
  `sort` int DEFAULT NULL COMMENT '排序',
  `weight` float DEFAULT NULL COMMENT '权重',
  `createTime` datetime DEFAULT NULL COMMENT '创建时间',
  `creater` varchar(255) DEFAULT NULL COMMENT '创建者',
  `company` varchar(255) DEFAULT NULL COMMENT '应用所属公司',
  `logo` varchar(255) DEFAULT NULL COMMENT 'logo图',
  `delFlag` int DEFAULT '0' COMMENT '删除标识0：正常，1：已删除',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

-- ---------------------------------------------
-- 版本更新记录
-- ---------------------------------------------
DROP TABLE IF EXISTS `version_update`;
CREATE TABLE `version_update` (
  `id` bigint NOT NULL,
  `appName` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `versionCode` int DEFAULT NULL COMMENT '版本号（纯数字，用于比较新旧，如：1001）',
  `versionName` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '版本名称（显示给用户，如："v1.0.1"）',
  `versionNumber` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '版本号',
  `updateContent` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '更新内容（支持多行，如："1. 新增 XX 功能；2. 修复 XX 问题"）',
  `isForceUpdate` tinyint(1) DEFAULT '0' COMMENT '是否强制更新（0：否，1：是）',
  `status` tinyint(1) NOT NULL DEFAULT '1' COMMENT '版本状态（0：无效，1：有效）',
  `createTime` datetime NOT NULL COMMENT '创建时间',
  `updateTime` datetime NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------------------------------------------
-- 笔记视图快捷入口（历史功能）
-- ---------------------------------------------
DROP TABLE IF EXISTS `sys_view`;
CREATE TABLE `sys_view` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '视图主键',
  `name` varchar(255) DEFAULT NULL COMMENT '视图名',
  `type` varchar(255) DEFAULT NULL COMMENT '视图类型',
  `linkTable` varchar(255) DEFAULT NULL COMMENT '关联表ID',
  `createUser` varchar(255) DEFAULT NULL COMMENT '创建人',
  `createTime` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

-- =============================================
-- 视图（依赖上方表与 note_schema.sql 的表，置于最后）
-- 注意：不带 DEFINER 子句，避免跨环境因 definer 账户不存在而创建失败
-- =============================================

DROP VIEW IF EXISTS `app-user`;
CREATE VIEW `app-user` AS
select `cloud_app`.`description` AS `description`,
       `cloud_app`.`creater` AS `creater`,
       `cloud_app`.`id` AS `id`,
       `cloud_app`.`name` AS `name`,
       `sys_user`.`user_id` AS `user_id`,
       `sys_user`.`user_name` AS `user_name`
from (`cloud_app` join `sys_user` on((`cloud_app`.`creater` = `sys_user`.`user_id`)));

DROP VIEW IF EXISTS `friends`;
CREATE VIEW `friends` AS
select `nf`.`user_id` AS `user_id`,
       `u`.`user_name` AS `user_name`
from (`note_friend` `nf` join `sys_user` `u`)
where (`nf`.`user_id` = `u`.`user_id`);

DROP VIEW IF EXISTS `user_view`;
CREATE VIEW `user_view` AS
select `n`.`id` AS `id`,
       `n`.`auth` AS `auth`,
       `n`.`perms` AS `perms`,
       `u`.`user_name` AS `user_name`
from ((`note_note` `n` join `sys_user` `u`) join `note_user_role` `ur`) join `note_role_menu` `rm`
where ((`n`.`auth` = `u`.`user_id`)
   and (`n`.`auth` = `ur`.`user_id`)
   and (`ur`.`role_id` = `rm`.`role_id`)
   and (`n`.`id` = `rm`.`menu_id`)
   and (`n`.`auth` = 1));
