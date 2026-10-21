---
title: "整机备份还原机制：SQL 词法白名单 + 快照回放替代事务回滚 + staging 两段式替换"
date: 2026-10-21
category: docs/solutions/architecture-patterns
module: ruoyi-system/backup-restore
problem_type: architecture_pattern
component: service_object
severity: high
applies_when:
  - "单体应用需应用内整机迁移备份与一键还原（全库 JDBC 逻辑导出 + 文件目录打包 zip）"
  - "上传备份包执行不可信 SQL，需词法级白名单 fail-closed 净化"
  - "MySQL DDL 隐式提交使单事务还原不可行，需受限回放路径与崩溃 marker 自动恢复"
  - "还原期间需触发级写锁阻止并发写入（显式植入而非 AOP，规避自调用绕过代理）"
  - "导出侧需保证自产包必可还原的 round-trip 自洽契约与一致性快照"
related_components:
  - database
  - authentication
  - background_job
tags: [backup-restore, sql-whitelist, fail-closed, snapshot-replay, staging-swap, write-lock, manifest-fingerprint, round-trip]
---

# 整机备份还原机制：SQL 词法白名单 + 快照回放替代事务回滚 + staging 两段式替换

## Context

单机自部署的 RuoYi 应用有两个无法回避的运维场景：**换机迁移**（旧服务器整体搬到新服务器）与**本地数据损坏后的恢复**。既有备份手段全部失效：服务器 cron 只备 MySQL 数据库，不包含 profile 目录树下的用户文件（upload/avatar/import），单备任一都无法完整迁移；界面"数据备份"按钮曾长期是 404 空壳。因此需要应用内整机备份：全库表结构+数据（JDBC 逻辑导出）+ 三目录文件打包为带清单的 zip；上传备份包经校验-预览-确认后**全量替换还原**。

但"全量替换"是全系统破坏性最大的操作：先 DROP 全部表再重建，执行中失败会留下半还原库；备份包经个人设备/网盘流转，是**不可信输入**；MySQL DDL 触发隐式提交，"单事务回滚"机制性不可行。本方案用六个互相咬合的机制把破坏面收拢：词法白名单（挡任意 SQL）、还原前快照回放（替代事务回滚）、staging 两段式+崩溃 marker（文件侧回滚与崩溃自愈）、导出/白名单自洽性契约（防自产包被自家校验拒绝）、触发级写锁（防备份/还原与业务写并发）、指纹+表集核对（防版本错配）。关键文件：`BackupRestoreServiceImpl.java`（编排）、`RestoreSqlSanitizer.java`（词法状态机）、`BackupLockUtil.java`（写锁）、`RestoreSchedulerHookImpl.java`（ruoyi-quartz 侧调度器回调）。

## Guidance

### 机制1：SQL 白名单——词法状态机 fail-closed + 解析产物重渲染

备份包无签名，还原执行前对 schema/data/*.sql 做词法级 token 扫描，任何无法分类的 token 整体拒绝（fail-closed），放行语句以解析产物重新渲染后执行（不透传包内原文）。要点：

- 词法覆盖 MySQL 全部歧义面：单引号串（`''` 与 `\x` 转义）、反引号标识符（双反引号转义）、双引号按串处理、`#` 行注释、`--` 行注释**要求后跟空白**（否则拒绝）、普通块注释丢弃、**`/*!..*/` 可执行注释视为内容非注释**——MySQL 会执行其内内容，朴素解析器当注释跳过即为逃逸面。
- 语句分割在 token 级按 `;` 切（串内分号不切），禁止字符级分号切分。
- 放行形态收窄到三种：`DROP TABLE [IF EXISTS] \`表\``、`CREATE TABLE [IF NOT EXISTS] \`表\`(...)`（括号体禁危险词 REFERENCES/DATA DIRECTORY 等、拒 CREATE...SELECT）、`INSERT INTO \`表\`(\`列\`...) VALUES(纯字面量/NULL/数值)`（拒 INSERT...SELECT/函数值/子查询/表达式）；表名必须 ∈ manifest 表清单。

