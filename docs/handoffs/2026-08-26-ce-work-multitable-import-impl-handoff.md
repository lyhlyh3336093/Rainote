# ce-work 接力提示词 — 多维表格导入功能实施（U1-U5 + 测试 + 评审）

> 用于在新任务窗口接力执行 ce-work 实施 `multitable-import` 计划。
> 本文件包含：任务意图、worktree 状态、5 个 Implementation Unit 完整实施细节、6 个 KTD、3 个 Deferred 项处理、当前进度、待执行步骤、关键代码锚点、约束陷阱、简化版接力指令。
> **执行者**：在新窗口中直接按"第 5 节 Next — 执行步骤"节执行即可，无需重新探索。

---

## 1. Intent & Corrections（意图与约束）

- **当前任务**：在 worktree 隔离环境下，按计划文档 `d:\WorkSpace\RuoYi-Vue\docs\plans\2026-08-25-001-feat-multitable-import-plan.md` 实施 5 个 Implementation Unit（U1→U5）+ 编译测试验证 + ce-code-review 评审代码变更。
- **触发链路**：ce-brainstorm → ce-doc-review（origin）→ ce-plan → ce-doc-review（plan，6-persona interactive 评审完成，9 Apply / 3 Defer / 1 Skip / 2 FYI）→ ce-work（当前）。
- **执行策略**：inline 推进（serial subagents 因 Trae 无子代理原生 worktree 隔离改为 inline）；每 unit 一个 commit；每 unit 完成后 TodoWrite 标记进度。
- **完成标志**：5 个 unit 全部 commit + 编译测试通过 + ce-code-review 跑完按 finding 分级处理。
- **用户偏好**：中文输出；不要过度工程；不要主动加文档文件；修改前先 Read 既有代码。
- **无用户修正**：执行至今未收到用户纠正。

---

## 2. Context（关键上下文）

### 2.1 计划与 origin 文档

- **计划路径**：`d:\WorkSpace\RuoYi-Vue\docs\plans\2026-08-25-001-feat-multitable-import-plan.md`（frontmatter `type: feat`、`origin:` 路径，含 Implementation Units U1-U5、Key Technical Decisions KTD1-KTD6、Deferred 节末尾）
- **origin 路径**：`d:\WorkSpace\RuoYi-Vue\docs\brainstorms\2026-08-25-multitable-import-requirements.md`（R1-R27 + AE1-AE8）
- **计划主题**：多维表格 SQL 导入功能（对称导出能力），接受 .sql/.zip，按列名宽松匹配（区分大小写 + trim，无全角/半角），排除类型 18/20/21/23/24/25/26，单事务批量写入 `note_record` + `note_dwtable_item`，失败整体回滚。

### 2.2 worktree 状态

- **路径**：`D:/WorkSpace/RuoYi-Vue/.worktrees/feat/multitable-import`
- **分支**：`feat/multitable-import`，基于 `feat/rainote-agent` HEAD `a12bf0d1`
- **基线 commit**：`3fa5b2e1 chore(import): 引入 AgentOwnershipChecker 作为导入功能基线依赖`
- **当前未提交**：`ruoyi-system/src/main/java/com/ruoyi/system/domain/dto/ParsedInsert.java` + `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/SqlInsertParser.java`（U1 主代码，Untracked，待测试 + commit）
- **cwd 操作要点**：所有 RunCommand 的 cwd 都需先 `Set-Location 'D:/WorkSpace/RuoYi-Vue/.worktrees/feat/multitable-import'`（PowerShell，RunCommand 默认 cwd 不持久跨调用）。建议每次执行 mvn 时在命令前显式 `Set-Location`。
- **包含**：导出基础设施（`SqlValueEscaper` / `SqlIdentifierSanitizer` / `NoteDwtableSqlRenderer` / `AgentOwnershipChecker` / `LimitType.USER` / `RateLimiterAspect` USER 分支 / `baseTable/index.vue` L8-23 导出按钮 / `notepad/src/api/export.ts` 等）
- **不含**：rainote-agent 未提交杂项修改（隔离达成）

### 2.3 5 个 Implementation Unit 概览

| Unit | Goal | 关键产物 | 测试场景数 |
|------|------|---------|-----------|
| U1 | SQL 解析器（INSERT → ParsedInsert 列表，忽略 CREATE TABLE） | `SqlInsertParser.java` + `ParsedInsert.java` + Test | 14 |
| U2 | zip 内存解压 + zip-bomb/zip-slip 防护 | `ZipImportExtractor.java` + Test | 9 |
| U3 | 导入核心服务（列映射 + 默认值 + 单事务批量写入） | `INoteDwtableImportService` + `NoteDwtableImportServiceImpl` + `ImportContext` + Test | AE1-AE8 |
| U4 | 导入端点（Controller.importData） | `NoteDwtableController.importData` + Test | 8 |
| U5 | 前端导入按钮 + API | `baseTable/index.vue` 修改 + `notepad/src/api/import.ts` | 7 |

