---
title: "fix: type=25 语义关联列级联删除"
type: fix
date: 2026-07-23
origin: docs/brainstorms/2026-07-23-semantic-column-cascade-delete-requirements.md
---

# fix: type=25 语义关联列级联删除

## Summary

补齐 `NoteColumnServiceImpl.deleteNoteColumnByIds` 中 type=25（语义关联列）分支的级联清理与文本恢复。当前实现对 type=25 列仅删除 NoteDwtableItem 单元格与列本身，不清理 NoteNotelink 记录（REVERSE 方向），也不恢复笔记中被关联的 `<a data-type="semantic">` 锚点文本，导致孤儿 NoteNotelink 记录与笔记中的死链 `[文本]` 残留。

本计划实现：删除前的数据校验与确认对话框（R1-R3）、REVERSE + FORWARD 双向级联记录清理（R4-R5）、后端 NoteBlock 内容重写以恢复锚点文本（R6-R8）、per-block best-effort 错误处理（R9-R10）。配套修复两个 research 中发现的正确性缺陷：FORWARD 锚点缺少列标识（导致 R7 的"不触碰其他列锚点"保证无法执行）与 `linkBlockId` 不变量在 value-clear 路径下被破坏（导致 F2 无数据路径遗漏 FORWARD 文本恢复）。

详细需求与设计决策见 origin: [docs/brainstorms/2026-07-23-semantic-column-cascade-delete-requirements.md](docs/brainstorms/2026-07-23-semantic-column-cascade-delete-requirements.md)。

---

## Problem Frame

`deleteNoteColumnByIds` 当前的 type=25 分支存在三个缺陷：

1. **顺序致命问题** — [deleteNoteColumnByIds](ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteColumnServiceImpl.java) 在 line 368 先执行 `deleteNoteDwtableItemByColumnId` 删除所有 NoteDwtableItem，之后才调用 `deleteDataWhenLink`。R6 要求"在删除 NoteDwtableItem 之前收集 linkBlockId 非空的记录"，当前顺序使 FORWARD 方向文本恢复无法实现。

2. **deleteDataWhenLink 对 type=25 失效** — [deleteDataWhenLink](ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteColumnServiceImpl.java) 通过 `back_field_id` 查找配对列并清理配对列的 NoteNotelink。type=25 列由 [linkToDwtable](ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteBlockServiceImpl.java) 创建时 property 只含 `{note_id}`，不设置 `back_field_id`，所以该方法在 property 解析后早返回 false，从不清理被删列自身的 NoteNotelink 记录。

3. **removeLink 是 stub** — [removeLink](ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteBlockServiceImpl.java) 仅查询 record 后返回 0，FORWARD 方向的单锚点取消关联后端无实际清理。

此外 research 发现两个需求文档未覆盖的正确性缺陷：

- **FORWARD 锚点无列标识** — [setAnchorAttributes](notepad/src/components/editorjs/tools/inline/SemanticLink/index.ts) 设置的属性集（data-table-id、data-record-id、data-block-id、data-target-id、data-source-id、data-link-id、data-owner-note-id）不包含任何列标识。由于 `linkToDwtable` 每次调用新建一个 type=25 列，同一 NoteBlock 对同一 table+record 的多次正向关联会产生匹配键完全相同的 FORWARD 锚点。R7 的"不触碰其他列锚点"保证无法执行——删除一列会误删存活列的锚点。这是 adversarial reviewer 标记的 confidence 100 发现。

- **linkBlockId 不变量在 value-clear 路径下破坏** — [updateNoteRecord](ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java) 构造新 NoteDwtableItem 时只设 id+value，[updateNoteDwtableItem](ruoyi-system/src/main/resources/mapper/system/NoteDwtableItemMapper.xml) 是 selective update（`<if test="linkBlockId != null">`），所以清空 cell value 时 linkBlockId 被保留。一个 FORWARD-only 链接的 cell value 被清空后，R1 检查"非空 cell value OR NoteNotelink"两者皆无 → 判定无数据 → 走 F2 路径 → F2 省略 FORWARD 文本恢复 → 锚点变成死链且无确认对话框。

---

## Requirements Traceability

