---
title: "feat: lookup/双向链接列查看数据面板已选置顶"
type: feat
date: 2026-08-05
origin: docs/brainstorms/2026-08-05-lookup-link-pin-selected-requirements.md
---

# feat: lookup/双向链接列查看数据面板已选置顶

## Summary

在 lookup 列（type=26）和双向链接列（type=21）点开"查看数据"面板时，把当前单元格已关联的记录置顶：打开时按当时的已选集合做一次稳定分区，保留后端返回的原顺序；面板内勾选变化不重排（快照语义）。lookup 列因 checkbox 隐藏，额外给置顶行加"已选"标记；两类列都在已选组与未选组之间渲染分组分隔。纯前端实现，不动后端。

---

## Problem Frame

点击 lookup 或双向链接列单元格弹出的"查看数据"面板列出目标表全部记录，已关联记录混在整表中无位置优先级，目标表记录多时需滚动整表才能确认关联。lookup 列尤其严重——checkbox 隐藏，打开面板后看不出哪些记录在贡献该 lookup 值。本计划在面板打开时做一次稳定分区把已选记录置顶，并加视觉区分。

---

## Requirements

**置顶行为**

- R1. 面板打开时按当时的已选集合做一次置顶排序；面板打开后用户在面板内的勾选/取消不触发重排。
- R2. 置顶对象为当前单元格已关联的记录：双向链接列经 `record['linkRecordId'][cell.column]` 取得；lookup 列（type=26）因其 item 不存储 `linkRecordId`（恒为 NULL），改由 `record['linkRecordId'][props.double_link_column_id]` 解析，与后端 `getLookupLinkRecordIds` 一致。
- R3. 已选集合为空或未提供时（如单元格无关联记录，或列配置预览路径），不执行置顶，列表按原顺序展示。

**排序**

- R4. 置顶做稳定分区：已选记录排在未选记录之前；两组内部均保留后端返回的原相对顺序。

**视觉区分**

- R5. 双向链接列沿用现有 checkbox 表示已选状态，置顶后不额外增加视觉标记。
- R6. lookup 列为已选（置顶）记录增加一个"已选"标记；未选记录不加该标记。标记仅对 lookup 列生效。
- R7. 在已选组与未选组之间渲染分组分隔，对 lookup 列与双向链接列都适用；仅当两组都非空时渲染。

---

## Key Technical Decisions

- **快照分区于加载时计算一次。** 面板 `onMounted` 拉取数据后对 `table.state.tableData` 做一次稳定分区写入，不设响应式 watcher 在勾选变化时重算。vxe-table 以 `row-config.keyField='id'` 按行 id 建索引，重排数据数组不影响 `checkRowKeys` 的预勾选状态——这是快照语义（R1/AE3）得以零成本成立的前提。
- **lookup 已选集合经 `double_link_column_id` 解析。** `cell.click` 已解析 `props`，对 type=26 用 `props.double_link_column_id` 作为 `linkRecordId` 的键，而非 `cell.column`（lookup item 该字段恒为 NULL）。镜像后端 `getLookupLinkRecordIds` 的解析路径。
- **"已选"标记复用 lookup 隐藏的 checkbox 列槽位。** 现有首列 `v-if="data.type!==FieldEnum?.lookUp"` 对 lookup 隐藏；为 lookup 渲染一个替代首列，对 `id ∈ data.record` 的行显示"已选"标签，避免新增列宽布局。
- **分组分隔用行类名而非合成行。** 用 vxe-table 的 `row-class-name` 给已选组末行加边界类（如 `pin-group-boundary`），scoped CSS 渲染下边框作分隔。不注入合成数据行，避免干扰 checkbox/keyField 逻辑。
- **无前端测试框架，验证以手动场景为主。** notepad 应用 `package.json` 仅有 `dev`/`build`/`preview`，无 vitest/jest。每个单元给出可执行的手动验证场景（追溯到 AE）；引入 vitest 列为 Deferred。

---

## Implementation Units

### U1. Fix lookup selected-set derivation in cell.click

