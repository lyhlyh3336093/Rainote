package com.ruoyi.system.service.impl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.dto.ParsedInsert;

/**
 * SQL INSERT 解析器（自写，KTD1）。
 * <p>
 * 把 .sql 文本解析为 {@link ParsedInsert} 列表。仅解析 {@code INSERT INTO}
 * 语句的列名与值；{@code CREATE TABLE} 与其他语句忽略（R7）。
 * <p>
 * 反向契约（与导出端 {@link NoteDwtableSqlRenderer} + {@link SqlValueEscaper}
 * + {@link SqlIdentifierSanitizer} round-trip）：
 * <ul>
 *   <li>表名/列名：反引号包裹（{@code `col_name`}）→ 剥离反引号 + trim</li>
 *   <li>字符串值：单引号包裹，{@code ''} 转义单引号，{@code \} 开头的 MySQL 转义序列</li>
 *   <li>{@code NULL} 关键字 → Java null</li>
 *   <li>数值/日期原样保留为字符串</li>
 *   <li>支持单 INSERT 多元组：{@code VALUES (...),(...),(...)} 展开为多条 ParsedInsert</li>
 * </ul>
 * 解析失败抛 {@link ServiceException}（含可读 detail）触发事务回滚（R19）。
 *
 * @author ruoyi
 */
public final class SqlInsertParser
{
    private SqlInsertParser()
    {
    }

    /**
     * 解析 .sql 字节为 ParsedInsert 列表。
     *
     * @param sqlBytes UTF-8 编码的 SQL 字节
     * @return 解析产物列表，保持 SQL 中语句顺序
     * @throws ServiceException 解析失败
     */
    public static List<ParsedInsert> parse(byte[] sqlBytes)
    {
        if (sqlBytes == null)
        {
            throw new ServiceException("SQL 解析失败：输入为 null");
        }
        return parse(new String(sqlBytes, StandardCharsets.UTF_8));
    }