```java
// Tokenizer：-- 混淆 fail-closed（RestoreSqlSanitizer）
if (c == '-' && source.peek(0) == '-') {
    int after = source.peek(1);
    if (after < 0 || isWhitespace((char) after)) { skipToLineEnd(); continue; }
    throw new RejectException("-- 后未跟空白，无法归类（疑似注释混淆）");
}
// /*!..*/ 可执行注释：内容收集后递归 tokenize 展开参与白名单校验，非跳过
// INSERT 值校验：WORD 仅放行 NULL/TRUE/FALSE/NUMERIC_WORD 正则，其余拒（拒函数调用值）
```

### 机制2：快照回放——受限内部路径 + 豁免清单 + 失败不递归

还原一致性不依赖 DB 事务，由"还原前快照失败后回放"兜底。回放是**受限内部路径**，不是"再执行一次 restore"，豁免与保留清单一刀切：

- 豁免：指纹校验（半还原库指纹必不匹配）、快照生成（不得用半损坏库顶替唯一恢复点）、confirmed 二次确认；现库 sys_user 差异收集一并跳过。
- 保留：解压防护、SQL 白名单（快照自产可信，重过零成本）、临时目录清理。
- 终态：回放成功→清 `RESTORE_INCONSISTENT` 标志并删崩溃 marker；**回放路径任何失败→置位 `RESTORE_INCONSISTENT` 后原样重抛，不递归**——置位后 `backup()` 拒绝、`restore()` 走免指纹豁免通道，用还原前快照执行还原完成自愈，不承诺"重启自愈"。

```java
void replaySnapshot() {
    try { replaySnapshotInternal(); }
    catch (Throwable t) {
        RESTORE_INCONSISTENT.set(true);   // backup 拒绝、快照包免指纹自愈
        throw t;                           // 由调用方终止，绝不递归回放
    }
}
```

### 机制3：文件两段式替换 + staging 回移 + 崩溃 marker

- swap 拆两段：旧三目录**移**至 `staging/swap-<nano>`（同盘原子 rename，跨盘降级复制+删源）→ 从解压目录拷新入位。回滚依据=staging 内目录存在性，`rollbackStaging()` 按目录独立回移。
- 回移是**可即时调用的内部方法**而非仅启动钩子：执行期失败立即回移；失败重试 3 次/100ms，耗尽→告警+置 `FILE_DIRS_UNAVAILABLE`（拒绝后续还原文件替换，直至人工处理）；启动 `@PostConstruct` 检测残留兜底回移。staging 排除于 >24h 临时目录清理（防清掉回滚依据）。
- 崩溃自愈 marker：`restore-in-progress.marker` 在快照生成成功后、SQL 执行前写入；还原成功或回放成功后删除。启动检测：marker 在且快照在位→自动回放（免指纹）；快照缺失→仅 log.error 请人工处理。

```java
// restore 执行段
exportInternal(SNAPSHOT_SLOT);   // 快照（不撞自身锁）
writeRestoreMarker();            // 崩溃自愈锚点
try { executePackageSqls(pkg); swapFileDirs(pkg); }
catch (Throwable t) { recoverFromFailure(t); throw ...("已回滚至还原前状态"); }
```

### 机制4：导出侧与白名单的自洽性契约

导出器产出的每个字面量必须落在白名单放行域内，且转义与重渲染**同源**——否则自产包会被自家校验拒绝（回放时是致命的）：

- 浮点走 `BigDecimal.toPlainString()`（`Double.toString` 会产出 `5.0E-4`，不匹配 `NUMERIC_WORD` 且被拒）；二进制列 hex `0x...`（空字节数组返回 `''`，裸 `0x` 非法）；`LocalDateTime` 等显式 `DateTimeFormatter` 格式化（消除 toString 的 `'T'` 依赖）。
- 字符串转义与重渲染共用同一实现 `RestoreSqlSanitizer.renderMySqlString`——导出物与执行物保证同一形态。
- 数据导出为**逐行单条 INSERT**（禁多 VALUES），为解析器提供确定可枚举形态；SELECT 走 LIMIT/OFFSET 5000 行分页，导出用 `REPEATABLE READ + START TRANSACTION WITH CONSISTENT SNAPSHOT`（mysqldump --single-transaction 同款）保证导出期间单一读视图。