- **Goal:** lookup 列打开面板时，已选集合从其引用的双向链接列的 `linkRecordId` 解析，而非 lookup item 自身的（NULL）`linkRecordId`。
- **Requirements:** R2
- **Dependencies:** 无
- **Files:** `notepad/src/components/baseTable/index.vue`
- **Approach:** 在 `cell.click` 构造 `table.state.related.record` 处（约 385 行），按列类型分键：`type === FieldEnum.lookUp` 时用 `props.double_link_column_id` 作为 `record['linkRecordId']` 的键；否则沿用 `cell.column`。`props` 已于 323 行解析，lookup 分支已用 `props.double_link_column_id` 定位目标表。
- **Patterns to follow:** 后端 `getLookupLinkRecordIds`（`ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java`），见 `docs/solutions/design-patterns/lookup-column-code-simplification-patterns.md`。
- **Test scenarios:**
  - Covers AE2 / R2. 一个引用了双向链接列关联记录 [2] 的 lookup 单元格打开面板 → `table.state.related.record === ['2']`（非空）。
  - Edge: lookup 单元格无关联 → `record === []`，不崩溃，置顶降级为 no-op。
  - Covers AE1. Regression: 双向链接列单元格打开 → `record` 仍按 `cell.column` 取键，行为不变。
  - Edge: `props.double_link_column_id` 缺失或引用记录 `linkRecordId` 为 null → 空集合，不崩溃。
- **Verification:** 打开一个已知关联的 lookup 单元格面板，检查 `table.state.related.record` 与引用双向链接列的 `linkRecordId` 一致。

### U2. Stable partition on panel load

- **Goal:** 面板加载目标表记录后，把已选记录置顶、未选在后，组内保留原顺序；只算一次。
- **Requirements:** R1, R3, R4
- **Dependencies:** U1（lookup 需正确的已选集合）
- **Files:** `notepad/src/components/baseTable/related/index.vue`
- **Approach:** `onMounted` 中 `table.get.list()` 完成后，若 `data.record` 非空，先建类型安全集合 `const selectedSet = new Set((data.record || []).map(String))`（`data.record` 来自 `.split(',')` 为字符串数组，而 `row.id` 为数字，直接 `Array.includes` 会因严格相等失败，整个功能会静默失效），再对 `table.state.tableData` 做两趟 `Array.filter`：先取 `selectedSet.has(String(row.id))` 的行，再取其余，拼接后写回 `table.state.tableData`。`filter` 保持插入顺序即稳定分区。不为勾选变化设重算 watcher（R1/AE3）。`data.record` 为空或 undefined（预览路径）时跳过分区。
- **Patterns to follow:** 现有 `table.get.list()` 数据加载；`Array.filter` 稳定顺序。
- **Test scenarios:**
  - Covers AE1. 后端顺序 [1,2,3,4]、已选 [1,3] → 分区后 [1,3,2,4]，1/3 预勾选。
  - Covers AE3. 打开后取消勾选 1、勾选 4 → 顺序仍 [1,3,2,4]（无重算）。
  - Covers AE4. `data.record` 空 → 原顺序，无置顶。
  - Edge: 全部已选 → 全部置顶，相对顺序保留。
  - Edge: 已选 id 不在加载记录中（陈旧）→ 忽略，不报错。
  - Edge: `data.record` undefined（预览路径）→ 视为空，不分区。
- **Verification:** 打开双向链接列单元格面板，确认顺序符合 AE1；勾选/取消后确认不重排。

### U3. Visual differentiation — "已选" marker + group separator

- **Goal:** lookup 列给置顶行渲染"已选"标记（checkbox 列已隐藏）；两类列都在已选组与未选组间渲染分组分隔。
- **Requirements:** R5, R6, R7
- **Dependencies:** U2（分区定位组边界）
- **Files:** `notepad/src/components/baseTable/related/index.vue`
- **Approach:**
  - **标记：** 为 lookup 列渲染替代首列（现有首列对 lookup 隐藏），对 `selectedSet.has(String(row.id))` 的行显示"已选"标签（复用 U2 的 `selectedSet`，同样需 String 强制以避免类型不匹配），其余行留空。确切视觉形式（标签/底色/图标）交实现，默认小型内联标签。
  - **分隔：** 用 vxe-table `row-class-name`（或等价 prop）在两组都非空时给已选组末行加 `pin-group-boundary` 类，scoped CSS 渲染下边框。不注入合成数据行，避免干扰 checkbox/keyField。
