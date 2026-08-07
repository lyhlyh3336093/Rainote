---
title: "SQL 标识符 Unicode 净化器模式（三层净化策略）"
date: 2026-08-07
category: design-patterns
module: ruoyi-system
problem_type: design_pattern
component: service_object
severity: medium
applies_when:
  - "从用户输入（多维表格名/列名等）派生 SQL 标识符时"
  - "标识符可能包含非 ASCII 字符（中文、日文、韩文等）时"
  - "在生成 SQL DDL 前需要对标识符进行合法化校验与转义时"
  - "目标数据库支持反引号包裹的 Unicode 标识符（如 MySQL 8.0）时"
tags: [sql, identifier, sanitizer, unicode, mysql, backtick, whitelist, escaping]
---

# SQL 标识符 Unicode 净化器模式（三层净化策略）

## Context

在为用户生成 SQL 脚本（如导出多维表结构、动态拼装 DDL/DML）时，需要对用户输入的表名/列名做"安全净化"，避免 SQL 注入和语法错误。常见的错误起点是套用"ASCII 白名单正则"：把所有非 ASCII 字符视为非法并替换为下划线。这种做法在国际化/中文场景下会破坏合法标识符——`经验树` 变成 `___`、`数据表23333` 变成 `___23333`，多个列名还会塌缩成相同的下划线串导致冲突，最终生成的 SQL 完全不可用。本模式给出一个三层净化 + 反引号兜底的通用方案，既保留 Unicode 标识符，又不降低安全性。

本模式在 `SqlIdentifierSanitizer.java` 中沉淀。触发契机是一个真实 bug：初版用 ASCII 白名单 `^[A-Za-z_][A-Za-z0-9_]{0,62}$`，导致中文表名/列名全部被替换为下划线。修复后改用 Unicode 字符类 `\p{L}\p{N}`，11 个单元测试全绿，端到端导出验证中文标识符原样保留。

## Guidance

核心原则：**安全净化 ≠ ASCII 化**。正确的判据是"字符是否属于合法标识符字符集"，而不是"字符是否在 ASCII 范围内"。

### 子模式 1：用 Unicode 字符类构建白名单

Java 正则的 `\p{L}`（任何语言的字母，含中文、日文、韩文、西里尔字母等）和 `\p{N}`（任何语言的数字）是构建国际化白名单的基础。它们等价于"Unicode 标准定义的字母/数字"，覆盖范围远大于 `[A-Za-z0-9]`，但依然排除了空格、标点、控制符、运算符等真正危险的字符。

```java
// 反例：ASCII 白名单，会把中文当作非法字符
private static final Pattern VALID_PATTERN =
    Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,62}$");

// 正例：Unicode 白名单，保留中文等合法标识符
private static final Pattern VALID_PATTERN =
    Pattern.compile("^[\\p{L}_][\\p{L}\\p{N}_]{0,62}$");
```

替换逻辑也要同步使用 Unicode 字符类——只替换真正非法的字符（空格、标点、控制符），保留 Unicode 字母数字：

```java
// 反例：把所有非 ASCII 字符替换为下划线
String sanitized = trimmed.replaceAll("[^A-Za-z0-9_]", "_");

// 正例：仅替换空格、标点、控制符等，保留 Unicode 字母数字
String sanitized = trimmed.replaceAll("[^\\p{L}\\p{N}_]", "_");
```

### 子模式 2：三层净化策略

单一规则难以覆盖所有情况。采用"白名单优先 → 保留字兜底 → 非法字符替换兜底"的三层瀑布式策略，每一层只处理自己擅长的场景：