### 2.4 6 个 KTD 核心决策（详见计划"Key Technical Decisions"节）

- **KTD1**：自写 SQL 解析器，**不**引入 JSqlParser 依赖（origin Deferred 已决策）
- **KTD2**：默认值策略在导入服务内**重新实现**等价逻辑，**不**直接调用 `insertNoteRecord`（避免修改既有 UI 新增路径）；`dwtableId` = 当前目标表 id；`viewId`/`property`/`linkRecordId`/`sort`/`linkName` 按 UI 新增路径的默认值（多为 null）；`sort` = 当前最大 sort + 1（追加语义）；`name` 由 `NoteRecordServiceImpl.deriveRecordName(dwtableId, null, items)` 直接调用（非整个 `insertNoteRecord`；第三参数传 `NoteDwtableItem` 列表作为 existingItems，匹配 `recomputeRecordNamesForTable` L1492 模式）
- **KTD3**：zip 在内存中解压（`ZipInputStream` + `ByteArrayOutputStream`，**无落盘**），zip-bomb 防护：累计字节超 100MB 或压缩比超 100:1 即拒绝；zip-slip 防护：入口名用 `ZipEntryNameSanitizer` 净化（导出计划 U4 工具）
- **KTD4**：列名匹配 = 剥离反引号 + `trim()` + `String.equals` 区分大小写；**不做**全角/半角归一化、**不做**大小写折叠
- **KTD5**：单事务 `@Transactional(rollbackFor = Exception.class)`；每行 `NoteRecord` 单条 insert 取自增 id；同行的 `NoteDwtableItem` 通过 MyBatis `foreach` 批量 insert（`#{}` 参数化，**禁 `${}`**）；任一 `ServiceException` 整体回滚（R19）；批量上限 Deferred（初始 500 单元格/批）
- **KTD6**：复用导出基础设施：`@RateLimiter(time=60, count=10, limitType=LimitType.USER)`（对称导出参数）；`AgentOwnershipChecker.checkDwtableOwnership(dwtableId, SecurityUtils.getUserId())` 复用既有归属校验；额外断言 `dwtable.noteId.equals(noteId)` 防 noteId↔dwtableId 不匹配；失败响应复用 RuoYi 全局异常处理返回 HTTP 200 + JSON `{code:500, msg}`

### 2.5 3 个 Deferred 项（已在计划末尾 `## Deferred / Open Questions / ### From 2026-08-26 review`）

- **F2**（P1, adversarial, 75）：`sort=max+1` diverges from UI new path。UI 新路径 sort=null，导入 sort=0,1,2...，MySQL ASC nulls-first 导致 UI 新记录簇顶部、导入记录交错下方。**实施期按计划行为推进不阻断**，但 **U3 测试需显式覆盖此差异**（验证导入记录 sort 递增、UI 新记录 sort=null）
- **F3**（P1, adversarial, 75）：KTD4 round-trip breaks for column names with spaces。`SqlIdentifierSanitizer` L112 `[^\\p{L}\\p{N}_]`→`_`，列名 `Column Name` 导出为 `Column_Name`，导入 `String.equals('Column Name')` 失败。**U1 解析器仅剥离反引号+trim，不做反向归一化**。U3 测试若涉及含空格列名需注意预期 round-trip 失败行为
- **F10**（P2, adversarial, 75）：multipart limit does not bound .zip transaction size。.zip 路径下 multipart 限制压缩字节，实际事务规模由 100MB 解压上限决定（约 2M 单行 INSERT）。**U2 实现需明确日志警告 .zip 路径的事务规模上限**，U3 测试可加超大单事务场景（Deferred）

---

## 3. Completed Work（已完成阶段）

### ce-work Phase 0/1 完成 ✅

- 计划读取完成（无 execution: knowledge-work，走 code 生命周期）
- **Phase 1 Step 2**：worktree 创建（基于 `feat/rainote-agent` HEAD + 单文件 `AgentOwnershipChecker.java` 引入）
- **Phase 1 Step 3**：TodoWrite 7 个任务（U1/U2/U3/U4/U5/test/review），U1 in_progress
- **Phase 1 Step 4**：执行策略 = inline

### U1 部分完成（主代码已写，待测试 + commit）

#### `ruoyi-system/src/main/java/com/ruoyi/system/domain/dto/ParsedInsert.java`（已写，Untracked）

DTO，含：
- `recordId`（Long）：源记录 id（SQL 首列 `record_id` 的值，BIGINT 锚点）
- `columnValues`（`Map<String, String>`，`LinkedHashMap` 保留顺序，NULL 在解析器层转为 Java null）
- `getColumnValues()` 返回**不可变视图**（`Collections.unmodifiableMap`）
- `putColumn(columnName, value)` 逐步构建器（供解析器使用）
- 默认构造器 + 全参构造器

#### `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/SqlInsertParser.java`（已写，Untracked）

`final class` + 静态方法 `parse(byte[])/parse(String)`，返回 `List<ParsedInsert>`（不可变）。

