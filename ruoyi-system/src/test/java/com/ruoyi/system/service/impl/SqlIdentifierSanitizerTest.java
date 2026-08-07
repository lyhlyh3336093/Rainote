package com.ruoyi.system.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SqlIdentifierSanitizer} 单元测试。
 * <p>
 * 测试矩阵围绕本次修复（中文标识符保留）设计，分七组（用注释分隔，便于查阅）：
 * <ol>
 *   <li>合法 ASCII 标识符（快路径）</li>
 *   <li>MySQL 保留字</li>
 *   <li>非法字符替换（含 SQL 注入防御回归）</li>
 *   <li>Unicode 中文标识符保留（核心修复回归）</li>
 *   <li>边界：null/空串/纯空白/数字开头/超长/纯符号/控制符</li>
 *   <li>columnId 兜底（含 null）</li>
 *   <li>{@link SqlIdentifierSanitizer#sanitizeTable(String)} 表名入口</li>
 * </ol>
 * <p>
 * 第 4、5 组是核心回归测试。任何把 {@code \p{L}\p{N}} 退化回 {@code [A-Za-z0-9]}
 * 的"重构"都会让这两组立刻报警。
 * <p>
 * 注：未使用 JUnit 5 {@code @Nested} 是因为 surefire 2.22.2 + jupiter-engine 5.7.2
 * 在本仓库配置下不识别嵌套类测试。所有方法平铺在外层，用注释分组即可。
 *
 * @author ruoyi
 */
class SqlIdentifierSanitizerTest
{
    // ====================== 1. 合法 ASCII 标识符（白名单快路径） ======================

    @Test
    @DisplayName("合法 ASCII 标识符：白名单命中，反引号包裹原值")
    void sanitize_validAscii_backtickWrapped()
    {
        assertEquals("`my_column`", SqlIdentifierSanitizer.sanitize("my_column", 1L));
        assertEquals("`col1`", SqlIdentifierSanitizer.sanitize("col1", 2L));
        assertEquals("`x`", SqlIdentifierSanitizer.sanitize("x", 3L));
        // 下划线开头合法
        assertEquals("`_private`", SqlIdentifierSanitizer.sanitize("_private", 1L));
    }

    // ====================== 2. MySQL 保留字 ======================

    @Test
    @DisplayName("保留字：反引号包裹原值（让 MySQL 识别为标识符而非关键字）")
    void sanitize_reservedWord_backtickWrapped()
    {
        assertEquals("`order`", SqlIdentifierSanitizer.sanitize("order", 1L));
        assertEquals("`select`", SqlIdentifierSanitizer.sanitize("select", 1L));
        assertEquals("`table`", SqlIdentifierSanitizer.sanitize("table", 1L));
        assertEquals("`group`", SqlIdentifierSanitizer.sanitize("group", 1L));
        assertEquals("`drop`", SqlIdentifierSanitizer.sanitize("drop", 1L));
        assertEquals("`insert`", SqlIdentifierSanitizer.sanitize("insert", 1L));
    }

    @Test
    @DisplayName("保留字大小写不敏感：order/Order/ORDER 均反引号包裹")
    void sanitize_reservedWord_caseInsensitive()
    {
        // 源码用 toLowerCase 比较；纯字母标识符会先被白名单命中，
        // 但行为上仍是反引号包裹，结果一致
        assertEquals("`order`", SqlIdentifierSanitizer.sanitize("order", 1L));
        assertEquals("`Order`", SqlIdentifierSanitizer.sanitize("Order", 1L));
        assertEquals("`ORDER`", SqlIdentifierSanitizer.sanitize("ORDER", 1L));
    }

    // ====================== 3. 非法字符替换（含 SQL 注入防御） ======================

    @Test
    @DisplayName("空格替换为下划线")
    void sanitize_spaces_replacedWithUnderscore()
    {
        assertEquals("`col_name`", SqlIdentifierSanitizer.sanitize("col name", 1L));
        // 连续多个空格 → 多个下划线（不塌缩，保留视觉对应）
        assertEquals("`col___def`", SqlIdentifierSanitizer.sanitize("col   def", 1L));
    }

    @Test
    @DisplayName("特殊符号替换为下划线")
    void sanitize_specialChars_replacedWithUnderscore()
    {
        assertEquals("`col_name_`", SqlIdentifierSanitizer.sanitize("col@name!", 1L));
        assertEquals("`col_name`", SqlIdentifierSanitizer.sanitize("col-name", 1L));
        assertEquals("`col_name`", SqlIdentifierSanitizer.sanitize("col.name", 1L));
    }

    /**
     * SQL 注入防御回归。这一组验证"Unicode 白名单不降低安全性"——
     * 真正危险的字符（引号、分号、注释符、反引号、控制符）依然被第三层替换逻辑清除。
     * <p>
     * 这是修复的关键约束：把 ASCII 白名单扩展为 Unicode 白名单后，
     * 不能让任何 SQL 元字符漏网。
     */
    @Test
    @DisplayName("SQL 注入字符防御：引号/分号/注释符/反引号全部替换为下划线")
    void sanitize_sqlInjectionChars_allReplaced()
    {
        // 单引号 + 分号 → 双下划线
        assertEquals("`col_name_`", SqlIdentifierSanitizer.sanitize("col'name;", 1L));
        // 双连字符（SQL 行注释）→ 双下划线
        assertEquals("`col__name`", SqlIdentifierSanitizer.sanitize("col--name", 1L));
        // 块注释 /* */ → 各符号分别替换，字母保留
        assertEquals("`col__x__name`", SqlIdentifierSanitizer.sanitize("col/*x*/name", 1L));
        // 反引号本身（尝试闭合标识符引号）→ 替换为下划线
        assertEquals("`col_name`", SqlIdentifierSanitizer.sanitize("col`name", 1L));
        // 双引号 → 替换为下划线
        assertEquals("`col_name`", SqlIdentifierSanitizer.sanitize("col\"name", 1L));
    }

    @Test
    @DisplayName("注入语句整体被净化为安全标识符")
    void sanitize_injectionPayload_neutralized()
    {
        // 1;DROP TABLE users → 数字开头前缀 _，; 和空格替换
        assertEquals("`_1_DROP_TABLE_users`", SqlIdentifierSanitizer.sanitize("1;DROP TABLE users", 1L));
        // users;-- → 末尾注入注释符，全部替换
        assertEquals("`users___`", SqlIdentifierSanitizer.sanitize("users;--", 1L));
        // select * from users → 含保留字但整体非保留字，按非法字符处理
        assertEquals("`select___from_users`", SqlIdentifierSanitizer.sanitize("select * from users", 1L));
    }

    // ====================== 4. Unicode 中文标识符保留（核心修复回归） ======================

    /**
     * 这一组是本次修复的核心回归测试。
     * <p>
     * 修复前：ASCII 白名单 {@code [^A-Za-z0-9_]} 把所有中文字符视为非法，
     *         结果 {@code 经验树} → {@code ___}，多个中文列名塌缩成同一下划线串。
     * <p>
     * 修复后：用 {@code \p{L}\p{N}} Unicode 字符类，中文字符原样保留。
     * 任何未来"重构"若误把 Unicode 字符类退化回 ASCII，本组测试会立即失败。
     */
    @Test
    @DisplayName("纯中文标识符：原样保留（修复核心）")
    void sanitize_pureChinese_preserved()
    {
        assertEquals("`经验树`", SqlIdentifierSanitizer.sanitize("经验树", 1L));
        assertEquals("`创建时间`", SqlIdentifierSanitizer.sanitize("创建时间", 1L));
        assertEquals("`附件`", SqlIdentifierSanitizer.sanitize("附件", 1L));
        assertEquals("`多行文本`", SqlIdentifierSanitizer.sanitize("多行文本", 1L));
    }

    @Test
    @DisplayName("中文 + 数字（非开头）：原样保留")
    void sanitize_chineseWithDigit_preserved()
    {
        assertEquals("`数据表23333`", SqlIdentifierSanitizer.sanitize("数据表23333", 1L));
        assertEquals("`第1列`", SqlIdentifierSanitizer.sanitize("第1列", 1L));
    }

    @Test
    @DisplayName("中文 + 下划线：原样保留")
    void sanitize_chineseWithUnderscore_preserved()
    {
        assertEquals("`表_名`", SqlIdentifierSanitizer.sanitize("表_名", 1L));
        assertEquals("`用户_id`", SqlIdentifierSanitizer.sanitize("用户_id", 1L));
    }

    @Test
    @DisplayName("中文 + 非法字符：中文保留，非法字符替换为下划线")
    void sanitize_chineseWithIllegalChars_chinesePreserved()
    {
        // 中文 + 空格
        assertEquals("`表_名`", SqlIdentifierSanitizer.sanitize("表 名", 1L));
        // 中文 + ASCII 符号（@、# 各替换为 _）
        assertEquals("`表_名_`", SqlIdentifierSanitizer.sanitize("表@名#", 1L));
        // 中文 + 全角符号（：是 U+FF1A，不在 \p{L}\p{N}_ 内）
        assertEquals("`表_名`", SqlIdentifierSanitizer.sanitize("表：名", 1L));
        // 中文 + SQL 元字符（' 和 ; 各替换为 _）
        assertEquals("`表_名_`", SqlIdentifierSanitizer.sanitize("表'名;", 1L));
    }

    @Test
    @DisplayName("其他 Unicode 字母：日文/韩文/西里尔字母同样保留")
    void sanitize_otherUnicode_preserved()
    {
        // 日文
        assertEquals("`テーブル`", SqlIdentifierSanitizer.sanitize("テーブル", 1L));
        // 韩文
        assertEquals("`테이블`", SqlIdentifierSanitizer.sanitize("테이블", 1L));
        // 西里尔字母
        assertEquals("`таблица`", SqlIdentifierSanitizer.sanitize("таблица", 1L));
    }

    @Test
    @DisplayName("完整 DDL 场景：多个中文列名各自独立，不再塌缩冲突")
    void sanitize_multipleChineseColumns_noCollapse()
    {
        // 修复前：以下 5 个列名都会被替换为 `___`，列定义互相冲突，DDL 不可执行
        // 修复后：每个列名独立保留，DDL 可直接在 MySQL 8.0 执行
        assertEquals("`多行文本`", SqlIdentifierSanitizer.sanitize("多行文本", 1L));
        assertEquals("`单选`", SqlIdentifierSanitizer.sanitize("单选", 2L));
        assertEquals("`日期`", SqlIdentifierSanitizer.sanitize("日期", 3L));
        assertEquals("`多选`", SqlIdentifierSanitizer.sanitize("多选", 4L));
        assertEquals("`附件`", SqlIdentifierSanitizer.sanitize("附件", 5L));
    }

    // ====================== 5. 边界场景 ======================

    @Test
    @DisplayName("null 输入：回退到 col<columnId>")
    void sanitize_null_returnsColIdFallback()
    {
        assertEquals("`col1`", SqlIdentifierSanitizer.sanitize(null, 1L));
        assertEquals("`col42`", SqlIdentifierSanitizer.sanitize(null, 42L));
    }

    @Test
    @DisplayName("空串输入：回退到 col<columnId>")
    void sanitize_empty_returnsColIdFallback()
    {
        assertEquals("`col1`", SqlIdentifierSanitizer.sanitize("", 1L));
    }

    @Test
    @DisplayName("纯空白输入：trim 后为空，回退到 col<columnId>")
    void sanitize_blank_returnsColIdFallback()
    {
        assertEquals("`col1`", SqlIdentifierSanitizer.sanitize("   ", 1L));
        assertEquals("`col1`", SqlIdentifierSanitizer.sanitize("\t\t", 1L));
    }

    @Test
    @DisplayName("前后空白：trim 后再判定")
    void sanitize_surroundingWhitespace_trimmed()
    {
        // trim 后 "abc" 命中白名单
        assertEquals("`abc`", SqlIdentifierSanitizer.sanitize("  abc  ", 1L));
        // trim 后 "经验树" 命中白名单
        assertEquals("`经验树`", SqlIdentifierSanitizer.sanitize("  经验树  ", 1L));
        // trim 后 "表 名" 仍含中间空格，走替换分支
        assertEquals("`表_名`", SqlIdentifierSanitizer.sanitize("  表 名  ", 1L));
    }

    @Test
    @DisplayName("中间控制符：不被 trim 移除，会被替换为下划线")
    void sanitize_controlCharInMiddle_replaced()
    {
        // \u0000 在 trim 时只移除首尾，中间的会被替换为 _
        assertEquals("`ab_c`", SqlIdentifierSanitizer.sanitize("ab\u0000c", 1L));
        // \t 同理
        assertEquals("`ab_c`", SqlIdentifierSanitizer.sanitize("ab\tc", 1L));
    }

    @Test
    @DisplayName("数字开头：前缀下划线（MySQL 标识符不能以数字开头）")
    void sanitize_startsWithDigit_prefixUnderscore()
    {
        assertEquals("`_123abc`", SqlIdentifierSanitizer.sanitize("123abc", 1L));
        // 单个数字
        assertEquals("`_1`", SqlIdentifierSanitizer.sanitize("1", 1L));
    }

    @Test
    @DisplayName("63 字符：白名单上限，不截断")
    void sanitize_length63_notTruncated()
    {
        // VALID_PATTERN: ^[\p{L}_][\p{L}\p{N}_]{0,62}$  → 总长度上限 1+62=63
        String name63 = repeat('a', 63);
        String result = SqlIdentifierSanitizer.sanitize(name63, 1L);
        assertEquals(63 + 2, result.length());
        assertEquals("`" + name63 + "`", result);
    }

    @Test
    @DisplayName("64 字符：超过白名单上限，走替换分支但不截断")
    void sanitize_length64_replacedButNotTruncated()
    {
        // 64 字符：白名单不命中（>63），走替换分支；replaceAll 无变化；长度 64 = MAX_LENGTH，不截断
        String name64 = repeat('a', 64);
        String result = SqlIdentifierSanitizer.sanitize(name64, 1L);
        assertEquals(64 + 2, result.length());
    }

    @Test
    @DisplayName("65+ 字符：截断到 64 字符")
    void sanitize_length65plus_truncatedTo64()
    {
        String name65 = repeat('a', 65);
        String result65 = SqlIdentifierSanitizer.sanitize(name65, 1L);
        assertEquals(64 + 2, result65.length());
        assertTrue(result65.startsWith("`") && result65.endsWith("`"));

        String name100 = repeat('a', 100);
        String result100 = SqlIdentifierSanitizer.sanitize(name100, 1L);
        assertEquals(64 + 2, result100.length());
    }

    @Test
    @DisplayName("纯符号：全部替换为下划线，非空不再兜底")
    void sanitize_pureSymbols_allReplacedToUnderscores()
    {
        // 全部为非法字符 → 替换为等长下划线
        assertEquals("`___`", SqlIdentifierSanitizer.sanitize("@#$", 1L));
        assertEquals("`_______`", SqlIdentifierSanitizer.sanitize("@#$%^&*", 1L));
        // 单个非法字符
        assertEquals("`_`", SqlIdentifierSanitizer.sanitize("@", 1L));
    }

    @Test
    @DisplayName("单字符：合法字母/数字/符号/空白分别处理")
    void sanitize_singleChar()
    {
        assertEquals("`a`", SqlIdentifierSanitizer.sanitize("a", 1L));
        // 数字开头前缀下划线
        assertEquals("`_1`", SqlIdentifierSanitizer.sanitize("1", 1L));
        // 单个非法字符 → 替换为单个下划线
        assertEquals("`_`", SqlIdentifierSanitizer.sanitize("@", 1L));
        // 单个空格 → trim 后为空 → 走兜底返回 col1
        assertEquals("`col1`", SqlIdentifierSanitizer.sanitize(" ", 1L));
    }

    // ====================== 6. columnId 兜底（columnId=null） ======================

    @Test
    @DisplayName("columnId=null + null：兜底为 `col`")
    void sanitize_nullColumnId_nullInput()
    {
        assertEquals("`col`", SqlIdentifierSanitizer.sanitize(null, null));
    }

    @Test
    @DisplayName("columnId=null + 空串：兜底为 `col`")
    void sanitize_nullColumnId_emptyInput()
    {
        assertEquals("`col`", SqlIdentifierSanitizer.sanitize("", null));
    }

    @Test
    @DisplayName("columnId=null + 纯空白：兜底为 `col`")
    void sanitize_nullColumnId_blankInput()
    {
        assertEquals("`col`", SqlIdentifierSanitizer.sanitize("   ", null));
    }

    @Test
    @DisplayName("columnId=null + 纯符号：正常替换为下划线（不需要 columnId）")
    void sanitize_nullColumnId_pureSymbols()
    {
        assertEquals("`___`", SqlIdentifierSanitizer.sanitize("@#$", null));
    }

    @Test
    @DisplayName("columnId=null + 合法中文：正常反引号包裹")
    void sanitize_nullColumnId_validChinese()
    {
        assertEquals("`经验树`", SqlIdentifierSanitizer.sanitize("经验树", null));
    }

    // ====================== 7. sanitizeTable 表名入口 ======================

    @Test
    @DisplayName("sanitizeTable：合法表名反引号包裹")
    void sanitizeTable_validName()
    {
        assertEquals("`my_table`", SqlIdentifierSanitizer.sanitizeTable("my_table"));
        assertEquals("`users`", SqlIdentifierSanitizer.sanitizeTable("users"));
    }

    @Test
    @DisplayName("sanitizeTable：保留字表名反引号包裹")
    void sanitizeTable_reservedWord()
    {
        assertEquals("`order`", SqlIdentifierSanitizer.sanitizeTable("order"));
        assertEquals("`table`", SqlIdentifierSanitizer.sanitizeTable("table"));
    }

    @Test
    @DisplayName("sanitizeTable：中文表名原样保留")
    void sanitizeTable_chineseName()
    {
        assertEquals("`经验树`", SqlIdentifierSanitizer.sanitizeTable("经验树"));
        assertEquals("`用户数据表`", SqlIdentifierSanitizer.sanitizeTable("用户数据表"));
    }

    @Test
    @DisplayName("sanitizeTable：null/空串/纯空白兜底为 `col`（无 columnId）")
    void sanitizeTable_nullEmptyBlank()
    {
        assertEquals("`col`", SqlIdentifierSanitizer.sanitizeTable(null));
        assertEquals("`col`", SqlIdentifierSanitizer.sanitizeTable(""));
        assertEquals("`col`", SqlIdentifierSanitizer.sanitizeTable("   "));
    }

    @Test
    @DisplayName("sanitizeTable：含非法字符替换为下划线")
    void sanitizeTable_withIllegalChars()
    {
        assertEquals("`user_data`", SqlIdentifierSanitizer.sanitizeTable("user data"));
        assertEquals("`表_名`", SqlIdentifierSanitizer.sanitizeTable("表 名"));
    }

    @Test
    @DisplayName("sanitizeTable：SQL 注入字符全部替换")
    void sanitizeTable_injectionChars()
    {
        assertEquals("`users___`", SqlIdentifierSanitizer.sanitizeTable("users;--"));
    }

    // ====================== 辅助方法 ======================

    /** 生成重复字符的字符串 */
    private static String repeat(char c, int count)
    {
        char[] arr = new char[count];
        java.util.Arrays.fill(arr, c);
        return new String(arr);
    }
}
