---
date: 2026-08-05
topic: lookup-link-pin-selected
---

# Lookup / 双向链接列已选数据置顶

## Summary

在 lookup 列和双向链接列点开"查看数据"面板时，把当前单元格已关联的记录置顶显示。打开时按当时的已选集合做一次稳定分区、保留原顺序；双向链接列沿用已有 checkbox 区分已选，lookup 列因无 checkbox 额外加一个"已选"标记。纯前端实现，不动后端。

---

## Problem Frame

点击 lookup 或双向链接列的单元格会弹出一个面板，列出目标表的全部记录。双向链接列用 checkbox 预勾选当前已关联的记录；lookup 列隐藏 checkbox，仅作只读查看。

问题在于已关联记录混在整张列表里，没有任何位置或视觉优先级。当目标表记录较多时，用户要滚动整表才能确认"这个单元格到底关联了哪些"。对 lookup 列尤其严重——它的 checkbox 是隐藏的，用户打开面板后完全看不出哪些记录正在贡献这个 lookup 值。

---

## Key Decisions

**打开时快照，不实时重排。** 面板打开时按当时的已选集合排一次序，用户在面板内勾选/取消不会让行跳动。列表稳定，代价是面板内的勾选变化不会立即反映到位置上（见 AE3）。

**仅 lookup 列加"已选"标记。** 双向链接列已有 checkbox 表示选中状态，位置加 checkbox 已足够区分；lookup 列 checkbox 被隐藏，置顶后若不加标记仍无法辨别哪些是已选，故仅对 lookup 列额外加标记。

**稳定分区，保留原顺序。** 已选组在前、未选组在后，两组内部都保留后端返回的原相对顺序。不引入按选中顺序或按名称排序的能力，零配置最少意外。

**纯前端实现。** 面板一次性拉取目标表全部记录、无分页，排序在面板加载数据后于前端完成，不改动后端接口与排序逻辑。

---

## Requirements

**置顶行为**

- R1. 面板打开时按当时的已选集合做一次置顶排序；面板打开后用户在面板内的勾选/取消不触发重排。
- R2. 置顶对象为当前单元格已关联的记录，即传入面板的已选集合（由 `cell.click` 从当前记录的 `linkRecordId` 拆分得到；对 lookup 列 (type=26)，因其 item 不存储 `linkRecordId`（恒为 NULL），已选集合改由 `record['linkRecordId'][lookupColumn.property.double_link_column_id]` 解析得到）。
- R3. 已选集合为空或未提供时（如单元格无关联记录，或列配置时的"查看数据"预览路径），不执行置顶，列表按原顺序展示。

**排序**

- R4. 置顶做稳定分区：已选记录排在未选记录之前；两组内部均保留后端返回的原相对顺序，不改变同组内记录的先后。

**视觉区分**

- R5. 双向链接列沿用现有 checkbox 表示已选状态，置顶后不额外增加视觉标记。
- R6. lookup 列为已选（置顶）记录增加一个"已选"标记；未选记录不加该标记。标记仅对 lookup 列生效。

---

## Key Flows

- F1. 打开面板置顶
  - **Trigger:** 用户在 lookup 列或双向链接列的单元格上点开"查看数据"。
  - **Steps:** `cell.click` 把当前记录的 `linkRecordId` 拆成已选集合传入面板（对 lookup 列 type=26，因其 item 不存储 `linkRecordId`，改由 `record['linkRecordId'][lookupColumn.property.double_link_column_id]` 解析）；面板加载目标表全部记录；按已选集合做稳定分区，已选记录置顶、其余在后，组内保留原顺序；lookup 列额外给已选行加"已选"标记。
  - **Outcome:** 面板打开时已关联记录置顶可见，lookup 列同时能辨别哪些记录在贡献该单元格的值。
  - **Covered by:** R1, R2, R4, R5, R6.

---

## Acceptance Examples

- AE1. 双向链接：已选记录置顶，组内保留原顺序
  - **Covers R1, R2, R4, R5.**
  - **Given:** 目标表记录按后端返回顺序为 [1, 2, 3, 4]；当前单元格已关联记录为 1 和 3。
  - **When:** 打开面板。
  - **Then:** 列表顺序为 [1, 3, 2, 4]；1 和 3 的 checkbox 预勾选；不加额外视觉标记。

