---
date: 2026-07-17
topic: cross-table-semantic-link
---

## Summary

在多维表格语义关联列(type=25)打开的笔记弹框中,新增"划词关联其他多维表格"能力:用户在 note modal 内划词后,通过工具栏第二个按钮触发跨表选择器,选择目标表格的语义关联列与记录,建立完整双向语义关联。数据层零改动,复用现有 NoteNotelink 模型与 U1-U6 反向追溯机制;改动集中在前端新增内联工具按钮与扩展 SemanticSelectComponent。

## Problem Frame

当前 note modal(`baseTable/index.vue` 的"语义关联"弹框)内的划词关联走 `SemanticLink` 工具的 `isModal=true` 分支:划词后直接用 config 中固定的 `tableId/recordId/linkColumnId/linkItemId`(即"当前表格的当前单元格")创建关联,**跳过了表格选择器**。这意味着用户在 note modal 里只能把划词关联到当前单元格,无法关联到其他多维表格的记录。

项目里已存在 `SemanticSelectComponent`(`editorjs/components/SemanticLink/index.vue`),支持跨表选择(拉 `/system/dwtable/list` → 选表格 → 选记录),但它**只在普通 doc 场景**(`isModal=false`)被使用,且不写入 `linkColumnId/linkItemId`,因此不建立反向追溯。

数据层 `NoteNotelink` 已具备 `linkDwTableId/linkRecordId/linkColumnId/linkItemId` 字段,完全支持关联到任意表格的任意语义关联列单元格。缺口在前端:note modal 场景缺少触发跨表选择的入口,且选择器组件不处理"选语义关联列"步骤。

本次补齐:note modal 内新增独立入口触发跨表选择,选择器扩展为"选表格 → 选语义关联列 → 选记录",复用 U1-U6 反向追溯让目标表格侧自然显示笔记锚点。

## Key Decisions

- **工具栏新增第二个内联工具按钮,与原"语义关联"按钮职责分离。** 原 `SemanticLink` 按钮保留,只做当前单元格快捷关联(`isModal=true` 现有分支零改动);新增按钮触发跨表选择器。两个按钮各自独立,避免在一个按钮里塞两种模式导致交互混淆。
- **完整双向语义关联,不降级为单向。** 新功能创建的 NoteNotelink 的 `linkColumnId/linkItemId` 指向**目标表格**的语义关联列(而非当前表格),`linkDwTableId/linkRecordId` 指向目标记录。这样目标表格侧的 U2 聚合查询、U5 引用面板、U6 删除级联会自然生效,无需改动已实现的反向追溯逻辑。
- **严格预校验:只允许选已有语义关联列(type=25)的表格。** 没有语义关联列的表格在选择器里不可选或选后报错提示;有多个语义关联列时让用户选具体列。保证每条关联都能在目标表格侧正常反向追溯,避免"关联创建了但目标表格无处显示"的悬空数据。
- **表格选择器包含当前表格并置顶。** 用户可关联到当前表格的其他记录(其他行),不止于"不同的多维表格"。当前表格置顶便于快捷选择,但允许用户选其他表格。

## Requirements

**入口与触发**

1. note modal 内的 EditorJS 内联工具栏新增第二个按钮(区别于现有"语义关联"按钮),用户划词后点击该按钮触发跨表关联流程。
2. 新按钮触发的流程独立于现有 `isModal=true` 快捷关联分支,不改动原按钮的行为与数据路径。

**目标表格选择**

3. 跨表选择器展示多维表格列表,当前表格置顶显示,其余表格按现有顺序排列。
4. 选择器只展示含有语义关联列(type=25)的表格;无语义关联列的表格不可选或选后给出"该表格需先创建语义关联列"提示。
5. 选定表格后,若该表格有多个语义关联列,提供选列步骤让用户指定具体列;只有一个时自动选用。

**关联创建与数据**