| Requirement | Description | Covered By |
|---|---|---|
| R1 | 删除前校验 NoteDwtableItem 非空 cell OR NoteNotelink by linkColumnId | U4 (前端), U3 (后端查询支撑) |
| R2 | 无数据直接删除，无确认对话框 | U4 |
| R3 | 有数据显示确认对话框，确认后调 delete API | U4 |
| R4 | 删除 linkColumnId == 被删列 id 的 NoteNotelink | U3 |
| R5 | 删除被删列的 NoteDwtableItem | U3 (重构顺序) |
| R6 | 删除前收集 linkBlockId 非空的 NoteDwtableItem（解耦条件） | U3 |
| R7 | 按 data-link-id (REVERSE) / data-column-id (FORWARD) 匹配并替换锚点 | U1, U2, U3 |
| R8 | strip 前后 `[` `]` | U1 |
| R9 | per-block try-catch + log | U3 |
| R10 | 始终删除列与记录，不受文本恢复影响 | U3 |

---

## Key Technical Decisions

**HTML 解析库：Jsoup。** 仓库当前无任何 HTML 解析依赖（pom.xml 全模块扫描确认）。Jsoup 1.17.2 兼容 Java 8、API 简洁、HTML round-trip 保真度与前端 [index.vue:273-281](notepad/src/components/editorjs/index.vue) 的 `tempDiv.innerHTML` 模式一致。替代方案（正则、FastJSON + 手工字符串替换）脆弱不推荐。

**FORWARD 锚点列标识：本特性修复。** 为 FORWARD 锚点新增 `data-column-id` 属性，使 R7 匹配谓词可执行。修复需前后端协同：`linkToDwtable` 返回新建 columnId、前端 `setAnchorAttributes` 设置 `data-column-id`、sanitize 配置允许该属性。历史锚点无此属性时降级为旧匹配键（data-table-id + data-record-id + 空 data-link-id）并 `log.warn` 标注潜在歧义。

**linkBlockId 不变量修复：解耦 R6 收集条件。** 采用选项 2 — 不修改 `updateNoteRecord` 的 selective update 语义，而是新增 mapper 查询方法 `selectItemsByColumnIdWithLinkBlockId`（查 `linkBlockId IS NOT NULL`），使 R6 的 FORWARD 收集基于 linkBlockId 存在性而非 cell value 非空。这样 value-clear 路径下的 FORWARD 锚点仍会被收集与恢复，F2 路径正确性得以保证。选项 1（清空 value 时显式置空 linkBlockId）需改 mapper 支持显式置空，风险更高。

**Best-effort over transactional（继承需求决策）。** 不引入 `@Transactional`，与现有 [deleteNoteColumnByIds](ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteColumnServiceImpl.java) 和 [recomputeRecordNamesForTable](ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java) 的 best-effort 模式一致。已知代价：部分文本恢复失败时列与记录仍删除，留下死锚点（AE3 预期行为）。per-block catch 必须用标识键 `log.error("[CASCADE-DELETE] columnId={}, blockId={}", ...)`，绝不静默吞（来自 [lookup-column-dedupe-cascade-recompute.md](docs/solutions/architecture-patterns/lookup-column-dedupe-cascade-recompute.md) 修复3的约束）。

**removeLink stub 保持不实现。** 本特性的级联删除在 `deleteNoteColumnByIds` 中集中处理，不依赖 `removeLink`。`executeUnlink` 的 FORWARD 单锚点取消关联仍走 stub 路径（前端做 DOM 还原但后端无清理），与本特性解耦，避免 scope 蔓延。stub 应视为死代码（来自 [lookup-column-code-simplification-patterns.md](docs/solutions/design-patterns/lookup-column-code-simplification-patterns.md) 模式5），不在本特性删除。

**表名重算复用现有机制。** F1 的"recompute affected table names"已被 [recomputeRecordNamesForTable](ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java) 覆盖，当前 `deleteNoteColumnByIds` 在 line 382-384 已调用。无需新增逻辑，只要 `affectedDwtableIds` 集合正确收集即可。

---

## Implementation Units

### U1. HTML 解析与文本恢复基础设施

**Goal:** 提供 NoteBlock.property 内容解析与 `<a data-type="semantic">` 锚点替换的底层能力，供 U3 调用。

**Files:**
- `ruoyi-system/pom.xml` — 新增 Jsoup 依赖
- `ruoyi-system/src/main/java/com/ruoyi/system/service/NoteBlockContentService.java` — 新建服务接口
- `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteBlockContentServiceImpl.java` — 新建实现
- `ruoyi-system/src/test/java/com/ruoyi/system/service/impl/NoteBlockContentServiceImplTest.java` — 新建测试

**Approach:**

