---
date: 2026-07-06
topic: lookup-column-value-dedupe
---

# Lookup 列值去重

## Summary

为 lookup 列(type=26)增加按值去重能力。在 lookup 列 property JSON 增加 `dedupe` 布尔字段，开启后 `resolveLookupValues` 对解析结果按 value 去重（保留首次 recordId 与原顺序）。切换开关时 `updateNoteColumn` 全量重算该列所有记录。影响展示、存储、集合运算三路。

---

## Problem Frame

lookup 列通过 `double_link_column_id` 关联多条记录，每条记录在 `source_column_id` 上取值后用逗号拼接展示。当多条关联记录在源列上有相同值时（如三条记录的分类都是"苹果"），lookup 显示"苹果,苹果,苹果"。当前无去重机制，用户无法消除值重复。

---

## Key Decisions

**去重在核心解析层 `resolveLookupValues`**：在解析方法末尾按 `dedupe` 标志去重 `List<LookupResult>`，使展示、存储、集合运算三路统一获得去重结果。相比只在 join 步骤去重更彻底，但会让集合运算也接收去重输入。

**按 value 字符串去重，保留首次 recordId**：相同的值只保留第一个出现的 LookupResult（含其 recordId），后续重复值丢弃。保持原顺序，不引入排序。

**`dedupe` 默认 false，缺省视为不去重**：现有 lookup 列 property 无此字段，读取时缺省按 false 处理，向后兼容。

**切换触发全量重算**：`updateNoteColumn` 检测 type=26 且 `dedupe` 标志变化时，遍历该表所有记录重新解析并更新存储值。开关开/关两个方向都需全量重算（关闭时不能"加回"重复值，必须重新解析）。

---

## Requirements

**去重配置**

- R1. lookup 列 type=26 的 property JSON 支持 `dedupe` 布尔字段。
- R2. property 中无 `dedupe` 字段或值为 null 时，按 false 处理，保持现有行为。

**去重逻辑**

- R3. `resolveLookupValues` 在返回结果前，当 `dedupe` 为 true 时，按 `LookupResult.value` 去重，保留首次出现的 LookupResult（含其 recordId），丢弃后续相同 value 的结果。
- R4. 去重保持原顺序，不改变首次出现值的相对位置。
- R5. 多个空 value 同样按值去重为一个，不特殊处理空字符串。

**重算触发**

- R6. `updateNoteColumn` 检测到 type=26 列的 `dedupe` 字段从旧值变为新值时，触发该列所有记录的全量重算。
- R7. 重算遍历该列所属表的所有记录，对每条记录重新调用 `resolveLookupValue` 并更新其 lookup item 的存储值。
- R8. 重算失败的单条记录不中断整体流程，记录错误日志后继续处理下一条。

---

## Key Flows

- F1. 切换去重开关
  - **Trigger:** 用户在列配置中切换 `dedupe` 字段并保存。
  - **Steps:** `updateNoteColumn` 读取原 property 与新 property 的 `dedupe` 值；值未变化则跳过；值变化时查询该表所有记录，逐条重新解析 lookup 值并更新 item。
  - **Outcome:** 该 lookup 列所有记录的存储值反映新的去重状态。
  - **Covered by:** R6, R7, R8.

---

## Acceptance Examples

- AE1. 开启去重后多记录同值去重
  - **Covers R3, R4.**
  - **Given:** lookup 列 3268 的 `dedupe` 设为 true，关联记录 1/2/3 在 source_column 上的值分别为"苹果/苹果/梨"。
  - **When:** 解析 lookup 值。
  - **Then:** 结果为 `["苹果","梨"]`，recordId 为 `[1, 3]`，保留首次出现值的 recordId。

- AE2. 关闭去重保留所有重复值
  - **Covers R2, R3.**
  - **Given:** 同 AE1 数据，但 `dedupe` 为 false 或缺省。
  - **When:** 解析 lookup 值。
  - **Then:** 结果为 `["苹果","苹果","梨"]`，recordId 为 `[1, 2, 3]`。

- AE3. 切换开关触发全量重算
  - **Covers R6, R7.**
  - **Given:** lookup 列 3268 的 `dedupe` 当前为 false，表中有 5 条记录，部分存在值重复。
  - **When:** 用户将 `dedupe` 改为 true 并保存。
  - **Then:** 5 条记录的 lookup 存储值全部重新解析并更新为去重后的值。

---

## Scope Boundaries

**不在本次范围：**

- 前端切换 UI 的实现（本次定义后端契约与逻辑，前端 UI 由后续工作处理）
- 记录级去重（同一 recordId 在 linkRecordId 中重复，属数据完整性问题）
- 去重时附带计数展示（如"苹果×3"）
- 去重时的排序（保持原顺序，不引入排序能力）

---

## Outstanding Questions

**Deferred to Planning:**

- 集合运算（差集 A-B）在 A 列为 lookup 列且 `dedupe` 为 true 时，输入去重后运算结果是否一致。数学上 A-B 对 A 的重复不敏感，结果不变；但需在实现时验证 `values` 与 `recordIds` 列表对齐不被去重破坏。
- `dedupe` 字段在 property JSON 中的写入位置需与现有 property 结构一致（字段名建议 `dedupe`）。

---

## Sources / Research

- lookup 列解析核心方法：`ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java:1002`（`resolveLookupValues`）
- lookup 值 join 与存储：同文件 `:1055`（`resolveLookupValue`）
- 集合运算中 lookup 数据获取：同文件 `:953`（`getLookupLinkRecordIds`）、`:984`（`toLookupValues`）
- 列更新入口：`ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteColumnServiceImpl.java:185`（`updateNoteColumn`，当前仅 type=21 有特殊处理）
- 既有 lookup 列集合运算逻辑错误修复记录：`docs/solutions/logic-errors/lookup-column-set-operation-logic-errors.md`
