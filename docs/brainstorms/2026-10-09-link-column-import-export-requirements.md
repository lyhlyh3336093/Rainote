---
date: 2026-10-09
topic: link-column-import-export
---

# 链接列导入导出双向适配需求（双向链接 / 语义链接）

## Summary

Excel 导入导出对双向链接（type=21）与语义链接（type=25）两种列的双向适配：导出把链接列写成人类可读名称（双向链接=目标记录名，语义链接=引用来源的笔记名+锚词）；导入按名称解析重建链接（预检容错匹配 + 失败报告零写入），完整还原双向链接的对称写回与语义链接的 NoteNotelink 行；替换式导入重建记录后，其他表指向本表的入链按名称自动重指向新记录。目标是导出物可再导入无损往返。

## Problem Frame

现状三条断层让链接列无法往返：导出对链接列输出"名称 + `_ID`"双列，但导入侧忽略 `_ID` 列、18/21 关联列一律靠 `列名_文本` 后缀匹配——两边约定不咬合；语义链接列被导入硬排除（"语义关联列不参与导入"），导出有、导入无；替换式导入对其他表的入链做整列清空（连显示值一并抹除），重导一次表、他表关联数据陪葬。

## Key Decisions

- **可读名称制 + 机器锚点副列。** 导出链接列以人类可读名称为主列，同时保留 `_ID` 副列；导入优先按 ID 精确重建链接，名称解析仅作为外部手写数据的回退路径。（2026-10-10 裁决：重名/空名/基列三项挂起问题由此消解，零损失可无条件达成。）
- **失败报告 + 容错匹配。** 解析不了就进结构化失败清单、整表零写入；解析器做容错（唯一前缀、去空格），能唯一确定即通过，歧义按失败处理。注：这是 21/25 列的**新设**行为而非沿用——"缺参一律失败"现状仅存在于 agent 路径，界面路径 18/21 未命中现有弹框补选救援；18 列的弹框补选维持不变。
- **入链按名称重指向。** 替换式导入重建记录后，其他表指向本表旧记录的入链按名称重指向新记录——取代现状的"配对列整列清空"，把破坏性行为升级为精准修复。
- **仅 Excel 链路。** SQL 导入维持现状（18/21/25 排除、link 字段不搬运）。type=18 关联列与 21 共用匹配机制，适配会动共享代码，但 18 的用户可见行为不变。

## Requirements

**导出侧**

- R1. 双向链接列导出为名称主列（单元格写目标记录名，多个目标以统一分隔符连接；名称含分隔符时导出须转义，导入先还原转义再逐名匹配，无法安全往返的按 R7 歧义失败处理）+ `列名_ID` 副列；表头命名与导入侧列识别沿用现有双列形态，导出/导入两侧必须一致。
- R2. 语义链接列导出为单个名称列：语义链接方向为"引用笔记 → 本表单元格"（库内实证），单元格写引用来源的笔记名并保留锚词文本（contextText/item.value）以维持词级显示保真；一个单元格多条链接（现存数据形态）以统一分隔符连接多条目，含分隔符的笔记名按 R1 同款转义。
- R3. `_ID` 副列保留且导入侧开始消费（优先按 ID 精确重建；`_ID` 与名称列不一致时以 ID 为准并按外部编辑处理名称）。
- R4. 导出遇到悬空引用（目标记录/表已不存在）时写空单元格，不报错不阻断。

**导入侧**

- R5. 双向链接列导入按记录名解析目标记录，重建 linkRecordId/linkColumnId/linkItemId 并复用现有对称写回机制。
- R6. 语义链接列导入按笔记名解析引用来源笔记（noteId），以本表新记录与新单元格坐标为目标逐条目重建 NoteNotelink 行（linkDwTableId=本表、linkColumnId/linkItemId=本表新坐标、linkNoteId 写入本表归属笔记的规范值——历史 null 的 COALESCE 兜底规则不适用于新建行）；任一条目解析失败并入 R8 失败清单。Excel 无法还原 blockId 词级定位，接受锚点降级。
- R7. 名称解析做容错匹配：整串优先（先整串匹配以处理记录名含分隔符，未命中再按分隔符拆分）、精确优先，其后按容错规则（去空格、唯一前缀等，细则规划阶段定）；多命中歧义按失败处理。
- R8. 导入文件中任一链接名称解析失败时，预检弹框呈现结构化失败清单（单元格定位 + 原因 + 容错命中说明），该次导入零写入（21/25 列的强失败为新设行为，取代现状界面路径的弹框补选；入链重指向失败按 R12 处理）。
- R9. 多值单元格逐名解析，全部命中才通过；任一失败并入失败清单。
- R10. agent 导入路径与界面路径行为一致：链接列同样按名称解析，失败时同样返回结构化清单（18 列缺参须改走界面的既有边界保持不变；21/25 列未命中按失败报告处理，修正文件后重导）。

**入链重指向**