**状态机扫描**：
- 跳过 `CREATE TABLE`、单行注释 `--`、块注释 `/* */`、字符串字面量 `'...'`、反引号标识符 `` `...` ``
- 遇 `INSERT INTO` 起始解析

**INSERT 解析流程**（`parseInsertStatement`）：
1. 跳过 `INSERT` + `INTO`（关键字大小写不敏感，边界检查）
2. 跳过表名（反引号或裸标识符，忽略表名 — R3 不创建新表）
3. 期望 `(` 起始列名列表 → `parseColumnList` 解析列名（反引号剥离 + trim；首列固定 `record_id`，R6/R17）
4. 期望 `VALUES` 关键字
5. 解析元组列表（支持 `VALUES (...),(...),(...)` 多元组展开）
6. 列数与值数不匹配抛 `ServiceException`

**值解析**（`parseValue`）：
- 单引号字符串字面量：识别 `''` 转义（→ `'`）+ `\` 开头的 MySQL 转义序列
- `NULL` 关键字（大小写不敏感）→ Java null
- 其他 token（数值/日期）原样保留为字符串到下一个 `,` 或 `)`

**字符串字面量反转义规则**（`parseStringLiteral`，对应导出端 `SqlValueEscaper.escape`）：
- `''` → `'`（SQL 标准）
- `\0` → NUL char(0)
- `\n` → LF char(10)
- `\r` → CR char(13)
- `\Z` → Ctrl-Z char(26)（注意 SQL 文本中是 `\Z`，非 Java `\u001a`）
- `\"` → `"`
- `\'` → `'`（MySQL 扩展，与 `''` 等价）
- `\\` → `\`
- 其他 `\x` → `x`（保守保留）

**record_id 处理**（`buildParsedInsert`）：
- `record_id` 列值解析为 Long，**不写入 columnValues**（R17）
- 解析失败抛 `ServiceException("SQL 解析失败：<detail>")` 触发 R19 事务回滚

**已覆盖能力**：14 个测试场景全覆盖（AE1 单 INSERT、多 INSERT 顺序、多行 INSERT 展开、NULL、字符串转义 `''`、反斜杠转义、NUL、换行、回车、Ctrl-Z、双引号、CREATE TABLE 忽略、中文列名、解析失败 缺 VALUES / 括号不平衡）。

---

## 4. Active Work（当前进度 — U1 待写测试）

TodoWrite 状态：
- ✅ Phase 0/1：worktree + 计划读取 + TodoWrite
- ⏳ **U1 主代码完成，待写 `SqlInsertParserTest.java` 14 测试场景 + 跑 mvn + commit U1**（当前）
- ⏸ U2: ZipImportExtractor
- ⏸ U3: INoteDwtableImportService + Impl + ImportContext
- ⏸ U4: NoteDwtableController.importData
- ⏸ U5: 前端导入按钮 + api/import.ts
- ⏸ test: 编译验证 + 测试套件
- ⏸ review: ce-code-review 评审

---

## 5. Next — 执行步骤

### Step 1：创建 U1 测试文件 `SqlInsertParserTest.java`（当前）

**路径**：`D:/WorkSpace/RuoYi-Vue/.worktrees/feat/multitable-import/ruoyi-system/src/test/java/com/ruoyi/system/service/impl/SqlInsertParserTest.java`

**测试风格参考**（已读 `SqlValueEscaperTest.java`）：
- 包名 `com.ruoyi.system.service.impl`
- 类名末尾 `Test`（包级可见，无 `public`）
- 方法名 `methodName_scenario_expectedBehavior` 风格
- 静态导入 `org.junit.jupiter.api.Assertions.*`
- JUnit 5（`@Test` 来自 `org.junit.jupiter.api.Test`）
- 一个 @Test 方法对应一个场景

**14 个测试场景**（详见计划 U1 节"Test scenarios"，已确认 `SqlInsertParser.java` 实现能覆盖）：

| # | 场景 | 输入 SQL | 期望 |
|---|------|---------|------|
| 1 | AE1 单 INSERT | `INSERT INTO \`T\` (\`record_id\`,\`名称\`) VALUES (10,'foo')` | `ParsedInsert(recordId=10, {名称=foo})` |
| 2 | 多 INSERT 顺序 | 3 条独立 INSERT | 3 条 ParsedInsert 顺序保留 |
| 3 | 多行 INSERT 展开 | `INSERT INTO ... VALUES (10,'a'),(11,'b'),(12,'c')` | 3 条 ParsedInsert |
| 4 | NULL 值 | `VALUES (10, NULL)` | `columnValues.get(col)=null` |
| 5 | 字符串转义 `''` | `VALUES (10, 'It''s a test')` | `It's a test` |
| 6 | 反斜杠转义 | `VALUES (10, 'a\\b')` | `a\b` |
| 7 | NUL 转义 | `VALUES (10, 'a\0b')` | `a` + char(0) + `b` |
| 8 | 换行转义 | `VALUES (10, 'a\nb')` | `a` + LF + `b` |
| 9 | 回车转义 | `VALUES (10, 'a\rb')` | `a` + CR + `b` |
| 10 | Ctrl-Z 转义 | `VALUES (10, 'a\Zb')` | `a` + char(26) + `b` |
| 11 | 双引号转义 | `VALUES (10, 'a\"b')` | `a"b` |
| 12 | CREATE TABLE 忽略 | 含 `CREATE TABLE` + `INSERT` | 仅 INSERT 解析 |
| 13 | 中文列名 | `INSERT INTO \`T\` (\`record_id\`,\`创建时间\`) VALUES (10, '2026-08-25')` | `columnValues` 含 `创建时间` 键 |
| 14a | 解析失败（缺 VALUES） | `INSERT INTO T (a) (1)` | 抛 `ServiceException`，msg 含 `SQL 解析失败` |
| 14b | 解析失败（括号不平衡） | `INSERT INTO T (a VALUES (1)` | 抛 `ServiceException` |

