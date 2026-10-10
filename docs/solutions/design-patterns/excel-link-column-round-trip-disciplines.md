---
title: 链接列 Excel 往返的数据完整性纪律（双向链接/语义链接双轨制）
date: 2026-10-10
category: docs/solutions/design-patterns
module: 多维表格导入导出（NoteDwtableExcelImportServiceImpl / NoteDwtableExportPivotServiceImpl / LinkNameResolver）
problem_type: design_pattern
component: service_object
severity: high
applies_when:
  - 修改 note_dwtable_item 的 linkRecordId/linkItemId/value 等逗号分隔多值字段
  - 实现 Excel 导出→导入往返或对既有数据做批量重写
  - 涉及双向链接（type=21）、语义链接（type=25）或 NoteNotelink 行的重建
  - 预检-执行两段式流程中做影响面分析
tags: [excel-import, csv-read-modify-write, dual-track, notelink, link-re-pointing, data-integrity, round-trip]
---

# 链接列 Excel 往返的数据完整性纪律（双向链接/语义链接双轨制）

## Context

链接列导入导出双向适配（2026-10，14 提交）落地过程中，审查与实现共同暴露出一族数据完整性陷阱：逗号分隔多值字段的 SQL 子串误替换、`_ID` 与名称条目的位置错位、列扫描式影响面捕获的漏捕、双向链接互指一致性破坏、NoteNotelink 行 id 变更导致笔记正文锚点断链。它们共同的特征是：**测试小 id 空间下全部静默通过，真实数据下才爆发**。本篇把解决方案固化为六条纪律与两条前端附注。

## Guidance

### 1. 多值 CSV 字段的读-改-写纪律

`linkRecordId`/`linkItemId`/`value` 是逗号分隔串。对它们做"把旧 id 换成新 id"时：

- **禁止集合式 SQL 替换**（`REPLACE()`/`LIKE`）——`REPLACE('13,3', '3', '7')` 得 `'17,7'`，id 3 的替换误中 13 与 31。查询侧同理：先取候选行再 Java 侧按 CSV 成员精确比对，不做 SQL 字符串匹配。
- **正确姿势**：Java 读-改-写——拆串 → 逐成员映射（旧 item→新 item）→ `Set` 去重 → `String.join` → 按主键单行 update。与 `lookup-column-dedupe-cascade-recompute.md` 的"禁 `List.toString().replace`"教训同族。

### 2. `_ID` 副列与名称条目的位置配对

`_ID` 副列与名称主列的条目**按导出顺序一一对应**，部分未命中回退时按位置配对。因此：

- 位置配对用途必须用**保留空条目**的拆分（`splitCsvEntries`）；`LinkNameResolver.resolve()` 整格解析会跳过空条目，丢失位置信息——两者不可混用。
- id 拆分的空 token 必须记 `null` 占位而不是 `continue` 跳过——`",5"` 跳过空 token 后 `5` 左移一位，会与错误的名称条目配对，错位名称可解析时静默错链。
- 已还原转义的单条目（含逗号名）不可再走整格解析二次拆分——用 `resolveEntry` 单条目入口。

### 3. 影响面捕获：数据面全量反查，不信属性扫描

判定"哪些他表单元格指向本表记录"时，按 `linkRecordId` 数据面全量反查（候选查询 + Java 成员精确过滤），**不要**复用列属性扫描（property 的 `table_id`/`back_field_id`）——配置残缺的入链（属性缺失/非法）在属性扫描下静默漏捕，但数据面引用真实存在。替换式导入的入链重指向从列扫描迁移到数据面反查即为此因。

### 4. 互指声明集维持双向链接对称一致性

双向链接的配对列互指是 UI 持续维护的不变量（`executeSymmetricWrite` 只增不删）。替换导入后配对列旧条目的存活必须以**对侧互指声明**为前提：声明集 = 21 列单元格解析出的目标集合，`declaredTargetsByColumnId`；Excel 中删除某正向链接 → 声明集失去该目标 → 背链条目按条目级清空并报告。18 列是单向关联，无互指语义，无条件重指向——这个分叉是有意的。

### 5. 双轨制与三类失败处置（2026-10-10 两项裁决）

- **ID 优先、名称回退**：`_ID` 副列保留并被导入消费（ID 与名称不一致以 ID 为准）；名称解析（`LinkNameResolver`：整串优先→精确→归一化→唯一前缀≥2字符）仅作无 `_ID` 的外部手写数据回退。机器锚点消解重名/空名/名称基列漂移三类问题。
- **三类失败处置**：名称回退歧义 → 链接失败清单、整表零写入；目标确已不存在 → 单元格级清空（链接字段+value）+ 结果报告；21 配对列无互指声明的旧条目 → 条目级清空 + 报告。预检与缺参清单并列阻断（`hasBlockingIssues`），确认前零写入。
- **重指向时序**：建新落库（回填新 id）后、**对称写回之前**执行——借对称写回既有的 FOR UPDATE 读取与去重合并旧 id→新 id，避免配对列出现重复条目；更新前 FOR UPDATE 锁读防并发丢失更新。