解析流程参照前端 [index.vue:273-281](notepad/src/components/editorjs/index.vue) 的 HTML round-trip 模式：

1. 用 FastJSON 解析 `NoteBlock.property` 字符串为 `JSONObject`
2. 取 `data.text` 字段（String，HTML 片段）
3. 用 Jsoup 解析 HTML 为 `Document`
4. 查找所有 `a[data-type="semantic"]` 元素
5. 按调用方传入的匹配谓词判断是否替换（REVERSE: data-link-id ∈ deleted NoteNotelink ids；FORWARD: data-column-id == 被删列 id，且历史锚点无 data-column-id 时降级匹配 data-table-id + data-record-id + 空 data-link-id）
6. 命中锚点：strip 前后 `[` `]`（正则 `^\[|\]$`），用 Jsoup `replaceWith(new TextNode(plainText))` 替换为纯文本节点
7. 序列化 Document 回 HTML 字符串
8. 写回 `data.text`，序列化 JSONObject 回字符串
9. 返回更新后的 property 字符串（调用方负责 selective update 写库）

**anchor 位置约定（implementer 需先验证）：** research 确认 paragraph block 的 HTML 在 `data.text`。但 EditorJS 其他 block 类型（header 有 `data.text`，list 有 `data.items[]` 数组）也可能含 anchor。implementer 应先 grep 前端代码确认 `a[data-type="semantic"]` 可能出现在哪些 block 类型的哪些字段，或在解析时递归遍历 `data` 对象的所有字符串值。建议首版只处理 paragraph 与 header（共用 `data.text`），list 等 block 类型若 grep 确认无 anchor 则跳过。

**约束（来自 learnings）：**
- Jsoup serialize 后的 HTML 实体保真：测试覆盖中文、嵌套标签、`&nbsp;`、`<`/`>` 实体
- 不用 `replaceAll(" ", "")` 类操作（来自 [lookup-column-dedupe-cascade-recompute.md](docs/solutions/architecture-patterns/lookup-column-dedupe-cascade-recompute.md) 修复1）
- strip `[`/`]` 必须用精确正则 `^\[|\]$`，不能用 `replace("[","").replace("]","")`（后者破坏 anchor 文本内部包含 `[` 或 `]` 的合法内容，与前端 [executeUnlink](notepad/src/components/editorjs/tools/inline/SemanticLink/index.ts) L342 一致）

**Test scenarios:**
- 解析标准 paragraph block property，REVERSE 锚点（data-link-id 命中 deleted set）被替换为纯文本，strip `[红门]` → `红门`
- 解析标准 paragraph block property，FORWARD 锚点（data-column-id 命中被删列 id）被替换为纯文本
- 非命中锚点（data-link-id 不在 deleted set，或 data-column-id 不匹配）保持不变
- 多锚点同一 block：一次 parse 替换全部命中锚点，一次 serialize 回写
- 畸形 JSON 解析抛异常（由上层 U3 catch）
- 畸形 HTML（Jsoup 容错解析，不抛异常，但结果可能不完美）
- strip `[复杂[文本]` → `复杂[文本`（只 strip 首尾第一个）
- Jsoup round-trip 保真：含中文、`&nbsp;`、嵌套 `<strong>` 的 HTML 解析后序列化不损坏
- history anchor（无 data-column-id）降级匹配 data-table-id + data-record-id + 空 data-link-id

---

### U2. FORWARD anchor data-column-id 基础设施

**Goal:** 为 FORWARD 锚点新增列标识，使 R7 的 FORWARD 匹配谓词可执行，解决 sibling type=25 columns 锚点歧义。

**Files:**
- `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteBlockServiceImpl.java` — `linkToDwtable` 返回值改为 columnId
- `ruoyi-system/src/main/java/com/ruoyi/system/controller/.../NoteBlockController.java` 或现有调用方 — 适配返回值
- `notepad/src/components/editorjs/tools/inline/SemanticLink/index.ts` — `setAnchorAttributes` 新增 data-column-id，sanitize 配置更新
- `notepad/src/...`（调用 linkToDwtable 的前端代码）— 适配返回值，拿到 columnId 后设置 anchor 属性

**Approach:**

1. **后端 `linkToDwtable` 返回 columnId** — 当前方法返回 `int`（0/1 表示成功/失败）。改为返回新建的 columnId（或返回包含 columnId 的响应对象）。`noteColumnMapper.insertNoteColumn` 后 NoteColumn 对象的 id 已被回填（MyBatis `useGeneratedKeys`），直接返回 `noteColumn.getId()`。