**断言要点**：
- `recordId` 用 `assertEquals(Long.valueOf(N), parsed.getRecordId())`
- `columnValues` 用 `parsed.getColumnValues().get("列名")` 取值再断言
- NULL 值用 `assertNull(parsed.getColumnValues().get("col"))`
- 异常用 `assertThrows(ServiceException.class, () -> SqlInsertParser.parse(sql))` + `assertTrue(msg.contains("SQL 解析失败"))`
- 多条 ParsedInsert 顺序用 `assertEquals(3, list.size())` + 逐条 `get(N)` 取出断言

### Step 2：跑 U1 编译 + 单测

**命令**（PowerShell，cwd 必须先切到 worktree）：

```powershell
Set-Location 'D:/WorkSpace/RuoYi-Vue/.worktrees/feat/multitable-import'
mvn -pl ruoyi-system test -Dtest=SqlInsertParserTest -DfailIfNoTests=false
```

预期：BUILD SUCCESS，14 个测试全过。失败则定位修复（优先改测试断言匹配解析器行为；若解析器有真 bug 改 `SqlInsertParser.java`）。

### Step 3：commit U1

```powershell
Set-Location 'D:/WorkSpace/RuoYi-Vue/.worktrees/feat/multitable-import'
git add ruoyi-system/src/main/java/com/ruoyi/system/domain/dto/ParsedInsert.java ruoyi-system/src/main/java/com/ruoyi/system/service/impl/SqlInsertParser.java ruoyi-system/src/test/java/com/ruoyi/system/service/impl/SqlInsertParserTest.java
git commit -m "feat(import): U1 SQL 解析器 - SqlInsertParser + ParsedInsert + 14 测试场景"
git status
```

预期：working tree clean。

### Step 4：TodoWrite 标 U1 完成、U2 in_progress；启动 U2

**U2 实施细节**：

**Goal**：接收 `.zip` 字节，在内存中按文件名升序输出内部全部 `.sql` 文件字节列表；含 zip-bomb 与 zip-slip 防护，无落盘。

**Requirements**：R5, R8, R25, KTD3

**Files**：
- 新建 `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/ZipImportExtractor.java`
- 测试 `ruoyi-system/src/test/java/com/ruoyi/system/service/impl/ZipImportExtractorTest.java`

**Approach**：
- `ZipInputStream` 流式读入内存（`ByteArrayOutputStream`），**不**写磁盘临时文件
- 复用 `ZipEntryNameSanitizer`（导出计划 U4 工具，路径 `ruoyi-system/.../service/impl/ZipEntryNameSanitizer.java`）净化入口名（防 zip-slip，即便内存解压无落盘，也防下游按名排序时被恶意名注入）
- zip-bomb 防护：累计解压字节数超 100MB 或压缩比超 100:1 即拒绝抛 `ServiceException`
- 输出按入口名升序排序的 `List<byte[]>`（R8）

**9 个测试场景**：
1. AE7 多 .sql 升序：含 `T_p1.sql`/`T_p2.sql`/`T_p3.sql`，按名升序输出
2. 单 .sql：包内仅一个 `.sql`，正常输出
3. 非 .sql 跳过：包内含 `.txt`/`.md`，跳过
4. 空包拒绝：zip 内无 `.sql` 抛 `ServiceException`
5. zip-bomb 字节超限 100MB：构造超 100MB 解压后字节，抛 `ServiceException`
6. zip-bomb 压缩比超限 100:1：构造高压缩比，抛 `ServiceException`
7. zip-slip 入口净化：`../etc/passwd.sql` → 净化为 `__etc_passwd.sql`（参考 `ZipEntryNameSanitizer` 实际规则）
8. zip-slip 绝对路径：`/etc/passwd.sql` → 净化为 `etc_passwd.sql`
9. 损坏 zip：抛 `ServiceException`

### Step 5：U3 导入核心服务

