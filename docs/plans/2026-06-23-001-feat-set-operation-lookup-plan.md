# feat: 集合运算支持 Lookup 列参与

**Origin:** `docs/brainstorms/2026-06-23-set-operation-lookup-requirements.md`
**Plan depth:** Standard
**Date:** 2026-06-23

---

## Summary

扩展 `updateNoteRecord` 中集合运算分支，使 A 列和 B 列可以任意组合 type=21（双向关联）和 type=26（lookup），同时修复现有集合运算中 value 和 linkRecordId 分别独立运算导致可能错位的 bug。

## Problem Frame

当前集合运算代码假设 A/B 列都是 type=21，直接从 item 取 `linkRecordId` 和 `value` 参与 `CollectionUtils` 交并差补运算。Lookup 列(type=26)虽然也有 `linkRecordId`，但其 `value` 是动态解析的，无法直接使用。此外，现有代码对 value 和 linkRecordId 分别独立做集合运算，当两者交集不一致时结果会错位。

## Requirements

来源：`docs/brainstorms/2026-06-23-set-operation-lookup-requirements.md`

1. A 列和 B 列支持 type=21 和 type=26 的任意组合（21+21、21+26、26+21、26+26）
2. type=21 直接取 item 的 linkRecordId 和 value
3. type=26 通过新方法获取 lookup 解析后的 value 及对应的 linkRecordId
4. 集合运算基于 linkRecordId 做运算，value 根据 linkRecordId 结果反查（修复 Bug 1）
5. 结果列存储格式不变
6. 现有 21+21 行为不受影响

## Key Technical Decisions

### KTD-1: 新增返回值结构 + 分层方法设计

新增两层方法：
1. `resolveLookupValues(NoteColumn itemColumn, List<String> linkRecordIds)` — 核心方法，输入已知的 linkRecordId 列表，只做 value 解析，返回 `List<LookupResult>`
2. `resolveLookupValueWithRecordId(NoteColumn itemColumn, Long recordId)` — 便捷方法，从数据库查询 linkRecordId 后委托给核心方法

这样设计是因为：lookup 列作为 A 列（当前修改列）时，linkRecordId 来自前端 itemMap（最新值），不从数据库查；作为 B 列时，linkRecordId 从数据库查。两种场景需要不同的 linkRecordId 来源，但 value 解析逻辑相同。

内部静态类 `LookupResult` 包含 `value`（String）和 `linkRecordId`（String）两个字段。

### KTD-2: 集合运算改为基于 linkRecordId 运算

现有代码对 `aValues` 和 `aRecordIds` 分别独立做 `CollectionUtils.intersection/union` 等，当 value 有交集但 recordId 无交集时结果脱节。改为：先对 linkRecordId 做集合运算得到 `rRecordIds`，然后根据 `rRecordIds` 中的每个 ID 从原始的 recordId→value 映射中查找对应 value。

**前提假设**：同一个 linkRecordId 在 A 和 B 中的 value 应一致（因为 value 就是该关联记录的名称/展示值），所以反查时从哪边取值不影响结果。但为代码清晰，反查时按运算语义决定查找顺序：
- 交集/并集：先查 aMap，再查 bMap
- 差集(A-B)：只查 aMap
- 补集：先查 aMap，再查 bMap（对称差中的 ID 只在一侧存在）

### KTD-3: lookup 列的 linkRecordId 来源

Lookup 列 item 在数据库中存储的 `linkRecordId` 是双向链接列 item 的 linkRecordId（即 lookup 引用的关联记录 ID 列表），与 type=21 语义一致，可直接从 item 获取。Value 必须通过解析逻辑获取。

### KTD-4: 当前修改列是 A 列时的数据获取

当 lookup 列作为 A 列（当前正在修改的列），linkRecordId 从 itemMap 取（前端传入的最新值），value 通过 `resolveLookupValues` 方法解析（传入 itemMap 中的 linkRecordId 列表）。**不调用 `resolveLookupValueWithRecordId`**，因为该方法会从数据库查询 linkRecordId，而数据库中的数据可能还是旧的。

---

## Implementation Units

### U1. 新增 LookupResult 内部类和 resolveLookupValues 核心方法

**Goal:** 提供一个能同时返回 lookup value 和 linkRecordId 的公共方法，供集合运算调用。核心方法接受已知的 linkRecordId 列表，只做 value 解析。

**Requirements:** R2, R3

**Dependencies:** 无