- **Patterns to follow:** 现有 `vxe-column` checkbox 模板；`FieldEnum` 类型判断。
- **Test scenarios:**
  - Covers AE2 / R6. lookup 面板 → 置顶行显示"已选"标记，未置顶行无标记；无 checkbox 列。
  - Covers R7. 任一列面板、两组都非空 → 组间渲染分隔。
  - Edge: 全部已选或全未选 → 不渲染分隔（退化情形）。
  - Covers AE1 / R5. Regression: 双向链接面板 → checkbox 列在、无"已选"标记；分隔仍渲染。
  - Covers AE3. 勾选变化后，标记/分隔仍按快照分区（取消 1 → 1 仍置顶带标记，分隔不动）。
- **Verification:** 打开 lookup 与双向链接面板，确认标记（仅 lookup）与分隔（两者）正确；勾选变化后确认快照稳定。

---

## Scope Boundaries

**不在本次范围（沿用 brainstorm）：**

- 实时重排（面板内勾选变化立即重排）—— 采用打开时快照。
- 按选中顺序或按名称排序 —— 仅稳定分区保留原顺序。
- 给双向链接列加额外视觉标记 —— 沿用 checkbox。
- 后端接口或后端排序改动 —— 纯前端实现。
- 列配置预览路径特判 —— 静默降级为不置顶（R3）。

### Deferred to Follow-Up Work

- notepad 前端测试框架（vitest）接入 —— 当前验证以手动场景为主。
- "已选"标记视觉形式打磨（标签/底色/图标最终定型）。
- 分隔形式升级为粘性"已选 / 其他"标题（当前默认行类名下边框）。
- 列配置预览路径是否显式标注"无单元格上下文，不置顶"（当前静默降级）。

---

## Open Questions

- "已选"标记最终视觉形式（标签 / 底色 / 图标）—— 交实现与视觉设计定型，默认小型内联标签。
- 分隔是否升级为粘性分组标题 —— 默认下边框分隔，粘性标题列为后续可选增强。

---

## Risks & Dependencies

- **无前端测试框架** → 验证以手动场景为主，回归风险靠 AE 追溯场景覆盖。引入 vitest 列为 Deferred。
- **重排 tableData 与 checkRowKeys 交互** → vxe-table 按 `id` 建索引保证预勾选状态不被重排破坏；U2 验证场景需显式确认勾选状态正确。
- **快照语义依赖不重算** → 不得为 `data.record` 或勾选事件设响应式 watcher 触发再分区；U2 实现需避免该路径。

---

## Acceptance Examples

沿用 brainstorm 的 AE1–AE4 作为验证契约：

- AE1. 双向链接：后端顺序 [1,2,3,4]、已选 [1,3] → [1,3,2,4]，1/3 预勾选，无额外标记。分隔渲染于 3 与 2 之间。
- AE2. lookup：后端顺序 [1,2,3,4]、已选 [2] → [2,1,3,4]，2 显示"已选"标记，无 checkbox。分隔渲染于 2 与 1 之间。
- AE3. 快照：打开后取消勾选 1、勾选 4 → 顺序仍 [1,3,2,4]；1 未勾选但仍置顶，4 勾选但仍在末尾；标记/分隔不因勾选变化移动。
- AE4. 空已选集合 → 原顺序，无置顶、无标记、无分隔。

---

## Sources / Research

- 面板组件：`notepad/src/components/baseTable/related/index.vue`（`data.record`/`data.type` props；`checkbox-config.checkRowKeys`；`onMounted` 加载 `table.state.tableData`；首列 `v-if="data.type!==FieldEnum?.lookUp"` 对 lookup 隐藏）。
- cell.click：`notepad/src/components/baseTable/index.vue`（约 320–388 行；385 行构造 `record`；332 行 lookup 分支已用 `props.double_link_column_id`）。
- 列配置预览路径：`notepad/src/components/baseTable/field/edit.vue:142`（"查看数据"仅双向关联渲染，`preview.related` 无 `record`）。
- 后端解析模式：`getLookupLinkRecordIds`（`ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java`）；沉淀文档 `docs/solutions/design-patterns/lookup-column-code-simplification-patterns.md`。
- 领域词汇：`CONCEPTS.md`（Lookup Column type=26、Double Link Column type=21、NoteDwtableItem）。
- 需求来源：`docs/brainstorms/2026-08-05-lookup-link-pin-selected-requirements.md`。