6. 用户选定"表格 + 语义关联列 + 记录"后,选中词被包裹为现有 `<a data-type="semantic">` 锚点,锚点带 `data-link-id` 承载新创建的 NoteNotelink 主键。
7. 创建的 NoteNotelink 记录:`noteId/blockId/contextText` 来自当前笔记与划词位置;`linkDwTableId/linkRecordId` 指向目标表格的目标记录;`linkColumnId/linkItemId` 指向目标表格的目标语义关联列与对应单元格。
8. 关联创建走现有 `POST system/notelink` 端点,不新增后端 API。

**反向追溯复用**

9. 关联创建后,目标表格的语义关联列单元格自然显示该笔记锚点(复用 U2 `selectNoteNotelinkByCell` 聚合查询),无需改动 U1-U6 已实现逻辑。
10. 笔记侧的 U5 引用面板与内联角标自然包含该跨表关联(复用 `selectNoteNotelinkByNoteId`),无需额外适配。

**取消关联**

11. 删除跨表关联锚点时复用 U6 的 `DELETE system/notelink/{linkId}` 路径与级联确认弹框(含"此操作会同时删除多维表格中的对应关联"警告),不新建删除流程。

## Key Flows

- F1. note modal 内划词跨表关联创建
  - **Trigger:** 用户在 note modal 内划词,点击工具栏新增的跨表关联按钮。
  - **Steps:** 选目标表格(当前表格置顶) → 选语义关联列(如有多个) → 选记录 → 确认。
  - **Outcome:** 选中词被包裹为带 `data-link-id` 的 `<a data-type="semantic">` 锚点;后端创建 NoteNotelink 记录(指向目标表格的列与记录);目标表格对应单元格显示该词,笔记侧 U5 面板显示该引用。
- F2. 目标表格侧反向追溯显示
  - **Trigger:** 关联创建后,用户打开目标表格查看语义关联列。
  - **Steps:** U2 聚合查询按 `linkColumnId+linkItemId` 返回该单元格所有 NoteNotelink → 单元格显示词文本拼接 → 点击词触发跳转回笔记并定位锚点(复用 U6 跳转与高亮)。
  - **Outcome:** 目标表格侧无需任何额外改动即可显示与追溯跨表关联。
- F3. 取消跨表关联
  - **Trigger:** 用户在笔记中删除一个跨表关联锚点。
  - **Steps:** 弹框提醒会触发目标表格对应关联删除(复用 U6 文案) → 用户确认 → `DELETE system/notelink/{linkId}` → 目标表格单元格保留其他划词并重新拼接。
  - **Outcome:** 仅删单条锚点,目标表格其他划词不受影响。

## Acceptance Examples

- AE1. 当前表格置顶 + 关联到当前表格其他记录
  - **Covers R3, R7.**
  - **Given:** 用户在表格 A 的语义关联列单元格 R1 打开的 note modal 内划词。
  - **When:** 点击跨表关联按钮,选择器中表格 A 置顶,用户选表格 A 的语义关联列、记录 R2,确认。
  - **Then:** 创建 NoteNotelink,`linkDwTableId=A, linkRecordId=R2`;表格 A 的单元格 R2 显示该划词词文本;笔记侧锚点带 `data-link-id`。
- AE2. 目标表格无语义关联列时预校验
  - **Covers R4.**
  - **Given:** 跨表选择器展示表格列表,表格 B 无任何 type=25 列。
  - **When:** 用户尝试选择表格 B。
  - **Then:** 表格 B 不可选,或选后提示"该表格需先创建语义关联列",无法进入下一步。
- AE3. 目标表格有多个语义关联列时选列
  - **Covers R5, R7.**
  - **Given:** 目标表格 C 有两个语义关联列 col1、col2。
  - **When:** 用户选表格 C 后,选择器展示 col1、col2 让用户选。
  - **Then:** 用户选 col2 后,创建的 NoteNotelink `linkColumnId=col2`,关联在 col2 的对应单元格显示。
