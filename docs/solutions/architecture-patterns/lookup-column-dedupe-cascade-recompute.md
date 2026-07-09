---
title: "Lookup列去重开关级联重算模式"
date: 2026-07-08
category: docs/solutions/architecture-patterns/
module: lookup-column-recompute
problem_type: architecture_pattern
component: service_object
severity: high
applies_when:
  - "Lookup列property中的配置开关发生变化，需要重算该列存储值及依赖它的集合运算列"
  - "在笔记记录系统中为计算列或Lookup列实现去重(dedupe)或类似的开关功能"
  - "需要在引用了某个已变更源列的所有列之间进行级联重算"
related_components:
  - database
tags: [lookup-column, dedupe, cascade-recompute, set-operations, data-integrity, config-toggle, recompute-trigger, spring-boot]
---

# Lookup列去重开关级联重算模式

## 背景

Lookup列(type=26)是RuoYi笔记/数据表系统中的计算列，其值通过 `double_link_column_id` → `linkRecordIds` → `source_column_id` 链路派生后以","拼接而成。当多个关联记录共享同一个源值时（例如三条记录都归类为"苹果"），Lookup列会显示"苹果,苹果,苹果"，无法折叠重复项。

此前没有去重机制，而更深层的问题是：**计算列构成一个依赖图**。Lookup列会作为集合运算列（并集/交集/差集/补集）的输入源，因此任何改变Lookup列值派生方式的操作都必须级联到所有依赖它的计算列——而不仅仅是Lookup列本身。