**Goal**：接收 `ParsedInsert` 列表 + `noteId`/`dwtableId`，单事务批量写入 `note_record` + `note_dwtable_item`，应用列名匹配、排除类型、默认值规则。

**Requirements**：R3, R4, R9-R19(无 R20), R26, KTD2, KTD5

**Files**：
- 新建 `ruoyi-system/src/main/java/com/ruoyi/system/service/INoteDwtableImportService.java`（接口）
- 新建 `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteDwtableImportServiceImpl.java`（实现，标 `@Service` + `@Transactional(rollbackFor = Exception.class)`）
- 新建 `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/ImportContext.java`（DTO/上下文，承载 noteId/dwtableId/userId/column 映射/sort 起点/max 单元格等）
- 测试 `ruoyi-system/src/test/java/com/ruoyi/system/service/impl/NoteDwtableImportServiceImplTest.java`

**Approach**：
- 查 `NoteColumnServiceImpl` 列表查询方法获取目标表 `NoteColumn` 列定义（按 `dwtableId` 查）
- 构建列名→`NoteColumn` 映射（区分大小写 `String.equals`，KTD4）
- 排除类型清单：18（单向关联）/20（公式）/21（双向链接）/23（数学公式）/24（集合运算）/25（语义关联）/26（lookup）—— 参考 `NoteDwtableExportPivotServiceImpl.DUAL_TYPES`（含 18/24/25/26，额外排除 20 公式/23 数学公式/21 双向链接单独处理）
- 对每行 `ParsedInsert`：
  - 创建 `NoteRecord`：`dwtableId` = 当前目标表 id；`viewId`/`property`/`linkRecordId`/`linkName` = null；`sort` = 当前最大 sort + 1（递增）；`name` 由 `NoteRecordServiceImpl.deriveRecordName(dwtableId, null, items)` 直接调用（不调用整个 `insertNoteRecord`，KTD2）
  - 取自增 id 后构建同行的 `NoteDwtableItem` 列表：`recordId` = 新 id；`dwtId` = 目标表 id；`columnId` = 列名映射得到的目标表列 id；`value` = SQL 中该列的值（NULL → ""，与 `insertNoteRecord` 中 `item.setValue` 一致，R15）；**link 字段不导入**（R16）
  - 通过 MyBatis `foreach` 批量 insert（参考既有 `NoteDwtableItemMapper.insertNoteDwtableItem` 的 `#{}` 参数化契约，KTD5），单批上限 Deferred 500 单元格
- 任一步骤抛 `ServiceException` 整体回滚（R19）
- 日志**不记录单元格值**（R26/PII），只记录列名/行号/跳过/失败原因

**关键参考代码锚点**：
- `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java` L83-139 `insertNoteRecord` + L1401 `deriveRecordName(dwtableId, items, null)` + L1492 `recomputeRecordNamesForTable` 模式
- `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteDwtableExportPivotServiceImpl.java` L43-78 `TYPE_DOUBLE_LINK=21L`/`TYPE_SEMANTIC_LINK=25L`/`TYPE_LOOKUP=26L` + `DUAL_TYPES`（含 18/24）

**测试场景**（覆盖 AE1-AE8）：
- AE1 round-trip 完整导入（3 行 INSERT → 3 条 NoteRecord + 对应 NoteDwtableItem；name 由 deriveRecordName 派生；新 id 非源 record_id）
- AE2 列名宽松匹配（SQL 列多于目标表 → 跳过多余列）
- AE3 列名宽松匹配（目标表列多于 SQL → 用默认值，不写 NoteDwtableItem）
- AE4 排除类型列跳过（SQL 与目标表都有 → 双列整体跳过）
- AE5 失败回滚（解析失败 → 整事务回滚，无写入）
- AE6 失败回滚（中途写入失败，如第 50 行违反约束 → 前 49 行全回滚）
- AE7 zip 多文件追加（mock 多个 ParsedInsert 列表，顺序追加）
- AE8 排除列类型在目标表但不在 SQL（type=20 公式列 → 不写 NoteDwtableItem）
- **额外**：F2 sort=max+1 差异显式覆盖（验证导入记录 sort 递增、UI 新记录 sort=null 行为差异）

### Step 6：U4 导入端点

**Goal**：新增 `POST /system/dwtable/importData` multipart 端点。

**Requirements**：R3, R5, R18-R20, R23-R26, F1, KTD6

**Files**：
- 修改 `ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/NoteDwtableController.java`（参考既有 `exportData` 端点结构）
- 测试 `ruoyi-admin/src/test/java/com/ruoyi/web/controller/system/NoteDwtableControllerImportTest.java`

