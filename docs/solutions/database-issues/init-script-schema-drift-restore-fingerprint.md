---
title: "初始化脚本与真实库结构漂移三连环：登录列、历史表、升级脚本误用与包内 schema 权威 diff 诊断"
date: 2026-11-04
category: database-issues
module: sql-init/backup-restore
problem_type: database_issue
component: database
symptoms:
  - "登录报 Unknown column 'u.first_login' in 'field list'（SysUserMapper.selectUserByUserName）"
  - "补列后主界面报 Table 'ry-vue.cloud_app' doesn't exist 等多表缺失"
  - "补表后整机还原报「备份包与当前库结构不一致（schema 指纹校验失败）」被 fail-closed 拒绝"
root_cause: incomplete_setup
resolution_type: migration
severity: critical
tags: [schema-drift, init-script, first-login, agent-audit, manifest-fingerprint, restore, information-schema, column-diff, deployment]
---

# 初始化脚本与真实库结构漂移三连环：登录列、历史表、升级脚本误用与包内 schema 权威 diff 诊断

## Problem

全新环境用仓库 `sql/` 目录脚本初始化数据库后，应用连环报错：登录炸（缺列）、主界面炸（缺表）、整机还原被 manifest 指纹校验拒绝（多列）。根因是 dev 库结构演进长期"手工改库不改脚本"，初始化脚本与真实库之间存在**三层结构漂移**；而逐报错逐补的修复方式陷入"补一列炸下一列"的猜谜循环，最终用备份包内 schema 建临时库做列级权威 diff 一次定位所有差异。

## Symptoms

1. `Unknown column 'u.first_login' in 'field list'`——Mapper 引用的列建表脚本里没有
2. `Table 'ry-vue.cloud_app' doesn't exist`——9 张历史功能表/视图的建表 SQL 从未沉淀
3. `备份包与当前库结构不一致（schema 指纹校验失败），已拒绝还原`——现库比备份包多一个 `result` 列

## What Didn't Work

- **逐报错逐补**：修完 first_login 登录通，立刻在主界面撞上 cloud_app 缺表；补完 9 张表，还原又被指纹校验拒绝——每个修复都只消除当层症状，盲修不可终止
- **凭印象猜漂移范围**：以为漂移就是"缺表"，没料到还有"多列"方向的漂移（升级脚本误用于初始化），猜测无法覆盖双向差异
- **mysqldump 补表时把视图当表**：`app-user`/`friends`/`user_view` 实为 VIEW（备份导出侧按 TABLE 类型过滤天然排除），幸运的是还原侧 `listTables` 同样按类型过滤，未影响指纹核对——但这依赖巧合而非设计

## Solution

### 第一步：三层修复（逐层核验）

```sql
-- 漂移1：dev 库手工加过的列，从未进 ry_20220822.sql
ALTER TABLE sys_user ADD COLUMN first_login varchar(255) DEFAULT NULL COMMENT '首次登录标记';
```

```powershell
# 漂移2：9 张历史表/视图从未沉淀进 sql/，从 dev 库 dump 补齐
mysqldump -uroot -proot --single-transaction --default-character-set=utf8mb4 ry-vue `
  sys_database_connection sys_view_management user_info cloud_app version_update `
  sys_view user_view friends --tables app-user -r missing-tables.sql
```

```sql
-- 漂移3（反向）：agentAudit.sql 是"已有环境升级脚本"（含 result 列，
-- 对应部署手册 8.1 节 ALTER），全新初始化误用它导致多列——
-- dev 真实结构与备份包都没有这列
ALTER TABLE `ry-vue`.`agent_audit_step_log` DROP COLUMN `result`;
```

### 第二步：包内 schema 权威 diff（定位漂移 3 的方法论）

备份包（zip）内 `schema/` 目录就是 49 张表的**权威 DDL**。将其导入临时库，与现库做 `information_schema.columns` 三元组列级 diff：

