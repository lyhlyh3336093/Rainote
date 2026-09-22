---
title: Excel 导入成功后网格不显示数据（记录缺归属视图 viewId）
date: 2026-09-21
category: docs/solutions/logic-errors
module: 多维表格 Excel 导入（notepad baseTable）
problem_type: logic_error
component: service_object
symptoms:
  - 导入弹框提示"导入成功 N 条记录"，但表格网格一行都不显示
  - 数据库中 note_record / note_dwtable_item / note_column 全部正确写入
  - UI 手动"添加记录"的行显示正常，仅导入的行不可见
root_cause: missing_workflow_step
resolution_type: code_fix
severity: high
tags: [excel-import, viewid, note-record, datalist, grid-display, multitable]
---

# Excel 导入成功后网格不显示数据（记录缺归属视图 viewId）

## Problem

多维表格 Excel（替换式）导入返回成功，但表格网格不显示任何数据行。数据实际已完整落库（记录、单元格、列均正确），仅前端网格为空。

## Symptoms

- 导入弹框提示"导入成功 N 条记录"，网格空白
- `note_record` 表中记录存在，但 `viewId` 列为 NULL
- UI 手动添加的记录显示正常（其 viewId 非空）
- 前端 `getTableList` 中 `filter(item => item?.tableData)` 把无 tableData 的记录全部过滤

## What Didn't Work

- 先怀疑前端刷新问题（列/记录未刷新）——实际 `table.get.columns()` + `table.get.list()` 均已调用，刷新链路正常
- 再怀疑替换式导入删列后视图 `hiddenFields` 残留旧列 id 导致渲染异常——与显示无关（hiddenFields 只影响列隐藏）
- 数据完整性排查（items/列/name 派生）全部正常——排除写入问题

## Solution

**根因链路**：网格数据接口 `GET /system/record/dataList`（`NoteRecordServiceImpl.selectDataListByDwtableId`）遍历目标表全部视图（`noteViewMapper.selectNoteViewList`），**按每个视图的 viewId 查询记录**（`selectNoteRecordList` 的动态 WHERE `viewId = #{viewId}`）——viewId 为 NULL 的记录永远不进结果集。UI 建行走 `/system/record/add` 由前端携带当前 viewId；而 Excel 导入（旧追加式与新替换式）与 SQL 导入写记录时 viewId 均为 null → 导入的记录在网格中永久不可见。

**修复**（[NoteDwtableExcelImportServiceImpl.java](../../../ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteDwtableExcelImportServiceImpl.java) `executeImport` 写入阶段）：导入记录分配目标表首视图：

```java
// 记录归属视图：网格 dataList 按视图 viewId 过滤记录（无 viewId 的记录不显示），
// UI 建行携带当前视图 id，导入分配目标表首视图（dataList 为全视图并集，任一视图即可显示）
Long targetViewId = resolveDefaultViewId(plan.dwtableId);
for (RowPlan row : plan.rows) {
    NoteRecord record = new NoteRecord();
    record.setDwtableId(plan.dwtableId);
    record.setViewId(targetViewId);   // ★ 修复点
    ...
}

// 首视图解析：无视图（异常形态）返回 null 退化为旧行为，不阻断导入
private Long resolveDefaultViewId(Long dwtableId) {
    List<NoteView> views = noteViewMapper.selectNoteViewListByDwtableId(dwtableId);
    if (views == null || views.isEmpty() || views.get(0) == null) {
        return null;
    }
    return views.get(0).getId();
}
```

**存量数据修复**（已导入但不可见的记录，执行前先确认范围）：

```sql
UPDATE note_record r
JOIN (SELECT dwtableId, MIN(id) AS firstView FROM note_view GROUP BY dwtableId) v
  ON v.dwtableId = r.dwtableId
SET r.viewId = v.firstView
WHERE r.viewId IS NULL;
```

或对替换式导入的表直接用修复后的代码重新导入一次（全删重建，天然覆盖）。

## Why This Works

`dataList` 的记录过滤以"挂靠表内某一视图"为可见性前提（全视图并集，挂任一视图即显示），这是 `note_record.viewId` 在本项目中的实际语义——不是可空的元数据，而是网格可见性的必填归属字段。导入链路此前与 UI 建行链路在这一个字段上不对齐，导致"写入成功"与"读取可见"两个契约断裂。

## Prevention

- **任何写 `note_record` 的链路必须给 viewId 赋值**（UI 建行、Excel 导入、SQL 导入、agent record.create）——写完先过"网格能不能查到"这一关
- 测试断言：`replace_recordsCarryDefaultViewId`（NoteDwtableExcelImportServiceImplTest）用 recordCaptor 断言每条 insert 的记录携带首视图 id
- ⚠️ **SQL 导入（NoteDwtableImportServiceImpl.appendRecord）存在同样隐患未修**——其记录同样 viewId=null，下次 SQL 导入后同样不可见，修复方式相同（解析首视图 + setViewId）
- 排查"导入成功但不显示"类问题的证据链：先查 `note_record.viewId` 是否为 NULL → 再查 items/列完整性 → 最后才怀疑前端刷新

## Related Issues

- [multitable-sql-import-pipeline.md](../architecture-patterns/multitable-sql-import-pipeline.md)——SQL 导入姊妹管线（同源隐患）
- `docs/plans/` 下的 Excel 导入计划文档——替换式导入改造（2026-09-21）