2. **前端 `setAnchorAttributes` 新增 `data-column-id`** — 在 FORWARD 分支（`data-link-id=""` 时）设置 `data-column-id = columnId`。REVERSE 分支（`data-link-id` 非空）也设置 `data-column-id = columnId`（一致性，且 REVERSE 锚点也属于某列）。调用 `linkToDwtable` 的前端代码需拿到返回的 columnId 传入 `setAnchorAttributes`。

3. **sanitize 配置更新** — [index.ts:39-55](notepad/src/components/editorjs/tools/inline/SemanticLink/index.ts) 的 sanitize 配置新增 `'data-column-id': true`。

4. **历史锚点兼容** — U1 的匹配逻辑已覆盖：无 data-column-id 的 FORWARD 锚点降级为旧匹配键（data-table-id + data-record-id + 空 data-link-id）并 `log.warn("[CASCADE-DELETE] legacy FORWARD anchor without data-column-id, blockId={}, potential column ambiguity")`。这保证现有数据不被破坏，同时标注潜在歧义。

**约束:**
- `linkToDwtable` 返回值变更需检查所有调用方（grep `linkToDwtable` 确认调用点）
- 前端调用 linkToDwtable 的代码需在 API 响应中拿到 columnId（后端响应体需包含该字段）

**Test scenarios:**
- `linkToDwtable` 创建列后返回非 null columnId
- 新建 FORWARD 锚点含 `data-column-id` 属性，值为创建列的 id
- 新建 REVERSE 锚点含 `data-column-id` 属性
- sanitize 不剥离 `data-column-id`
- 历史 anchor（无 data-column-id）仍能被 U1 降级匹配逻辑处理
- 同一 NoteBlock 对同 table+record 的两个 FORWARD 锚点（分属不同列）有不同 data-column-id，删除一列只命中本列锚点

---

### U3. type=25 级联删除核心实现

**Goal:** 重构 `deleteNoteColumnByIds` 的 type=25 分支，实现 R4-R10 完整级联删除与文本恢复。依赖 U1（文本恢复）与 U2（data-column-id 匹配）。

**Files:**
- `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteColumnServiceImpl.java` — 重构 `deleteNoteColumnByIds` 与 `deleteDataWhenLink`
- `ruoyi-system/src/main/java/com/ruoyi/system/mapper/NoteDwtableItemMapper.java` — 新增 `selectItemsByColumnIdWithLinkBlockId`
- `ruoyi-system/src/main/resources/mapper/system/NoteDwtableItemMapper.xml` — 新增查询
- `ruoyi-system/src/main/java/com/ruoyi/system/mapper/NoteNotelinkMapper.java` — 可能新增按 linkColumnId 批量查询（复用现有 `selectNoteNotelinkList`亦可）
- `ruoyi-system/src/main/resources/mapper/system/NoteNotelinkMapper.xml` — 确认 `selectNoteNotelinkList` 的 linkColumnId 过滤可用

**Approach:**

重构 `deleteNoteColumnByIds` 的 type=25 分支执行顺序（关键：先 SELECT 收集，再 DELETE）：

```
对每个被删 type=25 列 column:
  1. SELECT NoteNotelink where linkColumnId = column.id            → reverseLinks (REVERSE)
  2. SELECT NoteDwtableItem where columnId = column.id AND linkBlockId IS NOT NULL → forwardItems (FORWARD, 解耦条件)
  3. 按 NoteBlock 分组：
     - REVERSE: 按 NoteNotelink.noteId/blockId 分组
     - FORWARD: 按 NoteDwtableItem.linkNoteId/linkBlockId 分组
     - 合并同 NoteBlock 的 REVERSE + FORWARD 锚点替换任务
  4. 对每个 NoteBlock（批量查询提升到循环外，来自 learnings 修复5）:
     try:
       property = noteBlock.getProperty()
       reverseIds = reverseLinks 中本 block 的 NoteNotelink.id 集合
       forwardTuple = forwardItems 中本 block 的 (linkBlockId, recordId, dwtId, columnId)
       newProperty = noteBlockContentService.restoreAnchors(property, column.id, reverseIds, forwardTuple)
       noteBlock.setProperty(newProperty); noteBlockMapper.updateNoteBlock(noteBlock)  // selective
     catch Exception:
       log.error("[CASCADE-DELETE] columnId={}, blockId={}", column.id, blockId, e)  // 来自 learnings 修复3
       continue  // 来自 learnings 修复6，不贯穿空结果
  5. DELETE NoteNotelink where linkColumnId = column.id            → R4 (新增调用点，传被删列自身 id)
  6. DELETE NoteDwtableItem where columnId = column.id              → R5 (移到收集之后)
  7. DELETE column                                                   → 列本身
  8. affectedDwtableIds.add(column.dwtableId)                        → 现有逻辑保留
循环结束:
  9. for dwtableId in affectedDwtableIds: recomputeRecordNamesForTable(dwtableId)  → 现有逻辑保留
```

