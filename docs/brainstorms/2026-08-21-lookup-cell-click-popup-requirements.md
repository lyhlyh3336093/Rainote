---
date: 2026-08-21
topic: lookup-cell-click-popup
---

# Lookup / 双向链接列单元格单击弹出关联面板

## Summary

单击 lookup (type=26) 或双向链接 (type=21) 单元格任意位置时，打开现有的"已关联 {目标表名}"整表记录列表弹框（`Related` 面板）。弹框内容与样式完全复用现有实现，不新建弹框、不改内容。

---

## Problem Frame

多维表格的 lookup 列与双向链接列共享同一套关联面板：`<a-modal>` + `Related` 组件，标题"已关联 {目标表名}"，展示目标表全部记录，已关联记录置顶，lookup 列只读。

但该面板目前无法通过单击触发。`@click="table.cell.click(...)"` 只绑在 vxe-table 的 `#edit` 模板（双击进入编辑态后的字段编辑器）上；`#default` 显示态渲染的 `<a-tag>` 没有任何点击绑定。vxe-table 的 `:edit-config` 设为 `trigger: 'dblclick'`，意味着用户必须双击单元格进入编辑态，再点击编辑器才能打开面板。

对 lookup 列尤其不直观：它的 `np-select` 组件即使进入编辑态，`tagRender` 的 `onClick` 也只对双向链接列 `emit("tag-click")`，对 lookup 什么都不做。结果是用户单击 lookup 单元格没有任何可见反馈，无法查看"这个单元格到底关联了哪些记录"。

---

## Requirements

- R1. 单击 lookup (type=26) 单元格任意位置时，打开现有的"已关联 {目标表名}"整表记录列表弹框（`Related` 面板）。
- R2. 单击双向链接 (type=21) 单元格任意位置时，打开同一个弹框。
- R3. 弹框的内容、样式、交互（含 lookup 列只读、OK/Cancel 按钮禁用、已选记录置顶）完全复用现有 `Related` 面板实现，不新建弹框、不改内容。
- R4. 触发对象为单元格本身（任意位置均可触发），不限于单元格内的 tag；空单元格（无 tag）同样可触发。

---

## Scope Boundaries

**不在本次范围：**

- 集合运算列 (type=24) 同样使用该弹框，但本次不改其触发方式。
- 不改弹框内容与样式——仅改触发方式（由"双击编辑态再点击"改为"单击单元格"）。
- 不改现有双击进入编辑态的路径——单击触发与双击编辑互不干扰。
- 不新增独立的"单元格信息"小弹框或概要卡片。

---

## Dependencies / Assumptions

- 空单元格（无关联记录）单击是否也弹框，对话中未明确确认。假设：同样弹框，展示目标表全部记录、不置顶——与现有空已选集合降级行为一致（`Related` 面板在 `props.data.record` 为空时跳过稳定分区、按原顺序展示）。若规划时发现该假设不成立，需补充空单元格的特判。
- 现有 `table.cell.click` 函数已对 lookup 与双向链接分别解析 `targetId`（lookup 走 `props.double_link_column_id`，双向链接走 `props.table_id`），并构造 `table.state.related`、设置 `table.state.visible = true`。本次仅需在显示态（`#default` 模板）接通单击触发，分发逻辑无需重写。

---

## Sources / Research

- 单元格渲染入口与 `#edit` / `#default` 模板：`notepad/src/components/baseTable/index.vue:42-114`（`@click` 绑定在 `#edit` 的 `<component>` 上，`#default` 的 `<a-tag>` 无点击绑定）。
- vxe-table 编辑模式触发：`notepad/src/components/baseTable/index.vue:25-32`（`:edit-config` 设 `trigger: 'dblclick'`）。
- `cell.click` 分发与弹框打开：`notepad/src/components/baseTable/index.vue:364-445`（按列类型解析 `targetId`，lookup 用 `props.double_link_column_id`，双向链接用 `props.table_id`，末尾设 `table.state.visible = true`）。
- 关联弹框挂载：`notepad/src/components/baseTable/index.vue:128-133`（`<a-modal>` 标题"已关联 {name}"，`<Related>` 组件，lookup/集合运算禁用 OK/Cancel）。
- `np-select` 组件 `tagRender`：`notepad/src/components/Select/index.vue:32-52`（`onClick` 仅对双向链接 `emit("tag-click")`，lookup 不触发）。
- 字段配置：`notepad/src/stores/table/index.ts:121-156`（双向链接与 lookup 均用 `np-select`，`readonly: true`）。
- `Related` 面板与已选置顶：`notepad/src/components/baseTable/related/index.vue:118-157`（`isLookup` 标记，`props.data.record` 非空时稳定分区置顶）。
- 列类型语义：`CONCEPTS.md` 中 Lookup Column (type=26) 与 Double Link Column (type=21) 词条。
- 相关既有 brainstorm：`docs/brainstorms/2026-08-05-lookup-link-pin-selected-requirements.md`（已选记录置顶，描述了面板打开路径与 lookup 已选集合解析）。
