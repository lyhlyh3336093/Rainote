---
title: "Lookup列(type=26)在集合运算中的三个逻辑错误"
date: 2026-06-23
category: logic-errors
module: note-system
problem_type: logic_error
component: service_object
symptoms:
  - "lookup列与双向链接列做差集运算时结果为空"
  - "间接匹配触发集合运算时type=21列从itemMap取到错误数据"
  - "集合运算结果item不存在时NPE崩溃"
root_cause: logic_error
resolution_type: code_fix
severity: high
last_updated: 2026-07-02
tags: [lookup-column, set-operation, type-26, double-link, back-field-id, collection-utils]
---

# Lookup列(type=26)在集合运算中的三个逻辑错误

## Problem

在 `NoteRecordServiceImpl` 的集合运算分支中，当 lookup 列(type=26)作为集合运算的 A 列或 B 列时，存在三个逻辑错误导致运算结果为空或程序崩溃：(1) lookup 列的 linkRecordId 获取方式错误；(2) 间接匹配触发时 type=21 列从 itemMap 取到错误数据；(3) 结果 item 不存在时 NPE。

## Symptoms

- lookup 列与双向链接列做差集/交集/并集/补集运算时，结果始终为空
- 用户修改底层双向链接列后，引用该列的 lookup 集合运算列不更新
- 首次触发集合运算时，若结果 item 尚未创建，抛出 NullPointerException

## What Didn't Work

1. **直接查 lookup 列自身 item 的 linkRecordId** — lookup 列是计算列，自身 item 的 linkRecordId 和 linkColumnId 字段都是 NULL，关联记录 ID 实际存储在它引用的双向链接列 item 上
2. **仅检查 columnId 是否等于 double_link_column_id** — 用户修改的是配对双向链接列(如 3262)，而 lookup 列的 `double_link_column_id` 是另一个表中的列(如 3263)，两者 ID 不同
3. **间接匹配时 type=21 列仍从 itemMap 取数据** — itemMap 是当前修改列的数据，间接匹配时 A/B 列不是当前修改列，取到的是错误数据

## Solution

### 修复 1: lookup 列 linkRecordId 获取方式

lookup 列的关联记录 ID 不存储在自身 item 上，而是通过 `double_link_column_id` 查询关联的双向链接列 item 获取：

```java
// 错误方式：直接查 lookup 列自身 item（linkRecordId 为 NULL）
NoteDwtableItem queryItem = new NoteDwtableItem();
queryItem.setRecordId(recordId);
queryItem.setColumnId(lookupColumnId);  // lookup 列自身，linkRecordId 为 NULL

// 正确方式：通过 double_link_column_id 查双向链接列 item
// （现已提取为 getLookupLinkRecordIds helper，见 NoteRecordServiceImpl:953）
JSONObject prop = JSONObject.parseObject(lookupColumn.getProperty());
String doubleLinkColumnId = prop.get("double_link_column_id").toString();

// 关键：当 double_link_column_id 匹配当前修改列（直接或经 back_field_id 配对）时，
// 当前 item 尚未入库（集合运算早于 item 入库），DB 查询会拿到旧数据，必须改用 itemMap
if ((currentColumnId.equals(doubleLinkColumnId) || currentBackFieldId.equals(doubleLinkColumnId))
        && itemMap.get("recordId") != null && !"".equals(itemMap.get("recordId").toString())) {
    // 用 itemMap 新数据
    linkRecordIds = Arrays.asList(itemMap.get("recordId").toString().split(","));
} else {
    // 从 DB 查双向链接列 item
    NoteDwtableItem query = new NoteDwtableItem();
    query.setRecordId(recordId);
    query.setLinkColumnId(Long.valueOf(doubleLinkColumnId));
    NoteDwtableItem linkItem = noteDwtableItemMapper
        .selectNoteDwtableItemByRecordAndColumn(query);
    // linkItem.getLinkRecordId() 才是真正的关联记录 ID
}
```

> **代码结构演进**：上述逻辑现已统一提取到 `getLookupLinkRecordIds` helper（`NoteRecordServiceImpl.java:953`），四个对称数据获取块共用此 helper。该 helper 返回 `null` 表示 DB 无数据需跳过本次运算。详见 [lookup-column-code-simplification-patterns.md](../design-patterns/lookup-column-code-simplification-patterns.md)。

> **itemMap-vs-DB 决策**（修复1 的关键补充）：原修复仅覆盖 DB 查询路径。后续发现当 `double_link_column_id` 指向当前修改 item 时，集合运算在 item 入库前运行（见 `updateNoteRecord` 末尾），DB 返回旧数据导致结果为空。helper 内集中了这一时序判断，调用方无需重复实现。

### 修复 2: 间接匹配触发 + back_field_id 配对匹配

用户修改的列(如 3262)和 lookup 列的 `double_link_column_id`(如 3263)是配对的双向链接列，需通过 `back_field_id` 找到配对关系：

