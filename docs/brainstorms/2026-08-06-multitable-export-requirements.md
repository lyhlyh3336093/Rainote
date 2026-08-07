---
date: 2026-08-06
topic: multitable-export
---

# 多维表格导出功能需求文档

## Summary

为多维表格增加导出能力：用户选择 Excel 或 SQL 格式，把所选多维表格（笔记）下每张数据表导成 sheet（Excel）或 `CREATE TABLE` + `INSERT`（SQL），整体以压缩包下载。超过行阈值的表按行分片，确保导出物在目标库执行后能完整恢复显示数据。关联与派生类列双列输出原始 ID 与可读文本，为后续离线分析与展示迭代打底。

## Problem Frame

多维表格的数据以 EAV 形式存储在 `note_dwtable_item`（每行一个单元格：`recordId` + `columnId` + `value` 及各类 link 字段），前端展示靠 pivot 成行×列矩阵。当前没有把 pivot 后数据导出为文件的能力：`NoteDwtableController` 既有 `/export` 只导出表元数据列表（id/name/url），与单元格数据无关；`selectNoteDwtableDataById` 是未完成的桩（for 循环体为空，直接返回空 map），后端不存在可复用的 pivot 服务。

用户的首要场景是离线分析，且本轮导出物是下一轮"分析与展示"迭代的基础数据层。因此导出必须在保真与可读之间取得平衡——纯原始 ID 对 Excel 分析不直观，纯可读文本则丢失跨表关联、无法在 SQL 中 join。

## Key Decisions

- **导出粒度为一个多维表格的全部数据表**：选一个多维表格（笔记 noteId），把它下所有 `NoteDwtable` 一次导出，整个导出物以压缩包形式下载。每张 `NoteDwtable` 在 Excel 中是一个 sheet，在 SQL 中是一张 `CREATE TABLE`。符合 `noteId` = 多维表格 ID 的既有模型与 Airtable/Bitable 心智。

- **超限表按行分片，压缩包组织**：单表记录数超过 2000 行触发按行分片，每 2000 行一片，确保同步导出在 60s HTTP 超时内完成。分片以压缩包组织，分片关系与执行顺序由文件名编码（按片号排序执行），另含一个索引文件作为元数据 manifest（承载导出来源、格式、表清单与记录数、生成时间戳等文件名无法表达的信息）。阈值只看行数，不限列数。导出物在目标库执行后须能完整恢复显示数据。

- **关联/派生类列双列输出（原始 ID + 解析文本）**：双向关联(21)、单向关联(18)、lookup(26)、集合运算(24)、语义关联(25) 这些列在导出中渲染为两列——一列原始引用 ID（逗号分隔），一列对应记录的可读名称。兼顾基于 `FIND_IN_SET` 的 SQL 查询（非标量 JOIN）与 Excel 可读，代价是这些列在导出物中翻倍。

- **三种派生/特殊列的 ID 列语义已约定**：
  - **lookup(26)**：ID 列 = 底层双向关联列的 `linkRecordId`（lookup 自身不存 `linkRecordId`，需经 `property.double_link_column_id` 回溯到底层双向关联列取值）；文本列 = `source_column_id` 在那些记录上解析出的值。
  - **集合运算(24)**：ID 列 = 集合运算后的 `linkRecordId` 结果列表；文本列 = 这些记录 id 回查到的可读名称。
  - **语义关联(25)**：ID 列 = `linkNoteId`（去重，历史 null 行按 CONCEPTS.md 既定规则 `COALESCE(linkNoteId, NoteDwtable.noteId)` 兜底）；文本列 = 对应笔记的标题/名称。弃用 `NoteNotelink.id`（内部锚点 id，对离线分析无价值）与 `linkNoteId+linkDwTableId+linkRecordId` 三元组（冗余笨重）。

## Actors

- A1. **多维表格使用者**：在多维表格页操作导出，选择格式（Excel/SQL）并触发下载。是唯一的人类触发者。

## Requirements

### 导出范围与触发

- R1. 用户在多维表格页可选择一个多维表格（noteId），选择导出格式为 Excel 或 SQL，触发导出。
- R2. 导出范围限定为该 noteId 下全部 `NoteDwtable`（数据表），不含其他笔记的表。每张数据表作为一个独立导出单元。
- R3. 导出同步执行，整个导出物以压缩包形式下载给浏览器。不在本次支持异步后台任务。