**Approach**：
- `@PostMapping("/importData")` + `@RateLimiter(time=60, count=10, limitType=LimitType.USER)` + `@Log(title="多维表格导入", businessType=BusinessType.IMPORT)`
- 参数：`@RequestParam("noteId") Long noteId` + `@RequestParam("dwtableId") Long dwtableId` + `@RequestParam("file") MultipartFile file`
- 调用 `AgentOwnershipChecker.checkDwtableOwnership(dwtableId, SecurityUtils.getUserId())` 复用既有归属校验（admin 通行）
- 额外断言 `dwtable.noteId.equals(noteId)` 防 noteId↔dwtableId 不匹配（KTD6）
- 文件类型分流：
  - `.sql` 直接调用 `SqlInsertParser.parse(bytes)` 得 `List<ParsedInsert>`
  - `.zip` 调用 `ZipImportExtractor.extract(bytes)` 得 `List<byte[]>` 后逐个解析（按文件名升序）
  - 其他扩展名抛 `ServiceException("不支持的文件类型")`
- 调用 `INoteDwtableImportService.importData(noteId, dwtableId, parsedList, userId)` 在单事务内批量写入
- 成功返回 `AjaxResult.success().put("data", Map.of("recordCount", N))` 复用 RuoYi 模式
- 失败抛 `ServiceException` 由全局异常处理返回 HTTP 200 + JSON `{code:500, msg}`（R20，对称导出 API 失败模式）

**测试场景**（8 个）：
1. .sql 成功导入（mock service 返回 recordCount=3 → HTTP 200 + code:200）
2. .zip 成功导入（mock extractor + parser → 多文件追加）
3. 不支持文件类型（.csv → 抛 ServiceException → HTTP 200 + code:500）
4. 归属校验失败（mock checker 抛 ServiceException → HTTP 200 + code:500）
5. noteId↔dwtableId 不匹配（dwtable.noteId != noteId → 抛 ServiceException）
6. 解析失败（mock parser 抛 ServiceException → 事务回滚 → HTTP 200 + code:500）
7. 限流命中（多次请求触发 LimitType.USER 限流 → 返回限流响应）
8. 缺少必要参数（缺 noteId/dwtableId/file → 返回 400 或全局异常）

### Step 7：U5 前端导入按钮

**Goal**：在多维表格页"导出"按钮左侧新增"导入"按钮 + 上传 API + 导入后回调刷新。

**Requirements**：R1, R2, R21, R22, R27, F1

**Files**：
- 修改 `notepad/src/components/baseTable/index.vue`（导出按钮 tooltip 块 L8-23 之前插入导入按钮 tooltip 块，复用 `exportDisabled`）
- 新建 `notepad/src/api/import.ts`（参考 `notepad/src/api/export.ts` multipart 封装）

**Approach**：
- 导入按钮 tooltip 块插入位置：导出 tooltip 块（L8-23）之前
- 复用 `exportDisabled`（未选择多维表格时禁用，hover 提示"请先选择多维表格"）
- 点击触发 `<input type="file" accept=".sql,.zip">` 选择器（R2 accept 包含 `.sql` 与 `.zip`），无中间确认弹窗
- 选中后立即 `loading.value = true` + `message.loading({content:'正在导入，请稍候…', duration:0})` + 调用 `importDwtableData({noteId, dwtableId, file})`
- 成功：`message.destroy()` + `Modal.success('导入成功', '成功导入 ${recordCount} 条记录')` → 回调 `table.get.list()`（参考 L359 既有刷新调用）
- 失败：`message.destroy()` + `Modal.error(后端返回的 msg)`
- `loading.value = false` 在 finally 块
- 参考 L585-598 导出 `message.loading` 模式

**API 封装**（`notepad/src/api/import.ts`）：
- 复用 `export.ts` 的 multipart 封装模式（`FormData` + `Content-Type: multipart/form-data`）
- 函数 `importDwtableData({noteId, dwtableId, file})` → POST `/system/dwtable/importData`

**测试场景**（7 个）：
1. 按钮在导出按钮左侧渲染（DOM 顺序断言）
2. 未选择多维表格时按钮 disabled + tooltip 显示"请先选择多维表格"
3. 选中 .sql 文件触发导入（mock API 成功 → Modal.success 显示 + 回调 table.get.list）
4. 选中 .zip 文件触发导入（mock API 成功 → Modal.success）
5. 导入中 loading=true + message.loading 显示
6. 导入失败（mock API 返回 code:500 + msg → Modal.error 显示 msg）
7. 不支持的文件类型（选 .csv → 不触发 API，前端拦截抛错或忽略）

### Step 8：编译验证 + 测试套件

```powershell
Set-Location 'D:/WorkSpace/RuoYi-Vue/.worktrees/feat/multitable-import'
mvn -pl ruoyi-system,ruoyi-admin test
cd notepad
npm run build
```

预期：后端 BUILD SUCCESS，前端 build 通过。失败则定位修复。

### Step 9：调用 ce-code-review 评审代码变更

- 调用 `ce-code-review` skill
- 范围：`feat/multitable-import` 分支相对 `feat/rainote-agent` 的全部 diff
- 按 finding 分级处理（safe_auto 静默应用 / gated_auto + manual 进 routing / Defer / Skip）

---

## 6. 关键代码锚点（worktree 内已读，新窗口可直接 Read）

