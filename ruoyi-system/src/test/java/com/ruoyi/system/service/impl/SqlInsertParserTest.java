package com.ruoyi.system.service.impl;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.dto.ParsedInsert;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SqlInsertParser} 单元测试。
 * <p>
 * 覆盖 AE1 单 INSERT、多 INSERT 顺序、多行 INSERT 展开、NULL、
 * 字符串转义（''/反斜杠/NUL/换行/回车/Ctrl-Z/双引号）、
 * CREATE TABLE 忽略、中文列名、解析失败场景。
 * 反转义契约与导出端 {@link SqlValueEscaper} 对应（round-trip）。
 *
 * @author ruoyi
 */
class SqlInsertParserTest
{
    @Test
    void parse_singleInsert_extractsRecordIdAndColumns()
    {
        String sql = "INSERT INTO `T` (`record_id`,`名称`) VALUES (10,'foo')";
        List<ParsedInsert> result = SqlInsertParser.parse(sql);
        assertEquals(1, result.size());
        ParsedInsert parsed = result.get(0);
        assertEquals(Long.valueOf(10L), parsed.getRecordId());
        assertEquals("foo", parsed.getColumnValues().get("名称"));
        // record_id 不写入 columnValues（R17）
        assertFalse(parsed.getColumnValues().containsKey("record_id"));
    }

    @Test
    void parse_multipleInserts_preservesOrder()
    {
        String sql = "INSERT INTO `T` (`record_id`,`名称`) VALUES (10,'a');\n"
                + "INSERT INTO `T` (`record_id`,`名称`) VALUES (11,'b');\n"
                + "INSERT INTO `T` (`record_id`,`名称`) VALUES (12,'c');";
        List<ParsedInsert> result = SqlInsertParser.parse(sql);
        assertEquals(3, result.size());
        assertEquals(Long.valueOf(10L), result.get(0).getRecordId());
        assertEquals("a", result.get(0).getColumnValues().get("名称"));
        assertEquals(Long.valueOf(11L), result.get(1).getRecordId());
        assertEquals("b", result.get(1).getColumnValues().get("名称"));
        assertEquals(Long.valueOf(12L), result.get(2).getRecordId());
        assertEquals("c", result.get(2).getColumnValues().get("名称"));
    }

    @Test
    void parse_multiRowInsert_expandsTuples()
    {
        String sql = "INSERT INTO `T` (`record_id`,`名称`) VALUES (10,'a'),(11,'b'),(12,'c')";
        List<ParsedInsert> result = SqlInsertParser.parse(sql);
        assertEquals(3, result.size());
        assertEquals(Long.valueOf(10L), result.get(0).getRecordId());
        assertEquals("a", result.get(0).getColumnValues().get("名称"));
        assertEquals(Long.valueOf(11L), result.get(1).getRecordId());
        assertEquals("b", result.get(1).getColumnValues().get("名称"));
        assertEquals(Long.valueOf(12L), result.get(2).getRecordId());
        assertEquals("c", result.get(2).getColumnValues().get("名称"));
    }

    @Test
    void parse_nullValue_returnsJavaNull()
    {
        String sql = "INSERT INTO `T` (`record_id`,`名称`) VALUES (10, NULL)";
        List<ParsedInsert> result = SqlInsertParser.parse(sql);
        assertEquals(1, result.size());
        assertNull(result.get(0).getColumnValues().get("名称"));
    }

    @Test
    void parse_doubledSingleQuote_unescapes()
    {
        String sql = "INSERT INTO `T` (`record_id`,`名称`) VALUES (10, 'It''s a test')";
        List<ParsedInsert> result = SqlInsertParser.parse(sql);
        assertEquals("It's a test", result.get(0).getColumnValues().get("名称"));
    }

    @Test
    void parse_backslashEscape_unescapes()
    {
        // SQL 文本 a\\b → 解析后 a\b
        String sql = "INSERT INTO `T` (`record_id`,`名称`) VALUES (10, 'a\\\\b')";
        List<ParsedInsert> result = SqlInsertParser.parse(sql);
        assertEquals("a\\b", result.get(0).getColumnValues().get("名称"));
    }

    @Test
    void parse_nulEscape_unescapes()
    {
        String sql = "INSERT INTO `T` (`record_id`,`名称`) VALUES (10, 'a\\0b')";
        List<ParsedInsert> result = SqlInsertParser.parse(sql);
        assertEquals("a" + (char) 0 + "b", result.get(0).getColumnValues().get("名称"));
    }

    @Test
    void parse_newlineEscape_unescapes()
    {
        String sql = "INSERT INTO `T` (`record_id`,`名称`) VALUES (10, 'a\\nb')";
        List<ParsedInsert> result = SqlInsertParser.parse(sql);
        assertEquals("a\nb", result.get(0).getColumnValues().get("名称"));
    }

    @Test
    void parse_carriageReturnEscape_unescapes()
    {
        String sql = "INSERT INTO `T` (`record_id`,`名称`) VALUES (10, 'a\\rb')";
        List<ParsedInsert> result = SqlInsertParser.parse(sql);
        assertEquals("a\rb", result.get(0).getColumnValues().get("名称"));
    }

    @Test
    void parse_ctrlZEscape_unescapes()
    {
        String sql = "INSERT INTO `T` (`record_id`,`名称`) VALUES (10, 'a\\Zb')";
        List<ParsedInsert> result = SqlInsertParser.parse(sql);
        assertEquals("a" + (char) 0x1a + "b", result.get(0).getColumnValues().get("名称"));
    }

    @Test
    void parse_doubleQuoteEscape_unescapes()
    {
        String sql = "INSERT INTO `T` (`record_id`,`名称`) VALUES (10, 'a\\\"b')";
        List<ParsedInsert> result = SqlInsertParser.parse(sql);
        assertEquals("a\"b", result.get(0).getColumnValues().get("名称"));
    }

    @Test
    void parse_createTableStatement_ignored()
    {
        String sql = "CREATE TABLE `T` (\n"
                + "  `record_id` BIGINT,\n"
                + "  `名称` TEXT\n"
                + ");\n"
                + "INSERT INTO `T` (`record_id`,`名称`) VALUES (10,'foo');";
        List<ParsedInsert> result = SqlInsertParser.parse(sql);
        assertEquals(1, result.size());
        assertEquals(Long.valueOf(10L), result.get(0).getRecordId());
        assertEquals("foo", result.get(0).getColumnValues().get("名称"));
    }

    @Test
    void parse_chineseColumnName_preserved()
    {
        String sql = "INSERT INTO `T` (`record_id`,`创建时间`) VALUES (10, '2026-08-25')";
        List<ParsedInsert> result = SqlInsertParser.parse(sql);
        assertEquals(1, result.size());
        assertEquals("2026-08-25", result.get(0).getColumnValues().get("创建时间"));
    }

    @Test
    void parse_missingValuesKeyword_throwsServiceException()
    {
        String sql = "INSERT INTO T (a) (1)";
        ServiceException ex = assertThrows(ServiceException.class, () -> SqlInsertParser.parse(sql));
        assertTrue(ex.getMessage().contains("SQL 解析失败"));
    }

    @Test
    void parse_unbalancedParenthesis_throwsServiceException()
    {
        String sql = "INSERT INTO T (a VALUES (1)";
        ServiceException ex = assertThrows(ServiceException.class, () -> SqlInsertParser.parse(sql));
        assertTrue(ex.getMessage().contains("SQL 解析失败"));
    }
}
