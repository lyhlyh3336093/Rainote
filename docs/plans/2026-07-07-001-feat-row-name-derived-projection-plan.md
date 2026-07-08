# feat: 行名称派生投影同步（最左侧多行文本列驱动）

Created: 2026-07-07
Origin: brainstorm 对话结论——行名称权威源 = 最左侧 type=1 列的派生投影，始终同步

## Summary
将多维表格行名称（`NoteRecord.name`）改为最左侧多行文本列（type=1）的派生投影：编辑该列即同步行名称，行名称不再可独立编辑。统一插入、记录更新、列结构变更三类写入点的派生规则，并对现有数据做一次性回填。仅后端改动，前端 `name` 入参被忽略但 VO 保留兼容。

## Problem Frame
当前 `NoteRecord.name` 权威来源不清——插入时取 `items.get(0)`（不看列类型）、更新时仅当 name 为空才从多行文本列回填、其余情况不维护（见 `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java` L99-103 与 L125-135 的 TODO）。导致编辑最左侧多行文本后行名称不更新（ce-debug 定位的根因）。本计划消除双源问题，确立"派生投影"单一权威模型。

## Requirements
- R1：行名称始终等于该行最左侧 type=1 列的当前值（按 `sort` 取最左；多个 type=1 取最左那个）。
- R2：编辑最左侧 type=1 列内容后，记录更新路径立即同步行名称（不再受"name 为空"门槛限制）。
- R3：插入路径与更新路径使用同一派生规则（替换 `items.get(0)` 取名逻辑）。
- R4：列结构变更（删除/重排/改类型）导致最左侧 type=1 列变化时，触发受影响数据表所有行的 name 重算。
- R5：一次性回填所有现有记录的 name 至派生规则。
- R6：无 type=1 列或其值为空时 name=""。
- R7：前端 `name` 入参被忽略（派生值覆盖），VO 保留以兼容前端、无前端改动。
- 非目标：移除 `note_record.name` 持久化列改读时懒加载；任何前端改动。

## Key Technical Decisions
- **KTD-1 派生模型 = eager 同步（写时重算）**：沿用现有持久化字段 `name`，在写入点重算并落盘，而非读时投影（避免更大架构改动），匹配"始终同步"。
- **KTD-2 update 路径 dwtableId 反查**：`updateNoteRecord` 中 `noteRecord.getDwtableId()` 为 null（controller 不设，service 也无 view 解析），派生所需 dwtableId 从 `recordItems` 的 `dwtId` 字段反查（insert 时已写入），不可依赖 `noteRecord.getDwtableId()`。insert 路径不同——service 在 L81-85 已从 view 解析 dwtableId，insert 派生可安全用 `noteRecord.getDwtableId()`。
- **KTD-3 派生取值优先级**：派生发生在 items 保存之前，必须先从传入 `items` 取最左侧 type=1 列的新值；该列未在本批更新时才回退到 `recordItems`（DB 当前值）。否则复现 ce-debug 发现的"陈旧数据"缺陷。
- **KTD-4 依赖方向（防循环依赖）**：`NoteColumnService → NoteRecordService` 单向调用 `recomputeRecordNamesForTable`。`NoteRecordServiceImpl` 仅依赖 `noteColumnMapper`（mapper 非 service），故无环。实现者不得反向引入 `NoteRecordService → NoteColumnService`。
- **KTD-5 事务边界**：给 `updateNoteRecord`、`insertNoteRecord`、`recomputeRecordNamesForTable`、回填方法加 `@Transactional`，使 name 派生与 item 保存原子化；回填按表 `@Transactional`（每表独立事务，非一个大事务）以限制失败爆炸半径。
- **KTD-6 列结构变更始终重算**：列更新/删除/排序后无条件触发受影响表重算（幂等，表规模小），不做"仅当源列身份变化"的优化——匹配用户"触发重算"决策，且实现更简。

---

## Implementation Units