- R11. 替换式导入重建记录后，其他表中指向本表旧记录的双向链接入链与 18 关联列入链、以及 NoteNotelink 中指向本表旧记录的语义链接行，按名称重指向新记录。
- R12. 重指向失败区分两类（2026-10-10 裁决）：名称回退路径遇重名歧义时并入 R8 失败清单、该次导入零写入；仅目标记录确已不存在（如被用户从 Excel 删除）时清空该入链单元格（单元格级全清：链接字段与 value 显示值一并清除；语义链接为删除对应 NoteNotelink 行）并在导入结果中报告，导入不中断。
- R13. 重指向取代现状的整列清空（kept 列配对列与他表指向本表的 18 列一并被取代）；被删除列的级联删除（删列删配对列）维持现状语义。

## Key Flows

- F1. 双向链接往返
  - **Trigger:** 用户导出含双向链接列的表，之后（可经外部编辑）再导入。
  - **Steps:** 导出名称列 → 导入解析记录名 → 命中即重建链接并对称写回。
  - **Outcome:** 链接完整恢复，配对列两侧互指一致。
  - **Covers:** R1, R5, R7, R9
- F2. 语义链接往返
  - **Trigger:** 导出含语义链接列的表后再导入。
  - **Steps:** 导出引用笔记名+锚词 → 导入按笔记名解析来源、以本表新坐标逐条目重建 NoteNotelink 行（linkNoteId 为规范值）。
  - **Outcome:** 语义链接恢复且 URL 跳转正常。
  - **Covers:** R2, R6
- F3. 替换重导入重指向
  - **Trigger:** 替换式导入重建了本表记录（新 id）。
  - **Steps:** 收集他表入链与 notelink 旧行 → 按名称映射到新记录 → 失败者清空并报告。
  - **Outcome:** 他表数据不再被整列抹除，入链精准迁移。
  - **Covers:** R11, R12, R13
- F4. 失败报告
  - **Trigger:** Excel 中链接名称写错或目标缺失。
  - **Steps:** 预检解析 → 汇总失败单元格 → 弹框清单。
  - **Outcome:** 零写入，用户按清单修正后重导。
  - **Covers:** R8, R10

## Acceptance Examples

- AE1. **双向链接往返（Covers R1, R5, R9）** — Given 表 A 双向链接列指向表 B 记录"满攻"；When 导出 A 后不做修改再导入；Then 链接恢复且表 B 配对列出现对称引用。
- AE2. **语义链接往返（Covers R2, R6）** — Given 笔记X 的词级锚点链接到本表某单元格（语义链接列有值）；When 导出后再导入；Then NoteNotelink 行重建、目标坐标指向本表新记录、linkNoteId 为规范值、跳转 URL 正常。
- AE3. **入链重指向（Covers R11, R12）** — Given 表 B 有指向表 A 记录"满攻"的入链；When 替换式重导表 A；Then B 的入链重指向新 id；若"满攻"已不存在，该入链清空且结果含报告。
- AE4. **失败报告（Covers R7, R8）** — Given Excel 中某链接单元格名称写错；When 导入；Then 预检弹框列出该单元格定位与原因，整表零写入。

## Success Criteria

- 导出的表不经修改再导入，链接零损失（双向链接对称一致、语义链接可跳转）。
- 每个解析/重指向失败都有单元格定位与可读原因。
- 现有 18 列导入、SQL 导入、agent 导入回归不破坏。

## Scope Boundaries

- SQL 导入链路维持现状（18/21/25 排除、link 字段不搬运）。
- type=18 关联列的用户可见行为不变。
- 容错匹配的具体规则（前缀下限、大小写、全半角）归规划阶段。
- 不做存量悬空链接的批量体检/修复工具。

## Dependencies / Assumptions

已核实的技术事实（file:line 指针）：
- 双向链接(21)无独立链接表，存于 note_dwtable_item 的 linkRecordId/linkColumnId/linkItemId（逗号多值），配对列经属性 JSON 的 back_field_id 互指（`ruoyi-system/src/main/resources/mapper/system/NoteDwtableItemMapper.xml`）。
- 现有导入 18/21 走 `列名_文本` 后缀文本匹配（`ruoyi-system/src/main/java/com/ruoyi/system/service/impl/ExcelColumnMatcher.java` TEXT_SUFFIX），21 有对称写回 executeSymmetricWrite 与 clearLinkFieldsByColumnId 悬空清理（`NoteDwtableExcelImportServiceImpl.java`）。
- 语义链接(25)在 Excel 导入 EXCLUDED_TYPES 硬排除、原文"语义关联列不参与导入"（`ExcelColumnMatcher.java`）；SQL 导入排除 18/21/25（`NoteDwtableImportServiceImpl.java`）。
- 现有导出双列：18/21 输出 linkRecordId+记录名、25 输出 linkNoteId(COALESCE)+笔记标题（`NoteDwtableExportPivotServiceImpl.java`）；`_ID` 列被导入侧忽略。
- 语义链接本体在 note_notelink：引用源由 noteId/blockId 标识（笔记与块），目标单元格由 linkDwTableId/linkColumnId/linkItemId/linkRecordId 标识且指向语义链接列所在表（库内 19/19 条实证）；历史行 linkNoteId 可为 null、查询需 COALESCE 兜底（`NoteNotelinkMapper.xml`）。
- 替换导入现状清空会连带抹掉他表列的 value 显示值（`NoteDwtableItemMapper.xml` clearLinkFieldsByColumnId）；kept 列的 notelink 中指向旧记录的引用现状不清理（仅清被删列）——本需求 R11 覆盖此缺口。
- agent 导入路径现状：关联列缺参无法文本补值、须改走界面（`NoteDwtableExcelImportServiceImpl.java` agent 入口段）。