- AE2. lookup：已选记录置顶并加"已选"标记
  - **Covers R6.**
  - **Given:** 目标表记录顺序 [1, 2, 3, 4]；lookup 单元格引用的关联记录为 2。
  - **When:** 打开面板。
  - **Then:** 列表顺序为 [2, 1, 3, 4]；2 显示"已选"标记；面板无 checkbox（只读）。

- AE3. 快照语义：面板内勾选变化不重排
  - **Covers R1.**
  - **Given:** 面板已打开，已选记录 1、3 置顶，列表顺序为 [1, 3, 2, 4]。
  - **When:** 用户取消勾选 1，再勾选 4。
  - **Then:** 列表顺序不变（仍为 [1, 3, 2, 4]）；1 的 checkbox 变为未勾选但仍置顶，4 的 checkbox 勾选但仍在末尾；直到关闭面板重新打开才按新的已选集合重排。

- AE4. 空已选集合不置顶
  - **Covers R3.**
  - **Given:** 单元格无关联记录，或处于列配置时的"查看数据"预览路径（已选集合为空或未提供）。
  - **When:** 打开面板。
  - **Then:** 列表按原顺序展示，无置顶、无"已选"标记。

---

## Scope Boundaries

**不在本次范围：**

- 实时重排（面板内勾选变化立即重排）—— 已排除，采用打开时快照。
- 按 `linkRecordId` 选中顺序或按主显示列名称排序 —— 已排除，仅做稳定分区保留原顺序。
- 给双向链接列加额外视觉标记 —— 已排除，沿用 checkbox。
- 后端接口或后端排序改动 —— 已排除，纯前端实现。
- 列配置时"查看数据"预览路径的特判 —— 自然按空已选集合降级为不置顶，不单独处理。

---

## Dependencies / Assumptions

- 面板一次性拉取目标表全部记录、无分页（当前 `/system/record/dataList` 行为），故前端排序足够。若未来引入分页，需改为后端按已选优先返回。
- 对 lookup 列，因其 item 不存储 `linkRecordId`（恒为 NULL），传入面板的已选集合改由 `record['linkRecordId'][lookupColumn.property.double_link_column_id]` 解析得到（与后端 `getLookupLinkRecordIds` 逻辑一致），语义上等价于其引用的双向链接列的关联记录 ID 列表。

---

## Outstanding Questions

**Deferred to Planning:**

- "已选"标记的具体视觉形式（标签 / 底色 / 图标）与位置，交由规划与视觉设计确定。
- 是否对列配置预览路径显式标注"无单元格上下文，不置顶"，还是静默降级。

### From 2026-08-05 review

- **未说明已选组与未选组的组分界** — Requirements (R4) / Scope Boundaries (P2, design-lens, confidence 75)

  doc 承诺稳定分区但未说明用户如何感知组分界。双向链接列靠 checkbox 行级区分，无组级分隔时"置顶组"与"恰好排在顶部的记录"难以辨别，削弱置顶核心价值；AE3 快照语义下取消勾选的行滞留顶部时更易困惑。需决定是否在已选组与未选组之间渲染分组分隔（分隔行 / 粘性"已选 / 其他"标题），对两类列都适用。

---

## Sources / Research

- 面板组件：`notepad/src/components/baseTable/related/index.vue`（vxe-table，`checkbox-config.checkRowKeys = data.record`，lookup 隐藏 checkbox 列）。
- 面板调起与已选集合构造：`notepad/src/components/baseTable/index.vue` 的 `cell.click`（约 320-388 行，第 385 行构造 `record` 字段）。
- 列配置预览路径：`notepad/src/components/baseTable/field/edit.vue:142`（仅双向链接显示"查看数据"按钮，`preview.related` 无 `record` 字段）。
- 列类型语义：`CONCEPTS.md` 中 Lookup Column (type=26) 与 Double Link Column (type=21) 词条。
- 相关既有 brainstorm：`docs/brainstorms/2026-07-06-lookup-column-value-dedupe-requirements.md`。
