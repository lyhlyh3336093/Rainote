---
date: 2026-07-23
topic: semantic-column-cascade-delete
---

## Summary

When a user deletes a type=25 (语义关联) column, the system first validates whether the column has data; if so, a confirmation dialog warns about the cascade effects before proceeding. On confirmed deletion, the system cascade-deletes associated NoteNotelink records (REVERSE direction) and NoteDwtableItem records (FORWARD direction), and restores the original text in affected notes across both link directions. Text restoration runs at the backend by rewriting NoteBlock content, so all notes show restored text on next load.

---

## Problem Frame

Deleting a type=25 column today leaves orphaned data. The existing `deleteDataWhenLink` only cleans up the paired column (via `back_field_id`), never the deleted column's own NoteNotelink records. For type=25 columns, which typically lack `back_field_id`, the method returns early without any cleanup. The result: NoteNotelink records become orphans, and the blue `[text]` anchors in notes become dead links that no longer resolve.

Users have no way to clean this up from the table side — they must open each affected note and manually cancel each link one by one. There is also no pre-deletion warning, so users can delete a column with active semantic links without realizing the consequences.

The `removeLink` method in NoteBlockServiceImpl is a stub (returns 0), confirming that FORWARD direction cleanup was never implemented.

---

## Key Decisions

**Backend text restoration over frontend DOM.** The backend rewrites NoteBlock content to replace anchor elements with plain text. This covers all notes, including ones not currently open. The trade-off: open notes need a reload to see the restored text, which the user accepted.

**Best-effort over transactional.** Column deletion always succeeds after user confirmation. Individual text restoration failures (e.g., a NoteBlock with malformed HTML) are logged and skipped without aborting. This matches the existing `deleteNoteNotelinkByColumnId` pattern, which already uses try-catch.

**Bidirectional cleanup.** Both link directions are cleaned: REVERSE (NoteNotelink records, located via `linkColumnId`) and FORWARD (anchors with empty `data-link-id`, located via link info stored in NoteDwtableItem). FORWARD cleanup must collect link info before NoteDwtableItem records are deleted.

**Pre-deletion confirmation dialog.** A validation check runs before the delete API call. If the column has no data, deletion proceeds directly. If data exists, a dialog warns the user and requires explicit confirmation.

---

## Requirements

### Pre-deletion Validation

- R1. Before executing the column delete API for a type=25 column, the system checks whether the column has associated data — defined as any NoteDwtableItem cell in the column with a non-empty value, OR any NoteNotelink record whose `linkColumnId` equals the column's id. REVERSE direction links can exist independently of NoteDwtableItem cell values, so both data sources must be checked to avoid bypassing the confirmation dialog.
- R2. If the column has no data, deletion proceeds without a confirmation dialog.
- R3. If the column has data, a confirmation dialog is shown warning the user that the column contains data and deletion will cascade-clean associated note links and restore note text. The delete API is called only after the user clicks confirm.

### Cascade Deletion

- R4. On deletion of a type=25 column, all NoteNotelink records where `linkColumnId` equals the deleted column's id are deleted (REVERSE direction).
- R5. On deletion of a type=25 column, all NoteDwtableItem records for the column are deleted (existing behavior, FORWARD direction cell values).

### Text Restoration

- R6. Before NoteDwtableItem records are deleted, the system collects those with non-null `linkBlockId`, retaining the full `(linkBlockId, recordId, dwtId)` tuple from each item. `linkBlockId` identifies the NoteBlock containing FORWARD direction anchors; `recordId` + `dwtId` disambiguate which anchor(s) within the block to restore when multiple FORWARD anchors to the same column coexist in one NoteBlock.
- R7. For each affected NoteBlock (identified by NoteNotelink's `noteId`/`blockId` for REVERSE, or NoteDwtableItem's `linkNoteId`/`linkBlockId` for FORWARD), the system rewrites the block content to replace matching `<a data-type="semantic">` anchor elements with plain text. The matching predicate: REVERSE anchors whose `data-link-id` equals a deleted NoteNotelink's id; FORWARD anchors whose `data-table-id` + `data-record-id` match a collected NoteDwtableItem and whose `data-link-id` is empty. Anchors belonging to other columns or records must not be touched.
- R8. Anchor text restoration strips leading `[` and trailing `]` from the anchor's inner text (e.g., `[红门]` becomes `红门`), following the existing `executeUnlink` convention.