**Files:**
- `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java`
- `ruoyi-system/src/test/java/com/ruoyi/system/service/impl/NoteRecordServiceImplTest.java`

**Approach:**
1. 在 `NoteRecordServiceImpl` 内部新增静态类 `LookupResult`，包含 `value`（String）和 `linkRecordId`（String）两个字段
2. 新增核心方法 `resolveLookupValues(NoteColumn itemColumn, List<String> linkRecordIds)`，返回 `List<LookupResult>`
   - 输入：lookup 列对象、已知的 linkRecordId 列表
   - 内部逻辑：复用 `resolveLookupValue` 的指针追逐逻辑（沿 source_column_id 链追踪到非 lookup 列），对每个 linkRecordId 查找对应记录在最终 sourceColumn 下的 value
   - 返回：每个 linkRecordId 对应一个 LookupResult（包含 value 和 linkRecordId）
3. 新增便捷方法 `resolveLookupValueWithRecordId(NoteColumn itemColumn, Long recordId)`，返回 `List<LookupResult>`
   - 从数据库查询 lookup 列 item 的 linkRecordId（通过 recordId + columnId 查询 item，取 item.getLinkRecordId()）
   - 委托给 `resolveLookupValues`
4. 重构 `resolveLookupValue` 为委托给 `resolveLookupValues`，避免重复代码

**Patterns to follow:** 现有 `resolveLookupValue` 方法的指针追逐模式

**Test scenarios:**
- sourceColumn 是普通列，传入 linkRecordIds=["1","2"]，返回正确的 value 和 linkRecordId
- sourceColumn 也是 lookup 列，链式追踪到普通列
- 3 层 lookup 嵌套，追踪到最终普通列
- linkRecordIds 为空列表，返回空列表
- lookup 链超过 5 层深度限制，返回空 LookupResult 列表
- 只有一条关联记录，返回单个 LookupResult
- `resolveLookupValueWithRecordId` 从数据库查询 linkRecordId 后委托，结果正确
- `resolveLookupValue` 委托后行为不变

**Verification:** 单元测试全部通过，`resolveLookupValue` 仍正常工作

---

### U2. 重构集合运算逻辑：基于 linkRecordId 运算 + value 反查

**Goal:** 修复 Bug 1（value 和 linkRecordId 错位），将集合运算改为基于 linkRecordId 运算后反查 value。

**Requirements:** R4

**Dependencies:** 无（U2 不依赖 U1，U2 只改变集合运算的计算方式，不涉及 lookup 解析）

**Files:**
- `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java`

**Approach:**
1. 在获取 A/B 列数据后，构建 `Map<String, String> aRecordIdToValue` 和 `Map<String, String> bRecordIdToValue` 映射（linkRecordId → value）
2. 对 `aRecordIds` 和 `bRecordIds` 做 `CollectionUtils` 集合运算得到 `rRecordIds`
3. 遍历 `rRecordIds`，根据运算类型从对应的 map 中查找 value 组成 `rValues`：
   - 交集/并集/补集：先查 aMap，再查 bMap（同一 recordId 的 value 应一致）
   - 差集(A-B)：只查 aMap（结果只存在于 A 中）
   - 差集(B-A)：只查 bMap
4. 处理 value 查不到的情况（使用空字符串兜底）

**Technical design (directional):**

```
// 构建 recordId → value 映射
Map<String, String> aMap = new LinkedHashMap<>();
for (int i = 0; i < aRecordIds.size(); i++) {
    aMap.put(aRecordIds.get(i), i < aValues.size() ? aValues.get(i) : "");
}
Map<String, String> bMap = new LinkedHashMap<>();
// 同理构建 bMap
Map<String, String> mergedMap = new LinkedHashMap<>(bMap);
mergedMap.putAll(aMap); // aMap 覆盖 bMap（同一 ID 以 A 为准）

// 基于 linkRecordId 做集合运算
rRecordIds = CollectionUtils.intersection(aRecordIds, bRecordIds);

// 根据 rRecordIds 反查 value
for (String id : rRecordIds) {
    rValues.add(mergedMap.getOrDefault(id, ""));
}
```

**Patterns to follow:** 现有 `CollectionUtils` 运算模式

**Test scenarios:**
- A 和 B 的 value 和 recordId 完全对齐，交集结果正确
- A 的 value 有交集但 recordId 无交集，结果中 value 和 recordId 仍一一对应
- 差集运算后，rValues 中的 value 是被减集的 value
- 补集运算后，rValues 正确反映对称差
- 并集运算后，rValues 正确反映合并

