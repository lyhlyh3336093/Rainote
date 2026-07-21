---
date: 2026-07-15
topic: semantic-link-reverse
---

## Summary

给语义关联列(type=25)补上反向能力:从多维表格单元格发起对笔记的关联,粒度到笔记+block+块内划词选中的词语。一个单元格可挂多个独立划词,单元格显示拼接的词,点击按唯一链接 id 精确跳转并锚定到该词,笔记侧内联高亮+侧边面板双向可追溯。复用现有 SemanticLink 内联工具与 `<a data-type="semantic">` 锚点表示,扩展一个 `data-link-id` 字段承载 NoteNotelink 主键。

## Problem Frame

语义关联列(type=25)表达"笔记 ↔ 多维表格"的链接。正向(笔记→表格)已实现:笔记内的 SemanticLink 内联工具让用户选词、包成 `<a data-type="semantic">` 锚点、同步后端、并在表格侧创建 type=25 列与单元格数据。

反向(表格→笔记)尚未实现好:无法从多维表格单元格发起对笔记的关联,也无法把关联粒度推进到 block 与块内划词。用户在表格侧看不到被引用的词,无法点击跳回笔记定位到具体词语,笔记侧也没有"被表格引用"的追溯入口。本次补齐反向链路,并把关联粒度从笔记/block 级细化到块内划词级。**反向方向与词级粒度捆绑交付的依据:现有正向已用 SemanticLink 工具实现词级锚点,反向若只到 block 级,会导致正向词级、反向 block 级的粒度不对称,且单元格无法精确指向被引用的词——词级粒度是反向方向可用性的前提,故同期交付。**

## Key Decisions

- **复用 SemanticLink 内联工具与 `<a data-type="semantic">` 锚点表示,而非新建锚点机制。** 现有工具已具备选词包裹、内联高亮、点击、取消关联确认等基础设施。代价是反向流程需与现有正向数据模型对齐,并扩展一个 `data-link-id` 字段。
- **每个划词锚点以 NoteNotelink 主键 id 为唯一链接 id**,承载于锚点的 `data-link-id` 属性。这让同一笔记/block 内的多个划词可被精确区分,也让删除可按单条锚点级联。
- **删除粒度为单条锚点。** 删一个锚点只删该 NoteNotelink 记录,单元格保留其他划词;不清空整个单元格。
- **`data-link-id` 与 NoteNotelink 主键为 1:1 映射且不可变。** 一条 NoteNotelink 记录对应唯一一个 `data-link-id`,删除后该 id 不复用(不回收、不指向新记录)。这保证跳转定位与删除级联按 id 操作时语义稳定。

## Requirements

**反向创建**

1. 从语义关联列(type=25)单元格可发起对笔记的关联,关联粒度到笔记+block+块内划词选中的词语。
2. 一个单元格可挂多个独立划词,可跨 block、跨笔记。
3. 创建关联时,选中词在笔记里被包裹为现有 SemanticLink 工具的 `<a data-type="semantic">` 锚点,沿用其选词包裹/内联高亮/点击基础设施。

**锚点标识**

4. 每个划词锚点带唯一链接 id,值为该关联的 NoteNotelink 主键 id,承载于锚点的 `data-link-id` 属性。该映射为 1:1 且不可变(删除后 id 不复用)。现有 SemanticLink 内联工具的锚点表示需扩展该字段。
5. 单元格存储它关联的 NoteNotelink id 集合,按单元格聚合多行 NoteNotelink 记录。

**单元格展示**

6. 单元格显示内容为该单元格所挂全部划词的词文本拼接。删除任一划词后,展示重新拼接剩余词。

**跳转定位**

7. 点击单元格,打开目标笔记,在 DOM 中按 `data-link-id` 定位锚点,滚动至该元素并高亮。同一笔记含多个划词时,按 id 精确命中目标词,不受其他锚点干扰。**若锚点已删除、笔记不可访问、或 `data-link-id` 陈旧,跳转回退到笔记顶部并展示"关联已失效"提示,不报错中断。**

**笔记侧呈现**

8. 被引用的划词锚点在笔记内带内联高亮标记,点击该锚点跳回对应的表格单元格。
9. 笔记侧提供面板汇总"被哪些表格/单元格引用",与内联标记共用同一份锚点注册,不维护两套状态。

**删除级联**

10. 在笔记中删除一个划词锚点时,弹框提醒用户该操作会触发多维表格处对应关联的删除。
11. 用户确认后,按该锚点的 NoteNotelink id 删除该条关联记录;单元格的其他划词保留,单元格展示重新拼接剩余词。删除粒度为单条锚点,不清空整个单元格。**当被删的是该单元格的最后一条 NoteNotelink 时,单元格变为空(展示空态),不删除单元格本身;空态单元格是否自动清除由 Planning 阶段决定,本期默认保留。**

## Key Flows

- F1. 从单元格发起反向关联
  - **Trigger:** 用户在语义关联列单元格发起"关联笔记"。
  - **Steps:** 选笔记 → 选 block → 在 block 内划词选中目标词语 → 确认。
  - **Outcome:** 选中词被包裹为 `<a data-type="semantic" data-link-id="<notelinkId>">` 锚点;后端生成 NoteNotelink 记录(含 noteId/blockId/linkColumnId/linkItemId/contextText/itemValue);单元格展示拼接词。
- F2. 从单元格跳转定位到笔记划词
  - **Trigger:** 用户点击单元格中某个划词。
  - **Steps:** 打开目标笔记 → 按 `data-link-id` 在 DOM 查找锚点 → 滚动至该元素并高亮。若锚点不存在或笔记不可访问,回退至笔记顶部并提示"关联已失效"。
  - **Outcome:** 精确命中目标词,不受同笔记其他划词干扰;锚点缺失时降级为提示而非中断。