## Outstanding Questions

**Deferred to Planning**

- 容错匹配规则明细：前缀匹配的最小长度、大小写与全半角归一、去空格范围。
- 多值分隔符选型（含转义约定）：导出/导入两侧统一，并与现有 `列名_文本` 匹配的既有分隔约定对齐。
- 新名称列的表头命名与导入侧列识别约定：沿用列原名 vs `列名_文本` 后缀（与 18 列共用匹配机制的层次），导出/导入两侧必须一致。
- 重指向的执行时机与事务边界：与替换导入既有"删旧建新"阶段的先后关系。

## Sources

- `CONCEPTS.md` — Double Link Column (type=21)、Semantic Link Column (type=25)、back_field_id、Import Precheck 条目
- `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/ExcelColumnMatcher.java`、`NoteDwtableExcelImportServiceImpl.java`、`NoteDwtableExportPivotServiceImpl.java`、`NoteDwtableImportServiceImpl.java` — 导入导出现状
- `ruoyi-system/src/main/resources/mapper/system/NoteDwtableItemMapper.xml`、`NoteNotelinkMapper.xml` — 链接数据存储与清理 SQL
- `docs/solutions/database-issues/notelink-linknoteid-null-recovery.md` — linkNoteId 历史兜底规则

## Deferred / Open Questions

### From 2026-10-09 review

- **重名记录使纯名称制"零损失"不可达，是否恢复机器锚点** — Key Decisions / Success Criteria (P1, product-lens + adversarial, confidence 100)

  记录名无唯一性约束，表内任意两条同名记录会让"未经修改的往返"也整表失败；而导出现状本就携带精确锚点（首列 record_id 与 `_ID` 副列），废弃它们等于为省一列可读性牺牲核心目标，再建一整套容错规则去追回本可直接恢复的信息。候选处置：恢复"ID 优先、名称回退"（名称列保留可读、`_ID` 副列恢复且导入侧开始消费），或维持纯名称制并把成功标准限定为"名称可唯一解析的链接零损失"。涉及 brainstorm 阶段已确认的"可读名称单列制"决策，需用户裁决。

  **已裁决（2026-10-10）**：恢复"ID 优先、名称回退"——名称主列保留可读、`_ID` 副列保留且导入侧开始消费；空名/基列/零损失口径三项挂起问题随之消解。

- **重指向失败"清空他表入链"与零写入保护哲学冲突** — R12 (P1, coherence + product-lens + adversarial, confidence 75)

  被导入表获"任一失败零写入"的最强保护，旁观表反遭破坏性写入（清空不可逆），单用户场景下导入结果报告易被忽略。三个候选：仅澄清零写入范围限于导入文件内；歧义按 R8 失败报告、仅"目标确已不存在"才清空；一律保留悬空引用仅报告（可逆，与 R4 悬空导出语义衔接）。此为 brainstorm 已确认默认（清空+报告）的重开审视，需用户或规划阶段裁决。

  **已裁决（2026-10-10）**：歧义进失败清单（零写入）、仅目标确已不存在才清空+报告；重指向本身以 ID 精确映射为主路径（导入文件自带 record_id 首列，名称回退仅是外部编辑场景的兜底）。

- **"链接零损失"成功标准口径与重名前提未记录** — Success Criteria (P1, adversarial, confidence 100)

  机制事实应入 Dependencies：记录名由首个文本列派生、无唯一性约束、现有导入已有同名预选+报告机制。成功标准需按机器锚点裁决结果限定口径并补重名场景验收示例；"21 列歧义是否沿用 18 列的预选+报告而非整表失败"应列为规划必答。

- **空名记录的链接将静默丢失** — R1 / R9 (P1, product-lens, confidence 75)

  无文本列的表所有记录名为空；纯名称制下导出写空单元格、导入按惯例当作"无链接"，失败清单不触发。若机器锚点裁决为 ID 优先，空名可经 ID 还原，该问题消解；若维持纯名称制，需占位符+失败清单方案。

- **记录名派生基列跨替换导入不稳定** — R11 / R12 (P1, adversarial, confidence 75)

  记录名是"替换后首个文本列"的派生值；首个文本列被隐藏/删除时零编辑往返即可让名称基列整体切换、重指向大面积失败，复现"他表陪葬"。若机器锚点裁决为 record_id 映射，该问题消解；若维持名称重指向，R11 需增加基列一致性前置校验。