### Error Handling

- R9. Text restoration uses per-block error handling; if a NoteBlock's content cannot be parsed, the error is logged and that block is skipped without aborting the overall deletion.
- R10. Column deletion, NoteNotelink deletion, and NoteDwtableItem deletion always proceed after user confirmation, regardless of text restoration success or failure.

---

## Key Flows

- F1. Column deletion with data
  - **Trigger:** User clicks delete on a type=25 column that has data per R1 (non-empty cell values and/or NoteNotelink records).
  - **Steps:** Check column data → data found → show confirmation dialog → user clicks confirm → collect NoteNotelink records (REVERSE) + NoteDwtableItem records with linkBlockId (FORWARD) → group by NoteBlock → restore text in each NoteBlock (per-block try-catch) → delete NoteNotelink records → delete NoteDwtableItem records → delete column → recompute affected table names.
  - **Covered by:** R1, R3, R4, R5, R6, R7, R8, R9, R10

- F2. Column deletion without data
  - **Trigger:** User clicks delete on a type=25 column with no NoteDwtableItem cell values and no NoteNotelink records (R1 returns false for both data sources).
  - **Steps:** Check column data (NoteDwtableItem cells + NoteNotelink records) → no data → delete NoteDwtableItem records (if any) → delete NoteNotelink records by `linkColumnId` (defensive, R4) → delete column → recompute affected table names. Cascade record cleanup (R4, R5) runs on every type=25 deletion regardless of whether the confirmation dialog fired; FORWARD text restoration (R6-R8) is omitted on the no-data path because no NoteDwtableItem records with `linkBlockId` exist when all cells are empty (per Dependencies).
  - **Covered by:** R1, R2, R4, R5

- F3. Text restoration failure on a corrupted block
  - **Trigger:** During text restoration, a NoteBlock's content is malformed and cannot be parsed.
  - **Steps:** Parse fails → log error → skip this block → continue restoring other blocks → proceed with record and column deletion.
  - **Covered by:** R9, R10

---

## Acceptance Examples

- AE1. Column with REVERSE links only
  - **Covers R4, R7, R8.**
  - **Given:** A type=25 column with NoteNotelink records but no FORWARD mode links in NoteDwtableItem.
  - **When:** User confirms deletion.
  - **Then:** NoteNotelink records are deleted; anchors in notes (matching by `data-link-id`) are restored to plain text without brackets.

- AE2. Column with FORWARD links only
  - **Covers R5, R6, R7, R8.**
  - **Given:** A type=25 column where NoteDwtableItem records have `linkBlockId` set but no NoteNotelink records exist.
  - **When:** User confirms deletion.
  - **Then:** NoteDwtableItem link info is collected before deletion; anchors in notes (matching by `data-table-id` + `data-record-id`, with empty `data-link-id`) are restored to plain text.

- AE3. Column with a corrupted NoteBlock
  - **Covers R9, R10.**
  - **Given:** A type=25 column where one NoteBlock's content is malformed HTML.
  - **When:** User confirms deletion.
  - **Then:** Column and records are deleted; the corrupted block is skipped with a logged error; all other affected blocks are restored normally; the corrupted note retains a dead anchor that the user can manually delete.

- AE4. Empty column
  - **Covers R1, R2.**
  - **Given:** A type=25 column with no cell values and no NoteNotelink records.
  - **When:** User clicks delete.
  - **Then:** No confirmation dialog appears; the column is deleted directly.

---

## Scope Boundaries

- Real-time refresh of open notes — deferred. The backend rewrites NoteBlock content; open notes need a reload to see restored text. A Bus event for real-time refresh is not in this iteration.
- Historical orphaned data cleanup — deferred. This fix is forward-looking; NoteNotelink records and dead anchors created before this fix remain and need separate cleanup.
- The confirmation dialog applies to type=25 columns only. Extending it to other column types is a separate decision.

---

## Dependencies / Assumptions

