---
date: 2026-08-25
topic: multitable-import
---

# 多维表格导入功能需求文档

## Summary

为多维表格增加 SQL 导入能力：用户在导出按钮左侧点击"导入"，上传 `.sql` 或 `.zip` 文件（与现有 SQL 导出格式 round-trip），按列名宽松匹配当前目标表的列，将行数据写入 `note_record`、单元格数据写入 `note_dwtable_item`，排除双向链接/语义关联/公式/lookup 列。整次导入在单个事务内完成，成功弹框→确定→回调刷新表格；失败弹框→确定→事务已回滚，无脏数据残留。

---

## Problem Frame

多维表格已有 SQL 导出能力（见 `docs/brainstorms/2026-08-06-multitable-export-requirements.md`），用户可下载 `.sql`/`.zip` 备份或迁移数据，但缺少反向导入能力。数据丢失或迁移到新实例时，用户只能手动重新录入或直接操作数据库，前者费时易错，后者绕过应用层校验与默认值规则。

本轮导入补齐 SQL 导出的反向闭环：复用导出产物格式，使"导出→导入"形成完整 round-trip。导入严格走应用层，复用现有 `NoteRecordServiceImpl.insertNoteRecord` 的默认值规则与 `NoteDwtableItem` 写入逻辑，确保数据落库行为与 UI 新增一致。

---

## Key Decisions

- **导入按钮位于导出按钮左侧**：贴合"先导入再导出"的左→右视觉习惯；按钮的 disabled/loading 联动与导出按钮一致（未选择多维表格时禁用并 hover 提示）。无中间格式选择弹窗——导入只接受 SQL/zip，无导出的 Excel/SQL 二选一需求。

- **接受 .sql 或 .zip，与导出格式 round-trip**：用户可直接上传导出 zip，无需手动解压选片。`.zip` 内多个 `.sql` 按文件名升序依次处理，全部追加为新记录到当前目标表；不要求文件名与目标表名匹配（用户自行对齐目标表）。

- **按列名宽松匹配，非严格**：SQL 列名去反引号后与目标表 `note_column.name` 字符串相等即视为同列；SQL 中有但目标表无的列静默跳过；目标表有但 SQL 中无的列用"新增"方法默认值；支持跨表迁移与列不完全对齐场景。

- **源 `record_id` 忽略，每次导入即新记录**：SQL 首列 `record_id` 的值仅用于行内多列归属同一行的分组，不映射到目标库 `NoteRecord.id`；导入为每行创建新 `NoteRecord`（由 `insertNoteRecord` 流程分配 ID）。简化 ID 冲突处理，但多次导入同一文件会产生数据副本——用户自行控制。

- **整次导入在单个事务内，失败即整体回滚**：所有 `.sql` 文件、所有行、所有单元格的写入包在单个 `@Transactional` 内；任一步骤失败抛 `ServiceException` 触发回滚。失败弹框提示原因后，事务已回滚，用户点确定即可，无需显式清理脏数据。

- **排除列类型清单：21（双向链接）、25（语义关联）、26（lookup）、20（公式）、23（数学公式）、18（单向关联）、24（集合运算）**：这些列在 SQL 与目标表中均跳过导入值。原因：双向链接/语义关联/lookup/单向关联/集合运算均依赖跨表关联与底层 link 字段（对称于 `NoteDwtableExportPivotServiceImpl.DUAL_TYPES` 分类），公式依赖运行时计算，无法通过纯 SQL 文本导入恢复。

---

## Actors

- A1. **多维表格使用者**：在多维表格页操作导入，上传 `.sql` 或 `.zip` 文件触发导入。唯一人类触发者。

---

## Requirements

### 导入触发与按钮

- R1. 多维表格页"导出"按钮左侧新增"导入"按钮；按钮的 disabled/loading 联动与导出按钮一致（未选择多维表格时禁用并 hover 提示"请先选择多维表格"，导入中显示 loading）。
- R2. 点击导入按钮触发文件选择器（accept 包含 `.sql` 与 `.zip`），用户选择文件后立即开始导入，无中间确认弹窗（与导出的格式选择弹窗不同——导入无格式选择需求）。

### 目标范围与文件格式