**关键点：**

- **顺序重构** — 当前 line 368 的 `deleteNoteDwtableItemByColumnId` 必须移到步骤 6，在收集（步骤 2）与文本恢复（步骤 4）之后。
- **R4 新增调用点** — `deleteNoteNotelinkByColumnId(column.id)` 传被删列自身 id（不是 back_field_id）。已有 [deleteNoteNotelinkByColumnId](ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteNotelinkServiceImpl.java) 含 try-catch，符合 best-effort。
- **R6 解耦条件** — 新增 `selectItemsByColumnIdWithLinkBlockId` mapper 方法，查询条件 `columnId = #{columnId} AND linkBlockId IS NOT NULL`。这解决 value-clear 路径下 linkBlockId 保留但 value 为空的情况。
- **R7 匹配谓词** — REVERSE: anchor 的 `data-link-id` ∈ 步骤 1 收集的 NoteNotelink.id 集合；FORWARD: anchor 的 `data-column-id` == column.id（U2 提供），历史锚点降级匹配 data-table-id + data-record-id + 空 data-link-id。
- **per-block 批量** — 步骤 4 的 NoteBlock 查询应批量化（`selectNoteBlockByIds` 或 IN 查询），避免每块一次 DB 往返（来自 learnings 修复5）。若 NoteBlockMapper 无批量查询方法，新增。
- **不引入 @Transactional** — 与 best-effort 决策一致。
- **deleteDataWhenLink 不扩展** — type=25 的清理逻辑在 `deleteNoteColumnByIds` 中独立实现，不试图扩展 `deleteDataWhenLink`（该方法语义是清理配对列，来自 [lookup-column-set-operation-logic-errors.md](docs/solutions/logic-errors/lookup-column-set-operation-logic-errors.md) 对 back_field_id 的分析）。

**约束（来自 learnings）：**
- NPE 守护：收集 tuple 时同时检查 `(linkBlockId, recordId, dwtId)` 三者非空（来自修复4）
- 循环不变查询提升到循环外（来自修复5）
- 未知分支 `continue` + `log.warn`（来自修复6）
- 严格按顺序：先 SELECT 再 DELETE（itemMap-vs-DB 时序不变量，来自 [lookup-column-code-simplification-patterns.md](docs/solutions/design-patterns/lookup-column-code-simplification-patterns.md)）

**Test scenarios:**
- AE1 (REVERSE-only): 列有 NoteNotelink 无 FORWARD NoteDwtableItem → NoteNotelink 删除 + REVERSE 锚点按 data-link-id 恢复
- AE2 (FORWARD-only): 列有 NoteDwtableItem(linkBlockId 非空) 无 NoteNotelink → FORWARD 收集 + 锚点按 data-column-id 恢复
- AE3 (corrupted block): 一个 NoteBlock property 畸形 → 该块 skip + log.error + 其他块正常 + 列与记录仍删除
- AE4 (empty column): 无 cell value 无 NoteNotelink → 走 F2 路径，R4/R5 防御性删除，无文本恢复
- linkBlockId 不变量: FORWARD-only cell value 被清空（linkBlockId 保留）→ selectItemsByColumnIdWithLinkBlockId 仍命中 → F2 路径执行 FORWARD 文本恢复
- sibling columns: 同 NoteBlock 两个 FORWARD 锚点分属不同列（不同 data-column-id）→ 删除一列只恢复本列锚点，存活列锚点不变
- 删除后 affectedDwtableIds 的表触发 recomputeRecordNamesForTable
- null property 列删除不 NPE（扩展现有 `testDeleteNoteColumnByIds_type25_nullProperty_noNPE`）
- 多列批量删除（ids 含多个 type=25）：每列独立收集与恢复，互不干扰
- 删除时 linkColumnId 匹配：只删本列 NoteNotelink，不误删其他列的

