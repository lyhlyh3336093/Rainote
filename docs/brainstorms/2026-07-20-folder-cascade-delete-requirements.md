---
date: 2026-07-20
topic: folder-cascade-delete
---

# 文件夹笔记级联删除

## Summary

补齐 `NoteNoteServiceImpl` 三个生命周期方法中 `noteType==1L`（文件夹）分支的级联处理，让文件夹与其所有子孙笔记在删除/恢复的三个阶段（软删除进回收站、物理清空回收站、从回收站恢复）保持 `delFlag` 状态一致。子孙范围包括递归嵌套的所有子文件夹、笔记文档、多维表格数据表等。每个子孙按其自身 `noteType` 走与现有逻辑一致的关联数据处理（type=2 同步 NoteMeta；type=4 同步 NoteDwtable 软删/物理删/恢复）。

---

## Problem Frame

当前 [NoteNoteServiceImpl.deleteNoteNoteByIds](ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteNoteServiceImpl.java) 中 `noteType==1L` 分支为空实现（注释写着"如果是文件夹,就级联删除"但无任何代码），[deleteNoteFromGarbageByIds](ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteNoteServiceImpl.java) 与 [recoverNoteNoteByIds](ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteNoteServiceImpl.java) 完全没有 `noteType==1L` 分支。删除文件夹后，`parentId` 指向该文件夹的所有子孙笔记保持原 `delFlag` 状态残留为孤儿，仍显示在用户笔记树中；物理清空回收站时关联数据（NoteMeta/NoteBlock/NoteDwtable/NoteColumn/NoteRecord/NoteDwtableItem/NoteView）也全部残留。根因是删除/恢复责任分散在三个方法中各自按 `noteType` 用 if/else 处理，新增类型时极易遗漏分支——文件夹分支就是被遗漏的那一个。

---

## Key Decisions

**两阶段都级联**：软删除进回收站和物理清空回收站两个阶段都做级联。软删除时子孙同步进回收站，物理清空时子孙及关联数据同步物理删除。

**同步补齐恢复方法以保持对称**：[recoverNoteNoteByIds](ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteNoteServiceImpl.java) 也补 `noteType==1L` 分支，恢复文件夹时级联恢复子孙。否则删除级联、恢复不级联会导致子孙永久困在回收站。

**文件夹与子孙 delFlag 始终一致**：文件夹进回收站→子孙全部 delFlag=1；文件夹恢复→子孙全部 delFlag=0；文件夹物理清空→子孙全部物理删除。简化为"文件夹状态即子孙状态"，避免出现混合状态。

**递归收集子孙，且必须忽略 delFlag**：物理删除和恢复场景下子孙已是 delFlag=1，现有 `selectNoteNoteList`（[NoteNoteMapper.xml](ruoyi-system/src/main/resources/mapper/system/NoteNoteMapper.xml)）带 `delFlag=0` 过滤会漏查。需新增"按 parentId 查询子孙且不过滤 delFlag"的 Mapper 方法，三个场景共用。

**子孙关联数据按各自 noteType 复用现有级联逻辑**：不抽取统一分发器、不新建 NoteDeleteService。对每个子孙按其 `noteType` 走与现有方法体一致的分支（type=2 → NoteMeta.deleteFlag 同步；type=4 → NoteDwtable 软删/物理删/恢复），保持代码风格统一，避免引入新抽象层。

---

## Requirements

**递归子孙收集**

- R1. 新增按 `parentId` 查询直接子笔记且**不过滤 `delFlag`** 的 Mapper 方法，用于递归收集子孙。
- R2. 在 `NoteNoteServiceImpl` 中新增递归收集方法，输入文件夹 ID，返回所有子孙笔记 ID（含子文件夹的子孙，深度无限）。
- R3. 递归必须处理嵌套子文件夹（type=1 的子节点继续向下递归），避免出现子文件夹的孤儿。
- R4. 递归过程中遇到环或异常时不应静默丢失节点——若使用 visited 集合防环，被跳过的节点应记入日志。

**软删除阶段级联（`deleteNoteNoteByIds`）**

- R5. `noteType==1L` 分支：递归收集子孙 ID，对每个子孙按其 `noteType` 走与现有 type=2/type=4 分支一致的关联数据处理（NoteMeta 同步 deleteFlag=1，NoteDwtable 调用 `deleteNoteDwtableByNoteId` 软删）。
- R6. 子孙 ID 合并进批量软删除 ID 列表，最终一次性 `update note_note set delFlag=1 where id in (folderId + descendants)`。

**物理删除阶段级联（`deleteNoteFromGarbageByIds`）**

- R7. `noteType==1L` 分支：递归收集子孙 ID，对每个子孙按其 `noteType` 走与现有 type=2/type=4 分支一致的关联数据物理清理（NoteMeta.deleteFlag=2，type=4 调用 `noteDwtableServiceImpl.deleteNoteDwtableById` 物理删除含 view/record/item/column）。
- R8. 子孙 ID 合并进批量物理删除 ID 列表，最终一次性 `delete from note_note where id in (folderId + descendants)`。

**恢复阶段级联（`recoverNoteNoteByIds`）**

- R9. `noteType==1L` 分支：递归收集子孙 ID，对每个子孙按其 `noteType` 走与现有 type=2/type=4 分支一致的关联数据恢复处理（NoteMeta.deleteFlag=0，NoteDwtable 调用 `recoverNoteDwtableByNoteId` 软恢复）。
- R10. 子孙 ID 合并进批量恢复 ID 列表，最终一次性 `update note_note set delFlag=0 where id in (folderId + descendants)`。

