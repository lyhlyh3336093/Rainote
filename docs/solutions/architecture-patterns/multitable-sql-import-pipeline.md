---
title: "多维表格 SQL 导入管线：与导出对称的手写解析 + 单事务批量写入"
date: 2026-08-28
category: docs/solutions/architecture-patterns
module: ruoyi-system/dwtable-import
problem_type: architecture_pattern
component: service_object
severity: medium
applies_when:
  - "为既有导出能力补对称导入管线（round-trip 契约）"
  - "需在服务内解析自产 SQL 文本，权衡手写状态机 vs JSqlParser"
  - "zip 上传导入需要内存解压与 zip-bomb/zip-slip 防护"
  - "大批量行写入需要单事务原子性与批量 insert 折衷"
  - "导入路径需绕过 UI 写入路径并重实现默认值语义"
related_components:
  - database
  - tooling
tags: [dwtable, sql-import, round-trip, hand-written-parser, zip-bomb, zip-slip, batch-insert, transaction, n-plus-1, mybatis]
---

# 多维表格 SQL 导入管线：与导出对称的手写解析 + 单事务批量写入

## Context

导出侧已有 `NoteDwtableSqlRenderer` + `SqlValueEscaper` + `SqlIdentifierSanitizer` 产出 `.sql`/分片 `.zip`；导入侧此前为空白，用户只能手工重建数据。本学习来自 `feat/multitable-import` 分支完整实施并通过评审（11 位评审员、256 项测试、评审修复 commit `b89bd540`）的对称导入管线：`.sql/.zip` 上传 → 手写 INSERT 解析 → 单事务批量写入。计划文档 `docs/plans/2026-08-25-001-feat-multitable-import-plan.md` 经 6-persona 文档评审确认核心约束：MyBatis 全程 `#{}` 参数化、列名区分大小写 + trim、PII（单元格值）不进日志 (session history)。

核心设计边界：**导入端只承诺与导出端 round-trip，不承诺解析任意 SQL**——这一边界决定了所有技术选型（手写窄域解析器而非 JSqlParser、位置契约而非名字契约、宽松跳过而非严格校验）。

## Guidance

### 1. 手写 INSERT 状态机解析器（SqlInsertParser + ParsedInsert）

状态机扫描只认 `INSERT INTO`，跳过 CREATE TABLE、`--`、`/* */`、字符串字面量、反引号标识符；多行 `VALUES (...),(...)` 展开为多条 ParsedInsert。关键片段（跳过注释 + 关键字边界检查防 `INSERTX` 误配）：

```java
// 跳过单行注释 --
if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
    while (i < len && sql.charAt(i) != '\n') { i++; }
    continue;
}
// 检查 INSERT 关键字（带边界检查）
if (isKeywordAt(sql, i, "INSERT")) { i = parseInsertStatement(sql, i, result); }
```

反转义必须与导出端 `SqlValueEscaper.escape` 逐条对称：`''`→`'`、`\0`→NUL、`\n`/`\r`、`\Z`→char(26)、`\"`、`\'`、`\\`，其他 `\x`→`x`（保守保留）；`NULL` 关键字→Java null。**锚点用位置契约**：`record_id` 仅识别首列（导出端固定首列 record_id，用户又可能自建同名列——非首列同名按普通数据列处理）；数据列重复列名抛 ServiceException，把静默 last-wins 丢列变为可读失败（评审修复）：

```java
if (!seen.add(col)) {
    throw new ServiceException("SQL 解析失败：检测到重复列名 '" + col + "'，无法确定列映射");
}
```

### 2. zip 内存解压防护（ZipImportExtractor）

无落盘（`ZipInputStream`→`ByteArrayOutputStream`，不写临时文件），双 zip-bomb 防护：100MB 累计解压上限（读循环内逐 chunk 累计）+ 100:1 压缩比（每入口完成后对比总解压字节 vs 整个 zip 输入字节）。zip-slip 侧入口名经 `ZipEntryNameSanitizer.sanitize` 净化后才参与排序与日志（内存解压无路径穿越风险，净化保护的是下游排序与日志注入）。`.sql` 后缀判断大小写不敏感（`toLowerCase(ROOT)`，与控制器外层文件分流行为一致）。

