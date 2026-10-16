-- MySQL dump 10.13  Distrib 8.0.31, for Win64 (x86_64)
--
-- Host: localhost    Database: ry-vue
-- ------------------------------------------------------
-- Server version	8.0.31

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `note_block`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `note_block` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '块主键',
  `parentId` int DEFAULT NULL COMMENT '父id',
  `childId` varchar(255) DEFAULT NULL COMMENT '子模块id集合',
  `blockType` int DEFAULT NULL COMMENT '块类型',
  `property` varchar(2000) DEFAULT NULL COMMENT '配置信息',
  `filePath` varchar(255) DEFAULT NULL COMMENT '文件上传路径',
  `sort` int DEFAULT NULL COMMENT '排序',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2134 DEFAULT CHARSET=utf8mb3;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `note_column`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `note_column` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '列id',
  `name` varchar(255) DEFAULT '' COMMENT '列名称',
  `type` int DEFAULT NULL COMMENT '列类型\r\n字段类型：\r\n1：多行文本\r\n2：数字\r\n3：单选\r\n4：多选\r\n5：日期\r\n7：复选框\r\n11：人员\r\n13：电话号码\r\n15：超链接\r\n17：附件\r\n18：单向关联\r\n19：查找引用\r\n20：公式\r\n21：双向关联\r\n22：地理位置\r\n1001：创建时间\r\n1002：最后更新时间\r\n1003：创建人\r\n1004：修改人\r\n1005：自动编号',
  `dwtableId` int DEFAULT NULL COMMENT '多维表格ID',
  `property` varchar(1000) DEFAULT NULL COMMENT '配置信息',
  `isShow` int DEFAULT NULL COMMENT '是否显示',
  `sort` int DEFAULT NULL COMMENT '排序',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=3775 DEFAULT CHARSET=utf8mb3;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `note_dept`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `note_dept` (
  `dept_id` bigint NOT NULL AUTO_INCREMENT COMMENT '部门id',
  `parent_id` bigint DEFAULT '0' COMMENT '父部门id',
  `ancestors` varchar(50) DEFAULT '' COMMENT '祖级列表',
  `dept_name` varchar(30) DEFAULT '' COMMENT '部门名称',
  `order_num` int DEFAULT '0' COMMENT '显示顺序',
  `leader` varchar(20) DEFAULT NULL COMMENT '负责人',
  `phone` varchar(11) DEFAULT NULL COMMENT '联系电话',
  `email` varchar(50) DEFAULT NULL COMMENT '邮箱',
  `status` char(1) DEFAULT '0' COMMENT '部门状态（0正常 1停用）',
  `del_flag` char(1) DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）',
  `create_by` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`dept_id`)
) ENGINE=InnoDB AUTO_INCREMENT=200 DEFAULT CHARSET=utf8mb3 COMMENT='笔记部门表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `note_dwtable`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `note_dwtable` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '多维表格ID',
  `name` varchar(255) DEFAULT NULL COMMENT '名称',
  `url` varchar(255) DEFAULT NULL COMMENT '链接地址',
  `noteId` int DEFAULT NULL COMMENT '所属多维表格id',
  `delFlag` int DEFAULT NULL COMMENT '删除标识',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1680 DEFAULT CHARSET=utf8mb3;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `note_dwtable_item`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `note_dwtable_item` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '多维表格详情id',
  `dwtId` int DEFAULT NULL COMMENT '多维数据表id',
  `columnId` int DEFAULT NULL COMMENT '列id',
  `value` varchar(255) DEFAULT NULL COMMENT '值',
  `recordId` int DEFAULT NULL COMMENT '记录id',
  `linkRecordId` varchar(255) DEFAULT NULL COMMENT '关联记录id集合',
  `linkItemId` varchar(255) DEFAULT NULL COMMENT '关联的数据主键',
  `linkColumnId` int DEFAULT NULL COMMENT '关联的列id',
  `linkBlockId` int DEFAULT NULL COMMENT '关联笔记的块id',
  `linkNoteId` int DEFAULT NULL COMMENT '关联笔记id',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=6232 DEFAULT CHARSET=utf8mb3;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `note_dwtable_new_item`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `note_dwtable_new_item` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '多维表格详情id',
  `dwtId` int DEFAULT NULL COMMENT '多维数据表id',
  `columnId` int DEFAULT NULL COMMENT '列id',
  `recordId` int DEFAULT NULL COMMENT '记录id',
  `value` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci DEFAULT NULL COMMENT '显示信息',
  `linkItemId` varchar(255) DEFAULT NULL COMMENT '关联的数据主键',
  `linkNoteId` int DEFAULT NULL COMMENT '关联笔记id',
  `content` varchar(2000) DEFAULT NULL COMMENT '完整内容',
  `showValue` varchar(255) DEFAULT NULL COMMENT '被关联后显示信息',
  `type` varchar(255) DEFAULT NULL COMMENT '单元格类型(1.经典单元格,2.双向链接单元格.3.lookup单元格)',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=4100 DEFAULT CHARSET=utf8mb3;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `note_friend`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `note_friend` (
  `id` int unsigned NOT NULL AUTO_INCREMENT COMMENT '好友主键',
  `user_id` int DEFAULT NULL COMMENT '用户id',
  `friend_id` int DEFAULT NULL COMMENT '好友id',
  `status` varchar(255) DEFAULT NULL COMMENT '状态（0：好友，1：拉黑）',
  `creat_time` datetime DEFAULT NULL COMMENT '添加好友时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=40 DEFAULT CHARSET=utf8mb3;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `note_friend_request`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `note_friend_request` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '好友请求主键',
  `user_id` int DEFAULT NULL COMMENT '用户id',
  `request_id` int DEFAULT NULL COMMENT '请求id',
  `request_name` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci DEFAULT NULL COMMENT '请求人名称',
  `request_time` datetime DEFAULT NULL COMMENT '请求时间',
  `description` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci DEFAULT NULL COMMENT '请求附加信息',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=48 DEFAULT CHARSET=utf8mb3;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `note_link_relation`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `note_link_relation` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '关系主键',
  `forwardItemId` int DEFAULT NULL COMMENT '发起关联数据id',
  `forwardRecordId` int DEFAULT NULL COMMENT '发起关联记录id',
  `backItemId` int DEFAULT NULL COMMENT '被关联数据id',
  `backRecordId` int DEFAULT NULL COMMENT '被关联记录id',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='关联关系表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `note_meta`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `note_meta` (
  `id` int NOT NULL AUTO_INCREMENT,
  `noteId` int DEFAULT NULL COMMENT '笔记id',
  `createTime` int DEFAULT NULL COMMENT '创建时间戳',
  `createDate` varchar(255) DEFAULT NULL COMMENT '创建日期',
  `creator` varchar(255) DEFAULT NULL COMMENT '创建者open_id',
  `createName` varchar(255) DEFAULT NULL COMMENT '创建者用户名',
  `deleteFlag` int DEFAULT NULL COMMENT '删除标志，0表示正常访问未删除，1表示在回收站，2表示已经彻底删除',
  `editTime` int DEFAULT NULL COMMENT '最后编辑时间戳',
  `editDate` varchar(255) DEFAULT NULL COMMENT '修改日期',
  `editName` varchar(255) DEFAULT NULL COMMENT '最后编辑者用户名',
  `isExternal` int DEFAULT NULL COMMENT '是否外部文档',
  `isPined` int DEFAULT NULL COMMENT '是否在接口调用者目录里快速访问',
  `isStared` int DEFAULT NULL COMMENT '是否在接口调用者目录里收藏',
  `objType` varchar(255) DEFAULT NULL COMMENT '文档类型，固定是doc',
  `owner` varchar(255) DEFAULT '' COMMENT '当前所有者open_id',
  `ownerName` varchar(255) DEFAULT NULL COMMENT '当前所有者用户名',
  `serverTime` int DEFAULT NULL COMMENT '处理请求时的服务器时间戳',
  `title` varchar(255) DEFAULT NULL COMMENT '文档名称',
  `type` int DEFAULT NULL COMMENT '文档类型，固定是2',
  `url` varchar(255) DEFAULT NULL COMMENT '文档url',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=143 DEFAULT CHARSET=utf8mb3;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `note_note`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `note_note` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '笔记id',
  `title` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NOT NULL COMMENT '标题',
  `remark` varchar(2000) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci DEFAULT NULL COMMENT '描述',
  `revisionId` int DEFAULT NULL COMMENT '版本id',
  `parentId` int DEFAULT NULL COMMENT '上级id',
  `noteType` int DEFAULT NULL COMMENT '类型',
  `delFlag` int DEFAULT NULL COMMENT '删除标志',
  `auth` int DEFAULT NULL COMMENT '所属用户id',
  `collectionFlag` int DEFAULT NULL COMMENT '收藏标识',
  `content` longtext COMMENT '富文本正文内容',
  `perms` varchar(255) DEFAULT NULL COMMENT '权限标识',
  `templateFlag` int DEFAULT '0' COMMENT '模板标识',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=208 DEFAULT CHARSET=utf8mb3;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `note_notelink`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `note_notelink` (
  `id` int NOT NULL AUTO_INCREMENT,
  `contextText` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '笔记内容',
  `noteId` int DEFAULT NULL COMMENT '所属笔记id',
  `blockId` int DEFAULT NULL COMMENT '所属块id',
  `linkNoteId` int DEFAULT NULL COMMENT '关联多维表格id',
  `linkDwTableId` int DEFAULT NULL COMMENT '关联数据表id',
  `linkRecordId` int DEFAULT NULL COMMENT '关联行id',
  `linkColumnId` int DEFAULT NULL COMMENT '关联列id',
  `linkItemId` int DEFAULT NULL COMMENT '关联单元格id',
  `itemValue` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '单元格显示内容',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=32 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `note_record`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `note_record` (
  `id` int NOT NULL AUTO_INCREMENT,
  `viewId` int DEFAULT NULL COMMENT '所属视图id',
  `property` varchar(255) DEFAULT NULL COMMENT '属性设置',
  `linkRecordId` varchar(1000) DEFAULT NULL COMMENT '关联的记录id',
  `name` varchar(255) DEFAULT NULL COMMENT '显示名称',
  `linkName` varchar(255) DEFAULT NULL COMMENT '关联记录名称',
  `dwtableId` int DEFAULT NULL COMMENT '数据表id',
  `sort` int DEFAULT NULL COMMENT '排序',
  `noteId` varchar(255) DEFAULT NULL COMMENT '笔记id',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1029 DEFAULT CHARSET=utf8mb3;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `note_role`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `note_role` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  `role_name` varchar(30) NOT NULL COMMENT '角色名称',
  `role_key` varchar(100) NOT NULL COMMENT '角色权限字符串',
  `role_sort` int NOT NULL COMMENT '显示顺序',
  `data_scope` char(1) DEFAULT '1' COMMENT '数据范围（1：全部数据权限 2：自定数据权限 3：本部门数据权限 4：本部门及以下数据权限）',
  `menu_check_strictly` tinyint(1) DEFAULT '1' COMMENT '菜单树选择项是否关联显示',
  `dept_check_strictly` tinyint(1) DEFAULT '1' COMMENT '部门树选择项是否关联显示',
  `status` char(1) NOT NULL COMMENT '角色状态（0正常 1停用）',
  `del_flag` char(1) DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）',
  `create_by` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=115 DEFAULT CHARSET=utf8mb3 COMMENT='笔记角色表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `note_role_dept`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `note_role_dept` (
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `dept_id` bigint NOT NULL COMMENT '部门ID',
  PRIMARY KEY (`role_id`,`dept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='笔记角色和部门关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `note_role_menu`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `note_role_menu` (
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `menu_id` bigint NOT NULL COMMENT '菜单ID',
  PRIMARY KEY (`role_id`,`menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='笔记系统角色和菜单关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `note_task`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `note_task` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '任务id',
  `name` varchar(255) DEFAULT NULL COMMENT '任务名称',
  `description` varchar(255) DEFAULT NULL COMMENT '任务详情',
  `startTime` datetime DEFAULT NULL COMMENT '开始时间',
  `endTime` datetime DEFAULT NULL COMMENT '截止时间',
  `status` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci DEFAULT NULL COMMENT '状态：0，未开始，1，已接收，2.进行中，3，已过期，4.已完成，5，已删除',
  `leader` varchar(255) DEFAULT NULL COMMENT '发起人',
  `teams` varchar(2000) DEFAULT NULL COMMENT '成员id',
  `remindType` varchar(255) DEFAULT NULL COMMENT '提醒方式',
  `importentLevel` varchar(255) DEFAULT NULL COMMENT '重要程度',
  `urgentLevel` varchar(255) DEFAULT NULL COMMENT '紧急程度',
  `repeatType` varchar(255) DEFAULT NULL COMMENT '重复方式',
  `rewards` varchar(255) DEFAULT NULL COMMENT '奖励',
  `punishment` varchar(255) DEFAULT NULL COMMENT '惩罚',
  `relevantAttribute` varchar(255) DEFAULT NULL COMMENT '相关属性',
  `childrenTasks` varchar(1000) DEFAULT NULL COMMENT '子任务id集合',
  `parentTask` varchar(255) DEFAULT NULL COMMENT '父任务',
  `groupId` int DEFAULT NULL COMMENT '任务组Id',
  `receiverId` int DEFAULT NULL COMMENT '接收人id',
  `taskType` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci DEFAULT NULL COMMENT '任务类型：0，个人发送个人接收，1，个人发送群组接收，2，任务栏类型，',
  `delFlag` int DEFAULT NULL COMMENT '删除标识',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=63 DEFAULT CHARSET=utf8mb3;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `note_tuple`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `note_tuple` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '元组id',
  `tuple` text COMMENT '双向关联关系元组',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `note_user_role`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `note_user_role` (
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  PRIMARY KEY (`user_id`,`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='用户和笔记系统角色关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `note_view`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `note_view` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '视图id',
  `name` varchar(255) DEFAULT NULL COMMENT '视图名称',
  `type` int DEFAULT NULL COMMENT '视图类型(1.表格视图,2.甘特视图,3.图表视图,4.关联视图)',
  `hiddenFields` varchar(2000) DEFAULT NULL COMMENT '隐藏字段id集合',
  `property` varchar(3000) DEFAULT NULL COMMENT '视图属性',
  `dwtableId` int DEFAULT NULL COMMENT '所属数据表id',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=713 DEFAULT CHARSET=utf8mb3;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-10-16 14:47:03