### 分片与压缩包

- R21. 单表记录数超过 2000 行触发按行分片，每 2000 行一片。阈值只看行数，不限列数。
- R22. 整个导出物以压缩包形式组织，内含每张表的导出文件（合格表单文件、超限表多分片文件）与一个索引文件（manifest）。
- R23. 索引文件作为元数据 manifest，承载文件名无法表达的信息：导出来源（noteId）、格式、数据表清单与各自记录数、生成时间戳、schema 版本。分片关系与执行顺序由文件名编码（按片号排序执行即可还原），不依赖索引文件——索引文件为人工/编排参考，非数据恢复所必需。索引文件的具体格式归规划阶段。
- R24. Excel 模式下，整个导出物为单个 Excel 工作簿文件；超限表拆为多个 sheet，命名为"表名_p1""表名_p2"等，每片 2000 行；合格表单 sheet，名为数据表名。
- R25. SQL 模式下，整个导出物为压缩包含多个 `.sql` 文件；超限表拆为多个 `.sql` 文件，命名为"表名_p1.sql""表名_p2.sql"等；首片含 `CREATE TABLE` 语句，后续片为纯 `INSERT` 语句；合格表单 `.sql` 文件含一段 `CREATE TABLE` + `INSERT`。
- R26. 分片按 `NoteRecord.sort` 顺序切分，保证分片可按序拼接还原完整数据，不丢失或重复记录。

### 数据形态

- R4. 每张数据表按行×列矩阵导出，行来自 `NoteRecord`（按 `sort` 排序），列来自该表的 `NoteColumn`。
- R5. 列按 `NoteColumn.sort` 排序；`isShow` 为"隐藏"的列是否导出由规划阶段决定，但默认行为需在文档明确——本需求规定隐藏列默认不导出，仅导出 `isShow` 为显示的列。
- R6. 每张数据表必须输出一个记录 id 列（对应 `NoteRecord.id`），作为 SQL 中唯一可 JOIN 的标量锚点（关联/派生类 ID 列为逗号分隔多值，只能 `FIND_IN_SET`，不可 JOIN）。此为派生自双列决策的硬性要求，否则关联列的原始 ID 悬空。

### 列类型渲染

- R7. 关联/派生类列（类型 21 双向关联、18 单向关联、26 lookup、24 集合运算、25 语义关联）渲染为双列：一列原始 ID（逗号分隔），一列解析后的可读文本。注意：ID 列因逗号分隔多值，在 SQL 中只能用 `FIND_IN_SET` 查询，不构成可 JOIN 的标量外键；若需标量 JOIN，应基于 R6 的记录 id 列。列名需区分（如"列名_ID"与"列名_文本"），具体命名约定归规划阶段。
- R8. lookup(26) 的 ID 列取底层双向关联列的 `linkRecordId`（经 `property.double_link_column_id` 回溯），不取 lookup 自身 item 的 link 字段（CONCEPTS.md 明确其为 NULL）。
- R9. 集合运算(24) 的 ID 列取集合运算后的 `linkRecordId` 结果列表。
- R10. 语义关联(25) 的 ID 列取 `linkNoteId`（去重），历史 null 行按 `COALESCE(linkNoteId, NoteDwtable.noteId)` 兜底；文本列取对应笔记的标题/名称。
- R11. 基础类型列（1 多行文本、2 数字、3 单选、4 多选、5 日期、7 复选框、11 人员、13 电话号码、15 超链接、17 附件、22 地理位置）直接输出 `value` 字段，不截断、不丢失。附件列(17)输出原始 `value`（保真优先，其具体存储格式——JSON/URL/文件名——需规划阶段核实，解析优化可选）。大文本列(1)不截断完整输出，超 Excel 单元格上限时的处理见 Dependencies。单选/多选的 `value` 若为选项 id，规划阶段决定是否解析为选项文本（默认解析）。
- R12. 系统列（1001 创建时间、1002 最后更新时间、1003 创建人、1004 修改人、1005 自动编号）按其语义输出可读值；创建人/修改人输出用户标识（id 或名称，规划阶段决定）。

### Excel 格式

- R13. Excel 模式导出物为单个 Excel 工作簿文件（再打包进压缩包）。每张合格数据表一个 sheet，名为数据表名（`NoteDwtable.name`）；超限表的多 sheet 命名与分片见 R24。
- R14. sheet 首行为列名行，其余为数据行。关联/派生类列在 sheet 中占两列。
- R15. 列名含非 sheet 合法字符时按 Excel 通用规则处理（具体规则归规划阶段）。