```java
public static String sanitize(String raw, Long columnId)
{
    if (raw == null || raw.isEmpty())
    {
        return "`col" + (columnId != null ? columnId : "") + "`";
    }
    String trimmed = raw.trim();

    // 第一层：白名单命中 → 直接反引号包裹
    // 覆盖：my_column、col1、经验树、数据表23333、表_名
    if (VALID_PATTERN.matcher(trimmed).matches())
    {
        return "`" + trimmed + "`";
    }

    // 第二层：保留字 → 反引号包裹原值
    // 覆盖：order、select、table、group（即便在白名单内也强制反引号）
    if (RESERVED_WORDS.contains(trimmed.toLowerCase()))
    {
        return "`" + trimmed + "`";
    }

    // 第三层：含非法字符 → 替换为 _、截断、反引号包裹
    // 覆盖：col name → col_name；表@名# → 表_名_；123abc → _123abc
    String sanitized = trimmed.replaceAll("[^\\p{L}\\p{N}_]", "_");

    // 确保不以数字开头（SQL 标识符不能以数字开头）
    if (!sanitized.isEmpty() && Character.isDigit(sanitized.charAt(0)))
    {
        sanitized = "_" + sanitized;
    }

    // 截断到 64 字符（MySQL 标识符长度上限）
    if (sanitized.length() > MAX_LENGTH)
    {
        sanitized = sanitized.substring(0, MAX_LENGTH);
    }

    // 确保非空（极端情况：raw 全是非法字符且被全部替换后又截断为空）
    if (sanitized.isEmpty())
    {
        sanitized = "col" + (columnId != null ? columnId : "");
    }
    return "`" + sanitized + "`";
}
```

每一层的职责清晰：第一层是"快路径"，第二层是"语义保护"，第三层是"尽力而为的修复"。三层结束后统一用反引号包裹，确保最终产物一定是合法的带引号标识符。

### 子模式 3：反引号包裹作为最后一道防线

MySQL 8.0 对反引号包裹的 Unicode 标识符有原生支持，这意味着只要最终输出形如 `` `标识符` ``，无论是保留字、Unicode 字符还是含特殊字符的标识符，都能被正确解析。反引号兜底让前几层的"容错"变得安全——即使第三层把某些字符替换成了下划线，最终产物依然可执行，不会引发语法错误或注入。

```java
// 所有出口都形如：return "`" + value + "`";
// 即便 value 是 "order"（保留字）或 "经验树"（Unicode），都能安全执行
```

注意：反引号是 MySQL 方言。若目标数据库是 PostgreSQL/标准 SQL，应改用双引号 `"identifier"`；SQL Server 用方括号 `[identifier]`。把引号风格抽成可配置项即可适配多数据库。

### 子模式 4：长度与边界约束

标识符有硬性约束（MySQL 为 64 字符），净化时必须显式截断。同时要处理四个边界：

- **null/空串**：回退到 `col<columnId>` 等确定性占位符
- **数字开头**：前缀下划线（`123abc` → `_123abc`）
- **超长**：截断到 64 字符
- **全非法字符**：替换后可能为空，再次回退到占位符

边界处理要在"替换"之后、"包裹"之前完成，顺序不能乱。

## Why This Matters

根因在于把"非 ASCII"等同于"非法"。这是字符编码知识缺失导致的常见误判：开发者熟悉 `[A-Za-z0-9_]` 这套 ASCII 白名单，却没意识到 Unicode 字母数字在 SQL 标识符语境下是完全合法的字符。MySQL 8.0 的 lexer 在遇到反引号包裹的 Unicode 标识符时，会按 UTF-8 字节流原样识别，不需要任何额外转义。

不遵循本模式的影响：

- **数据丢失语义**：`经验树` → `___`，多个中文列名塌缩成相同的下划线串，列定义互相冲突，导出的 SQL 无法执行。
- **假安全感**：以为"替换非 ASCII"提高了安全性，实际上 `\p{L}\p{N}` 白名单已经排除了所有真正危险的字符（引号、分号、注释符、控制符等），安全性并未降低。
- **国际化失败**：产品无法支持中文、日文、韩文等非拉丁字符的表名/列名，与产品定位冲突。

遵循本模式的影响：

- 生成的 SQL 在 MySQL 8.0 下可直接执行，中文标识符原样保留。
- 安全性来自"白名单 + 反引号双层防御"，而非"ASCII 限制"。
- 代码可复用于任何需要净化用户输入标识符的场景（DDL 生成、ORM 动态字段、报表系统等）。