- AE4. 跨表关联的反向追溯生效
  - **Covers R9, R10.**
  - **Given:** note modal 内通过新功能创建了到表格 D 的跨表关联。
  - **When:** 用户打开表格 D 的语义关联列查看对应单元格。
  - **Then:** 单元格显示该划词词文本;点击词跳回笔记并定位高亮锚点;笔记侧 U5 引用面板包含该跨表引用条目。

## Scope Boundaries

**Deferred for later**

- 跨表关联的批量创建(一次选多词或多记录)。
- 选择器内表格搜索/分页(当前表格列表规模小,暂不需要)。
- 跨表关联的统计面板(如"本笔记关联了哪些表格的多少条记录")。

**Outside this scope**

- 改动现有 `isModal=true` 快捷关联分支(原"语义关联"按钮行为零改动)。
- 改动 U1-U6 已实现的反向追溯逻辑(U2 聚合查询、U5 引用面板、U6 删除级联)。
- 新增后端 API 或改动 NoteNotelink 表结构(数据层完全复用)。
- 改动普通 doc 场景(`isModal=false`)的 SemanticSelectComponent 现有行为(仅扩展,不破坏)。

## Dependencies / Assumptions

- 依赖 U1-U6 已实现且可用:U2 `selectNoteNotelinkByCell` 聚合查询、U5 `selectNoteNotelinkByNoteId` 引用面板、U6 `DELETE system/notelink/{linkId}` 删除级联。
- 依赖现有 NoteNotelink 数据模型已含 `linkDwTableId/linkRecordId/linkColumnId/linkItemId` 字段,无需 schema 变更。
- 依赖现有 `POST system/notelink` 端点支持接收 `linkDwTableId/linkRecordId/linkColumnId/linkItemId` 参数(已在 U1-U6 实现中验证)。
- 假设:目标表格的语义关联列已通过正向流程或手动创建存在,本期不负责自动创建列。

## Outstanding Questions

**Deferred to Planning**

- 新按钮的图标与 tooltip 文案设计(需与原"语义关联"按钮视觉区分)。
- 选择器组件"选列"步骤的交互形态:下拉选择、步骤条、还是表格内联展开。
- `linkItemId` 的获取方式:目标表格选定记录后,需查询或创建该记录在目标语义关联列的 NoteDwtableItem(若不存在)。Planning 阶段需读 `NoteDwtableItem` 相关 service 确认获取/创建路径。
- 新按钮在普通 doc 场景(`isModal=false`)是否也启用,还是仅 note modal 场景启用。
- 当前表格当前记录被选中时的自引用关联处理(是否禁止或允许)。

## Sources / Research

- SemanticLink 内联工具: `notepad/src/components/editorjs/tools/inline/SemanticLink/index.ts` — `showSemanticModal()` 的 `isModal` 分支(L82-124)、`addOrUpdateLink()` 反向模式分支(L145-191)、`setAnchorAttributes()` 锚点属性(L254-270)、`executeUnlink()` U6 删除路径(L321-361)。
- 跨表选择器组件: `notepad/src/components/editorjs/components/SemanticLink/index.vue` — 已支持选表格+选记录,需扩展选列步骤与预校验。
- Editor 组件 props 传递: `notepad/src/components/editorjs/index.vue` — L460 `semanticLink: { class: SemanticLink, config: props }` 将整个 props 作为 config 传入工具;L55 props 定义含 `tableId/recordId/linkColumnId/linkItemId/linkId`。
- note modal 结构: `notepad/src/components/baseTable/index.vue` — L126-132 note modal 模板,L443-458 note reactive 对象,L336-348 语义关联列点击查看逻辑。
- NoteNotelink 实体: `ruoyi-system/src/main/java/com/ruoyi/system/domain/NoteNotelink.java` — 已含 `linkDwTableId/linkRecordId/linkColumnId/linkItemId` 字段,数据层零改动。
- 前一阶段 brainstorm: `docs/brainstorms/2026-07-15-semantic-link-reverse-requirements.md` — U1-U6 反向方向的决策与边界,本期在其基础上扩展跨表能力。