```java
// formatValue 浮点分支（自洽性关键点）
return new BigDecimal(((Number) value).doubleValue()).toPlainString();
// 字符串分支：与重渲染共用同一转义
return RestoreSqlSanitizer.renderMySqlString(value.toString());
```

### 机制5：触发级写锁——显式植入消费者检查 vs AOP

- `BackupLockUtil` 静态 `AtomicBoolean` 承载锁（`tryAcquireBackupLock`/`releaseLock`/`checkNotLocked`），备份/还原共用、触发级互斥。
- 消费者侧**显式**在写方法开头调 `checkNotLocked()`（导入服务、agent 写操作、`removeAllData`、笔记恢复/排序/模板、上传/头像等 37 处），而非 AOP 切面：显式调用对入口可见、逐入口可审查，避免切面顺序（事务代理、agent 反射调用）导致的漏拦截；拒绝文案统一"系统备份/还原进行中，请稍后"。
- `backup()` 拆为**入口检查薄壳 + `exportInternal()`（不检锁）**：restore 已持锁，内部生成快照复用 exportInternal，避免撞自身置位的锁（重入拆分）。

### 机制6：指纹 + 表集双向核对 + 豁免通道

校验阶段（不写库）按序执行，全部通过才放行：

- manifest 表清单与现库非 QRTZ 表**双向集合比对**（大小写归一）——防包清单与现库结构漂移（恶意包声明现库没有的表做选择性还原、或幽灵表）；再比对 schema 指纹（表名+列名+类型+精度素材行排序 SHA-256，两侧共用同一 `fingerprintLine` 格式）。`QRTZ_` 前缀表在清单内直接 fail-closed 拒绝。
- 豁免通道：现库为空（无数据可丢，全新环境旗舰场景）跳过指纹；`RESTORE_INCONSISTENT` 置位跳过指纹+表集核对+差异收集（放行还原前快照包自愈）。
- `confirmed=true` 对本次上传文件**重跑全部校验**，不跨请求信任预览结果（预览包 A、确认包 B 的偷换攻击）；sys_user 差异只输出账号数与"凭据已变更"布尔（user_id→password 哈希 map 比对，密码值与哈希不进任何输出与日志）。

```java
if (!fingerprintExempt) {
    if (!manifestTables.equals(currentTableSet)) throw ...("表清单与现库不一致");
    if (!computeCurrentFingerprint(conn).equals(pkgFingerprint)) throw ...("指纹校验失败");
    collectUserDifference(conn, pkg);
}
```

## Why This Matters

- **机制1 若无**：恶意/损坏包=任意 SQL 执行面——`CREATE USER`/`GRANT` 提权、`INTO OUTFILE` 写文件、`INSERT...SELECT` 从 mysql.user 外带数据、`LOAD_FILE/SLEEP` 函数值；`/*!..*/` 被朴素解析器当注释跳过即是逃逸面（MySQL 实际执行其内容）；字符级分号切分会把字符串内的分号切成两条语句。
- **机制2 若无**：回放走完整 restore 路径会被自家指纹校验拒绝（半还原库指纹必不匹配）→ 回滚机制性死锁；回放时重新生成快照会用半损坏库顶替唯一恢复点；回放失败若递归，系统进入不可终止的循环。
- **机制3 若无**：文件 swap 中途失败留下半新半旧目录且无回滚依据；进程在还原中崩溃后重启，库与文件状态不可判定（marker 是唯一判据）；staging 若被 24h 清理误删，回滚依据永久消失。
- **机制4 若无**：`Double.toString` 的科学计数法（5.0E-4）让自产包被自家白名单拒绝——备份能用、还原/回放必炸（本项目真实踩坑：Tier 2 审查四人格交叉命中，H2 翻译层测试全绿掩盖了该断裂，真 MySQL 首次还原即炸）；导出与重渲染转义不同源，字符串经一次回放后漂移。
- **机制5 若无**：备份进行中导入/删除并发写入，文件侧无事务保护，包内文件与库内数据对不上（库侧有一致性快照缓解，文件侧写锁是唯一防线）；AOP 方式在 agent 反射入口、切面顺序上易漏。
- **机制6 若无**：版本错配的包还原进不同 schema，INSERT 列错位/类型截断静默损坏数据；包清单缺表时 DROP 重建直接丢表；空库不豁免则全新环境首次还原被拒（核心迁移场景失效）；confirmed 不重跑校验则两步协议可被包偷换绕过。