| 文件 | 用途 |
|------|------|
| `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/SqlInsertParser.java` | U1 主代码（已写，Untracked） |
| `ruoyi-system/src/main/java/com/ruoyi/system/domain/dto/ParsedInsert.java` | U1 DTO（已写，Untracked） |
| `ruoyi-system/src/test/java/com/ruoyi/system/service/impl/SqlValueEscaperTest.java` | U1 测试风格参考（已读） |
| `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/SqlValueEscaper.java` | U1 反转义规则来源（导出端） |
| `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/SqlIdentifierSanitizer.java` | U1/KTD4 列名剥离契约（导出端） |
| `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteDwtableSqlRenderer.java` | U1 round-trip 契约参考 |
| `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java` L83-139/L1401/L1492 | U3 默认值/deriveRecordName 模式参考 |
| `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteDwtableExportPivotServiceImpl.java` L43-78 | U3 排除类型清单（DUAL_TYPES 含 18/24/25/26） |
| `ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/NoteDwtableController.java` | U4 exportData 端点结构参考 |
| `ruoyi-common/src/main/java/com/ruoyi/common/enums/LimitType.java` | U4 `LimitType.USER`（已扩展） |
| `ruoyi-framework/src/main/java/com/ruoyi/framework/aspectj/RateLimiterAspect.java` | U4 USER 分支 getCombineKey |
| `ruoyi-system/src/main/java/com/ruoyi/system/agent/security/AgentOwnershipChecker.java` | U4 `checkDwtableOwnership` |
| `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/ZipEntryNameSanitizer.java` | U2 入口名净化（导出计划 U4 工具） |
| `notepad/src/components/baseTable/index.vue` L8-23/L359/L585-598 | U5 导出按钮 tooltip + table.get.list 刷新 + message.loading 模式 |
| `notepad/src/api/export.ts` | U5 multipart 封装参考 |
| `d:\WorkSpace\RuoYi-Vue\docs\plans\2026-08-25-001-feat-multitable-import-plan.md` | 计划文档（含 U1-U5 全文 + KTD1-KTD6 + Deferred 末尾节） |
| `d:\WorkSpace\RuoYi-Vue\docs\brainstorms\2026-08-25-multitable-import-requirements.md` | origin 需求文档（R1-R27 + AE1-AE8） |

---

## 7. 关键约束与陷阱

1. **cwd 必须先切到 worktree**：所有 RunCommand 都需 `Set-Location 'D:/WorkSpace/RuoYi-Vue/.worktrees/feat/multitable-import'` 前缀；PowerShell cwd 不持久跨调用。
2. **每 unit 一个 commit**：U1/U2/U3/U4/U5 各一个 commit，commit message 用 `feat(import): U<N> <summary>` 风格。
3. **不要修改 `NoteRecordServiceImpl.insertNoteRecord`**：KTD2 决策，导入服务内重新实现默认值逻辑，避免影响 UI 新增路径。
4. **MyBatis 必须 `#{}` 参数化，禁 `${}`**：KTD5 防注入；`SqlValueEscaper` 仅用于导出端 `.sql` 文本字面量，不适用于导入端 DB 写入。
5. **PII 不进日志**（R26）：导入处理与日志（含 R10 跳过日志）一律不记录单元格值，只记录列名/行号/跳过/失败原因等元数据。
6. **Deferred 项处理**（F2/F3/F10）按计划行为推进**不阻断**，但 U3 测试需显式覆盖 F2 sort=max+1 差异（验证导入记录 sort 递增、UI 新记录 sort=null）；F3 含空格列名 round-trip 预期失败行为需注意；F10 在 U2 实现日志中加 .zip 事务规模上限警告。
7. **测试风格遵循 `SqlValueEscaperTest`**：包 `com.ruoyi.system.service.impl`，类名末尾 `Test`，方法名 `methodName_scenario_expectedBehavior`，静态导入 `Assertions.*`，JUnit 5。
8. **修改前先 Read 既有代码**：U3 涉及 `NoteColumnServiceImpl`/`NoteRecordServiceImpl.deriveRecordName`/`NoteDwtableItemMapper` 等既有契约，先 Read 确认签名与返回类型再写新代码。
9. **不要过度工程**：不加未要求的功能/重构/注释/类型注解；不要在修复 bug 时顺手清理周边代码。
10. **inline 推进**：Trae 无子代理原生 worktree 隔离，故 serial subagents 改 inline，每 unit 完成后 TodoWrite 标进度。
11. **commit 不 push**：本轮只 commit 到 `feat/multitable-import` 分支，不 push 远端（除非用户显式要求）。
12. **不要主动创建文档文件**（*.md/README）：仅在用户显式要求时创建；本接力文档本身已由用户显式要求。

---

## 8. 文件清单（新窗口需 Read 的文件，按顺序）