**事务一致性**

- R11. 三个方法整体在事务中执行（已隐式或显式 `@Transactional`），任一子孙级联失败应回滚整个操作，避免出现"文件夹已删/子孙未删"的中间状态。

---

## Scope Boundaries

**Deferred for later**

- 抽取统一分发器 / 新建 NoteDeleteService：本次仅补齐文件夹分支，未来若再出现类型遗漏可考虑结构化重构。
- type=3（表格）和 type=5（权限字符）的关联数据级联：当前方法体也未处理这两种类型，本次保持现状不扩展。
- `deleteNoteNoteById`（单条删除入口）：未被原始请求提及，本次不动。
- 前端二次确认 UI（"该文件夹下有 N 篇笔记，是否一并删除？"）：本次仅做后端级联。

**Outside this product's identity**

- 不引入软删除级联审计日志（记录"谁在何时级联删除了哪些子孙笔记"）。
- 不引入"文件夹移入回收站后 30 天自动物理清理"的定时任务。
- 不改变 `parentId` 关系模型本身（不引入闭包表、物化路径等层级存储重构）。

---

## Key Flows

- F1. 删除文件夹（移入回收站）
  - **Trigger:** 用户在前端点击删除按钮，调用 `GET /note/note/remove/{folderId}`。
  - **Steps:** Controller 调 `deleteNoteNoteByIds([folderId])`；Service 查到 noteType=1，递归收集子孙 ID；对每个子孙按其 noteType 同步关联数据软删除标记；把子孙 ID 合并进 ids 数组；批量 `update note_note set delFlag=1`。
  - **Outcome:** 文件夹及所有子孙笔记 delFlag=1，NoteMeta/NoteDwtable 同步标记为软删除。
  - **Covered by:** R1, R2, R3, R5, R6, R11.

- F2. 清空回收站中的文件夹
  - **Trigger:** 用户在回收站点击"彻底删除"，调用 `GET /note/note/clearGarbage/{folderId}`。
  - **Steps:** Controller 调 `deleteNoteFromGarbageByIds([folderId])`；Service 查到 noteType=1，递归收集子孙 ID（忽略 delFlag，因为子孙已是 delFlag=1）；对每个子孙按其 noteType 物理清理关联数据；把子孙 ID 合并进 ids 数组；批量 `delete from note_note`。
  - **Outcome:** `note_note` 中无文件夹及子孙行；`note_meta`/`note_dwtable`/`note_block`/`note_column`/`note_record`/`note_dwtable_item`/`note_view` 中无对应残留。
  - **Covered by:** R1, R2, R3, R4, R7, R8, R11.

- F3. 从回收站恢复文件夹
  - **Trigger:** 用户在回收站点击"恢复"，调用恢复接口。
  - **Steps:** Service 查到 noteType=1，递归收集子孙 ID（忽略 delFlag）；对每个子孙按其 noteType 同步关联数据恢复标记；把子孙 ID 合并进 ids 数组；批量 `update note_note set delFlag=0`。
  - **Outcome:** 文件夹及所有子孙笔记 delFlag=0，重新显示在用户笔记树中原位置；NoteMeta/NoteDwtable 同步恢复。
  - **Covered by:** R1, R2, R3, R4, R9, R10, R11.

---

## Success Criteria

- S1. 删除一个含多层嵌套子孙（混合 type=1/2/4）的文件夹后，所有子孙笔记的 `delFlag` 与文件夹一致（同为 1）。
- S2. 物理清空该文件夹后，`note_note` 表无该文件夹及子孙行；`note_meta`、`note_dwtable`、`note_block`、`note_column`、`note_record`、`note_dwtable_item`、`note_view` 表无对应残留行。
- S3. 从回收站恢复该文件夹后，所有子孙笔记的 `delFlag` 恢复为 0，NoteMeta 与 NoteDwtable 同步恢复，前端笔记树完整呈现原嵌套结构。
- S4. 三个生命周期阶段中任一关联操作失败时，整个操作回滚，不出现"文件夹已变更/子孙未变更"的中间状态。

---

## Dependencies / Assumptions

- 假设 `noteType=3`（表格）和 `noteType=5`（权限字符）当前无关联数据需要级联清理（基于现有方法体未处理这两种类型）。若后续发现有关联数据，需在 R5/R7/R9 中追加分支。
- 假设 `parentId` 关系不会形成环（业务上不允许笔记互为父子）。R4 仍要求防御性处理。
- 假设现有 `noteDwtableServiceImpl.deleteNoteDwtableById` 已正确级联清理 view/record/item/column（已在 [NoteDwtableServiceImpl.java](ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteDwtableServiceImpl.java) 第 159-185 行验证）。
- 假设三个方法已有或可加 `@Transactional` 注解，保证 R11 事务一致性。

---

## Outstanding Questions

- Q1. 用户在前端删除文件夹时是否需要二次确认（"将一并删除 N 篇子笔记"）？本次后端级联不依赖前端确认，但若需要可作为后续前端任务。
- Q2. 若文件夹下子孙数量极大（如 1000+），递归 + 批量 IN 查询可能触发 SQL 长度限制或性能问题。是否需要在 R2 中限制单批大小或改用游标？当前需求未限定上限，实现时按一般规模（< 500 子孙）设计，超大场景作为已知限制。