## When to Apply

- 用户提供表名/列名，系统需要生成可执行的 SQL（DDL 或 DML）
- 目标用户群使用非拉丁字符（中文、日文、韩文、西里尔字母等）命名
- 需要把动态结构（如多维表格、自定义字段、低代码平台）导出为 SQL 脚本
- 需要避免 SQL 注入，同时又不能破坏合法的国际化标识符
- 目标数据库支持反引号/双引号包裹的 Unicode 标识符（MySQL 8.0+、PostgreSQL、SQL Server 等均支持）

不需要应用本模式的场景：标识符完全由系统内部生成且保证为 ASCII（如 `col_<uuid>`），此时简单的字符串拼接即可。

## Examples

### 场景 1：纯中文表名

输入：`经验树`

修复前（ASCII 白名单替换）：
```sql
CREATE TABLE `___` (...);  -- 不可用，且与其他中文表名冲突
```

修复后（Unicode 白名单保留）：
```sql
CREATE TABLE `经验树` (...);  -- 可直接执行
```

### 场景 2：中文 + 数字 + 特殊符号混合

输入：`数据表23333`、`表 名`、`表@名#`

修复前：
```sql
`___23333`   -- 数据表23333
`___`        -- 表 名（空格替换）
`___`        -- 表@名#（符号替换，与上一行冲突）
```

修复后：
```sql
`数据表23333`  -- 白名单直接通过
`表_名`         -- 空格替换为下划线，中文保留
`表_名_`        -- 符号替换为下划线，中文保留
```

### 场景 3：完整 DDL 导出对比

修复前（中文列名塌缩成下划线，列定义冲突）：
```sql
CREATE TABLE `___23333` (
  `record_id` BIGINT,
  `____` TEXT,           -- "多行文本"
  `__` VARCHAR(255),     -- "单选"
  `__` DATETIME,         -- "日期"（与上行冲突）
  `__` VARCHAR(255),     -- "多选"（冲突）
  `__` VARCHAR(2000)     -- "附件"（冲突）
);
```

修复后（中文原样保留，列定义独立）：
```sql
CREATE TABLE `数据表23333` (
  `record_id` BIGINT,
  `多行文本` TEXT,
  `单选` VARCHAR(255),
  `日期` DATETIME,
  `多选` VARCHAR(255),
  `附件` VARCHAR(2000)
);
INSERT INTO `数据表23333` (`record_id`, `多行文本`, `单选`, `日期`, `多选`, `附件`)
VALUES (641, '3', NULL, NULL, NULL, NULL);
```

## Prevention / 防御性测试

测试矩阵应覆盖四类输入：合法标识符、保留字、含非法字符、Unicode 场景。Unicode 场景是本次修复的关键，必须单独覆盖。

```java
// 1. 合法 ASCII 标识符（快路径）
assertEquals("`my_column`", SqlIdentifierSanitizer.sanitize("my_column", 1L));
assertEquals("`col1`", SqlIdentifierSanitizer.sanitize("col1", 2L));

// 2. 保留字（即便合法也强制反引号）
assertEquals("`order`", SqlIdentifierSanitizer.sanitize("order", 1L));
assertEquals("`select`", SqlIdentifierSanitizer.sanitize("select", 1L));
assertEquals("`table`", SqlIdentifierSanitizer.sanitize("table", 1L));
assertEquals("`group`", SqlIdentifierSanitizer.sanitize("group", 1L));

// 3. 含非法字符（替换为下划线）
assertEquals("`col_name`", SqlIdentifierSanitizer.sanitize("col name", 1L));
assertEquals("`col_name_`", SqlIdentifierSanitizer.sanitize("col@name!", 1L));

// 4. Unicode 保留（核心修复点）
assertEquals("`经验树`", SqlIdentifierSanitizer.sanitize("经验树", 1L));
assertEquals("`创建时间`", SqlIdentifierSanitizer.sanitize("创建时间", 1L));
assertEquals("`数据表23333`", SqlIdentifierSanitizer.sanitize("数据表23333", 1L));
assertEquals("`表_名`", SqlIdentifierSanitizer.sanitize("表_名", 1L));

// 5. Unicode + 非法字符混合（中文保留，符号替换）
assertEquals("`表_名`", SqlIdentifierSanitizer.sanitize("表 名", 1L));
assertEquals("`表_名_`", SqlIdentifierSanitizer.sanitize("表@名#", 1L));

// 6. 边界：数字开头、超长、null、空串
assertEquals("`_123abc`", SqlIdentifierSanitizer.sanitize("123abc", 1L));

String longName = new String(new char[100]).replace('\0', 'a');
String result = SqlIdentifierSanitizer.sanitize(longName, 1L);
assertEquals(64 + 2, result.length());  // 64 字符 + 2 反引号
assertTrue(result.startsWith("`") && result.endsWith("`"));

assertEquals("`col1`", SqlIdentifierSanitizer.sanitize(null, 1L));
assertEquals("`col1`", SqlIdentifierSanitizer.sanitize("", 1L));
```