### U1. 抽取可复用派生核心逻辑
**Goal:** 在 NoteRecordServiceImpl 提供单记录派生与按表重算两个可复用方法。
**Requirements:** R1, R6
**Dependencies:** 无
**Files:**
- ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java（修改）
- ruoyi-system/src/main/java/com/ruoyi/system/service/INoteRecordService.java（修改，新增接口方法）
- ruoyi-system/src/test/java/com/ruoyi/system/service/impl/NoteRecordServiceImplTest.java（修改）
**Approach:**
- 新增 `String deriveRecordName(Long dwtableId, List<Map<String,Object>> incomingItems, List<NoteDwtableItem> existingItems)`：查 `selectNoteColumnList(dwtableId)`（已按 sort 排序）找首个 type==1 列；无则返回 ""；有则先在 incomingItems 按 columnId 取 value，取不到在 existingItems 按 columnId 取 value，仍取不到返回 ""。空值统一返回 ""。
- 新增 `int recomputeRecordNamesForTable(Long dwtableId)`：`@Transactional`（KTD-5）；查该表全部 record（`selectNoteRecordList` 按 dwtableId，mapper 已支持）；对每条用 `deriveRecordName(dwtableId, null, 该记录的 items)` 重算并 `updateNoteRecord` 落盘。
- dwtableId 解析遵循 KTD-2（本方法入参显式传入，由调用方负责解析）。
**Patterns to follow:** 沿用现有 `noteColumnMapper.selectNoteColumnList` / `noteDwtableItemMapper.selectNoteDwtableItemList` 用法。
**Test scenarios:**
- 左侧 type=1 列在 incomingItems 有新值 → 返回新值（happy）。
- 左侧 type=1 列不在 incomingItems → 回退 existingItems 值（edge）。
- 表无 type=1 列 → 返回 ""（edge）。
- 左侧 type=1 列值为空/null → 返回 ""（edge）。
- 多个 type=1 列 → 返回 sort 最小那个的值（edge）。
- 最左列非 type=1、次左列是 type=1 → 返回次左列值（edge）。
- recomputeRecordNamesForTable：表内 3 条记录重算后 name 分别等于各自 type=1 item 值；其中 1 条无 type=1 item → name=""（integration）。
**Verification:** 单测全绿；`recomputeRecordNamesForTable` 对给定表产出与手算一致的 name 集合。

### U2. 改造 updateNoteRecord 始终派生
**Goal:** 移除"name 为空"门槛，始终用 U1 派生 name；忽略前端 name。
**Requirements:** R1, R2, R6, R7
**Dependencies:** U1
**Files:**
- ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java（修改）
- ruoyi-system/src/test/java/com/ruoyi/system/service/impl/NoteRecordServiceImplTest.java（修改）
**Approach:**
- 删除 L125-135 的 `if (name==null||"")` 门槛块及其 TODO。
- 在 L136 `noteRecordMapper.updateNoteRecord` 之前：按 KTD-2 解析 dwtableId（从 `recordItems.get(0).getDwtId()`；recordItems 为空则跳过派生、保持原 name）；调用 `deriveRecordName(dwtableId, items, recordItems)`；`noteRecord.setName(结果)`。
- 取值优先级遵循 KTD-3（先 incoming items 再 recordItems）。
- 加 `@Transactional`（KTD-5）覆盖 name 持久化 + items 保存。
- 前端传入的 name 被 setName 覆盖（R7）。
**Test scenarios:**
- 已有非空 name 的记录，更新最左侧 type=1 item → updateNoteRecord 被调用时 name 等于新值（happy，回归 ce-debug 的 bug）。
- 更新非 type=1 item → name 保持等于当前 type=1 item 值（happy）。
- 表无 type=1 列 → name=""（edge）。
- 传入 items 中 type=1 值为空 → name=""（edge）。
- 前端传 name="旧" 但派生得 "新" → 落盘 name="新"，前端值被忽略（happy，R7）。
- recordItems 为空 → name 不被改动（edge）。
**Verification:** ce-debug 场景（编辑最左侧多行文本后行名称同步）单测通过；现有 updateNoteRecord 测试无回归。

### U3. 对齐 insertNoteRecord 到同一派生规则
**Goal:** 用 U1 派生替换 `items.get(0)` 取名逻辑。
**Requirements:** R1, R3, R6
**Dependencies:** U1
**Files:**
- ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java（修改）
- ruoyi-system/src/test/java/com/ruoyi/system/service/impl/NoteRecordServiceImplTest.java（修改）
**Approach:**
- 删除 L99-103 的 `items.indexOf(itemMap)==0` 取名块。
- items 保存循环结束后，用 `deriveRecordName(noteRecord.getDwtableId(), items, null)` 算 name（insert 路径 dwtableId 已由 service L81-85 从 view 解析，KTD-2），`noteRecordMapper.updateNoteRecord` 落盘。
- 加 `@Transactional`（KTD-5）。
**Test scenarios:**
- 插入且最左侧 type=1 列在 items 有值 → name 等于该值（happy）。
- 插入且 items 首项非 type=1 → name 取最左侧 type=1 列值而非 items.get(0)（回归原取错列行为，edge）。
- 插入且表无 type=1 列 → name=""（edge）。
**Verification:** 新建记录行名称与最左侧 type=1 列一致；不再取首列。