- F3. 笔记内删除锚点级联到表格
  - **Trigger:** 用户在笔记中删除一个划词锚点。
  - **Steps:** 弹框提醒会触发表格侧对应关联删除 → 用户确认 → 按该锚点 NoteNotelink id 删除该条记录 → 单元格保留其他划词并重新拼接展示。
  - **Outcome:** 仅删单条锚点,单元格其他划词不受影响。若为最后一条,单元格变空但保留(不删除单元格)。

## Acceptance Examples

- AE1. 同笔记多划词时的跳转精确性
  - **Covers R4, R7.**
  - **Given:** 一个单元格关联了笔记 N 的两个词,分别位于 block B1 和 block B2;笔记 N 内因此有两个 `<a data-type="semantic" data-link-id="...">` 锚点。
  - **When:** 用户点击单元格中对应 B2 那个划词。
  - **Then:** 打开笔记 N,定位到 `data-link-id` 匹配 B2 那条记录的锚点并高亮,不会误定位到 B1 的锚点。
- AE2. 多划词单元格中删一个锚点
  - **Covers R6, R10, R11.**
  - **Given:** 单元格 C 挂了三个划词(三条 NoteNotelink 记录),展示为"词A,词B,词C"。
  - **When:** 用户在笔记中删除"词B"对应的锚点,并在弹框中确认。
  - **Then:** 仅"词B"那条 NoteNotelink 记录被删除;单元格 C 保留"词A,词C"两条,展示重新拼接为"词A,词C";不清空单元格。

## Scope Boundaries

**Deferred for later**

- 一个词被多个单元格引用的场景。当前使用中一个词最多被引用一次,多对一引用不在本期范围。

**Outside this scope**

- 现有正向(笔记→表格)创建流程的交互与逻辑改动。反向独立建设,不回改正向创建流程。**例外:R4 要求扩展 SemanticLink 工具的 `<a data-type="semantic">` 锚点表示层,新增 `data-link-id` 属性——这是正向与反向共用的表示层扩展,属本期内改动,但仅限锚点属性新增,不改正向的选词/包裹/点击/取消逻辑。**

## Dependencies / Assumptions

- 现有正向能力可用:SemanticLink 内联工具、`<a data-type="semantic">` 锚点、`system/block/linkToDwtable` 与 `system/block/removeLink` 后端、type=25 列与 NoteDwtableItem 存储。
- NoteNotelink 模型已具备承载反向关联的字段(id 主键、noteId、blockId、linkColumnId、linkItemId、contextText、itemValue),无需新建实体。
- **NoteDwtableItem 与 NoteNotelink 关系**:正向(`linkToDwtable`)写 NoteDwtableItem,反向链接记录写 NoteNotelink(含 blockId/划词粒度)。两者通过 linkColumnId+linkItemId 指向同一单元格;是同一链接的正/反向视图还是独立存储,需 Planning 阶段读源码核实。本期反向操作仅涉及 NoteNotelink 记录的创建/删除,不改 NoteDwtableItem。
- **`removeLink` 后端现状**:现有 `system/block/removeLink` 实现为 stub(只 fetch 返回 0,无 mapper 删除调用),属 pre-existing 问题。本方案反向删除按 NoteNotelink id 操作,是新路径,不依赖该 stub;但该 stub 待 Planning 阶段决定是否一并修复。
- 假设:当前使用场景中一个词最多被一个单元格引用,不会出现多对一引用。

## Outstanding Questions

**Deferred to Planning**

- 从单元格发起关联时的创建 UI 形态:是复用现有 SemanticSelectComponent 选词界面,还是新增单元格内 picker;以及进入笔记划词的交互细节。
- block 被整体删除时,其内划词锚点的级联处理(随 block 删除并通知表格侧,还是保留悬空记录待清理)。
- `data-link-id` 字段对历史已创建锚点的回填策略。
- [doc-review] 单元格的 loading / empty / error 状态展示(加载中、无关联空态、加载失败/部分失败)——R6 仅定义正常态。
- [doc-review] 侧边面板(R9)的入口形态(按钮/抽屉/常驻)、可见性规则、无引用时的空状态。
- [doc-review] F1 反向创建流程的外部驱动编辑器入口协议:如何从单元格点击驱动编辑器内执行选词包裹锚点(现有 SemanticLink 工具是编辑器内用户主动触发)。
- [doc-review] 交互(点击/滚动/高亮)的 a11y 承诺:键盘可达、屏幕阅读器、焦点管理。

## Sources / Research

- 现有 SemanticLink 内联工具: `notepad/src/components/editorjs/tools/inline/SemanticLink/index.ts` — 选词包裹、`<a data-type="semantic">` 属性设置、点击 `open-semantic-detail`、`confirmUnlink`/`executeUnlink` 取消关联确认、`removeLink` 解绑。
- NoteNotelink 实体: `ruoyi-system/src/main/java/com/ruoyi/system/domain/NoteNotelink.java` — id 主键 + noteId/blockId/linkColumnId/linkItemId/contextText/itemValue。
- NoteColumn 类型枚举: `ruoyi-system/src/main/java/com/ruoyi/system/domain/NoteColumn.java` — type=25 语义关联。
- 正向后端入口: `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteBlockServiceImpl.java` — `linkToDwtable()` 创建 type=25 列与 NoteDwtableItem。
- 现有 `<c id=...>` 为页内导航锚点(另一套系统,目录导航用),非语义关联锚点,本方案不复用该套。