- R3. 导入目标为当前选定的多维表格（noteId）下的当前数据表（dwtableId）。SQL 中的 `CREATE TABLE` 语句与表名均忽略，不创建新数据表。
- R4. 导入仅写入 `note_record`（行）与 `note_dwtable_item`（单元格）两张表，不写入 `note_column`、`note_dwtable`、`note_view`、`note_notelink` 等表（列定义与表定义不动）。
- R5. 导入接受 `.sql` 单文件或 `.zip` 包；`.zip` 自动解压并按文件名升序依次处理内部所有 `.sql` 文件。
- R6. `.sql` 文件格式与现有 SQL 导出产物一致：含 `CREATE TABLE` + `INSERT` 语句；首列固定为 `record_id`（`BIGINT`，源记录 id）；其余列名取自 `NoteColumn.name` 经 `SqlIdentifierSanitizer` 合法化（保留中文与反引号）。
- R7. `CREATE TABLE` 语句忽略，仅解析 `INSERT` 语句提取列名与值。
- R8. `.zip` 内多个 `.sql` 全部追加为当前目标表的新记录；多个 `.sql` 之间按文件名升序处理；不要求文件名与目标表名匹配。

### 列映射

- R9. 列映射按 `NoteColumn.name` 宽松匹配：SQL 列名去反引号后与目标表 `note_column.name` 字符串相等（区分大小写）即视为同列。
- R10. SQL 中有但目标表无对应列名的列 → 跳过该列的值，后端日志只记录列名与跳过原因（不记录单元格值），不影响导入。
- R11. 目标表有但 SQL 中无对应列名的列 → 该列的值用"新增"方法默认值（不写该列的 `NoteDwtableItem`，或写默认值，与 `insertNoteRecord` 路径行为一致）。
- R12. 排除类型列（21 双向链接、25 语义关联、26 lookup、20 公式、23 数学公式、18 单向关联、24 集合运算）：无论该列在 SQL 中还是目标表中，均不导入值。SQL 中这类列的双列（ID 列 + 文本列）整体跳过；目标表中这类列不写入 `NoteDwtableItem`。排除清单与 `NoteDwtableExportPivotServiceImpl.DUAL_TYPES` 分类一致。

### 数据写入与默认值

- R13. 每行 `INSERT` 创建一条新的 `NoteRecord`，`dwtableId` = 当前目标表 id；`viewId`/`property`/`linkRecordId`/`sort`/`linkName` 等字段按 `insertNoteRecord` 的默认值规则填入（SQL 中未给出的字段同理）。
- R14. `NoteRecord.name` 由 `deriveRecordName(dwtableId, items, null)` 派生，与 `insertNoteRecord` 路径一致；SQL 中的 `record_id` 列值不映射到 `NoteRecord.id`（新 ID 由 DB 分配）。
- R15. 每行非排除列的单元格值写入 `NoteDwtableItem`：`recordId` = 该行新创建的 `NoteRecord.id`，`dwtId` = 当前目标表 id，`columnId` = 经列名映射得到的目标表列 id，`value` = SQL 中该列的值（`NULL` 时默认 `""`，与 `insertNoteRecord` 中 `item.setValue` 行为一致）。
- R16. `NoteDwtableItem` 的 link 字段（`linkRecordId`/`linkItemId`/`linkColumnId`/`linkBlockId`/`linkNoteId`）不导入值，留空（这些字段属于排除类型列的存储字段）。
- R17. SQL 中的 `record_id` 列（首列 `BIGINT`）仅用于行内多列归属同一行的分组，不写入数据库。

### 事务与失败处理

- R18. 整次导入（`.zip` 内全部 `.sql`、全部行、全部单元格）包在单个 `@Transactional` 内。
- R19. 任一步骤失败（解析失败、列映射异常、DB 写入异常、归属校验失败等）即抛 `ServiceException`，整个事务回滚。
- R20. 失败时返回前端错误响应：HTTP 200 + JSON `{code:500, msg:"<失败原因>"}`，复用 RuoYi 全局异常处理（与导出 API 失败响应模式一致）。

### 成功与失败 UX

- R21. 导入成功：弹框提示"导入成功"，用户点击"确定"后回调刷新当前多维表格数据（重新加载表行与单元格）。
- R22. 导入失败：弹框提示失败原因（后端返回的 `msg`），用户点击"确定"后无需额外清理（事务已回滚）。

### 安全与限流

