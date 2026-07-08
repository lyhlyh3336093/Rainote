---
title: "Lookup Column Dedupe Toggle Cascade Recompute Pattern"
date: 2026-07-08
category: docs/solutions/architecture-patterns/
module: lookup-column-recompute
problem_type: architecture_pattern
component: service_object
severity: high
applies_when:
  - "A config flag on a lookup column property changes and stored values plus dependent set-operation columns must be recomputed"
  - "Implementing a dedupe or similar toggle feature on computed or lookup columns in the note-record system"
  - "Cascade recompute is needed across columns that reference a changed source column"
related_components:
  - database
tags: [lookup-column, dedupe, cascade-recompute, set-operations, data-integrity, config-toggle, recompute-trigger, spring-boot]
---

# Lookup Column Dedupe Toggle Cascade Recompute Pattern

## Context

The lookup column (type=26) is a computed column in the RuoYi note/dwtable system whose values are derived by following `double_link_column_id` → `linkRecordIds` → `source_column_id` values, then joined with ",". When multiple linked records share the same source value (e.g., three records all categorized "苹果"), the lookup column displayed "苹果,苹果,苹果" with no way to collapse the duplicates.

There was no deduplication mechanism, and a deeper gap existed: **computed columns form a dependency graph**. A lookup column feeds into set operation columns (union/intersection/subtract/disjunction), so any change to how a lookup column derives its values must cascade through every dependent computed column — not just the lookup column itself.