- NoteDwtableItem records for FORWARD mode links store `linkBlockId` and `linkNoteId` — verified at `NoteBlockServiceImpl.java:224-225`. These fields are the only way to locate FORWARD direction anchors without a NoteNotelink record.
- The NoteDwtableItem half of R1's data check can use the frontend's in-memory cell data (the table is displayed). The NoteNotelink half (records matched by `linkColumnId`) is not held in frontend memory and requires a backend query, so REVERSE-only columns reliably trigger the confirmation dialog rather than being misclassified as no-data.
- Backend HTML parsing of NoteBlock content is required to locate and replace `<a data-type="semantic">` elements. The specific parsing library is a planning decision.
- Multiple anchors may exist in the same NoteBlock; text restoration should batch per block (parse once, replace all matching anchors, update once) for efficiency.
- `linkBlockId` is only set on NoteDwtableItem records with non-empty cell values (per `linkToDwtable`, `NoteBlockServiceImpl.java:221-226`) — so the no-data path (F2) correctly omits FORWARD text restoration (R6-R8) since no FORWARD anchors exist when all cells are empty.

---

## Sources / Research

- `ruoyi-system/.../NoteColumnServiceImpl.java:358-386` — `deleteNoteColumnByIds`: calls `deleteDataWhenLink` for type==25 (L372), deletes NoteDwtableItem by columnId (L368).
- `ruoyi-system/.../NoteColumnServiceImpl.java:448-471` — `deleteDataWhenLink`: only handles `back_field_id` paired column; calls `deleteNoteNotelinkByColumnId` with the paired column's id, not the deleted column's own id.
- `ruoyi-system/.../NoteNotelinkServiceImpl.java:125-135` — `deleteNoteNotelinkByColumnId`: existing method with try-catch, called only from `deleteDataWhenLink`.
- `ruoyi-system/.../NoteBlockServiceImpl.java:192-242` — `linkToDwtable`: stores `linkBlockId` and `linkNoteId` in NoteDwtableItem (L224-225).
- `ruoyi-system/.../NoteBlockServiceImpl.java:252-258` — `removeLink`: stub, returns 0.
- `ruoyi-system/.../NoteNotelinkMapper.xml:114-120` — `deleteNoteNotelinkByColumnId` DELETE query.
- `notepad/.../SemanticLink/index.ts:263-281` — `setAnchorAttributes`: FORWARD anchors get `data-link-id=""`, REVERSE anchors get `data-link-id=<NoteNotelink.id>`.
- `notepad/.../SemanticLink/index.ts:321-398` — `executeUnlink`: existing single-anchor unlink logic, strips brackets via `innerText.replace(/^\[|\]$/g, '')` (L342).

---

## Deferred / Open Questions

### From 2026-07-23 review

- **No failure flow for the delete API call itself** — Key Flows (F1) / Error Handling (R9-R10) (P1, design-lens, confidence 75)

  F1 describes only the happy path through a long multi-step backend operation, and R10/F3 cover only text-restoration failure. If the column delete API call or a record-deletion step fails (network error, permission denied, DB error), the implementer has no specified user-facing behavior, so they will either block silently or invent an inconsistent error UX. A destructive cascade operation must define what the user sees and what state the table/column is left in when deletion fails partway.

- **No loading/progress state specified during multi-step deletion** — Requirements / Pre-deletion Validation (R3) / Key Flows (F1) (P2, design-lens, confidence 75)

  After the user confirms, the backend runs collect → group → per-block text restore → delete records → delete column → recompute table names, which can take visible seconds when many notes are affected. R3 specifies only that the dialog is shown and the API is called on confirm, with no disabled/loading state for the dialog or table. Without it, users may believe nothing happened and click delete again or navigate away mid-operation, producing duplicate calls or confusing partial states.

- **Open-note stale anchor UX is unspecified beyond 'needs a reload'** — Scope Boundaries (P2, design-lens, confidence 75)

  The doc defers real-time refresh and states open notes need a reload, but never specifies how a user recognizes a note is stale, what happens when they click a now-dead anchor in the still-open note, or what 'reload' means (full page vs. single note). This is exactly the dead-link problem the feature targets, so leaving the stale-state interaction undefined risks an inconsistent or confusing experience for anyone with an affected note open at deletion time.

- **'User can manually delete' dead anchor assumes an unspecified interaction** — Acceptance Examples (AE3) (P2, design-lens, confidence 75)

  AE3's outcome rests on a manual dead-anchor deletion interaction that is never specified. The Problem Frame references manually canceling active links, but a dead anchor (orphaned after deletion) is a different object, and the doc gives no flow for how the user finds, selects, and removes it. Implementers will either build nothing (leaving users stuck with dead anchors) or invent an ad-hoc interaction inconsistent with the existing link-cancel UX.