### SQL 格式

- R16. SQL 模式导出物为压缩包含多个 `.sql` 文件，每张合格数据表对应一个文件含一段 `CREATE TABLE` + `INSERT`；超限表的多文件与 DDL 分布见 R25。
- R17. 表名取数据表名（`NoteDwtable.name`），列名取 `NoteColumn.name`；命名需做 SQL 标识符合法化（保留字、特殊字符、长度限制），具体规则归规划阶段。
- R18. SQL 类型映射由列类型决定（数字→`INT`/`DECIMAL`、文本→`VARCHAR`/`TEXT`、日期→`DATETIME` 等），目标方言为 MySQL（与现库一致）；关联/派生类列的 ID 列与文本列均映射为 `VARCHAR`（因逗号分隔多值）。此类 ID 列只能用 `FIND_IN_SET` 查询，不可作为标量外键 JOIN；记录 id 列（R6）为 `BIGINT`，是唯一可 JOIN 的标量锚点。

### 双格式共同

- R19. 导出按当前用户对所选多维表格的访问权限校验——用户无权访问该笔记时不允许导出。具体校验路径（复用现有归属校验）归规划阶段。
- R20. 空数据表（无记录）仍导出表结构与列名，不含数据行。

### 安全与审计

- R27. 导出含 PII 列（人员 11、创建人 1003、修改人 1004）的表时，后端须记录审计日志，字段含：导出人、noteId、表名、格式、时间戳、含 PII 标记。复用既有日志通道。
- R28. 导出端点纳入既有速率限制，防止单用户高频拉取含 PII 数据。具体限流参数归规划阶段。

## Key Flows

- F1. 导出触发流
  - **Trigger:** 用户在多维表格页选择一个多维表格，点击导出，选择 Excel 或 SQL。
  - **Actors:** A1
  - **Steps:** 前端发起导出请求携带 noteId 与格式；后端校验用户对该 noteId 的访问权限；后端查询该 noteId 下全部 `NoteDwtable`；对每张表查询其 `NoteColumn`（过滤 `isShow`）与 `NoteRecord` + `NoteDwtableItem`；按列类型渲染规则 pivot 成行×列矩阵；对每张表按记录数判断是否触发分片（R21）；合格表渲染为单文件/单 sheet，超限表按行分片渲染为多 sheet/多文件（R24/R25）；生成索引文件（R23）；打包为压缩包；以压缩包下载形式返回。
  - **Covered by:** R1, R2, R3, R4, R5, R6, R13, R16, R19, R21, R22, R23, R24, R25, R26

## Acceptance Examples

- AE1. 双列渲染
  - **Covers R7, R8.**
  - **Given:** 表 A 有双向关联列指向表 B，表 B 有记录 id=100（名称"张三"）、id=101（名称"李四"）；表 A 某记录的该关联列 `linkRecordId="100,101"`。
  - **When:** 导出表 A。
  - **Then:** 导出物中该关联列占两列，ID 列值为 `100,101`，文本列值为 `张三,李四`。

- AE2. lookup 列回溯
  - **Covers R8.**
  - **Given:** 表 A 有 lookup 列 L，其 `property.double_link_column_id` 指向表 A 的双向关联列 D；表 A 某记录在 D 上的 `linkRecordId="200,201"`；`source_column_id` 在记录 200、201 上的值分别为"产品X""产品Y"。
  - **When:** 导出表 A。
  - **Then:** L 渲染为双列，ID 列值为 `200,201`（取自 D 的 `linkRecordId`，非 L 自身 item），文本列值为 `产品X,产品Y`。

- AE3. 语义关联 ID 取值
  - **Covers R10.**
  - **Given:** 表 A 某记录通过语义关联列关联到笔记 P（id=50，标题"需求文档"）与笔记 Q（id=51，标题"会议纪要"）；对应 NoteNotelink 行的 `linkNoteId` 分别为 50、51。
  - **When:** 导出表 A。
  - **Then:** 该列 ID 列值为 `50,51`，文本列值为 `需求文档,会议纪要`。不出现 `NoteNotelink.id`。