This work (planned in [the plan doc](file:///d:/WorkSpace/RuoYi-Vue/docs/plans/2026-07-06-001-feat-lookup-column-value-dedupe-plan.md) and scoped in [the brainstorm doc](file:///d:/WorkSpace/RuoYi-Vue/docs/brainstorms/2026-07-06-lookup-column-value-dedupe-requirements.md), requirements R1–R9, units U1/U2/U3) added a `dedupe` config flag to the lookup column's `property` JSON and implemented the cascade recompute pattern. A subsequent code review surfaced six concrete bugs in the first implementation, each illustrating a recurring failure mode when touching computed-column recompute logic.

## Guidance

### Primary pattern: config flag toggle → cascade recompute across dependent computed columns

When a stored boolean on a computed column's `property` JSON changes the column's derivation behavior, the update path must trigger a **full-table recompute** of that column's stored values **and** a full-table recompute of every other computed column that references it as a source. The recompute is triggered in the column-update entry point by diffing old vs. new property JSON.

**Trigger site** — [NoteColumnServiceImpl.java](file:///d:/WorkSpace/RuoYi-Vue/ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteColumnServiceImpl.java) `updateNoteColumn` (~L220):

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

Two recompute methods live in [NoteRecordServiceImpl.java](file:///d:/WorkSpace/RuoYi-Vue/ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java), which owns `resolveLookupValue` and all required mappers:

- `recomputeLookupColumnValues(NoteColumn lookupColumn)` — iterates every record in the lookup column's table, re-resolves the value, and upserts the stored `NoteDwtableItem` (insert if absent, update otherwise). Per-record failures are caught and logged so one bad record does not abort the whole table.
- `recomputeSetOperationsForLookup(NoteColumn lookupColumn)` — finds every set operation column in the same table, filters to those whose `columnAId`/`columnBId` reference this lookup column, then for each record recomputes the set result using `fetchColumnDataFromDB` to read fresh source A/B values.

The same two-step cascade is invoked from the toggle endpoint (`GET /system/column/deduplicate`) in [NoteColumnController.java](file:///d:/WorkSpace/RuoYi-Vue/ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/NoteColumnController.java), which flips the `dedupe` boolean and then calls both recompute methods.

### Dedup at the single derivation point

Dedup is applied once, at the core resolver (`resolveLookupValues`), so the display, storage, and set-operation paths all receive deduplicated input rather than each path re-implementing it. Using `LinkedHashMap.putIfAbsent` preserves insertion order while collapsing duplicate values:

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

### Index alignment between derived recordIds and values

When a downstream consumer needs both the recordIds and values from a lookup column, **both lists must be derived from the same `resolveLookupValues` result** — never mix a raw `getLookupLinkRecordIds` return (full length) with `toLookupValues(resolveLookupValues(...))` (deduped length). `fetchColumnDataFromDB` does this correctly:

```java
List<LookupResult> results = resolveLookupValues(column, linkIds);
recordIds.addAll(results.stream().map(LookupResult::getLinkRecordId).collect(Collectors.toList()));
values.addAll(toLookupValues(results));
```

## Why This Matters

A computed column's stored values are **denormalized caches** of its derivation logic. When the derivation inputs change (here, the `dedupe` flag), any cached value not refreshed becomes stale — and worse, silently wrong. Two structural risks make this pattern load-bearing:

1. **Inconsistent caches across a dependency chain.** If only the lookup column is recomputed but a set operation column that consumes it is not, the set operation column retains pre-dedup values. The user sees a lookup column showing `["苹果","梨"]` but a union column still showing `["苹果","苹果","梨"]`. The data appears contradictory with no error.

2. **Silent data corruption from string-munging idioms.** The recompute path builds result strings from lists. Using `List.toString().replace("[","").replace("]","").replaceAll(" ","")` to serialize a list silently strips *all* spaces from every value — turning `"张 三"` into `"张三"` and `"New York"` into `"NewYork"`. This is a data-integrity bug, not a formatting nit.

The six code-review fixes below each capture a distinct failure mode that is easy to reintroduce when writing recompute/serialization logic. They are documented here so the next computed-column feature does not repeat them.

## When to Apply

- You are adding a **stored config flag** (boolean, enum, or mode) on a computed column whose value depends on it, and the column's results are cached/stored rather than computed purely on read.
- A computed column is a **source for other computed columns** (set operations, roll-ups, derived columns). Changing the source's derivation requires cascading to all consumers.
- You are writing a **full-table recompute** method that iterates records and upserts stored items.
- You are serializing a `List<String>` into a single comma-separated string for storage.
- You are branching on a `calcType`/`mode` string in a recompute loop and the default branch currently leaves the result empty.

## Examples

### Fix 1 (P0 — data corruption): never strip spaces when serializing a list

**File**: [NoteRecordServiceImpl.java](file:///d:/WorkSpace/RuoYi-Vue/ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java), `recomputeSetOperationsForLookup` (~L1329).

**Before** — `replaceAll(" ", "")` strips *all* spaces from values, corrupting `"张 三"` → `"张三"`:

```java
resultItem.setValue(rValues.toString().replace("[", "").replace("]", "").replaceAll(" ", ""));
resultItem.setLinkRecordId(rRecordIds.toString().replace("[", "").replace("]", "").replaceAll(" ", ""));
```

**After** — join with comma, preserving spaces:

```java
resultItem.setValue(String.join(",", rValues));
resultItem.setLinkRecordId(String.join(",", rRecordIds));
```

`List.toString()` produces `[a, b, c]`; the old code stripped the brackets and every space. `String.join(",", list)` is the correct idiom. This was the highest-severity fix because it silently mutated user data on every recompute.

### Fix 2 (P1 — missing cascade call): recompute both the column AND its dependents

**File**: [NoteColumnServiceImpl.java](file:///d:/WorkSpace/RuoYi-Vue/ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteColumnServiceImpl.java), `updateNoteColumn` (~L234).

**Before** — only the lookup column was recomputed, leaving set operation columns stale:

```java
if (oldDedupe != newDedupe)
{
    noteRecordService.recomputeLookupColumnValues(noteColumn);
}
```

**After** — both cascade steps:

```java
if (oldDedupe != newDedupe)
{
    noteRecordService.recomputeLookupColumnValues(noteColumn);
    noteRecordService.recomputeSetOperationsForLookup(noteColumn);
}
```

This is the canonical statement of the cascade pattern: a config-flag change requires refreshing the changed column **and** every computed column that consumes it. The toggle endpoint already had both calls; the update path initially had only one.

### Fix 3 (P1 — silent exception swallowing): log, never silently swallow

**File**: [NoteColumnServiceImpl.java](file:///d:/WorkSpace/RuoYi-Vue/ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteColumnServiceImpl.java), `updateNoteColumn` catch block (~L239).

**Before** — silent swallow, no signal anything went wrong:

```java
catch (Exception e)
{
    // property非合法JSON，跳过重算
}
```

**After** — logged with columnId context, plus a `Logger` field and imports added to the class:

```java
catch (Exception e)
{
    // property非合法JSON或重算失败，跳过重算
    log.error("[UPDATE-COLUMN] dedupe重算失败 columnId={}", noteColumnvo.getId(), e);
}
```

A swallowed exception in a recompute path is invisible: the user toggles the flag, the stored values never update, and there is no log to find. Always log with an identifying key (`columnId`, `recordId`) so the failure is traceable.

### Fix 4 (P2 — NPE risk): guard the value, not just the linkRecordId

**File**: [NoteRecordServiceImpl.java](file:///d:/WorkSpace/RuoYi-Vue/ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java), `fetchColumnDataFromDB` type=21 path (~L1202).

**Before** — NPE if `value` is null but `linkRecordId` is non-null (then `item.getValue().split(",")` throws):

```java
if (item == null || item.getLinkRecordId() == null || "".equals(item.getLinkRecordId()))
```

**After** — added null check for value:

```java
if (item == null || item.getLinkRecordId() == null || "".equals(item.getLinkRecordId())
        || item.getValue() == null)
```

When two parallel fields can independently be null, a guard clause must check both — the early-return should fire if *either* is unusable, not only if the first one is.

### Fix 5 (P3 — redundant queries): hoist invariant queries out of loops

**File**: [NoteRecordServiceImpl.java](file:///d:/WorkSpace/RuoYi-Vue/ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java), `recomputeSetOperationsForLookup` (~L1219).

**Before** — `selectNoteRecordList` called once per `setColumn` inside the loop, M identical queries:

```java
for (NoteColumn setColumn : setColumns)
{
    NoteRecordVo queryRecord = new NoteRecordVo();
    queryRecord.setDwtableId(lookupColumn.getDwtableId());
    List<NoteRecord> records = noteRecordMapper.selectNoteRecordList(queryRecord);
    // ... uses records
}
```

**After** — hoisted before the loop, called once:

```java
NoteRecordVo queryRecord = new NoteRecordVo();
queryRecord.setDwtableId(lookupColumn.getDwtableId());
List<NoteRecord> records = noteRecordMapper.selectNoteRecordList(queryRecord);

for (NoteColumn setColumn : setColumns)
{
    // ... uses records inside loop
}
```

When a query depends only on loop-invariant inputs (here, `lookupColumn.getDwtableId()`), compute it once. On a table with 10 set operation columns this is a 10× reduction in identical DB round-trips.

### Fix 6 (P2 — silent data wipe on unknown calcType): never fall through with an empty result

**File**: [NoteRecordServiceImpl.java](file:///d:/WorkSpace/RuoYi-Vue/ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java), `recomputeSetOperationsForLookup` (~L1295).

**Before** — unknown `calcType` falls through, `rRecordIds` stays empty, and the code proceeds to write the empty result back to storage — **silently wiping the user's data**:

```java
if ("intersection".equals(calcType))
{
    rRecordIds = (List<String>) CollectionUtils.intersection(aRecordIds, bRecordIds);
}
else if ("union".equals(calcType))
{
    rRecordIds = (List<String>) CollectionUtils.union(aRecordIds, bRecordIds);
}
// no else — unknown calcType leaves rRecordIds empty → stored value wiped
```

**After** — else branch skips and warns:

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

The rule: a recompute path that writes its result back to storage must **never** persist a value derived from an unhandled branch. If you cannot compute a result, `continue` and leave the existing stored value intact — do not write an empty list. This is the most dangerous class of bug in a recompute loop because it is both silent and destructive.

### Verification

71 tests pass (25 `NoteColumnServiceImplTest` + 46 `NoteRecordServiceImplTest`), 0 failures. The test suite covers dedupe on/off semantics, index alignment for all four set operation types, the toggle trigger, single-record failure isolation (R8), and the unknown-calcType skip behavior.

## Related

- [Lookup Column Code Simplification Patterns](file:///d:/WorkSpace/RuoYi-Vue/docs/solutions/design-patterns/lookup-column-code-simplification-patterns.md) — broader code-simplification patterns for lookup column implementation. Complements this doc; this doc adds the cascade-recompute architecture and the six concrete failure-mode fixes.
- [Lookup Column Set Operation Logic Errors](file:///d:/WorkSpace/RuoYi-Vue/docs/solutions/logic-errors/lookup-column-set-operation-logic-errors.md) — earlier set-operation logic errors. Some issues overlap with Fixes 2/4/6 here; this doc supersedes those portions with fuller before/after and the cascade-pattern framing. The earlier doc remains useful for its coverage of pre-existing alignment bugs not addressed here.
- [Plan doc](file:///d:/WorkSpace/RuoYi-Vue/docs/plans/2026-07-06-001-feat-lookup-column-value-dedupe-plan.md) — requirements R1–R9, implementation units U1/U2/U3.
- [Brainstorm doc](file:///d:/WorkSpace/RuoYi-Vue/docs/brainstorms/2026-07-06-lookup-column-value-dedupe-requirements.md) — requirements and acceptance examples.