---

### U4. 前端删除确认流程

**Goal:** 实现 R1-R3 的删除前数据校验与确认对话框。

**Files:**
- `notepad/src/components/baseTable/field/index.vue` — `deleteField` 增强
- `notepad/src/stores/table/index.ts` — `deleteColumn` 可能增加 loading/disabled 状态
- 可能新增 API 调用文件（若 notepad 的 api 层未封装 notelink list）

**Approach:**

1. **R1 数据校验** — `deleteField` 对 type=25 列点击删除时：
   - NoteDwtableItem 检查：从前端内存的表格 cell 数据扫描该列是否有非空 value
   - NoteNotelink 检查：调用 `GET /system/notelink/list?linkColumnId={columnId}`（[NoteNotelinkController.list](ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/NoteNotelinkController.java) 已支持 linkColumnId 过滤）。注意该 endpoint 有 `@PreAuthorize("@ss.hasPermi('system:notelink:list')")` 权限校验，前端调用需确保用户有权限。
   - 任一数据源非空 → hasData = true

2. **R2 无数据** — hasData = false → 直接调用 delete API，无对话框

3. **R3 有数据** — hasData = true → 显示确认对话框（警告"该列包含数据，删除将级联清理关联笔记链接并恢复笔记文本"）→ 用户确认 → 调用 delete API

4. **loading/disabled 基础状态** — 确认后调用 delete API 期间，删除按钮 disabled，防止重复点击。完整进度条/多步进度反馈 deferred（Open Question）。

**约束:**
- NoteNotelink list 查询可能返回分页数据，需检查 total > 0 而非 list 长度（`startPage()` 分页）
- 或调用时不分页（传 pageSize=1 或专用 count endpoint）— 若性能敏感可新增 count endpoint，但首版用 list + total 判断即可

**Test scenarios:**
- 空列点击删除 → 无对话框，直接删除
- 有 cell value 的列 → 显示对话框 → 确认后删除
- 有 NoteNotelink 无 cell value 的列（REVERSE-only）→ 显示对话框（R1 双数据源校验生效）
- cell value 被清空但 linkBlockId 保留的列 → NoteDwtableItem cell 检查无数据，但 NoteNotelink 检查可能无（FORWARD-only 无 NoteNotelink）→ 注意：此场景 R1 可能仍判定无数据走 F2，但 F2 的 FORWARD 文本恢复（U3 解耦条件）会兜底恢复 anchor。前端不展示对话框，但后端仍正确清理。这是可接受的行为（无确认但有恢复）。
- 取消对话框 → 不删除
- 确认后删除按钮 disabled → API 完成后恢复
- NoteNotelink list 查询失败（网络/权限）→ 降级为只检查 cell value，或提示错误（首版降级 + console.warn）

---

### U5. 测试

**Goal:** 扩展现有测试约定，覆盖 U1-U4 的核心场景。

**Files:**
- `ruoyi-system/src/test/java/com/ruoyi/system/service/impl/NoteColumnServiceImplTest.java` — 扩展 U3 测试
- `ruoyi-system/src/test/java/com/ruoyi/system/service/impl/NoteBlockContentServiceImplTest.java` — 新建 U1 测试
- 前端测试（若 notepad 有测试框架）— U4 测试

**Approach:**

遵循现有测试约定（[NoteColumnServiceImplTest.java](ruoyi-system/src/test/java/com/ruoyi/system/service/impl/NoteColumnServiceImplTest.java)）：
- `@ExtendWith(MockitoExtension.class)`，纯 Mockito，无 Spring 上下文
- 命名：`testMethodName_scenario_expectedBehavior`

**Test scenarios** 已在各 unit 中列出。U5 汇总并补充边界：

- U1: 10 个场景（见 U1）
- U2: 6 个场景（见 U2）
- U3: 11 个场景（见 U3）
- U4: 7 个场景（见 U4）
- 跨 unit 集成：U3 调用 U1 的 `restoreAnchors` 时传入 U2 提供的 data-column-id 匹配谓词，端到端验证 REVERSE + FORWARD 混合场景

---

## Risks & Dependencies

