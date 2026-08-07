package com.ruoyi.system.service.impl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SqlValueEscaper} 单元测试。
 * <p>
 * 覆盖 SQL 注入防护向量、null 处理、数值校验、控制字符转义。
 *
 * @author ruoyi
 */
class SqlValueEscaperTest
{
    @Test
    void escape_null_returns_NULL()
    {
        assertEquals("NULL", SqlValueEscaper.escape(null));
    }

    @Test
    void escape_emptyString_returnsEmptyLiteral()
    {
        assertEquals("''", SqlValueEscaper.escape(""));
    }

    @Test
    void escape_normalString_wrapsInSingleQuotes()
    {
        assertEquals("'hello'", SqlValueEscaper.escape("hello"));
    }

    @Test
    void escape_singleQuote_doubled()
    {
        // 经典 SQL 注入向量：'; DROP TABLE T; --
        String result = SqlValueEscaper.escape("'; DROP TABLE T; --");
        // 单引号被转义为 ''，整个值被包裹在单引号内，成为字面量
        assertEquals("'''; DROP TABLE T; --'", result);
    }

    @Test
    void escape_doubleQuote_escaped()
    {
        String result = SqlValueEscaper.escape("say \"hi\"");
        assertEquals("'say \\\"hi\\\"'", result);
    }

    @Test
    void escape_backslash_doubled()
    {
        String result = SqlValueEscaper.escape("C:\\path");
        assertEquals("'C:\\\\path'", result);
    }

    @Test
    void escape_newline_escaped()
    {
        String result = SqlValueEscaper.escape("line1\nline2");
        assertEquals("'line1\\nline2'", result);
    }

    @Test
    void escape_carriageReturn_escaped()
    {
        String result = SqlValueEscaper.escape("line1\rline2");
        assertEquals("'line1\\rline2'", result);
    }

    @Test
    void escape_nulChar_escaped()
    {
        String result = SqlValueEscaper.escape("a\0b");
        assertEquals("'a\\0b'", result);
    }

    @Test
    void escape_ctrlZ_escaped()
    {
        String result = SqlValueEscaper.escape("a\u001ab");
        assertEquals("'a\\Zb'", result);
    }

    @Test
    void tryParseNumber_validNumber_returnsTrue()
    {
        assertTrue(SqlValueEscaper.tryParseNumber("42"));
        assertTrue(SqlValueEscaper.tryParseNumber("3.14"));
        assertTrue(SqlValueEscaper.tryParseNumber("-100"));
        assertTrue(SqlValueEscaper.tryParseNumber("0"));
    }

    @Test
    void tryParseNumber_injectionVector_returnsFalse()
    {
        // 注入向量：1; DROP TABLE--
        assertFalse(SqlValueEscaper.tryParseNumber("1; DROP TABLE--"));
        assertFalse(SqlValueEscaper.tryParseNumber("1' OR '1'='1"));
        assertFalse(SqlValueEscaper.tryParseNumber("abc"));
        assertFalse(SqlValueEscaper.tryParseNumber(null));
        assertFalse(SqlValueEscaper.tryParseNumber(""));
    }
}