    /**
     * 解析 .sql 文本为 ParsedInsert 列表。
     * 用状态机扫描，跳过 CREATE TABLE / 注释 / 字符串字面量 / 反引号标识符，
     * 仅解析 INSERT INTO 语句。
     *
     * @param sql SQL 文本
     * @return 解析产物列表
     * @throws ServiceException 解析失败
     */
    public static List<ParsedInsert> parse(String sql)
    {
        if (sql == null)
        {
            throw new ServiceException("SQL 解析失败：输入为 null");
        }
        List<ParsedInsert> result = new ArrayList<>();
        int len = sql.length();
        int i = 0;
        while (i < len)
        {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c))
            {
                i++;
                continue;
            }
            // 跳过反引号标识符（语句级不应出现，但保险）
            if (c == '`')
            {
                i = skipQuotedIdentifier(sql, i);
                continue;
            }
            // 跳过字符串字面量（语句级不应出现，但保险）
            if (c == '\'')
            {
                i = skipStringLiteral(sql, i);
                continue;
            }
            // 跳过单行注释 --
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-')
            {
                while (i < len && sql.charAt(i) != '\n')
                {
                    i++;
                }
                continue;
            }
            // 跳过块注释 /* */
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*')
            {
                i += 2;
                while (i + 1 < len && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/'))
                {
                    i++;
                }
                i += 2;
                continue;
            }
            // 检查 INSERT 关键字
            if (isKeywordAt(sql, i, "INSERT"))
            {
                i = parseInsertStatement(sql, i, result);
                continue;
            }
            // 其他字符（CREATE/TABLE/;/等）跳过
            i++;
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 解析一条 INSERT 语句。
     *
     * @return 语句结束位置
     */
    private static int parseInsertStatement(String sql, int from, List<ParsedInsert> result)
    {
        int len = sql.length();
        int i = from + 6; // consume INSERT
        i = skipWhitespace(sql, i);
        if (!isKeywordAt(sql, i, "INTO"))
        {
            throw new ServiceException("SQL 解析失败：INSERT 后缺少 INTO（位置 " + i + "）");
        }
        i += 4;
        i = skipWhitespace(sql, i);
        // 跳过表名（反引号或裸标识符），忽略表名
        i = skipIdentifier(sql, i);
        i = skipWhitespace(sql, i);
        if (i >= len || sql.charAt(i) != '(')
        {
            throw new ServiceException("SQL 解析失败：期望 '(' 开始列名列表（位置 " + i + "）");
        }
        i++; // consume '('
        List<String> columnNames = new ArrayList<>();
        i = parseColumnList(sql, i, columnNames);
        // 列名列表结束后 i 指向 ')' 之后
        i = skipWhitespace(sql, i);
        if (!isKeywordAt(sql, i, "VALUES"))
        {
            throw new ServiceException("SQL 解析失败：列名列表后缺少 VALUES（位置 " + i + "）");
        }
        i += 6;
        i = skipWhitespace(sql, i);
        // 解析元组列表
        int tupleCount = 0;
        while (i < len && sql.charAt(i) == '(')
        {
            i++; // consume '('
            List<String> values = new ArrayList<>();
            i = parseValueList(sql, i, values);
            result.add(buildParsedInsert(columnNames, values));
            tupleCount++;
            i = skipWhitespace(sql, i);
            if (i < len && sql.charAt(i) == ',')
            {
                i++; // consume ','
                i = skipWhitespace(sql, i);
                continue;
            }
            break;
        }
        if (tupleCount == 0)
        {
            throw new ServiceException("SQL 解析失败：VALUES 后无元组（位置 " + i + "）");
        }
        return i;
    }

    /**
     * 构建一条 ParsedInsert。
     * 列名与值按位置对应；record_id 锚点仅识别首列（R6 位置契约），
     * 且不写入 columnValues（R17）；非首列的同名列按普通数据列处理。
     * 数据列出现重复列名时抛 ServiceException（把静默 last-wins 丢列变为可读失败，R19）。
     */
    private static ParsedInsert buildParsedInsert(List<String> columnNames, List<String> values)
    {
        if (columnNames.size() != values.size())
        {
            throw new ServiceException("SQL 解析失败：列数 " + columnNames.size()
                    + " 与值数 " + values.size() + " 不匹配");
        }
        // 重复列名检测（首列 record_id 锚点豁免——导出端固定首列 record_id + 用户同名列共存场景）
        Set<String> seen = new HashSet<>();
        for (int idx = 0; idx < columnNames.size(); idx++)
        {
            String col = columnNames.get(idx);
            if (idx == 0 && "record_id".equals(col))
            {
                continue;
            }
            if (!seen.add(col))
            {
                throw new ServiceException("SQL 解析失败：检测到重复列名 '" + col + "'，无法确定列映射");
            }
        }
        Long recordId = null;
        ParsedInsert parsed = new ParsedInsert();
        for (int idx = 0; idx < columnNames.size(); idx++)
        {
            String col = columnNames.get(idx);
            String val = values.get(idx);
            if (idx == 0 && "record_id".equals(col))
            {
                if (val != null)
                {
                    try
                    {
                        recordId = Long.parseLong(val.trim());
                    }
                    catch (NumberFormatException e)
                    {
                        throw new ServiceException("SQL 解析失败：record_id 非数值 '" + val + "'");
                    }
                }
                continue;
            }
            parsed.putColumn(col, val);
        }
        parsed.setRecordId(recordId);
        return parsed;
    }

    /**
     * 判断 sql 在 pos 位置是否匹配指定关键字（大小写不敏感，但 SQL 关键字一般大写）。
     * 关键字后必须是非标识符字符（边界检查）。
     */
    private static boolean isKeywordAt(String sql, int pos, String keyword)
    {
        int kwLen = keyword.length();
        if (pos + kwLen > sql.length())
        {
            return false;
        }
        for (int k = 0; k < kwLen; k++)
        {
            if (Character.toUpperCase(sql.charAt(pos + k)) != keyword.charAt(k))
            {
                return false;
            }
        }
        int after = pos + kwLen;
        if (after < sql.length() && isIdentChar(sql.charAt(after)))
        {
            return false;
        }
        return true;
    }

    /**
     * 跳过空白字符。
     *
     * @return 第一个非空白字符位置
     */
    private static int skipWhitespace(String sql, int from)
    {
        int len = sql.length();
        int i = from;
        while (i < len && Character.isWhitespace(sql.charAt(i)))
        {
            i++;
        }
        return i;
    }

    /**
     * 跳过标识符（反引号包裹或裸）。
     *
     * @return 标识符后位置
     */
    private static int skipIdentifier(String sql, int from)
    {
        int len = sql.length();
        if (from >= len)
        {
            return from;
        }
        if (sql.charAt(from) == '`')
        {
            return skipQuotedIdentifier(sql, from);
        }
        int i = from;
        while (i < len && isIdentChar(sql.charAt(i)))
        {
            i++;
        }
        return i;
    }

    /**
     * 跳过反引号包裹的标识符（处理 {@code ``} 转义）。
     *
     * @return 反引号后位置
     */
    private static int skipQuotedIdentifier(String sql, int from)
    {
        int len = sql.length();
        int i = from + 1;
        while (i < len)
        {
            char c = sql.charAt(i);
            if (c == '`')
            {
                if (i + 1 < len && sql.charAt(i + 1) == '`')
                {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new ServiceException("SQL 解析失败：反引号标识符未闭合（位置 " + from + "）");
    }

    /**
     * 跳过字符串字面量（{@code '...'} 开头），处理 {@code ''} 与 {@code \} 转义。
     *
     * @return 字符串字面量结束位置（最后一个 {@code '} 之后）
     */
    private static int skipStringLiteral(String sql, int from)
    {
        int len = sql.length();
        int i = from + 1;
        while (i < len)
        {
            char c = sql.charAt(i);
            if (c == '\'')
            {
                if (i + 1 < len && sql.charAt(i + 1) == '\'')
                {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            if (c == '\\')
            {
                i += 2;
                continue;
            }
            i++;
        }
        throw new ServiceException("SQL 解析失败：字符串字面量未闭合（位置 " + from + "）");
    }

    /**
     * 标识符字符：字母/数字/下划线（{@code \p{L}\p{N}_}）。
     */
    private static boolean isIdentChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * 解析列名列表（{@code (} 之后到 {@code )} 之前）。
     *
     * @param columnNames 输出列表
     * @return {@code )} 之后位置
     */
    private static int parseColumnList(String sql, int from, List<String> columnNames)
    {
        int len = sql.length();
        int i = from;
        while (i < len)
        {
            i = skipWhitespace(sql, i);
            if (i >= len)
            {
                throw new ServiceException("SQL 解析失败：列名列表未闭合");
            }
            if (sql.charAt(i) == ')')
            {
                return i + 1;
            }
            // 解析一个列名（反引号或裸）
            String colName;
            if (sql.charAt(i) == '`')
            {
                int start = i + 1;
                int end = findClosingBacktick(sql, start);
                colName = sql.substring(start, end).trim();
                i = end + 1;
            }
            else
            {
                int start = i;
                while (i < len && isIdentChar(sql.charAt(i)))
                {
                    i++;
                }
                colName = sql.substring(start, i).trim();
            }
            if (colName.isEmpty())
            {
                throw new ServiceException("SQL 解析失败：列名为空（位置 " + i + "）");
            }
            columnNames.add(colName);
            i = skipWhitespace(sql, i);
            if (i < len && sql.charAt(i) == ',')
            {
                i++;
                continue;
            }
            if (i < len && sql.charAt(i) == ')')
            {
                return i + 1;
            }
            throw new ServiceException("SQL 解析失败：列名后期望 ',' 或 ')'（位置 " + i + "）");
        }
        throw new ServiceException("SQL 解析失败：列名列表未闭合");
    }

    /**
     * 找到反引号标识符的闭合反引号（处理 {@code ``} 转义）。
     *
     * @param start 第一个内部字符位置
     * @return 闭合反引号位置
     */
    private static int findClosingBacktick(String sql, int start)
    {
        int len = sql.length();
        int i = start;
        while (i < len)
        {
            if (sql.charAt(i) == '`')
            {
                if (i + 1 < len && sql.charAt(i + 1) == '`')
                {
                    i += 2;
                    continue;
                }
                return i;
            }
            i++;
        }
        throw new ServiceException("SQL 解析失败：反引号标识符未闭合（位置 " + start + "）");
    }

    /**
     * 解析值列表（{@code (} 之后到 {@code )} 之前）。
     *
     * @param values 输出列表
     * @return {@code )} 之后位置
     */
    private static int parseValueList(String sql, int from, List<String> values)
    {
        int len = sql.length();
        int i = from;
        while (i < len)
        {
            i = skipWhitespace(sql, i);
            if (i >= len)
            {
                throw new ServiceException("SQL 解析失败：值列表未闭合");
            }
            if (sql.charAt(i) == ')')
            {
                return i + 1;
            }
            ValueParseResult v = parseValue(sql, i);
            values.add(v.value);
            i = v.endPos;
            i = skipWhitespace(sql, i);
            if (i < len && sql.charAt(i) == ',')
            {
                i++;
                continue;
            }
            if (i < len && sql.charAt(i) == ')')
            {
                return i + 1;
            }
            throw new ServiceException("SQL 解析失败：值后期望 ',' 或 ')'（位置 " + i + "）");
        }
        throw new ServiceException("SQL 解析失败：值列表未闭合");
    }

    /** 值解析结果 */
    private static class ValueParseResult
    {
        final String value;
        final int endPos;

        ValueParseResult(String value, int endPos)
        {
            this.value = value;
            this.endPos = endPos;
        }
    }

    /**
     * 解析单个值：
     * <ul>
     *   <li>{@code '...'} 字符串字面量（识别 {@code ''} 与 {@code \} 转义）</li>
     *   <li>{@code NULL} 关键字 → Java null</li>
     *   <li>其他（数值/日期）→ 原样字符串到下一个 ',' 或 ')'</li>
     * </ul>
     */
    private static ValueParseResult parseValue(String sql, int from)
    {
        int len = sql.length();
        if (from >= len)
        {
            throw new ServiceException("SQL 解析失败：值缺失");
        }
        char c = sql.charAt(from);
        if (c == '\'')
        {
            return parseStringLiteral(sql, from);
        }
        // NULL 关键字（大小写不敏感）
        if (isKeywordAt(sql, from, "NULL"))
        {
            return new ValueParseResult(null, from + 4);
        }
        // 其他：数值/日期/未引用 token，原样到 ',' 或 ')' 或 EOF
        int i = from;
        StringBuilder sb = new StringBuilder();
        while (i < len)
        {
            char ch = sql.charAt(i);
            if (ch == ',' || ch == ')')
            {
                break;
            }
            sb.append(ch);
            i++;
        }
        String token = sb.toString().trim();
        if (token.isEmpty())
        {
            throw new ServiceException("SQL 解析失败：值为空（位置 " + from + "）");
        }
        return new ValueParseResult(token, i);
    }

    /**
     * 解析字符串字面量（{@code '...'} 开头）。
     * 反转义规则（对应 {@link SqlValueEscaper#escape(String)}）：
     * <ul>
     *   <li>{@code ''} → {@code '}（SQL 标准）</li>
     *   <li>{@code \0} → NUL char(0)</li>
     *   <li>{@code \n} → LF char(10)</li>
     *   <li>{@code \r} → CR char(13)</li>
     *   <li>{@code \Z} → Ctrl-Z char(26)</li>
     *   <li>{@code \"} → {@code "}</li>
     *   <li>{@code \'} → {@code '}（MySQL 扩展，与 {@code ''} 等价）</li>
     *   <li>{@code \\} → {@code \}</li>
     *   <li>其他 {@code \x} → {@code x}（保守保留）</li>
     * </ul>
     */
    private static ValueParseResult parseStringLiteral(String sql, int from)
    {
        int len = sql.length();
        int i = from + 1;
        StringBuilder sb = new StringBuilder();
        while (i < len)
        {
            char c = sql.charAt(i);
            if (c == '\'')
            {
                if (i + 1 < len && sql.charAt(i + 1) == '\'')
                {
                    sb.append('\'');
                    i += 2;
                    continue;
                }
                return new ValueParseResult(sb.toString(), i + 1);
            }
            if (c == '\\')
            {
                if (i + 1 >= len)
                {
                    throw new ServiceException("SQL 解析失败：字符串末尾反斜杠未闭合（位置 " + i + "）");
                }
                char next = sql.charAt(i + 1);
                switch (next)
                {
                    case '0':
                        sb.append('\0');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 'Z':
                        sb.append((char) 0x1a);
                        break;
                    case '"':
                        sb.append('"');
                        break;
                    case '\'':
                        sb.append('\'');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    default:
                        sb.append(next);
                        break;
                }
                i += 2;
                continue;
            }
            sb.append(c);
            i++;
        }
        throw new ServiceException("SQL 解析失败：字符串字面量未闭合（位置 " + from + "）");
    }
}