- R23. 导入按当前用户对当前多维表格的访问权限校验，复用既有归属校验机制（对称于导出）。
- R24. 导入端点纳入既有速率限制，对称于导出的 `@RateLimiter`；具体限流参数归规划阶段。
- R25. `.zip` 导入需设置最大解压后总字节数与最大压缩比上限（zip-bomb 防护），超出即拒绝并返回失败响应（复用 R20 失败响应模式）；具体阈值归规划阶段。
- R26. 多维表格单元格为用户自由输入、可能含 PII；导入处理与日志（含 R10 跳过日志）一律不记录单元格值，仅记录列名、行号、跳过/失败原因等元数据。

### 客户端断连与取消

- R27. 导入在服务端跑至事务完成，无视客户端断连；本轮迭代不提供客户端取消入口。成功/失败弹框为 best-effort——若用户在导入过程中离开页面，下次加载页面时表格反映已提交状态（已成功则新记录可见，已回滚则无变化），不再补弹框。

---

## Key Flows

- F1. 导入触发流
  - **Trigger:** 用户在多维表格页选定当前数据表，点击导入按钮，选择 `.sql` 或 `.zip` 文件。
  - **Actors:** A1
  - **Steps:** 前端发起导入请求携带 noteId、dwtableId、文件（multipart）；后端校验用户对该 noteId/dwtableId 的访问权限；后端按文件类型分流（`.sql` 直接解析，`.zip` 解压后按文件名升序处理）；后端解析每个 `.sql` 的 `INSERT` 语句提取列名与值；后端查询目标表 `NoteColumn` 列定义，按列名映射并跳过排除类型列；对每行 `INSERT` 创建 `NoteRecord`（默认值规则）+ 批量 `NoteDwtableItem`（值或默认）；全部写入在单个事务内，任一步骤失败抛 `ServiceException` 触发回滚；成功返回成功响应，失败返回错误响应。
  - **Covered by:** R1, R2, R3, R4, R5, R6, R7, R8, R9, R12, R13, R14, R15, R17, R18, R19, R20, R23, R24

---

## Acceptance Examples

- AE1. round-trip 完整导入
  - **Covers R5, R6, R9, R13, R14, R15, R21.**
  - **Given:** 用户从某数据表 T 导出 SQL 得到 T.sql，含 `CREATE TABLE` 与 3 行 `INSERT`（首列 `record_id` = 10/11/12，其余列名与目标表列名一致）。
  - **When:** 用户在 T 表（或同 schema 的新实例 T 表）点击导入，选择 T.sql。
  - **Then:** 导入成功，目标表新增 3 条 `NoteRecord`（id 由 DB 分配，非 10/11/12）+ 对应 `NoteDwtableItem`；`NoteRecord.name` 按 `deriveRecordName` 派生；用户点击"确定"后表格刷新显示 3 条新记录。

- AE2. 列名宽松匹配（SQL 列多于目标表）
  - **Covers R10.**
  - **Given:** SQL 含列 `["record_id", "名称", "状态", "备注"]`；目标表只有列 `["名称", "状态"]`（"备注"在目标表不存在）。
  - **When:** 用户导入该 SQL。
  - **Then:** 导入成功；"名称"与"状态"列的值正常写入；"备注"列的值跳过（后端日志记录）；目标表新增的 `NoteRecord` 关联的 `NoteDwtableItem` 数量 = 2（仅"名称""状态"两列）。

- AE3. 列名宽松匹配（目标表列多于 SQL）
  - **Covers R11.**
  - **Given:** SQL 含列 `["record_id", "名称"]`；目标表有列 `["名称", "状态", "备注"]`（"状态""备注"在 SQL 中不存在）。
  - **When:** 用户导入该 SQL。
  - **Then:** 导入成功；"名称"列的值正常写入；"状态""备注"列不写入 `NoteDwtableItem`（与 `insertNoteRecord` 在 items 缺失时的行为一致）。

- AE4. 排除类型列跳过（SQL 与目标表都有该列）
  - **Covers R12.**
  - **Given:** SQL 含列 `["record_id", "名称", "关联列_ID", "关联列_文本"]`，其中"关联列"在源表是 type=21（双向链接）；目标表"关联列"也是 type=21。
  - **When:** 用户导入该 SQL。
  - **Then:** 导入成功；"名称"列的值写入；"关联列_ID"与"关联列_文本"两列的值均跳过；目标表"关联列"对应的 `NoteDwtableItem` 不写入。