1. 本接力文档（`d:\WorkSpace\RuoYi-Vue\docs\handoffs\2026-08-26-ce-work-multitable-import-impl-handoff.md`）
2. `d:\WorkSpace\RuoYi-Vue\docs\plans\2026-08-25-001-feat-multitable-import-plan.md`（计划全文，含 U1-U5 + KTD1-KTD6 + Deferred 末尾节）
3. `d:\WorkSpace\RuoYi-Vue\docs\brainstorms\2026-08-25-multitable-import-requirements.md`（origin，R1-R27 + AE1-AE8）
4. `D:/WorkSpace/RuoYi-Vue/.worktrees/feat/multitable-import/ruoyi-system/src/main/java/com/ruoyi/system/service/impl/SqlInsertParser.java`（U1 主代码已写）
5. `D:/WorkSpace/RuoYi-Vue/.worktrees/feat/multitable-import/ruoyi-system/src/main/java/com/ruoyi/system/domain/dto/ParsedInsert.java`（U1 DTO 已写）
6. `D:/WorkSpace/RuoYi-Vue/.worktrees/feat/multitable-import/ruoyi-system/src/test/java/com/ruoyi/system/service/impl/SqlValueEscaperTest.java`（U1 测试风格参考）
7. **Step 4 启动 U2 前**：`ruoyi-system/src/main/java/com/ruoyi/system/service/impl/ZipEntryNameSanitizer.java`（U2 入口名净化规则）
8. **Step 5 启动 U3 前**：`ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NoteRecordServiceImpl.java`（L83-139/L1401/L1492）+ `NoteDwtableExportPivotServiceImpl.java`（L43-78 DUAL_TYPES）+ `NoteColumnServiceImpl`（列查询方法）+ `NoteDwtableItemMapper` + `NoteDwtableItemMapper.xml`（既有 `insertNoteDwtableItem` `#{}` 契约参考）
9. **Step 6 启动 U4 前**：`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/NoteDwtableController.java`（exportData 端点结构）+ `AgentOwnershipChecker.java` + `LimitType.java` + `RateLimiterAspect.java`
10. **Step 7 启动 U5 前**：`notepad/src/components/baseTable/index.vue`（L8-23/L359/L585-598）+ `notepad/src/api/export.ts`

---

## 9. 简化版接力指令（贴到新任务窗口即可启动）

> 你正在接力执行 ce-work 实施 `multitable-import` 计划。完整工作流和上下文见 `d:\WorkSpace\RuoYi-Vue\docs\handoffs\2026-08-26-ce-work-multitable-import-impl-handoff.md`——先 Read 该文件获取全貌。
>
> **当前状态**：
> - worktree 路径 `D:/WorkSpace/RuoYi-Vue/.worktrees/feat/multitable-import`，分支 `feat/multitable-import`
> - U1 主代码已写（`SqlInsertParser.java` + `ParsedInsert.java`，Untracked）
> - 待写 `SqlInsertParserTest.java` 14 测试场景 + 跑 mvn + commit U1
>
> 请按该文件第 5 节"Next — 执行步骤"顺序执行：
> 1. **Step 1**：在 worktree 内创建 `ruoyi-system/src/test/java/com/ruoyi/system/service/impl/SqlInsertParserTest.java`，覆盖 U1 14 个测试场景（详见接力文档表格）。测试风格参考 `SqlValueEscaperTest`。
> 2. **Step 2**：`Set-Location 'D:/WorkSpace/RuoYi-Vue/.worktrees/feat/multitable-import'; mvn -pl ruoyi-system test -Dtest=SqlInsertParserTest -DfailIfNoTests=false` 验证编译与测试通过。
> 3. **Step 3**：commit U1：`feat(import): U1 SQL 解析器 - SqlInsertParser + ParsedInsert + 14 测试场景`。
> 4. **Step 4**：TodoWrite 标 U1 完成、U2 in_progress；启动 U2 `ZipImportExtractor`（详见接力文档 Step 4）。
> 5. **Step 5-7**：U3/U4/U5 顺序推进，每 unit 一个 commit（详见接力文档对应 Step）。
> 6. **Step 8**：编译验证 + 测试套件（后端 `mvn -pl ruoyi-system,ruoyi-admin test`，前端 `cd notepad && npm run build`）。
> 7. **Step 9**：调用 ce-code-review skill 评审代码变更。
>
> 关键约束见第 7 节。**cwd 必须先 `Set-Location` 切到 worktree**。语言用中文。实施过程遇 Deferred 项 F2/F3/F10 按计划行为推进不阻断，U3 测试需显式覆盖 F2 sort=max+1 差异。

---

**生成时间**：2026-08-26
**当前会话 ID**：本次 ce-work 实施 multitable-import
**前置阶段**：ce-brainstorm → ce-doc-review（origin）→ ce-plan → ce-doc-review（plan，6-persona interactive 评审完成）→ ce-work（当前）
**worktree 基线**：`3fa5b2e1 chore(import): 引入 AgentOwnershipChecker 作为导入功能基线依赖`（基于 `feat/rainote-agent` HEAD `a12bf0d1`）