排序用**自然序**而非字典序：导出分片名形如 `T_p1.sql`…`T_p10.sql`，字典序会把 `T_p10` 排在 `T_p2` 前，破坏导入顺序契约。数字段按 `Long` 数值比较，等值时继续比较剩余部分。

### 3. 单事务批量写入（NoteDwtableImportServiceImpl）

`@Transactional(rollbackFor = Exception.class)` 全量原子性：任一行解析/写入失败整体回滚（导入非幂等，回滚比半批残留安全）。写入形态按实体职责分层：每行 `NoteRecord` 单条 insert（`useGeneratedKeys` 回填自增 id 供 items 外键引用）；同行 `NoteDwtableItem` 累积进 batch，达 500 单元格上限即 flush + clear（防单条 SQL 过大）：

```java
recordCount += appendRecord(ctx, parsed, batch);
if (batch.size() >= ctx.batchLimit) {
    noteDwtableItemMapper.insertNoteDwtableItems(batch);
    batch.clear();
}
// 循环外 flush 尾批
```

mapper 侧新增 `insertNoteDwtableItems`，MyBatis `foreach` 拼多元组 INSERT，全程 `#{item.xxx}` 参数化禁 `${}`：

```xml
<insert id="insertNoteDwtableItems" parameterType="java.util.List">
    insert into note_dwtable_item (dwtId, columnId, recordId, value) values
    <foreach collection="list" item="item" separator=",">
        (#{item.dwtId}, #{item.columnId}, #{item.recordId}, #{item.value})
    </foreach>
</insert>
```

### 4. 默认值重实现（KTD2）+ N+1 上下文预解析

**不调用** `insertNoteRecord`（其 UI 路径含未显式设默认值的 `property`/`linkRecordId`/`linkName`，直接复用会耦合 UI 行为演进），在导入服务内重实现等价逻辑：dwtableId=目标表、viewId/property/link 四字段=null、name=首个 type=1 列值。

评审 P1 发现：逐行调 `deriveRecordName` 会每行重查列定义（N+1 查询放大事务时长与持锁时间）。修复模式：`buildContext` 一次性查列后**预解析派生源列 id 存入 ImportContext**，`appendRecord` 内联取值——语义等价 `deriveRecordName` 的 existingItems 分支，整个导入仅 1 次列查询，测试 `verify(times(1))` 锁定查询次数防回归：

```java
// buildContext：一次查询同时构建列映射 + 预解析 name 派生列
if (nameColumnId == null && column.getType() != null && column.getType() == 1L) {
    nameColumnId = column.getId();
}
// appendRecord：内联派生，不再查库
record.setName(deriveName(ctx, rowItems));
```

NULL→空串（与 `insertNoteRecord` 的 setValue 契约一致）；link 字段不导入；排除类型 18/20/21/23/24/25/26 不进列映射 → SQL 侧与目标表侧统一跳过。

### 5. sort=max+1 追加语义（有意 diverge）

mapper 新增 `selectMaxSortByDwtableId`（`select max(sort) from note_record where dwtableId=#{dwtableId}`），导入记录 sort 从 max+1 递增；UI 新增路径 sort=null。MySQL ASC nulls-first → UI 新记录聚顶、导入记录沉底。这是**有意的排序分歧**（计划 Deferred 显式决策），测试显式覆盖该语义而非"纠正"它——当行为分歧是产品决策时，测试应锁定决策而非锁定实现巧合。

### 6. 已知残留（显式 Deferred，不阻断）

- **列名净化 round-trip 断裂**：导出端 `SqlIdentifierSanitizer` 净化列名（空格→`_` 等），导入端严格 `String.equals` 匹配——含空格/特殊字符列名 round-trip 静默丢单元格（计划 F3 显式决策，反向归一化留 v2）。
- **行数无上限**：仅 100MB 解压上限间接约束（约百万行级，undo log/持锁风险记入评审 residual）；已加日志警告。
- **导入非幂等**：前端超时文案引导"确认结果"而非盲目重试（防重试双写）；`selectMaxSortByDwtableId` 读取-递增非原子，并发导入 sort 可能交错。
- **双实现漂移**：导入服务重实现默认值后，`insertNoteRecord` 演进不会自动传导——需语义等价测试对冲。

