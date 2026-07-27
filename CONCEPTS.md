# Concepts

Shared domain vocabulary for this project — entities, named processes, and status concepts with project-specific meaning. Seeded with core domain vocabulary, then accretes as ce-compound and ce-compound-refresh process learnings; direct edits are fine. Glossary only, not a spec or catch-all.

## Note System

### NoteColumn
A column definition in a data table (note_dwtable). Each column has a type that determines its behavior and data storage model. Key types include type=21 (double link) and type=26 (lookup).

### Double Link Column (type=21)
A bidirectional association column that stores direct links between records across tables. Its item stores `linkRecordId` (comma-separated target record IDs) and `linkColumnId` (the target column ID). Paired columns across tables reference each other via `back_field_id` in their property JSON.

### Lookup Column (type=26)
A computed column that derives its value from a double link column's associated records. Its item does NOT store `linkRecordId` or `linkColumnId` — these are always NULL. Instead, its property JSON contains `double_link_column_id` (pointing to a double link column) and `source_column_id` (pointing to the column whose values to display). To resolve a lookup value, follow: lookup column -> double_link_column_id -> find that column's item -> get linkRecordId -> query source_column_id values for those records.

property JSON中可携带 `dedupe` 布尔开关。为true时，派生结果中的重复值会被折叠，同时保留插入顺序（通过 `LinkedHashMap.putIfAbsent`），使展示、存储、集合运算源三条路径都接收到去重后的输入。切换 `dedupe` 不是只读的展示变更——Lookup列的存储值是反规范化缓存，因此该切换会触发Lookup列本身的全表重算，以及所有引用该列的集合运算列的全表重算。列更新路径和 `deduplicate` 端点都必须调用两步级联（`recomputeLookupColumnValues` + `recomputeSetOperationsForLookup`）；只调用第一步会导致依赖的集合运算列保持陈旧。

### Set Operation Column
A column whose value is computed by performing set operations (union, intersection, subtraction, complement) on the linkRecordId lists of two other columns (A and B). Uses `CollectionUtils` operations on linkRecordId, then reverse-looks up values from a mapping.

### Semantic Link Column (type=25)
A column linking a note to a multi-dimensional table cell/record. Distinct from type=21 (record↔record) and type=26 (derived lookup): it expresses the note↔table relationship. Its anchor representation inside note text is the `<a data-type="semantic">` element produced by the SemanticLink Editor.js inline tool (separate from the `<c id=...>` page-navigation anchor system). The link record is stored as a NoteNotelink row (id, noteId, blockId, linkNoteId, linkDwTableId, linkRecordId, linkColumnId, linkItemId, contextText, itemValue). Forward direction (note→table) is implemented; reverse direction (table→note) extends it to word-level granularity, where each anchor carries a unique `data-link-id` equal to the NoteNotelink id so a cell can address a specific selected word among many.

`linkNoteId`（目标多维表格的归属笔记 id）与 `linkDwTableId`（目标数据表 id）共同定位跳转目标，URL 形如 `/base/{linkNoteId}/{linkDwTableId}`。`linkNoteId` 是反向语义关联功能引入时才加入创建路径的，此前创建的历史记录该字段为 null；查询时需 LEFT JOIN NoteDwtable 并用 `COALESCE(linkNoteId, NoteDwtable.noteId)` 兜底，否则跨标签页跳转会因 noteId 缺失而失败。

### NoteDwtable
A multi-dimensional table data table owned by a note. Its `id` is the data-table primary key; its `noteId` is the owner note that hosts the table view. NoteNotelink's `linkDwTableId` references this `id`, and the owner `noteId` is the value NoteNotelink's `linkNoteId` should hold.

### NoteNotelink
A link record representing a semantic association between a note and a multi-dimensional table cell, persisted per anchor. `noteId`/`blockId` identify the source note and block; `linkDwTableId`/`linkColumnId`/`linkItemId`/`linkRecordId` identify the target cell; `linkNoteId` is the target table's owner note id (used as the navigation URL's first segment). Historical rows may have null `linkNoteId` — see the Semantic Link Column entry for the recovery rule.

### back_field_id
A property in type=21 columns that references the paired column in the other table. If column 3262 has `back_field_id=3263`, then column 3263 has `back_field_id=3262`. This pairing is essential for indirect trigger matching in set operations.

### NoteDwtableItem
A cell in the data table, identified by recordId + columnId. For type=21 columns, stores `value`, `linkRecordId`, and `linkColumnId`. For type=26 columns, these link fields are NULL — the data lives in the referenced double link column's item.