本次工作（见 [计划文档](file:///d:/WorkSpace/RuoYi-Vue/docs/plans/2026-07-06-001-feat-lookup-column-value-dedupe-plan.md) 与 [需求文档](file:///d:/WorkSpace/RuoYi-Vue/docs/brainstorms/2026-07-06-lookup-column-value-dedupe-requirements.md)，需求R1–R9，实现单元U1/U2/U3）为Lookup列的 `property` JSON新增了 `dedupe` 配置开关，并实现了级联重算模式。随后的代码审查在首次实现中发现了6个具体缺陷，每一个都体现了触碰计算列重算逻辑时容易复发的失败模式。

## 指导

### 核心模式：配置开关切换 → 跨依赖计算列级联重算

当计算列 `property` JSON中存储的布尔开关改变了列的派生行为时，更新路径必须触发该列存储值的**全表重算**，**并**触发所有以该列为源的其他计算列的全表重算。重算在列更新入口通过对比新旧property JSON的差异来触发。

**触发位置** —— [NoteColumnServiceImpl.java](file:///d:/WorkSpace/RuoYi-Vue/ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteColumnServiceImpl.java) `updateNoteColumn` (~L220)：

```java
//----lookup列dedupe开关变化时触发全量重算----
if (originColumn.getType() == 26L && noteColumnvo.getType() == 26L
        && originColumn.getProperty() != null && noteColumn.getProperty() != null)
{
    try
    {
        JSONObject oldProp = JSONObject.parseObject(originColumn.getProperty());
        JSONObject newProp = JSONObject.parseObject(noteColumn.getProperty());
        if (oldProp != null && newProp != null)
        {
            boolean oldDedupe = Boolean.TRUE.equals(oldProp.getBoolean("dedupe"));
            boolean newDedupe = Boolean.TRUE.equals(newProp.getBoolean("dedupe"));
            if (oldDedupe != newDedupe)
            {
                noteRecordService.recomputeLookupColumnValues(noteColumn);
                noteRecordService.recomputeSetOperationsForLookup(noteColumn);
            }
        }
    }
    catch (Exception e)
    {
        // property非合法JSON或重算失败，跳过重算
        log.error("[UPDATE-COLUMN] dedupe重算失败 columnId={}", noteColumnvo.getId(), e);
    }
}
```

两个重算方法位于 [NoteRecordServiceImpl.java](file:///d:/WorkSpace/RuoYi-Vue/ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java)，该类持有 `resolveLookupValue` 和所有所需的mapper：

- `recomputeLookupColumnValues(NoteColumn lookupColumn)` —— 遍历Lookup列所在表的每条记录，重新解析值，并upsert存储的 `NoteDwtableItem`（不存在则插入，存在则更新）。单条记录失败会被捕获并记录日志，不会因一条坏记录而中断整表重算。
- `recomputeSetOperationsForLookup(NoteColumn lookupColumn)` —— 查找同表中的所有集合运算列，筛选出 `columnAId`/`columnBId` 引用了本Lookup列的列，然后对每条记录用 `fetchColumnDataFromDB` 读取最新源A/B值后重算集合结果。

切换端点（`GET /system/column/deduplicate`）在 [NoteColumnController.java](file:///d:/WorkSpace/RuoYi-Vue/ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/NoteColumnController.java) 中也调用了同样的两步级联：先翻转 `dedupe` 布尔值，再调用两个重算方法。

**注意**：上述示例只对比 `dedupe` 字段。模式本身适用于 property 中的任意派生影响开关（排序、大小写、唯一性等）。引入新的派生影响开关时，必须扩展 diff 谓词并添加回归测试，否则该开关变更不会触发重算。

### 在单一派生点应用去重

去重只在核心解析器（`resolveLookupValues`）中应用一次，这样展示、存储、集合运算三条路径都能接收到去重后的输入，而无需各路径重复实现。使用 `LinkedHashMap.putIfAbsent` 在折叠重复值的同时保留插入顺序：

```java
Boolean dedupe = jsonObject.getBoolean("dedupe");
if (Boolean.TRUE.equals(dedupe) && !results.isEmpty())
{
    LinkedHashMap<String, LookupResult> deduped = new LinkedHashMap<>();
    for (LookupResult r : results)
    {
        deduped.putIfAbsent(r.getValue(), r);
    }
    results = new ArrayList<>(deduped.values());
}
```

### 派生recordIds与values的索引对齐

当下游消费者同时需要Lookup列的recordIds和values时，**两个列表必须从同一个 `resolveLookupValues` 结果派生**——绝不能将原始 `getLookupLinkRecordIds` 返回（全长）与 `toLookupValues(resolveLookupValues(...))` 返回（去重后长度）混用。`fetchColumnDataFromDB` 正确地做到了这一点：

```java
List<LookupResult> results = resolveLookupValues(column, linkIds);
recordIds.addAll(results.stream().map(LookupResult::getLinkRecordId).collect(Collectors.toList()));
values.addAll(toLookupValues(results));
```

## 为什么这很重要

计算列的存储值是其派生逻辑的**反规范化缓存**。当派生输入改变（本例中是 `dedupe` 开关），任何未刷新的缓存值都会变旧——更糟的是，会悄无声息地变错。两个结构性风险使此模式至关重要：

1. **依赖链上的缓存不一致。** 如果只重算了Lookup列而没有重算消费它的集合运算列，集合运算列会保留去重前的值。用户看到Lookup列显示 `["苹果","梨"]`，而并集列仍然显示 `["苹果","苹果","梨"]`。数据自相矛盾，却没有任何报错。

2. **字符串拼接惯用法导致的静默数据损坏。** 重算路径需要从列表构建结果字符串。使用 `List.toString().replace("[","").replace("]","").replaceAll(" ","")` 序列化列表会静默地剥离每个值的**所有**空格——把 `"张 三"` 变成 `"张三"`，把 `"New York"` 变成 `"NewYork"`。这是数据完整性缺陷，不是格式问题。

下方6个代码审查修复各自捕获了一种独特的失败模式，这些模式在编写重算/序列化逻辑时极易复发。记录在此是为了让下一个计算列特性不再重蹈覆辙。

## 适用场景

- 你正在为计算列添加**存储型配置开关**（布尔、枚举或模式），且该列的值依赖于它，列的结果是缓存/存储的而非纯读取时计算。
- 某个计算列是**其他计算列的源**（集合运算、汇总、派生列）。改变源的派生方式需要级联到所有消费者。
- 你正在编写**全表重算**方法，遍历记录并upsert存储项。
- 你正在将 `List<String>` 序列化为单个逗号分隔字符串存储。
- 你正在重算循环中根据 `calcType`/`mode` 字符串分支，且默认分支当前会让结果为空。

## 示例

### 修复1（P0 — 数据损坏）：序列化列表时绝不要剥离空格

**文件**：[NoteRecordServiceImpl.java](file:///d:/WorkSpace/RuoYi-Vue/ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java)，`recomputeSetOperationsForLookup` (~L1329)。

**修改前** —— `replaceAll(" ", "")` 会剥离值的**所有**空格，将 `"张 三"` 损坏为 `"张三"`：

```java
resultItem.setValue(rValues.toString().replace("[", "").replace("]", "").replaceAll(" ", ""));
resultItem.setLinkRecordId(rRecordIds.toString().replace("[", "").replace("]", "").replaceAll(" ", ""));
```

**修改后** —— 用逗号拼接，保留空格：

```java
resultItem.setValue(String.join(",", rValues));
resultItem.setLinkRecordId(String.join(",", rRecordIds));
```

`List.toString()` 产生 `[a, b, c]`；旧代码剥离了方括号和所有空格。`String.join(",", list)` 才是正确的惯用法。这是最高严重级别的修复，因为它在每次重算时都静默篡改用户数据。

### 修复2（P1 — 缺失级联调用）：列与其依赖者都要重算

**文件**：[NoteColumnServiceImpl.java](file:///d:/WorkSpace/RuoYi-Vue/ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteColumnServiceImpl.java)，`updateNoteColumn` (~L234)。

**修改前** —— 只重算了Lookup列，集合运算列保持陈旧：

```java
if (oldDedupe != newDedupe)
{
    noteRecordService.recomputeLookupColumnValues(noteColumn);
}
```

**修改后** —— 两步级联：

```java
if (oldDedupe != newDedupe)
{
    noteRecordService.recomputeLookupColumnValues(noteColumn);
    noteRecordService.recomputeSetOperationsForLookup(noteColumn);
}
```

这是级联模式的权威表述：配置开关变更需要刷新变更的列**以及**所有消费它的计算列。切换端点已有两个调用；更新路径最初只有一个。

### 修复3（P1 — 静默吞异常）：要记录日志，绝不静默吞没

**文件**：[NoteColumnServiceImpl.java](file:///d:/WorkSpace/RuoYi-Vue/ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteColumnServiceImpl.java)，`updateNoteColumn` catch块 (~L239)。

**修改前** —— 静默吞没，没有任何出错信号：

```java
catch (Exception e)
{
    // property非合法JSON，跳过重算
}
```

**修改后** —— 带columnId上下文记录日志，并为类添加了 `Logger` 字段和import：

```java
catch (Exception e)
{
    // property非合法JSON或重算失败，跳过重算
    log.error("[UPDATE-COLUMN] dedupe重算失败 columnId={}", noteColumnvo.getId(), e);
}
```

重算路径中被吞没的异常是不可见的：用户切换开关，存储值从未更新，也没有任何日志可查。务必用标识键（`columnId`、`recordId`）记录日志，使失败可追溯。

### 修复4（P2 — NPE风险）：要同时守护value，不只是linkRecordId

**文件**：[NoteRecordServiceImpl.java](file:///d:/WorkSpace/RuoYi-Vue/ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java)，`fetchColumnDataFromDB` type=21路径 (~L1202)。

**修改前** —— `value` 为null但 `linkRecordId` 非null时会NPE（随后 `item.getValue().split(",")` 抛异常）：

```java
if (item == null || item.getLinkRecordId() == null || "".equals(item.getLinkRecordId()))
```

**修改后** —— 为value添加null检查：

```java
if (item == null || item.getLinkRecordId() == null || "".equals(item.getLinkRecordId())
        || item.getValue() == null)
```

当两个并行字段可以独立为null时，守护子句必须同时检查两者——早返回应该在**任一**不可用时触发，而不是只在第一个不可用时。

### 修复5（P3 — 冗余查询）：将循环不变查询提升到循环外

**文件**：[NoteRecordServiceImpl.java](file:///d:/WorkSpace/RuoYi-Vue/ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java)，`recomputeSetOperationsForLookup` (~L1219)。

**修改前** —— `selectNoteRecordList` 在循环内每个 `setColumn` 调用一次，M次相同查询：

```java
for (NoteColumn setColumn : setColumns)
{
    NoteRecordVo queryRecord = new NoteRecordVo();
    queryRecord.setDwtableId(lookupColumn.getDwtableId());
    List<NoteRecord> records = noteRecordMapper.selectNoteRecordList(queryRecord);
    // ... uses records
}
```

**修改后** —— 提升到循环前，只调用一次：

```java
NoteRecordVo queryRecord = new NoteRecordVo();
queryRecord.setDwtableId(lookupColumn.getDwtableId());
List<NoteRecord> records = noteRecordMapper.selectNoteRecordList(queryRecord);

for (NoteColumn setColumn : setColumns)
{
    // ... uses records inside loop
}
```

当查询只依赖循环不变输入（此处是 `lookupColumn.getDwtableId()`）时，只计算一次。在一张有10个集合运算列的表上，这是10倍相同DB往返的削减。

### 修复6（P2 — 未知calcType的静默数据清空）：绝不要以空结果贯穿到底

**文件**：[NoteRecordServiceImpl.java](file:///d:/WorkSpace/RuoYi-Vue/ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java)，`recomputeSetOperationsForLookup` (~L1295)。

**修改前** —— 未知 `calcType` 贯穿到底，`rRecordIds` 保持为空，代码继续将空结果写回存储——**静默清空用户数据**：

```java
if ("intersection".equals(calcType))
{
    rRecordIds = (List<String>) CollectionUtils.intersection(aRecordIds, bRecordIds);
}
else if ("union".equals(calcType))
{
    rRecordIds = (List<String>) CollectionUtils.union(aRecordIds, bRecordIds);
}
// 无else —— 未知calcType使rRecordIds为空 → 存储值被清空
```

**修改后** —— else分支跳过并告警：

```java
else if ("union".equals(calcType))
{
    rRecordIds = (List<String>) CollectionUtils.union(aRecordIds, bRecordIds);
}
else
{
    // 未知calcType，跳过本记录，避免清空已有结果
    log.warn("[RECOMPUTE-SET-OP] 未知calcType={} setColumnId={}, recordId={}",
            calcType, setColumn.getId(), record.getId());
    continue;
}
```

规则是：将结果写回存储的重算路径**绝不**应持久化从未处理分支派生的值。如果无法计算结果，就 `continue` 并保留现有存储值——不要写空列表。这是重算循环中最容易复发的一类缺陷模式，因为它既静默又具破坏性。注意：这里的"最容易复发"指缺陷模式的复发性，与修复1的P0（发布阻断优先级）是不同维度——P0按修复紧迫性排序，"容易复发"按模式危险性排序。

### 验证

71个测试通过（25个 `NoteColumnServiceImplTest` + 46个 `NoteRecordServiceImplTest`），0失败。测试套件覆盖了去重开/关语义、四种集合运算类型的索引对齐、切换触发、单条记录失败隔离（R8）以及未知calcType跳过行为。

## 已知局限

以下局限在当前实现中存在，采用本模式时应知悉：

1. **级联仅一层**：`recomputeSetOperationsForLookup` 只重算直接引用本Lookup列的集合运算列。若存在集合运算列消费另一个集合运算列（并集的并集），第二层不会被刷新。多级依赖链需要图遍历来修复点。

2. **无事务边界**：两个重算方法作为独立的服务调用执行，没有 `@Transactional` 包裹。若第一个调用成功、第二个抛异常，列属性已提交，Lookup列已刷新但集合运算列保持陈旧——正是上文"为什么这很重要"中描述的静默不一致场景。catch块只隔离了单条记录错误，不隔离级联级别的编排失败。

3. **逐行catch导致跨行不一致**：`recomputeLookupColumnValues` 捕获并跳过单条记录失败，使部分行反映新去重状态、其余行保留旧状态，唯一信号是日志行。对于反规范化缓存模式，这是数据完整性风险——半迁移表既不是全旧也不是全新。

4. **端点未指定授权**：`GET /system/column/deduplicate` 和 `updateNoteColumn` 重算路径未在文档中声明允许的调用角色。全表重算是特权变更，应在控制器层加 `@PreAuthorize` 限制。

5. **GET端点变更状态**：切换端点使用 GET 方法翻转布尔值并触发全表重算。GET 变更可被书签、预取、`<img>` 标签触发，且绕过非常规方法的 CSRF 处理。应改为 POST/PATCH。

6. **同步重算无规模约束**：两个重算方法在列更新路径中同步执行，遍历每条记录×每个依赖列。大表（万行×多个集合运算列）会阻塞请求或持锁超时。当前无异步队列路径或规模阈值守护。

## 相关文档

- [Lookup列代码简化模式](file:///d:/WorkSpace/RuoYi-Vue/docs/solutions/design-patterns/lookup-column-code-simplification-patterns.md) —— 更广泛的Lookup列实现代码简化模式。与本文档互补；本文档新增了级联重算架构和六个具体失败模式修复。
- [Lookup列集合运算逻辑错误](file:///d:/WorkSpace/RuoYi-Vue/docs/solutions/logic-errors/lookup-column-set-operation-logic-errors.md) —— 较早的集合运算逻辑错误文档。部分问题与本文修复2/4/6重叠；本文档以更完整的前后对比和级联模式框架取代了那些部分。较早的文档对未在此处处理的既有对齐缺陷的覆盖仍有参考价值。
- [计划文档](file:///d:/WorkSpace/RuoYi-Vue/docs/plans/2026-07-06-001-feat-lookup-column-value-dedupe-plan.md) —— 需求R1–R9，实现单元U1/U2/U3。
- [需求文档](file:///d:/WorkSpace/RuoYi-Vue/docs/brainstorms/2026-07-06-lookup-column-value-dedupe-requirements.md) —— 需求与验收示例。