- AE5. 失败回滚（解析失败）
  - **Covers R18, R19, R20, R22.**
  - **Given:** 用户上传一个 `.sql`，`CREATE TABLE` 语法损坏导致 `INSERT` 无法解析。
  - **When:** 后端解析时抛出 ParseException。
  - **Then:** 整个事务回滚（无任何 `NoteRecord`/`NoteDwtableItem` 写入）；前端弹框显示失败原因（如"SQL 解析失败：<具体错误>"）；用户点击"确定"后无需任何额外清理。

- AE6. 失败回滚（中途写入失败）
  - **Covers R18, R19, R20, R22.**
  - **Given:** SQL 含 100 行 `INSERT`；第 50 行的某列值违反约束（如外键不存在）。
  - **When:** 后端写入第 50 行时抛 SQLException。
  - **Then:** 整个事务回滚（前 49 行已写入的 `NoteRecord`/`NoteDwtableItem` 全部回滚）；目标表无任何脏数据；前端弹框显示失败原因；用户点击"确定"即可。

- AE7. zip 多文件追加导入
  - **Covers R5, R8, R21.**
  - **Given:** 用户从一张 5000 行的表导出 SQL 得到 zip，内含 T_p1.sql（1-2000 行）、T_p2.sql（2001-4000 行）、T_p3.sql（4001-5000 行）。
  - **When:** 用户上传该 zip 到当前目标表 T'（同 schema）。
  - **Then:** 后端按文件名升序依次处理 T_p1、T_p2、T_p3；目标表新增 5000 条 `NoteRecord` 与对应 `NoteDwtableItem`；用户点击"确定"后表格刷新显示 5000 条新记录。

- AE8. 排除列类型在目标表但不在 SQL
  - **Covers R12.**
  - **Given:** 目标表 T' 有列 `["名称"（type=1）, "公式列"（type=20）]`；SQL 含列 `["record_id", "名称"]`（不含"公式列"）。
  - **When:** 用户导入该 SQL。
  - **Then:** 导入成功；"名称"列的值写入；"公式列"（type=20）作为排除类型不写入 `NoteDwtableItem`；公式列的值由运行时计算（与现有公式列行为一致）。

---

## Scope Boundaries

### Deferred for later

- Excel/CSV 等非 SQL 格式导入：本次仅接受 SQL，与导出对称。
- 双向链接/语义关联/公式/lookup 列的导入：本次明确排除，留待后续迭代解决跨表关联与运行时计算。
- 导入前 dry-run 预校验：本次用事务回滚兜底，不做预校验环节。
- 重复导入去重：每次导入即新记录，多次导入同一文件产生副本；如需去重另行设计。
- 跨实例 ID 一致性：不保留源 `record_id`，每次导入新分配 ID；如需 ID 稳定迁移另行设计。

### Outside this product's identity

- 跨表 schema 迁移：导入仅写入当前目标表，不创建新表；不基于 SQL 的 `CREATE TABLE` 重建 schema。
- 直接执行任意 SQL：导入仅解析 `INSERT` 的列名与值，不执行任意 SQL（不创建表、不删除、不更新现有数据）。

---

## Dependencies / Assumptions

- 现有 SQL 导出格式（`NoteDwtableSqlRenderer` + `SqlIdentifierSanitizer` + `SqlValueEscaper`）的列名/值规则是导入解析器的契约基础：列名 = `NoteColumn.name` 经 `SqlIdentifierSanitizer.sanitize` 反引号包裹（保留中文），导入解析需剥离反引号后做 `NoteColumn.name` 匹配。
- 现有 `NoteRecordServiceImpl.insertNoteRecord`（L83-139）的默认值规则与 `deriveRecordName` 派生逻辑是导入默认值的契约基础；导入复用其行为，实现上可能直接调用 `insertNoteRecord` 或重新实现相同逻辑，归规划阶段。
- 现有 `NoteDwtableExportPivotServiceImpl` 中的列类型常量（`TYPE_DOUBLE_LINK=21L`、`TYPE_SEMANTIC_LINK=25L`、`TYPE_LOOKUP=26L`）与 `NoteColumn` 注释（type=20 公式、type=23 数学公式）定义了排除类型清单；导入实现需复用这些常量。
- 现有 `AgentOwnershipChecker` 可复用于导入归属校验，对称于导出。
- 数据库为 MySQL（与导出一致）；导入 `INSERT` 解析需处理 MySQL 方言（反引号、`NULL`、字符串引号等）。
- RuoYi 全局异常处理使后端 `ServiceException` 返回 HTTP 200 + JSON `{code:500, msg}`；前端按导出 API 模式（Content-Type 检测）解析失败响应。
- 现有导出端点 `/system/dwtable/exportData` 为导入端点的对称参考（路径建议 `/system/dwtable/importData`，归规划阶段）。