```java
// 获取当前修改列的 back_field_id，用于间接匹配
String currentBackFieldId = "";
NoteColumn currentCol = noteColumnMapper.selectNoteColumnById(Long.parseLong(columnId));
if (currentCol != null && currentCol.getType() == 21L) {
    JSONObject currentProp = JSONObject.parseObject(currentCol.getProperty());
    currentBackFieldId = currentProp.get("back_field_id") != null
        ? currentProp.get("back_field_id").toString() : "";
}

// 间接匹配：columnId 或其 back_field_id 等于 double_link_column_id
if (columnId.equals(doubleLinkColId) || currentBackFieldId.equals(doubleLinkColId)) {
    bMatched = true;
}
```

数据关系示例：
- 3262(所需原料, type=21, 表1606) 的 `back_field_id=3263`
- 3263(所需原料双向链接, type=21, 表1605) 的 `back_field_id=3262`
- 3268(所需类型, type=26, lookup) 的 `double_link_column_id=3263`
- 用户修改 3262 → `currentBackFieldId=3263` 匹配 `doubleLinkColIdB=3263` → 触发集合运算

### 修复 3: 间接匹配时 type=21 列从数据库查询

间接匹配时 A/B 列不是当前修改列，不能从 itemMap 取数据，需从数据库查询：

```java
// type=21 双向关联列
if (columnAId.equals(columnId)) {
    // 直接匹配：itemMap 就是 A 列的数据
    aRecordIds = new ArrayList<>(Arrays.asList(
        itemMap.get("recordId").toString().split(",")));
    aValues = new ArrayList<>(Arrays.asList(
        itemMap.get("value").toString().split(",")));
} else {
    // 间接匹配：从数据库查询 A 列数据
    NoteDwtableItem queryItemA = new NoteDwtableItem();
    queryItemA.setRecordId(noteRecord.getId());
    queryItemA.setColumnId(Long.parseLong(columnAId));
    NoteDwtableItem itemA = noteDwtableItemMapper
        .selectNoteDwtableItemByRecordAndColumn(queryItemA);
    if (itemA == null || itemA.getLinkRecordId() == null
        || "".equals(itemA.getLinkRecordId())) {
        continue;
    }
    aRecordIds = new ArrayList<>(Arrays.asList(itemA.getLinkRecordId().split(",")));
    aValues = new ArrayList<>(Arrays.asList(itemA.getValue().split(",")));
}
```

### 修复 4: 结果 item 不存在时新建而非 NPE

```java
NoteDwtableItem resultItem = noteDwtableItemMapper
    .selectNoteDwtableItemByRecordAndColumn(queryItem);
if (resultItem == null) {
    resultItem = new NoteDwtableItem();
    resultItem.setRecordId(noteRecord.getId());
    resultItem.setColumnId(column.getId());
    resultItem.setDwtId(queryColumn.getDwtableId());
}
resultItem.setValue(rValues.toString().replace("[", "").replace("]", "").replaceAll(" ", ""));
resultItem.setLinkRecordId(rRecordIds.toString().replace("[", "").replace("]", "").replaceAll(" ", ""));
if (resultItem.getId() == null) {
    noteDwtableItemMapper.insertNoteDwtableItem(resultItem);
} else {
    noteDwtableItemMapper.updateNoteDwtableItem(resultItem);
}
```

## Why This Works

lookup 列是计算列，其值来源于底层双向链接列的关联数据。三个错误的根本原因都是没有正确理解 lookup 列的数据存储模型：

1. **linkRecordId 存储位置**：lookup 列自身 item 不存储 linkRecordId，它通过 `double_link_column_id` 引用一个双向链接列，关联记录 ID 存储在那个双向链接列的 item 上
2. **触发链路**：用户修改的是配对双向链接列(如 3262)，而 lookup 列引用的是另一个表中的配对列(如 3263)，需要通过 `back_field_id` 找到配对关系才能完成间接触发
3. **数据来源**：间接匹配时当前修改列的数据(itemMap)不是 A/B 列的数据，必须从数据库查询

## Prevention

- **理解 lookup 列数据模型**：lookup 列(type=26)是计算列，自身 item 的 linkRecordId/linkColumnId 为 NULL，所有关联数据通过 `double_link_column_id` 间接获取
- **区分直接匹配和间接匹配**：当集合运算的 A/B 列不是当前修改列时，不能从 itemMap 取数据，必须从数据库查询
- **配对双向链接列关系**：两个配对的双向链接列通过 `back_field_id` 互相引用，间接触发匹配时需要检查 `back_field_id`
- **null 安全**：集合运算结果 item 可能不存在（首次运算），需判空后新建

## Related Issues

- 代码简化后续: [lookup-column-code-simplification-patterns.md](../design-patterns/lookup-column-code-simplification-patterns.md) — 本 doc 三个修复后的代码清理，将修复1 的 linkRecordId 获取逻辑提取为 `getLookupLinkRecordIds` helper
- 需求文档: `docs/brainstorms/2026-06-23-set-operation-lookup-requirements.md`
- 实施计划: `docs/plans/2026-06-23-001-feat-set-operation-lookup-plan.md`