```sql
-- 临时库 tmp_check 导入包内 DDL 后：
SELECT 'MISSING_COL' AS kind, t.table_name, t.column_name, t.column_type AS auth_type, NULL AS cur_type
FROM information_schema.columns t
LEFT JOIN information_schema.columns r
  ON r.table_schema='ry-vue' AND r.table_name=t.table_name AND r.column_name=t.column_name
WHERE t.table_schema='tmp_check' AND r.column_name IS NULL
UNION ALL
SELECT 'TYPE_DIFF', t.table_name, t.column_name, t.column_type, r.column_type
FROM information_schema.columns t
JOIN information_schema.columns r
  ON r.table_schema='ry-vue' AND r.table_name=t.table_name AND r.column_name=t.column_name
WHERE t.table_schema='tmp_check' AND t.column_type <> r.column_type
UNION ALL
SELECT 'EXTRA_COL', r.table_name, r.column_name, NULL, r.column_type
FROM information_schema.columns r
LEFT JOIN information_schema.columns t
  ON t.table_schema='tmp_check' AND t.table_name=r.table_name AND t.column_name=r.column_name
WHERE r.table_schema='ry-vue'
  AND r.table_name IN (SELECT DISTINCT table_name FROM information_schema.columns WHERE table_schema='tmp_check')
  AND t.column_name IS NULL
ORDER BY 2, 3;
-- 一次返回全部差异：本例仅 1 行 EXTRA_COL agent_audit_step_log.result
-- 修齐后 DROP DATABASE tmp_check（勿残留）
```

三层修齐后指纹校验通过，正规还原成功。E2E 核验：笔记 56 / 记录 393 / 多维表 127 / 列 678 / 附件 149 个 194MB，与备份包 manifest 全部一致。

## Why This Works

- **manifest 指纹双向核对是 fail-closed 的**（[full-backup-restore-mechanism.md](../architecture-patterns/full-backup-restore-mechanism.md) 机制 5）：备份包表集 vs 现库表集双向比对 + 列级指纹，任何方向的结构差异都会拒绝——这是设计行为而非故障，修复方向必须是"让现库对齐包内权威结构"，而不是绕过校验
- **备份包本身就是权威结构基线**：导出侧从真实 dev 库生成 schema/，比人工记忆或散落脚本更可信；用它做裁判比逐报错猜谜有确定性保证
- 漂移必然**双向存在**："缺"（脚本没跟上手工演进）与"多"（升级脚本误用于初始化）方向相反，单向排查（只找缺表缺列）永远漏掉另一半

## Prevention

- **结构变更双轨同步**：改库必须同步改 `sql/` 初始化脚本（或反之以脚本为准重建 dev 库），杜绝"手工改库"单轨演进
- **升级脚本与初始化脚本严格分离**：文件头注释显式声明用途与适用场景；部署手册明确两类脚本的分流时机
- **全新部署后跑一次包内 diff**：有备份包时用本文临时库三元组 diff 做结构核验，替代逐接口点击发现的"连环炸"
- **根治闭环（2026-11-04 已落地）**：`sql/legacySchema.sql` 新增（9 个历史表/视图权威 DDL，须在 note_schema.sql 之后执行）；`ry_20220822.sql` 补齐 `first_login` 列并把 sys_user 初始 INSERT 改为显式列清单（防未来加列再断）；dev 库与生产库均已应用 8.1 节 ALTER 补 `agent_audit_step_log.result` 列（代码 U4 特性需要该列，此前 dev 缺列导致 agent result 审计静默降级）；部署手册第 3 节初始化序列已同步。**全流程验证**：本地临时库按手册顺序跑全部 24 个脚本 → 与 dev 真实库列级 diff 零差异、49 张非 QRTZ 表与备份包 manifest 一致
- 还原被指纹拒绝时，先解开备份包对比 `manifest.json` 的 `tables` 清单与 `schema/` DDL，再决定修复方向

## Related Issues

- [full-backup-restore-mechanism.md](../architecture-patterns/full-backup-restore-mechanism.md)——manifest 指纹双向核对机制的权威描述（机制 5）；本坑是该机制 fail-closed 行为的实战验证
- [nginx-proxy-pass-trailing-slash-silent-rewrite.md](../integration-issues/nginx-proxy-pass-trailing-slash-silent-rewrite.md)——同一部署窗口还原功能被卡的另一层面（路由层），两坑叠加使"还原失败"一度有三个互斥假说
- [multitable-sql-import-pipeline.md](../architecture-patterns/multitable-sql-import-pipeline.md)——SQL 导入管线与 round-trip 契约；其"导入端只承诺与导出端 round-trip"的边界思想同样适用于备份还原（还原端只承诺与导出端结构一致）
- `docs/deploy-aliyun-ecs.md` 8.1 节——agentAudit.sql 升级列说明，本坑中该节脚本被误用于全新初始化的源头
