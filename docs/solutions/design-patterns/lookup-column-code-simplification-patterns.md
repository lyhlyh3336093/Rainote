---
title: "Lookup列代码简化模式：helper提取与itemMap决策集中化"
date: 2026-07-02
category: design-patterns
module: note-system
problem_type: design_pattern
component: service_object
severity: low
applies_when:
  - "Structuring lookup column data-retrieval code that chooses between in-memory itemMap data and DB queries"
  - "Eliminating duplicate lookup-resolution logic across multiple call sites in set operations"
  - "Refactoring lookup value resolution to preserve order correspondence with linkRecordIds"
symptoms:
  - "Duplicate lookup linkRecordId-resolution logic repeated across four data-retrieval blocks"
  - "Scattered useItemMap checks mixing in-memory new data with DB query paths"
  - "Dead code (resolveLookupValueWithRecordId) left behind after behavior was consolidated"
  - "Verbose logging and redundant comments obscuring the core lookup flow"
resolution_type: code_fix
related_components:
  - database
tags:
  - lookup-column
  - set-operation
  - refactoring
  - helper-extraction
  - itemmap-vs-db
  - note-record
---

# Lookup列代码简化模式：helper提取与itemMap决策集中化

## Context

`NoteRecordServiceImpl.java` 的集合运算分支（`updateNoteRecord` 方法内）包含四个对称的数据获取块——A/B 列在 `aMatched` 和 `bMatched` 两种匹配路径下各有一块——每块都需要在 lookup 列（type=26）参与运算时获取其 `linkRecordId` 列表，再调用 `resolveLookupValues` 解析 value。

简化前，每块独立重复实现同一个决策：`linkRecordId` 列表应来自内存 `itemMap`（刚修改、尚未入库的 item）还是来自 `noteDwtableItemMapper` 的 DB 查询？这造成两个问题：

1. **维护风险**——一次修复或行为变更需手动应用到四处。四块的 `useItemMap` 判断已经漂移：表述不一致，尽管语义相同。
2. **隐蔽的正确性风险**——itemMap-vs-DB 决策依赖一个时序不变量（集合运算在 `updateNoteRecord` 末尾 item 入库*之前*运行）。当 `double_link_column_id` 匹配当前修改列时，DB 仍是旧数据，必须用 `itemMap`。在四个内联块中重复这一推理极易出错。