- AE4. 记录 id 列必出
  - **Covers R6.**
  - **Given:** 表 A 有 3 条记录，id 分别为 10、11、12。
  - **When:** 导出表 A。
  - **Then:** 导出物含一个记录 id 列，三行值分别为 10、11、12，位于列名行首位或紧随其后；无论用户是否在 UI 隐藏记录 id 列，导出物始终包含此列。

- AE5. 空表导出
  - **Covers R20.**
  - **Given:** 表 A 有 2 列但 0 条记录。
  - **When:** 导出表 A。
  - **Then:** Excel 中该 sheet 只有列名行；SQL 中含 `CREATE TABLE` 语句但无 `INSERT`。

- AE6. 多表一次导出
  - **Covers R2, R13, R16, R22.**
  - **Given:** 笔记 N（noteId=7）下有数据表 T1、T2 两张，均为合格表。
  - **When:** 用户选择笔记 N 触发 Excel 导出。
  - **Then:** 下载一个压缩包，内含一个 Excel 工作簿文件（含 sheet"T1"与 sheet"T2"）与一个索引文件；若选 SQL，压缩包内含 T1.sql、T2.sql 与索引文件。

- AE7. 超限表按行分片（Excel）
  - **Covers R21, R24, R26.**
  - **Given:** 表 T 有 5000 条记录，按 `sort` 排序后 id 为 1-5000。
  - **When:** 用户导出含 T 的多维表格，选 Excel。
  - **Then:** 工作簿中 T 拆为 3 个 sheet："T_p1"（1-2000 行）、"T_p2"（2001-4000 行）、"T_p3"（4001-5000 行）；三个 sheet 列名行一致；按片号顺序拼接还原 5000 条完整记录，无丢失无重复。

- AE8. 超限表按行分片（SQL）
  - **Covers R21, R25, R26.**
  - **Given:** 表 T 有 5000 条记录，按 `sort` 排序。
  - **When:** 用户导出含 T 的多维表格，选 SQL。
  - **Then:** 压缩包含 T_p1.sql、T_p2.sql、T_p3.sql；T_p1.sql 含 `CREATE TABLE` + 前 2000 条 `INSERT`；T_p2.sql、T_p3.sql 仅含 `INSERT`；按文件名顺序执行三个文件后目标库恢复 T 表全部 5000 条记录。

## Scope Boundaries

### Deferred for later

- 按视图（`NoteView`）筛选/排序导出：本次仅按原始记录与列导出全表，视图的筛选与排序条件不参与。
- 导入功能：仅做导出，不做反向导入。
- 列宽、单元格样式、Excel 公式等导出物美化：本次只保证数据正确，不做样式优化。
- 异步后台导出任务：本次同步导出 + 分片处理超限表，不引入异步机制；如未来单表远超 2000 行的分片仍超时，再评估异步化。

### Outside this product's identity

- 跨多维表格批量导出：一次只导出一个 noteId 下的表，不做多笔记勾选批量。
- 导出为 CSV / JSON 等其他格式：本次仅 Excel 与 SQL。
- 导出物内嵌跨表外键关系图或 ER 图：导出物是数据，不是结构可视化。

## Dependencies / Assumptions

- 现有 `NoteDwtableServiceImpl.selectNoteDwtableDataById` 是未完成桩，导出功能不能直接复用其 pivot 逻辑；规划阶段需决定是补全该方法还是新建独立的导出 pivot 服务。
- 现有 `ExcelUtil`（`ruoyi-common/src/main/java/com/ruoyi/common/utils/poi/ExcelUtil.java`）为注解驱动，所有公开导出方法接收 `List<T>`（T 为带 `@Excel` 的固定 POJO），无支持动态列的公开方法。本需求的列是每表动态的，无法用固定 POJO + `@Excel` 实现，规划阶段需决定动态列导出方案（直接用 POI API 或扩展工具类）。
- 数据库为 MySQL（`com.mysql.cj.jdbc.Driver`，PageHelper `helperDialect=mysql`），SQL 导出方言与现库一致。
- Excel 库为 Apache POI 4.1.2（`pom.xml` 无 EasyExcel 依赖）。
- 现有归属校验机制（`AgentOwnershipChecker`）可复用于导出权限校验。
- Excel 单元格有 32767 字符硬限（POI/Office Open XML 规范）。大文本列(1)不截断完整输出，当 `value` 超过此上限时 POI 会抛异常或截断；规划阶段需决定超限处理（截断并加标记、转存为附件、或接受 POI 行为），目标是不丢失数据且导出不失败。SQL 模式映射为 `TEXT`/`LONGTEXT`，无此约束。
- PII 列（人员 11、创建人/修改人 1003/1004）默认输出原始用户标识以服务离线分析的用户 join 需求；脱敏选项（哈希、替换为昵称）作为 Deferred 项留给规划阶段评估，结合 R27 审计日志与 R28 速率限制共同约束 PII 外流。
- 2000 行分片阈值（R21）为单表口径，基于"单表 × 60s socketTimeout"推导，假设笔记下数据表数量典型（≤5 张）；多表叠加的总导出时间未做最坏情况论证，需规划阶段验证并定义兜底（总行数上限或优雅失败提示）。