### U4. 列结构变更触发按表重算
**Goal:** 在列更新/删除/排序后触发受影响表 name 重算。
**Requirements:** R4
**Dependencies:** U1
**Files:**
- ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteColumnServiceImpl.java（修改）
- ruoyi-system/src/test/java/com/ruoyi/system/service/impl/NoteColumnServiceImplTest.java（修改）
**Approach:**
- 注入 `INoteRecordService`（KTD-4，单向依赖）。
- `updateNoteColumn` 末尾：`noteRecordService.recomputeRecordNamesForTable(column.getDwtableId())`。
- `deleteNoteColumnByIds` / `deleteNoteColumnById`：删除前取受影响 dwtableId，删除后重算该表。
- `updateSort`：重排后重算受影响表。
- 无条件重算（KTD-6，幂等）。
**Test scenarios:**
- 删除最左侧 type=1 列 → 该表 name 重算自下一个 type=1 列（或 ""）（happy）。
- 重排使不同列成最左 type=1 → name 重算（happy）。
- 改列类型为/出 1 → name 重算（edge）。
- 删除非源列 → 重算运行但 name 不变（edge，幂等）。
- updateSort → 重算运行（integration）。
**Verification:** 列结构变更后该表所有行 name 与新源列一致。

### U5. 一次性回填
**Goal:** 提供按表事务的回填能力，重算所有现有记录 name。
**Requirements:** R5
**Dependencies:** U1
**Files:**
- ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java（修改，新增 recomputeAllRecordNames）
- ruoyi-system/src/main/java/com/ruoyi/system/service/INoteRecordService.java（修改）
- ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/NoteRecordController.java（修改，新增 POST /noteRecord/backfillNames）
- ruoyi-system/src/test/java/com/ruoyi/system/service/impl/NoteRecordServiceImplTest.java（修改）
**Approach:**
- `recomputeAllRecordNames()`：`noteDwtableMapper.selectNoteDwtableList(new NoteDwtable())` 取全部表；逐表调用 `recomputeRecordNamesForTable`（每表各自 @Transactional，KTD-5/KTD-6）。
- controller 暴露 `POST /noteRecord/backfillNames`（一次性管理端点，无前端改动）。
**Test scenarios:**
- 回填遍历全部表；陈旧 name 记录被重算（happy）。
- type=1 值为空的记录 → name=""（edge）。
- 无 type=1 列的表 → 该表记录 name=""（edge）。
**Verification:** 回填后抽查若干表，name 与最左侧 type=1 列一致。

---

## Scope Boundaries

### Deferred to Follow-Up Work
- 回填性能优化：当前逐记录查 item，可改批量查询/JOIN——推迟到实现期评估。
- 并发：派生幂等收敛；type=1 单元格 lost-update 为既有问题，非本计划引入。

### 已考虑并排除的非同步点
- `insertNoteColumn`：新列走 `getNextSort` 排到最右、且 item 初始为空（`setValue("")`），不改变任何行 name——故不触发重算。
- 记录级 `updateSort`：重排记录不改源列值，name 不变——故不触发派生。
- lookup 列重算路径（`recomputeLookupColumnValues`）：type=26 永非源列——故不触发派生。

### Outside scope
- 移除 `note_record.name` 持久化列改读时懒加载投影。
- 任何前端改动。
- `linkName` 字段（独立关注点，不动）。

## Open Questions
- **OQ-1 `property` 字段语义**：`NoteRecord.property` 是否区分普通记录与分组头/特殊行？派生默认对所有记录生效；若 property 标识特殊行需排除，请确认。当前假设：派生对所有记录生效。

## Risks & Dependencies
- 依赖 U1 派生核心先就绪（U2–U5 依赖 U1）。
- `@Transactional` 新增属行为变更（事务边界扩大），需确认与既有调用方无副作用（如外层已有事务的传播行为）。
- 回填为全量重写，执行前建议备份 `note_record.name`。