---

## Outstanding Questions

### Resolve Before Planning

- 无。所有阻塞决策已在对话中明确。

### Deferred to Planning

- 导入 API 端点路径（建议对称 `/system/dwtable/importData`）：归规划阶段。
- 导入 API 的 multipart 文件大小上限：归规划阶段（参考 RuoYi multipart 默认与导出 zip 大小）。
- 速率限制参数（对称导出 60s/10 次）：归规划阶段。
- SQL `INSERT` 解析的具体方案（自写解析器、JSqlParser 库、或正则）：归规划阶段。
- `NoteRecord.name` 是否调用 `deriveRecordName` 还是直接调用 `insertNoteRecord` 整体路径：归规划阶段。
- 排除类型列在目标表中已有值是否清空：本次默认不写入（保持原值或留空），行为细节归规划阶段。
- "导入成功"弹框的具体文案：归规划阶段。
- 列名匹配是否区分大小写：本次默认区分（与 `NoteColumn.name` 存储一致），如有特殊需求归规划阶段。
- 列名归一化规则（去反引号后的字符串比较是否需进一步 normalize，如全角/半角、trim）：归规划阶段。

---

## Sources / Research

- `docs/brainstorms/2026-08-06-multitable-export-requirements.md` — 现有 SQL 导出功能需求文档，导入的对称对照。
- `CONCEPTS.md` — NoteDwtableItem（type=21 列存 `linkRecordId`/`linkColumnId`）、Lookup 列(type=26，link 字段为 NULL) 的存储模型。
- `notepad/src/components/baseTable/index.vue` L14-23 — 现有导出按钮位置与 disabled 联动逻辑（导入按钮左侧新增的参考）。
- `notepad/src/components/baseTable/index.vue` L162-186 — 现有导出格式选择弹窗（导入不复用，因无格式选择需求）。
- `notepad/src/api/export.ts` — 导出 API 封装（POST `/system/dwtable/exportData`，Content-Type 检测模式），导入 API 对称参考。
- `ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/NoteDwtableController.java` L97-119 — 导出端点 `exportData` 实现，导入端点对称参考。
- `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteDwtableSqlRenderer.java` — SQL 渲染器，定义 `CREATE TABLE` + `INSERT` 结构、`SHARD_SIZE=2000` 分片规则、列名经 `SqlIdentifierSanitizer` 包裹。
- `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/SqlIdentifierSanitizer.java` — 列名/表名净化工具，保留中文与反引号规则（导入解析反向处理）。
- `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/SqlValueEscaper.java` — SQL 值转义工具（导入解析时反向处理）。
- `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteDwtableExportPivotServiceImpl.java` L43-78 — 列类型常量定义（`TYPE_DOUBLE_LINK=21L`、`TYPE_SEMANTIC_LINK=25L`、`TYPE_LOOKUP=26L` 等）。
- `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java` L83-139 — `insertNoteRecord` 实现，定义默认值规则与 `deriveRecordName` 派生逻辑（导入复用契约）。
- `ruoyi-system/src/main/java/com/ruoyi/system/domain/NoteRecord.java` — 行实体字段（`viewId`/`property`/`linkRecordId`/`sort`/`name`/`dwtableId`/`linkName`）。
- `ruoyi-system/src/main/java/com/ruoyi/system/domain/NoteDwtableItem.java` — 单元格实体字段（`dwtId`/`columnId`/`recordId`/`value`/`linkRecordId`/`linkItemId`/`linkColumnId`/`linkBlockId`/`linkNoteId`）。
- `ruoyi-system/src/main/java/com/ruoyi/system/domain/NoteColumn.java` L39-42 — 列类型注释（type=20 公式、type=23 数学公式），排除类型清单的来源。

## Deferred / Open Questions

### From 2026-08-25 review

- **Single-transaction import lacks acknowledged scale ceiling.** — R18 事务与失败处理 (P2, adversarial, confidence 75)

  R18 规定整次导入（`.zip` 内全部 `.sql`、全部行、全部单元格）包在单个 `@Transactional` 内，但未声明单事务规模上限；AE7 验收用例为 5000 行，超大单事务导入（数十万行）可能撑爆 undo log 或长时间持有锁。无具体修复方案——观察项，留待规划阶段评估是否需要分批或上限保护。