- **Stale local data check can bypass the confirmation dialog** — Dependencies / Assumptions / Requirements (R1-R2) (P2, design-lens, confidence 75)

  R1/R2 gate the confirmation dialog on a local in-memory check of cell data. If the frontend's view is stale (another user added cells, or the table hasn't refreshed), the check reports 'no data', skips the dialog per R2, and the user triggers a cascade delete with no warning. The no-data path (F2) has no backend validation, so this edge case silently defeats the pre-deletion warning that is the feature's primary user safeguard.

- **No user-facing feedback when a corrupted block is skipped** — Error Handling (R9) / Acceptance Examples (AE3) (P2, design-lens, confidence 75)

  R9 logs the error and skips the block, and AE3 expects the user to notice and manually delete the resulting dead anchor. But there is no specified notification, marker, or summary telling the user which notes had restoration failures. Users must discover dead anchors by accident across potentially many notes, which is the same manual-cleanup burden the feature is meant to reduce.

- **Best-effort decision (R9/R10) creates dead anchors harder to recover from than the original orphan problem** — Key Decisions / Error Handling (R9, R10) vs AE3 (P2, adversarial, confidence 75)

  When text restoration fails for a block, R10 still deletes the column and NoteNotelink records. The affected note is left with a dead [text] anchor whose NoteNotelink record is gone. AE3 claims 'the user can manually delete' it, but the existing REVERSE unlink path (executeUnlink, index.ts:347-349) issues DELETE /system/notelink/${linkId} — which requires the NoteNotelink record to still exist. With it deleted, manual unlink cannot succeed. The deferred historical cleanup locates orphans via linkColumnId, so it will not find these new dead anchors either. On the error path the system reaches a state less recoverable than the pre-fix orphan state the feature was built to eliminate.

- **FORWARD anchor matching cannot distinguish sibling type=25 columns** — Text Restoration (R6, R7) (P1, feasibility + adversarial, confidence 100)

  R7 specifies matching FORWARD anchors by `data-table-id` + `data-record-id` + empty `data-link-id`, and states anchors "belonging to other columns or records must not be touched." But FORWARD anchor attributes (verified in `setAnchorAttributes` at `SemanticLink/index.ts:263-281` and the EditorJS sanitize config) include no column identifier — only data-table-id, data-record-id, data-block-id, data-target-id, data-source-id, data-link-id, data-owner-note-id. Since `linkToDwtable` (`NoteBlockServiceImpl.java:201-208`) creates a new type=25 column on every call with no dedup, two type=25 columns can coexist in the same dwtable. If both have FORWARD links to the same record from the same NoteBlock, deleting one column would match and restore BOTH anchors, corrupting the surviving column's link (its NoteDwtableItem still has `linkBlockId` pointing at a NoteBlock whose anchor was destroyed). R7's stated guarantee is unenforceable with the specified matching key; resolution requires adding a column discriminator (e.g., `data-column-id`) to FORWARD anchors at `linkToDwtable` time and including `columnId` in R6's tuple and R7's predicate — or proving one NoteBlock cannot hold two FORWARD anchors to the same table+record via different columns.

- **linkBlockId invariant holds only at creation; value-clear preserves it** — Dependencies / F2 (P2, adversarial, confidence 75)

  The Dependencies invariant states `linkBlockId` is "only set on NoteDwtableItem records with non-empty cell values," but the cited evidence (`linkToDwtable`, `NoteBlockServiceImpl.java:221-226`) proves this only at creation. The record-update path (`updateNoteRecord`, `NoteRecordServiceImpl.java:144-151`) rebuilds each item with only id+value, so a selective MyBatis update preserves `linkBlockId` when a value is cleared. A FORWARD-only link (no NoteNotelink, since `linkToDwtable` creates none) whose cell value was later cleared leaves `linkBlockId` set on an empty-valued cell: R1 sees no data (no non-empty cell, no NoteNotelink) → F2 runs → F2 omits FORWARD text restoration → the FORWARD anchor becomes a dead link with no confirmation dialog and no restoration, breaking the invariant F2 relies on. Resolution requires either clearing `linkBlockId` when a type=25 cell value is cleared in `updateNoteRecord`, or decoupling R6's FORWARD collection (`linkBlockId != null`) from R1's cell-value check so cleared-value FORWARD anchors are still restored on the F2 path.