### 6. NoteNotelink 行 id 保活重建

语义链接方向是"引用笔记 → 本表单元格"（link\* 是目标坐标、noteId/blockId 是源）。替换导入重建 25 列时：

- **清旧行 + 按原主键 upsert**（`insert ... on duplicate key update` 以原行 id 恢复）：行 id 与 blockId 保活——笔记正文词级锚点的 `data-link-id`（= NoteNotelink id）不因重导断链。直接删旧插新会让所有正文锚点悬空。
- `_ID` 命中前先做**归属校验**（`linkColumnId`==本列、`linkDwTableId`==本表），不匹配按未命中走名称回退——堵越权改写与旧版导出文件（`_ID` 曾是笔记 id）撞行错配。
- `blockId` 是源笔记侧锚点，不受本表重建影响，ID 命中时按原行恢复；仅名称回退新建行时降级为 null。`linkNoteId` 写规范值（本表归属笔记），COALESCE 兜底只用于查询历史行。

## Why This Matters

每条纪律都对应一类**不可逆或不可见**的损坏：子串误替换静默改错数据（id 3→13）；位置错位静默错链（不进失败清单）；属性扫描漏捕让用户数据"陪葬"；互指破坏让 AE 自身的验收断言失效；行 id 变更断掉正文锚点且测不出（mock 单测必绿）。这类 bug 在小 id、良构文件的单元测试里全部隐形。

## When to Apply

- 任何对逗号分隔多值字段的批量重写或查询过滤（纪律 1）
- 导入/同步流程中 ID 列与名称列并存、部分回退的场景（纪律 2）
- "谁引用了我"类影响面分析（纪律 3）
- 双向/对称数据结构的批量维护（纪律 4）
- 往返（round-trip）格式设计：机器锚点与人类可读的取舍（纪律 5）
- 重建携带外键式引用的行（正文锚点、跨表引用）时（纪律 6）

## Examples

重指向的读-改-写（正确姿势）：

```java
// 拆串 → 逐成员映射 → 去重 → join → 单行 update（禁止 SQL REPLACE）
for (String id : splitCsvToList(item.getLinkRecordId())) {
    Long newId = oldToNew.get(Long.valueOf(id));
    if (newId != null && mergedRecordIds.add(newId)) {
        mergedValues.add(newName);  // List 而非 Set——与 id 串等长对齐
    }
}
itemMapper.updateLinkFieldsById(item.getId(), join(mergedRecordIds), join(mergedItemIds), join(mergedValues));
```

反例（纪律 1 的坑）：`UPDATE ... SET linkRecordId = REPLACE(linkRecordId, '3', '7')` —— '13,3' 变 '17,7'。

前端附注（本次沉淀补录的两条既有教训）：
- Vue3 预检弹框组件：父组件同一同步块内设挂载条件与 open prop 时，watch 必须 `{ immediate: true }`，否则首挂死锁（commit 2ee70b9a 的根因）。
- 大清单渲染必须有上限：`ImportPrecheckModal` 的 `RENDER_LIMIT`/`UNHANDLED_RENDER_LIMIT` 模式——渲染前 N 条 + "其余 M 条"计数行，防数千条目冻结弹框（本次 linkFailures 分组沿用 `LINK_FAILURE_RENDER_LIMIT = 50`）。

## Related

- `docs/solutions/architecture-patterns/multitable-sql-import-pipeline.md` —— SQL 导入管线（排除集口径仅限 SQL 链路，Excel 链路 25 列已移出排除）
- `docs/solutions/database-issues/notelink-linknoteid-null-recovery.md` —— linkNoteId 兜底仅查询侧，写入侧走规范值
- `docs/solutions/logic-errors/lookup-column-dedupe-cascade-recompute.md` —— CSV join 序列化与静默吞异常教训（本篇纪律 1 同族）
- `CONCEPTS.md` —— Relation Text Matching（18 列专属）、Link Re-pointing、Dual-Track Round-Trip、Declared Target Set 词条
- 实施计划：`docs/plans/2026-10-10-001-feat-link-column-import-export-plan.md`；需求：`docs/brainstorms/2026-10-09-link-column-import-export-requirements.md`（含两项裁决记录）