**依赖：**
- **Jsoup 新增依赖** — 需在 `ruoyi-system/pom.xml` 新增。Jsoup 1.17.2 兼容 Java 8，Maven Central 可用。若团队对新增依赖有审批流程，需提前确认。
- **U2 前后端协同** — `linkToDwtable` 返回值变更影响所有调用方。需 grep 确认调用点（前端 + 后端自调用）。
- **U1 依赖 U2 的 data-column-id** — U1 的 FORWARD 匹配谓词需要 U2 提供的 columnId。若 U2 延迟，U1 可先实现 REVERSE 匹配，FORWARD 匹配待 U2 就绪。

**风险：**
- **HTML serialize 保真度** — Jsoup 解析 + 序列化可能改变无关 HTML（实体编码、属性顺序、自闭合标签）。需对比前端 `tempDiv.innerHTML` round-trip 行为。测试覆盖含中文、嵌套标签、实体的 case（U1 已列）。
- **NoteBlock.property 结构假设** — anchor 位置假设在 `data.text`。implementer 需 grep 确认 semantic anchor 不出现在 `data.items[].text`（list block）等嵌套结构。若出现，U1 解析逻辑需扩展。
- **历史锚点兼容** — 现有数据无 data-column-id，降级匹配可能误删 sibling columns 的锚点。降级路径 `log.warn` 标注，但无法完全避免。这是接受的已知风险（历史数据清理 deferred）。
- **无 @Transactional 的半迁移状态** — 部分文本恢复失败时列与记录仍删除，留下死锚点。AE3 预期行为，但 Open Question "Best-effort decision creates dead anchors harder to recover from" 指出死锚点的手动删除路径（executeUnlink REVERSE 依赖 NoteNotelink 存在）会失效。死锚点恢复 deferred 到 historical cleanup。
- **NoteNotelink list 权限** — 前端调用 `/system/notelink/list` 需 `system:notelink:list` 权限。需确认普通用户有此权限，否则 R1 的 REVERSE 检查会失败降级。

---

## Scope Boundaries

- **历史孤儿数据清理** — deferred。本特性 forward-looking，type=25 列删除前已存在的孤儿 NoteNotelink 与死锚点需单独清理工具。
- **removeLink stub 实现** — 不在本特性实现。`executeUnlink` FORWARD 单锚点取消关联的后端清理保持 stub。
- **实时刷新 open notes** — deferred。后端重写 NoteBlock content，open notes 需 reload 查看恢复文本。Bus 事件实时刷新不在本迭代。
- **死锚点手动删除 UX** — deferred。文本恢复失败留下的死锚点，其手动删除交互（特别是 REVERSE 死锚点 NoteNotelink 已删时）与 historical cleanup 一起处理。
- **完整 loading/progress 状态** — deferred。首版只实现基础 disabled 状态防重复点击，多步进度反馈不在本特性。
- **delete API 失败的用户反馈** — deferred。F1 只描述 happy path，delete API 调用本身失败（网络/权限/DB）的用户 facing 行为未定义，首版依赖框架默认错误处理。
- **表级级联删除的 type=25 清理** — 不扩展。表级删除（[deleteNoteDwtableById](ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteDwtableServiceImpl.java)）同样存在 type=25 孤儿问题，但属不同 scope。
- **确认对话框扩展到其他列类型** — 不扩展。type=25 only。

---

## Open Questions

### From 2026-07-23 review (deferred from requirements doc)

- **No failure flow for the delete API call itself** (P1, design-lens) — F1/R9-R10 只覆盖文本恢复失败，delete API 或记录删除步骤失败的用户行为未定义。首版依赖框架默认错误处理，deferred。
- **No loading/progress state specified during multi-step deletion** (P2, design-lens) — 首版基础 disabled 状态，完整进度 deferred。
- **Open-note stale anchor UX unspecified** (P2, design-lens) — deferred，与实时刷新一起处理。
- **'User can manually delete' dead anchor assumes unspecified interaction** (P2, design-lens) — deferred，与死锚点手动删除 UX 一起。
- **Stale local data check can bypass confirmation dialog** (P2, design-lens) — 前端内存数据可能 stale。首版接受此风险（R1 前端检查），后端 F2 路径的防御性清理（R4/R5）兜底。完整后端校验 deferred。
- **No user-facing feedback when corrupted block skipped** (P2, design-lens) — R9 只 log，用户无可见反馈。deferred。
- **Best-effort creates dead anchors harder to recover** (P2, adversarial) — 已知代价，死锚点恢复 deferred 到 historical cleanup。
- **FORWARD anchor matching cannot distinguish sibling columns** (P1, adversarial, confidence 100) — **本特性修复**（U2 实现 data-column-id）。历史锚点降级匹配仍存风险，接受。
- **linkBlockId invariant holds only at creation; value-clear preserves it** (P2, adversarial) — **本特性修复**（U3 解耦 R6 收集条件）。