## Outstanding Questions

### Resolve Before Planning

- 无。所有阻塞决策已在对话中明确。

### Deferred to Planning

- 隐藏列（`isShow` 为隐藏）是否导出：本需求规定默认不导出，若产品有"导出全部列"开关需求则另议。
- 单选/多选列的 `value` 若为选项 id，是否解析为选项文本：本需求规定默认解析，具体解析路径归规划阶段。
- 创建人/修改人系统列输出用户 id 还是名称：归规划阶段。
- SQL 表名/列名合法化的具体规则（保留字、长度、特殊字符）：归规划阶段。
- Excel sheet 名含非法字符（如 `[]`、`:`）的具体处理规则：归规划阶段。
- 关联/派生类双列的列名命名约定（如"列名_ID""列名_文本"）：归规划阶段。
- 大文本/附件列（类型 17 附件、类型 1 长文本）的截断或外链处理：附件列(17)输出原始 `value`，大文本列(1)不截断完整输出，已定。附件 `value` 的具体存储格式核实归规划阶段。
- PII 列（人员 11、创建人 1003、修改人 1004）是否提供脱敏选项（哈希/替换昵称）：默认输出原始用户标识，脱敏留规划阶段评估。
- 多表叠加总导出时间的最坏情况验证与兜底策略（总行数上限/优雅失败提示）：归规划阶段。
- 索引文件的具体格式（JSON/Markdown/纯文本）与字段结构：归规划阶段。
- 压缩包命名规则与内部目录结构：归规划阶段。
- 分片行数阈值（2000）是否需按实际性能测试结果调整：归规划阶段验证。

## Sources / Research

- `CONCEPTS.md` — 领域词汇表，定义 NoteDwtable、NoteDwtableItem、NoteColumn、双链列(type=21)、lookup 列(type=26)、集合运算列、语义关联列(type=25) 的存储模型与 `linkNoteId` 历史兜底规则。
- `ruoyi-system/src/main/java/com/ruoyi/system/domain/NoteDwtable.java` — 数据表实体，`noteId` 为归属笔记外键。
- `ruoyi-system/src/main/java/com/ruoyi/system/domain/NoteDwtableItem.java` — EAV 单元格存储，含 `recordId`/`columnId`/`value`/`linkRecordId`/`linkColumnId`/`linkItemId`/`linkBlockId`/`linkNoteId`。
- `ruoyi-system/src/main/java/com/ruoyi/system/domain/NoteColumn.java` — 列定义，`type` 枚举含 21 种业务类型（1-26）与 5 种系统列（1001-1005），含 `isShow`/`sort`/`property`（JSON）。
- `ruoyi-system/src/main/java/com/ruoyi/system/domain/NoteRecord.java` — 行实体，含 `sort`（行序）、`name`、`dwtableId`（直接归属数据表）。
- `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteDwtableServiceImpl.java` — `selectNoteDwtableDataById` 为未完成桩（L72-105，for 循环体空，返回空 map）。
- `ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/NoteDwtableController.java` — 现有 `/system/dwtable/export` 仅导出表元数据列表，非单元格数据。
- `ruoyi-common/src/main/java/com/ruoyi/common/utils/poi/ExcelUtil.java` — 注解驱动的 Excel 工具类，无动态列导出方法。
- `ruoyi-admin/src/main/resources/application.yml` — MySQL 驱动与方言；HTTP `socketTimeout: 60000`（60s）是分片阈值的约束依据。
- `pom.xml` / `ruoyi-common/pom.xml` — Apache POI 4.1.2，无 EasyExcel。