**Verification:** 现有 21+21 组合行为不变，Bug 1 场景不再出现

---

### U3. 修改集合运算分支支持 type=26 列

**Goal:** 在集合运算获取 A/B 列数据时，根据列类型走不同分支，支持 type=26 lookup 列参与运算。

**Requirements:** R1, R2, R3, R5, R6

**Dependencies:** U1, U2

**Files:**
- `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java`
- `ruoyi-system/src/test/java/com/ruoyi/system/service/impl/NoteRecordServiceImplTest.java`

**Approach:**

1. 在获取 A 列数据时（`columnAId.equals(columnId)` 分支），查询 A 列类型：
   - type=21：现有逻辑，从 itemMap 取 recordId 和 value
   - type=26：从 itemMap 取 recordId 作为 linkRecordId 列表，调用 `resolveLookupValues(itemColumnA, linkRecordIds)` 获取 value 列表

2. 在获取 B 列数据时（从数据库查询 itemB），查询 B 列类型：
   - type=21：现有逻辑，从 itemB 取 linkRecordId 和 value
   - type=26：从 itemB 取 linkRecordId 列表，调用 `resolveLookupValues(itemColumnB, linkRecordIds)` 获取 value 列表

3. 在 `columnBId.equals(columnId)` 分支中做同样处理（A/B 对称）

4. 需要通过 `noteColumnMapper.selectNoteColumnById()` 查询列类型

**关键点**：lookup 列作为 A 列时，linkRecordId 来自 itemMap（前端最新值），value 通过 `resolveLookupValues` 解析。**不调用 `resolveLookupValueWithRecordId`**，因为该方法会从数据库查询 linkRecordId，而数据库数据可能尚未更新。

**Patterns to follow:** 现有集合运算分支结构

**Test scenarios:**
- 21+21 组合：行为与现有完全一致（回归测试）
- 21+26 组合：A 列双向关联、B 列 lookup，运算结果正确
- 26+21 组合：A 列 lookup、B 列双向关联，运算结果正确
- 26+26 组合：A 列 lookup、B 列 lookup，运算结果正确
- lookup 列作为 A 列（当前修改列），itemMap 中 recordId 正确传入，value 通过 resolveLookupValues 解析
- lookup 列作为 B 列（从数据库查询），itemB 的 linkRecordId 正确获取，value 通过 resolveLookupValues 解析
- lookup 列的 linkRecordId 为空时，跳过运算（与现有 type=21 空值处理一致）

**Verification:** 四种列类型组合的集合运算结果正确，现有 21+21 行为不变

---

## Scope Boundaries

### In scope
- 新增 `LookupResult` 内部类、`resolveLookupValues` 核心方法和 `resolveLookupValueWithRecordId` 便捷方法
- 修复 Bug 1：集合运算基于 linkRecordId 运算后反查 value
- 集合运算分支支持 type=26 列
- 四种列类型组合的单元测试

### Deferred for later
- 新增集合运算类型（对称差、去重合并等）
- 修改前端配置界面
- 修改 `selectNoteRecordData` 方法
- 修改 `deleteRecordById` 中集合运算结果的同步清理逻辑
- Bug 3 的完整修复（lookup 列作为 A 列时 itemMap 数据完整性验证）

### Outside scope
- 修改集合运算结果列的存储格式
- 修改 `resolveLookupValue` 方法本身的逻辑（仅重构为委托）

---

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| lookup 列作为 A 列时 itemMap 中 recordId 不完整 | 集合运算结果不正确 | Bug 3 记录在案，U3 中增加空值保护；如确认有问题需前端配合 |
| 重构集合运算逻辑引入回归 | 现有 21+21 功能异常 | U3 包含回归测试场景 |
| `resolveLookupValues` 与 `resolveLookupValue` 代码重复 | 维护成本 | U1 中让 resolveLookupValue 委托给 resolveLookupValues |
| lookup 列作为 A 列时数据库数据未更新 | resolveLookupValueWithRecordId 返回旧数据 | KTD-4 明确使用 resolveLookupValues 而非 resolveLookupValueWithRecordId |

---

## Open Questions

1. **Bug 3 确认**：当 lookup 列作为 A 列（当前修改列）时，前端 `itemMap` 中 `recordId` 字段是否包含 lookup 关联的记录 ID？如果不包含，需要前端配合修改或改为从数据库查询。当前计划中先按 itemMap 包含 recordId 的假设实现，增加空值保护。