关键防御点：**第 4、5 组测试是回归测试**。修复前这些断言会全部失败（中文被替换为下划线），修复后全绿。任何未来的"重构"若误把 `\p{L}\p{N}` 退化回 `[A-Za-z0-9]`，这两组测试会立即报警。

额外建议补充的测试（防御 SQL 注入）：

```java
// 含引号、分号、注释符等真正危险字符 → 必须被替换，不能原样进入 SQL
// 注意：每个非法字符独立替换为 _，不塌缩（col'name; → col_name_，2 个符号 → 2 个 _）
assertEquals("`col_name_`", SqlIdentifierSanitizer.sanitize("col'name;", 1L));
// col--name → col__name（两个连字符各替换，中间不塌缩）
assertEquals("`col__name`", SqlIdentifierSanitizer.sanitize("col--name", 1L));
// col/*x*/name → col__x__name（/* 和 */ 共 4 个符号各替换，字母 x 保留）
assertEquals("`col__x__name`", SqlIdentifierSanitizer.sanitize("col/*x*/name", 1L));
```

这些用例验证"Unicode 白名单不降低安全性"——危险字符依然被第三层替换逻辑清除。

## Related

- **同模块的净化器伙伴**：本模式与 [ZipEntryNameSanitizer](../../../ruoyi-system/src/main/java/com/ruoyi/system/service/impl/ZipEntryNameSanitizer.java)（zip slip 防御）、[SqlValueEscaper](../../../ruoyi-system/src/main/java/com/ruoyi/system/service/impl/SqlValueEscaper.java)（SQL 字面值转义）共同构成多维表格导出的安全净化链。标识符层走 `SqlIdentifierSanitizer`，字面值层走 `SqlValueEscaper`，文件名层走 `ZipEntryNameSanitizer`。
- **SQL 注入防御分层**：本模式是"标识符层"的注入防御，需与"字面值层"（参数化查询/预编译语句）配合使用。标识符不能用占位符，必须走白名单净化；字面值必须用占位符，不能拼字符串。
- **MySQL 8.0 Unicode 标识符支持**：MySQL 8.0 的 lexer 原生支持反引号包裹的 Unicode 标识符，无需额外配置。低版本（5.7 及以下）也支持，但建议确认目标版本。
- **多数据库适配**：若需支持 PostgreSQL/SQL Server，把反引号抽成 `quoteChar` 配置项（PostgreSQL 用 `"`，SQL Server 用 `[]`），白名单正则无需改动。
- **MyBatis 动态 SQL**：在 MyBatis 中用 `${}` 拼接表名/列名时，必须先经过本净化器；`${}` 不会做任何转义，是 SQL 注入的高风险点。
- **设计模式交叉引用**：[lookup-column-code-simplification-patterns.md](../design-patterns/lookup-column-code-simplification-patterns.md) 展示了类似的"提取核心决策逻辑集中化"思想，适用于 service 层 helper 设计。