本次简化（ce-simplify-code）在保持行为不变的前提下提取 helper、删除死代码、统一判断逻辑，7 个测试用例前后全绿。本 doc 是其前置 bug 修复工作（见 [Related](#related)）之后的代码清理沉淀。

## Guidance

在对称的 lookup 列代码路径中应用以下模式。

### 模式 1 — 为重复的 linkRecordId 获取提取单一 helper

将"选择 itemMap 还是 DB"的决策抽到一个私有 helper 中，每个对称块调用它而非各自重推导逻辑：

```java
private List<String> getLookupLinkRecordIds(NoteColumn lookupColumn, String currentColumnId,
        String currentBackFieldId, Map<String, Object> itemMap, Long recordId)
{
    JSONObject prop = JSONObject.parseObject(lookupColumn.getProperty());
    String doubleLinkColumnId = prop.get("double_link_column_id").toString();

    if ((currentColumnId.equals(doubleLinkColumnId) || currentBackFieldId.equals(doubleLinkColumnId))
            && itemMap.get("recordId") != null && !"".equals(itemMap.get("recordId").toString()))
    {
        // 当前修改item尚未入库，必须用itemMap新数据
        List<String> ids = new ArrayList<>(Arrays.asList(itemMap.get("recordId").toString().split(",")));
        return ids;
    }

    NoteDwtableItem query = new NoteDwtableItem();
    query.setRecordId(recordId);
    query.setLinkColumnId(Long.valueOf(doubleLinkColumnId));
    NoteDwtableItem linkItem = noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(query);
    if (linkItem == null || linkItem.getLinkRecordId() == null || "".equals(linkItem.getLinkRecordId()))
    {
        return null;
    }
    List<String> ids = new ArrayList<>(Arrays.asList(linkItem.getLinkRecordId().split(",")));
    return ids;
}
```

### 模式 2 — 将 itemMap-vs-DB 决策集中到该 helper 内部

不让调用方直接触碰 `itemMap`。helper 拥有这个时序不变量。调用方传入 `currentColumnId` 和 `currentBackFieldId`，由 helper 决策。匹配谓词——`double_link_column_id` 等于当前列 ID 或当前列的 `back_field_id`——只存在于这一处。

### 模式 3 — 用 return null 表示"跳过本次集合运算"

当 DB 查询无数据时 helper 返回 `null`，调用方将其视为 `continue` 信号。这让四处的跳过逻辑保持一致且简洁：

```java
aRecordIds = getLookupLinkRecordIds(columnA, columnId, currentBackFieldId, itemMap, noteRecord.getId());
if (aRecordIds == null) {
    continue;
}
aValues = toLookupValues(resolveLookupValues(columnA, aRecordIds));
```

`null` 保留给"无事可做，跳过"——区别于空列表（表示"零个 ID 但仍继续"）。保持这一区分使四个调用点读起来完全一致。

### 模式 4 — 用专用 toLookupValues helper 保持顺序对应

`resolveLookupValues` 返回 `List<LookupResult>`；调用方需要 `List<String>`，其索引 `i` 与 `linkRecordIds[i]` 对应。提取该映射，让四块停止重复同一个 for 循环：

```java
private List<String> toLookupValues(List<LookupResult> results)
{
    List<String> values = new ArrayList<>(results.size());
    for (LookupResult lr : results)
    {
        values.add(lr.getValue());
    }
    return values;
}
```

### 模式 5 — 删除提取后产生的死代码

四块全部迁移到 `getLookupLinkRecordIds` + `resolveLookupValues` + `toLookupValues` 后，便捷包装方法 `resolveLookupValueWithRecordId`（内部查 DB 取 `linkRecordIds` 再调 `resolveLookupValues`）已无调用方，应删除。提取后务必扫一遍新孤立的 helper——留着它们会诱使未来的调用方绕过集中化的决策逻辑。

## Why This Matters

- **构造性一致性。** 简化前，itemMap-vs-DB 决策的修复需应用到四个内联块（行 275、304、327、349），且四块的 `useItemMap` 判断已漂移。简化后决策只在一个方法中，四块不再包含决策，无法再次漂移。
- **防止 stale-data 回归。** itemMap-vs-DB 分支对正确性至关重要：当 `double_link_column_id` 匹配当前修改列时，集合运算在 `updateNoteRecord` 末尾 item 入库*之前*运行，DB 查询返回修改前状态，lookup 会静默使用旧数据。将这一不变量集中到 `getLookupLinkRecordIds` 中，意味着解释*为何*必须用 itemMap 的注释（"当前修改item尚未入库"）只存在一次，紧贴执行代码，而非被改写或省略四次。
- **缩小表面积。** 删除 `resolveLookupValueWithRecordId` 消除了一条绕过新 helper 的代码路径。若不删除，未来开发者可能调用旧便捷方法，重新引入重复的内联决策。
- **测试信号。** 7 个测试前后全绿，确认重构保持行为。这是未来对四块中任一块做"小修复"时都应重新验证的基线。

## When to Apply

- lookup 列代码（`type == 26`）中，多个对称块（A/B/C/D，或任意 N 路集合运算）各自获取 `linkRecordId` 列表后再解析 value。
- itemMap-vs-DB 决策在块间重复，或 `useItemMap` 判断的表述开始在块间漂移。
- 集合运算在修改 item 持久化*之前*运行，因此对当前 item 的 link 行做 DB 查询会返回旧数据——必须改查 `itemMap`。（`updateNoteRecord` 即为此情形。）
- `List<LookupResult>` → `List<String>` 映射重复出现，且与 `linkRecordIds` 的顺序对应必须保持。
- 提取 helper 后，原有的便捷包装方法（如 `resolveLookupValueWithRecordId`）已无调用方。

## Examples

### Before — 四块各自重复内联逻辑

四个块（A/B 在 aMatched/bMatched 路径下）独立重推导 itemMap-vs-DB 决策，并重实现 `LookupResult` → `String` 循环。四个 `useItemMap` 表述不完全相同，跳过空值的逻辑按块内联：

```java
// Block A (lookup 分支) — 四份近似拷贝之一
if (columnA != null && columnA.getType() == 26L) {
    JSONObject propA = JSONObject.parseObject(columnA.getProperty());
    String doubleLinkA = propA.get("double_link_column_id").toString();
    boolean useItemMapA = (columnId.equals(doubleLinkA) || currentBackFieldId.equals(doubleLinkA))
            && itemMap.get("recordId") != null && !"".equals(itemMap.get("recordId").toString());
    if (useItemMapA) {
        aRecordIds = new ArrayList<>(Arrays.asList(itemMap.get("recordId").toString().split(",")));
    } else {
        NoteDwtableItem q = new NoteDwtableItem();
        q.setRecordId(noteRecord.getId());
        q.setLinkColumnId(Long.valueOf(doubleLinkA));
        NoteDwtableItem li = noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(q);
        if (li == null || li.getLinkRecordId() == null || "".equals(li.getLinkRecordId())) {
            continue;
        }
        aRecordIds = new ArrayList<>(Arrays.asList(li.getLinkRecordId().split(",")));
    }
    List<LookupResult> aResults = resolveLookupValues(columnA, aRecordIds);
    aValues = new ArrayList<>();
    for (LookupResult r : aResults) {
        aValues.add(r.getValue());
    }
}
// ... 块 B、C、D 重复同样结构，各有细微差异
```

死代码 `resolveLookupValueWithRecordId(...)` 同时存在，内部查 DB 取 `linkRecordIds` 再调 `resolveLookupValues`。

### After — 四块调用同一 helper

```java
// Block A (lookup 分支)
if (columnA != null && columnA.getType() == 26L) {
    aRecordIds = getLookupLinkRecordIds(columnA, columnId, currentBackFieldId, itemMap, noteRecord.getId());
    if (aRecordIds == null) {
        continue;
    }
    aValues = toLookupValues(resolveLookupValues(columnA, aRecordIds));
} else {
    // type=21 双向关联列 — 保留内联，因其分支结构不同
    if (columnAId.equals(columnId)) {
        aRecordIds = new ArrayList<String>(Arrays.asList(itemMap.get("recordId").toString().split(",")));
        aValues    = new ArrayList<String>(Arrays.asList(itemMap.get("value").toString().split(",")));
    } else {
        // ... DB 查询 A 列数据 ...
    }
}
// 块 B、C、D 现在完全一致 — 同一 helper、同一 null 检查、同一 toLookupValues。
```

### 死代码删除

`resolveLookupValueWithRecordId` 已删除。四块迁移到 `getLookupLinkRecordIds` + `resolveLookupValues` + `toLookupValues` 后它已无调用方。留着它会为未来代码提供绕过集中化 itemMap-vs-DB 决策的途径，因此删除是重构的一部分，而非装饰性清理。

### Deferred improvements（识别但未修复）

简化过程中注意到两个性能问题，因超出"保持行为不变"的简化范围而有意推迟：

- **N+1 lookup 查询。** `resolveLookupValues`（约行 1037）对每个 `linkRecordId` 发起一次 DB 查询。批量化需要新的 Mapper 方法（如 `selectLookupValuesByRecordIds`）并改变 lookup-value 解析契约。记录于此，为下一轮提供明确起点。
- **冗余的 selectNoteColumnById 调用。** `resolveLookupValues` 在单次 `updateNoteRecord` 调用内每次都重新遍历 source column 链。source column 不会在调用中途变化，可缓存到以 column id 为键的请求级 Map 中。与 N+1 修复一起做更合适，因两者触及同一热路径。

两者均未在本轮修复，以保持 diff 严格行为不变（7 测试全绿）；两者都是后续性能-track pass 的候选。

## Related

- **前置 bug 修复**: [lookup-column-set-operation-logic-errors.md](../logic-errors/lookup-column-set-operation-logic-errors.md) — 本简化清理的正是该 doc 记录的三个 bug 修复后的代码。`getLookupLinkRecordIds` 包含了该 doc 修复1 的 linkRecordId 获取逻辑，并额外封装了 itemMap-vs-DB 决策。
- **领域词汇**: [CONCEPTS.md](../../../CONCEPTS.md) — Lookup Column (type=26)、Double Link Column (type=21)、Set Operation Column、`back_field_id`、NoteDwtableItem 的定义。
- **需求文档**: `docs/brainstorms/2026-06-23-set-operation-lookup-requirements.md`
- **实施计划**: `docs/plans/2026-06-23-001-feat-set-operation-lookup-plan.md`