## When to Apply

- **单实例 JVM 部署**：`AtomicBoolean` 锁不跨进程，多实例需换分布式锁（既定边界，部署形态变化时重估）。
- **MySQL**：词法状态机按 MySQL 语义写死（反斜杠转义、`/*!..*/` 执行、双引号串、hex 字面量、`FOREIGN_KEY_CHECKS`）；其他数据库需重写词法层。
- **无签名包供应链残余的接受边界**：白名单挡语句级攻击，不防合法语句的值级篡改（覆写行数据/插入恶意管理员行）——sys_user 变更告警提供事后线索，显式接受；根治需包签名（HMAC）。
- **destructive 全量替换类还原**：DDL 隐式提交使事务回滚不可行，"快照回放"模式才成立；若操作仅含 DML，优先普通事务回滚。
- 包经个人设备/网盘流转（不可信输入面）且体量 ≤500MB（同步上传边界）。

## Examples

端到端时序：管理员点"数据备份"→ `backup()` 检查不一致标志与写锁 → `exportInternal` 以一致性快照导出全库（逐表 SHOW CREATE TABLE + 分页 SELECT 逐行 INSERT）+ 三目录文件 + manifest（指纹/表清单/统计）流式写 zip 落 `backupPath/backup.zip`。迁移到新机：上传包 → `previewRestore`（不持锁）解压防护→manifest→指纹+表集核对→白名单净化全 SQL→返回预览（表数/记录数/文件数/账号数/凭据变更布尔）→ 管理员二次确认，`restore(file, confirmed=true)` 重跑全部校验 → 加锁+`schedulerHook.pause()` → `exportInternal(SNAPSHOT_SLOT)` 生成还原前快照（不撞自身锁）→ 写崩溃 marker → 单连接顺序执行净化后 SQL（FOREIGN_KEY_CHECKS=0，逐表 DROP→CREATE→INSERT，每表一 commit）→ staging 两段式文件替换 → 重建字典/参数缓存 + `scheduler.clear()` 从新 sys_job 重载 → 删 marker/staging、清标志、恢复调度器、解锁。若执行到第 N 张表失败：`recoverFromFailure` 回放快照（免指纹受限路径）+ staging 回移，库与文件回到还原前状态，报"还原失败：已回滚"；若回放本身再失败：置 `RESTORE_INCONSISTENT`，backup/restore 均被引导至"用还原前快照执行还原"自愈，绝不递归。若进程在执行段崩溃：重启后 `@PostConstruct` 检测 marker→自动回放快照拉回还原前状态并告警"已自动回退"。

实测规模参考：60 表（49 入包、QRTZ_ 11 张排除）+ 149 文件 ≈ 197MB 包，备份 15s / 还原 28s（本地 NVMe+MySQL8）。

## Related

- [multitable-sql-import-pipeline.md](../architecture-patterns/multitable-sql-import-pipeline.md) — 单表 SQL 导入管线（同为手写 SQL 解析+净化域；该篇的 SqlValueEscaper 反斜杠转义语义与本篇机制4 的 H2 测试盲区同源）
- [sql-identifier-unicode-sanitization.md](../design-patterns/sql-identifier-unicode-sanitization.md) — 白名单哲学同域（Unicode 字符类白名单 vs SQL 词法语句白名单）
- [excel-link-column-round-trip-disciplines.md](../design-patterns/excel-link-column-round-trip-disciplines.md) — 数据完整性纪律同族（预检-执行两段式、位置配对、零写入门禁）
- [agent-result-message-channel.md](../design-patterns/agent-result-message-channel.md) — agent 备份命令的结果透出通道（本特性伴生沉淀）
