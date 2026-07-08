# 集合运算支持 Lookup 列参与

## 背景

当前 `updateNoteRecord` 方法中的集合运算分支（约第216-310行）仅支持双向关联列(type=21)作为运算参与列。集合运算列的 property 中记录了 `columnAId` 和 `columnBId`，代码假设这两个列都是 type=21，直接从 item 的 `linkRecordId` 和 `value` 取数据参与交并差补运算。

Lookup 列(type=26)的值来源于关联记录，同样具有 `linkRecordId` 和 `value`，但目前无法参与集合运算。

## 目标

扩展集合运算分支，使 A 列和 B 列可以任意组合 type=21 和 type=26，即支持 21+21、21+26、26+21、26+26 四种组合。

## 核心规则

1. **值来源按列类型区分**：
   - type=21（双向关联列）：直接取 item 的 `linkRecordId` 和 `value`
   - type=26（lookup列）：调用新方法获取 lookup 解析后的 `value` 及对应的 `linkRecordId`

2. **Lookup 列的 linkRecordId 获取方式**：lookup 列的值通过双向链接关联到另一张表的记录，lookup 解析过程中已经涉及了 `linkRecordId`。新建一个与 `resolveLookupValue` 类似的方法，返回值同时包含 lookup 的 value 和对应的 linkRecordId。

3. **运算逻辑不变**：并集(union)、交集(intersection)、差集(subtract)、补集(disjunction)四种运算，结果同时包含 `value` 和 `linkRecordId`。

4. **结果列存储格式不变**：集合运算结果列的 item 仍然存储 `value` 和 `linkRecordId`，格式与现有方式一致（逗号分隔字符串）。

## 关键改动

### 1. 新增 Lookup 解析方法

在 `NoteRecordServiceImpl` 中新增方法（与 `resolveLookupValue` 类似），返回值包含 value 和 linkRecordId：

- 方法名建议：`resolveLookupValueWithRecordId`
- 输入：NoteColumn（lookup列）、Long recordId（当前记录ID）
- 输出：包含 `value`（String）和 `linkRecordId`（String）的结构
- 逻辑：复用 `resolveLookupValue` 的指针追逐逻辑追踪到最终列，同时保留 lookup 列 item 自身的 `linkRecordId`（即 lookup 所引用的关联记录行ID）

### 2. 修改集合运算分支的数据获取逻辑

当前代码（约第240-270行）在获取 A/B 列数据时，直接从 item 取 `linkRecordId` 和 `value`。修改为：

```
if (列类型 == 21) {
    // 现有逻辑：直接取 item 的 linkRecordId 和 value
} else if (列类型 == 26) {
    // 新增逻辑：调用 resolveLookupValueWithRecordId 获取 value 和 linkRecordId
}
```

### 3. 集合运算计算部分不变

获取到统一的 `aRecordIds`、`aValues`、`bRecordIds`、`bValues` 后，现有的交并差补运算逻辑无需修改。

## 审查发现的问题

### Bug 1：现有集合运算中 value 和 linkRecordId 的对应关系可能错位

**现状**：当前代码对 `aValues` 和 `aRecordIds` 分别独立做集合运算（如 `CollectionUtils.intersection(aValues, bValues)` 和 `CollectionUtils.intersection(aRecordIds, bRecordIds)`），但 `CollectionUtils.intersection` 返回的结果顺序不保证与输入一致。

**风险**：当 A 和 B 有部分交集时，`rValues` 和 `rRecordIds` 的元素可能不再一一对应。例如 A 的 value=["张三","李四"] recordId=["1","2"]，B 的 value=["李四","王五"] recordId=["2","3"]，交集运算后 rValues=["李四"] rRecordIds=["2"]，此时恰好对齐。但如果 A 的 value=["张三","李四"] recordId=["1","2"]，B 的 value=["李四"] recordId=["3"]，交集后 rValues=["李四"] rRecordIds=[]（因为 recordId 没有交集），value 和 recordId 就完全脱节了。