### From planning research

- **NoteBlock.property anchor 位置** — anchor 是否只出现在 paragraph/header 的 `data.text`，还是可能出现在 list 等 block 的 `data.items[].text`？implementer 需先 grep 确认。U1 已说明降级策略（递归遍历或限定已知字段）。
- **NoteNotelink list 权限** — 普通用户是否有 `system:notelink:list` 权限？若无，R1 前端 REVERSE 检查会失败。需确认或新增轻量 count endpoint。
- **历史 anchor 降级匹配的歧义风险** — 历史 FORWARD 锚点无 data-column-id，降级匹配可能误删 sibling columns 锚点。接受已知风险，`log.warn` 标注。

---

## Sources / Research

**需求文档：** [docs/brainstorms/2026-07-23-semantic-column-cascade-delete-requirements.md](docs/brainstorms/2026-07-23-semantic-column-cascade-delete-requirements.md)（3 轮 ce-doc-review，11 项修复落地）

**机构知识（必读）：**
- [docs/solutions/architecture-patterns/lookup-column-dedupe-cascade-recompute.md](docs/solutions/architecture-patterns/lookup-column-dedupe-cascade-recompute.md) — 6 个失败模式（修复1-6）和 6 项已知局限直接约束本计划的错误处理、NPE 守护、循环结构和事务边界。已知局限②③（无 @Transactional + 逐行 catch）与本计划 best-effort 决策同构。
- [docs/solutions/design-patterns/lookup-column-code-simplification-patterns.md](docs/solutions/design-patterns/lookup-column-code-simplification-patterns.md) — 模式3（skip 语义）、模式5（死代码处理，removeLink stub）、itemMap-vs-DB 时序不变量。
- [docs/solutions/logic-errors/lookup-column-set-operation-logic-errors.md](docs/solutions/logic-errors/lookup-column-set-operation-logic-errors.md) — 修复4（结果 item 不存在时新建而非 NPE）、back_field_id 配对查找逻辑（解释 deleteDataWhenLink 为何对 type=25 失效）。

**关键代码位置：**
- `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteColumnServiceImpl.java:358-386,448-471` — `deleteNoteColumnByIds`, `deleteDataWhenLink`（U3 核心修改点）
- `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteBlockServiceImpl.java:192-258` — `linkToDwtable`（U2 返回值变更）, `removeLink` stub（保持不实现）
- `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteNotelinkServiceImpl.java:125-135` — `deleteNoteNotelinkByColumnId`（已有 best-effort，U3 新增调用点）
- `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java:128-735,1440-1471` — `updateNoteRecord`（linkBlockId 不变量问题，U3 解耦而非修改）, `recomputeRecordNamesForTable`（复用现有）
- `ruoyi-system/src/main/java/com/ruoyi/system/domain/NoteBlock.java` — property 字段（String，EditorJS data JSON）
- `ruoyi-system/src/main/resources/mapper/system/NoteDwtableItemMapper.xml:24-37,108-122` — list 查询 + selective update（U3 新增 selectItemsByColumnIdWithLinkBlockId）
- `ruoyi-system/src/main/resources/mapper/system/NoteNotelinkMapper.xml:24-37,118-120` — list 查询（支持 linkColumnId 过滤）+ deleteByColumnId
- `ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/NoteNotelinkController.java:41-47` — list endpoint（U4 前端调用）
- `notepad/src/components/editorjs/tools/inline/SemanticLink/index.ts:39-55,263-281,321-398` — sanitize 配置, `setAnchorAttributes`（U2）, `executeUnlink`（R8 strip 正则参考）
- `notepad/src/components/editorjs/index.vue:273-316,501-532` — property 序列化/反序列化 + HTML round-trip 模式（U1 参考）
- `notepad/src/components/baseTable/field/index.vue:132-148` — `deleteField`（U4 前端入口）
- `ruoyi-system/pom.xml` — U1 新增 Jsoup 依赖
- `ruoyi-system/src/test/java/com/ruoyi/system/service/impl/NoteColumnServiceImplTest.java` — 测试约定（U5 扩展）

**技术栈：** Java 1.8, Spring Boot 2.5.14, MyBatis (XML mapper), FastJSON 2.0.23, 无 HTML 解析库（U1 新增 Jsoup）