## Why This Matters

- **对称契约是导入正确性的根**：反转义表、首列 record_id 锚点、净化列名三处契约中，前两处已对称（丢单元格→可读异常），第三处已知断裂。新导入能力会**显性化导出端既有缺陷**（如评审发现的 SQL 导出双列错位）——补对称能力前应先审计导出端。
- **失败可读优于失败静默**：重复列名从 last-wins 静默丢列改为抛异常，是本特性的典型修复方向——宽松匹配（错列跳过）与严格失败（结构歧义拒绝）的分界应写在计划里。
- **事务边界即一致性承诺**：非幂等导入 + 单事务，意味着超时后"重试"必然双写；文案与限流（`@RateLimiter` USER 维度）是同一一致性设计的一部分。
- **N+1 修复的价值在事务内被放大**：行级查询不只慢，还拉长持锁时间。

## When to Apply

- **手写解析器 vs JSqlParser**：仅当输入是**自产 SQL**（格式可控、方言已知、只需 INSERT 一种语句）且要规避新依赖时选手写；若需支持任意第三方 dump、多语句类型、方言差异，选成熟解析库。手写时必配：状态机跳过注释/字符串/标识符、关键字边界检查、未闭合字面量报错、错误信息带位置。
- **zip 内存解压**：适用于入口总量可控（解压后 <100MB 量级）的服务端上传场景；落盘方案仅在单文件超内存预算时考虑。必配：累计解压上限、压缩比检查、入口名净化、非目标后缀跳过日志。
- **单事务批量写入**：适用于"全成或全败"语义优先、规模中等（≤数万行）的导入；数十万行级应评估分批事务 + 幂等键。行实体单 insert + 子实体 foreach 批量的分层，适用于"父行取自增 id 供子行外键"的 1:N 场景。
- **默认值重实现**：当复用既有 service 方法会拖入 UI 特有行为/未定义默认值时适用；代价是双实现漂移，须以"语义等价测试 + 派生源预解析"对冲。
- **N+1 上下文预解析**：循环体内任何"每行重查不变的元数据"都应上提到导入前 `buildContext`，用上下文对象贯穿避免长参数列表；`verify(times(1))` 锁定查询次数。

## Examples

round-trip 契约示例（导出→导入逐环节对应）：

```sql
-- 导出产出（SqlValueEscaper 转义）
INSERT INTO `note_dwtable_1` (`record_id`, `col_a`, `col_b`) VALUES (1, 'It''s a \n test', NULL), (2, 'x', 'y');
```

- 导入端：首列 `record_id`=1 识别为锚点（不进数据列）；`''`→`'`、`\n`→LF 还原；`NULL`→Java null→落库空串；多元组展开为 2 行。
- 排除列（如 type=21 双向链接列）导出时即不产出，导入时目标表映射也排除——双端一致跳过。
- 分片 zip：`T_p1.sql`…`T_p10.sql` 自然序解压合并，`T_p2` 先于 `T_p10` 处理，行序=导出序。

## Related

- `docs/solutions/design-patterns/sql-identifier-unicode-sanitization.md` —— 导出侧标识符净化三层策略；导入解析器的列名/反转义契约与之为 round-trip 对称（本文第 1、6 节的契约源）。该文档的"净化链"表述目前仅覆盖导出方向，导入侧闭环后宜更新。
- `docs/solutions/design-patterns/lookup-column-code-simplification-patterns.md` —— 其"已知局限"记录了 N+1 lookup 查询并建议批量 Mapper；本文第 4 节的导入路径采用**上下文预解析**缓解同类问题，形成模式对照（两条路线并存）。
- `docs/solutions/architecture-patterns/lookup-column-dedupe-cascade-recompute.md` —— 其"已知局限 2：无事务边界"是缺事务的反面案例；本文第 3 节为同域正例（单事务批量写入）。
- 计划文档 `docs/plans/2026-08-25-001-feat-multitable-import-plan.md`（KTD1-KTD6 决策与 Deferred 清单的权威来源）。