**建议**：集合运算应基于 `linkRecordId` 做运算，然后根据运算后的 `linkRecordId` 结果去查找对应的 `value`，而不是对 value 和 recordId 分别独立运算。这不仅是 lookup 支持需要解决的问题，也是现有 21+21 组合中潜在的数据错位 bug。

### Bug 2：lookup 列 item 的 linkRecordId 含义与双向关联列不同

**现状**：在 `selectNoteRecordData` 方法（约第730行）中可以看到，lookup 列(type=26)的 item 在数据库中存储的 `linkRecordId` 是**双向链接列 item 的 linkRecordId**，即 lookup 所引用的关联记录 ID 列表。这与双向关联列(type=21)的 `linkRecordId` 语义一致。

**但是**：lookup 列的 `value` 字段在数据库中存储的是**空字符串或未解析的原始值**，实际展示的值是通过 `resolveLookupValue` 动态解析得到的。因此，直接从 lookup 列的 item 取 `value` 是拿不到正确值的。

**影响**：新方法 `resolveLookupValueWithRecordId` 必须通过 `resolveLookupValue` 的逻辑获取 value，不能直接读 item.value。而 `linkRecordId` 可以直接从 item 获取（与 type=21 语义一致）。

### Bug 3：当前修改列是 A 列时，A 的数据从 itemMap 取而非数据库取

**现状**：当 `columnAId.equals(columnId)` 时（当前修改的列是 A 列），代码从 `itemMap.get("recordId")` 和 `itemMap.get("value")` 取 A 的数据，而不是从数据库查询。这是合理的——因为当前正在更新的值还没写入数据库。

**风险**：当 lookup 列作为 A 列时，`itemMap` 中传递的 `recordId` 和 `value` 可能不包含 lookup 解析后的值。需要确认前端在更新 lookup 列的 item 时，`itemMap` 中 `recordId` 字段是否包含 lookup 关联的记录 ID。

**建议**：如果 lookup 列作为 A 列（当前正在修改的列），需要从 itemMap 中取 linkRecordId，然后调用 `resolveLookupValueWithRecordId` 获取 value。如果 itemMap 中没有足够的 lookup 信息，可能需要改为从数据库查询刚更新后的 item。

### 对其他方法的影响

1. **`resolveLookupValue` 方法**：不需要修改，新方法 `resolveLookupValueWithRecordId` 内部复用其指针追逐逻辑，但返回值结构不同（增加 linkRecordId）。

2. **`selectNoteRecordData` 方法**（查询数据展示）：该方法在 type=26 时调用 `resolveLookupValue`，返回值只有 value 没有 linkRecordId。如果后续前端需要展示集合运算结果中 lookup 来源的 linkRecordId，该方法可能也需要调整，但不在本次需求范围内。

3. **`deleteNoteRecordById` 方法**（删除记录）：该方法在删除记录时会清理双向关联列的 linkRecordId。如果集合运算结果列引用了 lookup 列的关联记录，删除时需要同步更新集合运算结果。当前代码在删除记录后没有触发集合运算的重新计算，这是一个已有的潜在问题，不在本次需求范围内。

## 范围边界

### 包含

- 新增 `resolveLookupValueWithRecordId` 方法
- 修改集合运算分支中获取 A/B 列数据的逻辑，支持 type=26
- 修复 Bug 1：集合运算应基于 linkRecordId 做运算，value 根据 linkRecordId 结果反查
- 确保四种组合（21+21、21+26、26+21、26+26）都能正确运算

### 不包含

- 新增集合运算类型（如对称差、去重合并等）
- 修改集合运算结果列的存储格式
- 修改前端配置界面
- 修改 `resolveLookupValue` 方法本身
- 修改 `selectNoteRecordData` 方法
- 修改 `deleteRecordById` 中集合运算结果的同步清理逻辑

## 成功标准

1. A列和B列分别为 type=21 或 type=26 的任意组合时，集合运算结果正确
2. 现有的 21+21 组合行为不受影响（回归兼容）
3. lookup 列参与运算时，`linkRecordId` 为 lookup 所引用的关联记录行ID，`value` 为 lookup 解析后的最终值
4. 集合运算结果的 value 和 linkRecordId 保持一一对应关系
