package com.ruoyi.system.service.impl;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.ruoyi.common.exception.ServiceException;

/**
 * 还原包 SQL 白名单净化器（KTD2 词法级契约）。
 * <p>
 * 备份包无签名，还原执行前对 schema/data/*.sql 做<strong>词法级 token 状态机</strong>
 * 扫描与语句白名单校验，放行语句以<strong>解析产物重新渲染</strong>后执行（不透传包内原文）：
 * <ul>
 * <li>词法（fail-closed）：单引号串（含 {@code ''} 与 {@code \x} 转义）/反引号标识符
 * （双反引号转义）/双引号串（按串处理）/{@code #} 行注释/{@code --} 行注释（要求后跟
 * 空白）/{@code /*...&#42;/} 块注释（丢弃）/{@code /*!...&#42;/} <strong>可执行注释视为内容
 * 非注释</strong>（内容展开参与白名单校验，堵住朴素解析器把其当注释跳过的逃逸面）；
 * 任何无法分类的 token（未闭合串、{@code --} 后非空白、未识别字符）整体拒绝。</li>
 * <li>语句分割：token 级按 {@code ;} 分割（字符串字面量内的分号不切分，禁止字符级切分）。</li>
 * <li>语句白名单：{@code DROP TABLE [IF EXISTS] `表`}、{@code CREATE TABLE [IF NOT EXISTS]
 * `表`(...)}（纯结构，括号体与表选项禁危险词，拒 CREATE...SELECT）、
 * {@code INSERT INTO `表`(`列`...) VALUES(纯字面量/NULL/数值)}（拒 INSERT...SELECT、
 * 函数调用值、子查询、表达式）；表名必须在 manifest 表清单内。</li>
 * <li>重渲染：字符串经状态机解码为逻辑值后按统一规则重新转义重建，原文与执行物解耦。</li>
 * <li>流式：Reader 逐字符滑动窗口扫描，逐语句回调消费，堆内存占用 O(单语句)，
 * 与 U1 分页批查共同保证 500MB 级包不撑爆堆。</li>
 * </ul>
 *
 * @author ruoyi
 */
public final class RestoreSqlSanitizer
{
    /** 可执行注释内容长度上限：自产包不含 /*!..*&#47;/，超长即恶意构造直接拒绝 */
    private static final int MAX_EXEC_COMMENT_LENGTH = 64 * 1024;

    private RestoreSqlSanitizer()
    {
    }

    /** token 类型：裸词（含数字/hex）、字符串字面量（解码后逻辑值）、反引号标识符、符号 */
    private enum TokenType
    {
        WORD, STRING, IDENTIFIER, SYMBOL
    }

    /** 单个 token：STRING/IDENTIFIER 的 text 为解码后逻辑值；SYMBOL 的 text 为单字符 */
    private static final class Token
    {
        final TokenType type;

        final String text;

        Token(TokenType type, String text)
        {
            this.type = type;
            this.text = text;
        }

        boolean isSymbol(char c)
        {
            return type == TokenType.SYMBOL && text.length() == 1 && text.charAt(0) == c;
        }

        boolean isWord(String word)
        {
            return type == TokenType.WORD && text.equalsIgnoreCase(word);
        }
    }

    /** 白名单语句类型 */
    public enum Type
    {
        DROP_TABLE, CREATE_TABLE, INSERT
    }

    /**
     * 白名单语句解析产物：携带重渲染所需的归一化素材（表名、列名、值 token、结构 token）
     */
    public static final class ParsedStatement
    {
        private final Type type;

        private final String table;

        /** DROP 的 IF EXISTS / CREATE 的 IF NOT EXISTS（重渲染保留该形态） */
        private final boolean ifExists;

        /** CREATE 括号体 token（含嵌套括号） */
        private final List<Token> structureTokens;

        /** CREATE 右括号后的表选项 token（ENGINE=... 等） */
        private final List<Token> tailTokens;

        /** INSERT 列名（反引号标识符逻辑值） */
        private final List<String> columns;

        /** INSERT 值 token（STRING/WORD/前导负号+数字） */
        private final List<Token> values;

        ParsedStatement(Type type, String table, boolean ifExists, List<Token> structureTokens,
                List<Token> tailTokens, List<String> columns, List<Token> values)
        {
            this.type = type;
            this.table = table;
            this.ifExists = ifExists;
            this.structureTokens = structureTokens;
            this.tailTokens = tailTokens;
            this.columns = columns;
            this.values = values;
        }

        public Type getType()
        {
            return type;
        }

        /** 表名（反引号内逻辑值，原大小写保留） */
        public String getTable()
        {
            return table;
        }

        public List<String> getColumns()
        {
            return columns;
        }

        /**
         * 指定列的值逻辑值（字符串解码后内容 / 数字原文 / NULL/TRUE/FALSE 原文）
         *
         * @param columnName 列名（忽略大小写）
         * @return 值文本；列不存在或值为 NULL 字面量时返回 null
         */
        public String getValueOfColumn(String columnName)
        {
            if (columns == null)
            {
                return null;
            }
            for (int i = 0; i < columns.size(); i++)
            {
                if (columns.get(i).equalsIgnoreCase(columnName))
                {
                    Token value = nthValue(i);
                    if (value == null || "NULL".equalsIgnoreCase(value.text))
                    {
                        return null;
                    }
                    return value.text;
                }
            }
            return null;
        }

        /** 按值序取第 index 个值 token（前导负号与数字算一个值） */
        private Token nthValue(int index)
        {
            int valueIndex = 0;
            for (Token token : values)
            {
                if (token.isSymbol('-'))
                {
                    continue;
                }
                if (valueIndex == index)
                {
                    return token;
                }
                valueIndex++;
            }
            return null;
        }

        /**
         * 解析产物重渲染（KTD2：不透传原文，字符串经解码后统一重新转义重建）
         */
        public String render()
        {
            StringBuilder sql = new StringBuilder(128);
            switch (type)
            {
                case DROP_TABLE:
                    sql.append("DROP TABLE ");
                    if (ifExists)
                    {
                        sql.append("IF EXISTS ");
                    }
                    sql.append(quoteIdentifier(table));
                    break;
                case CREATE_TABLE:
                    sql.append("CREATE TABLE ");
                    if (ifExists)
                    {
                        sql.append("IF NOT EXISTS ");
                    }
                    sql.append(quoteIdentifier(table)).append(" (")
                            .append(renderTokens(structureTokens)).append(')');
                    if (!tailTokens.isEmpty())
                    {
                        sql.append(' ').append(renderTokens(tailTokens));
                    }
                    break;
                case INSERT:
                    sql.append("INSERT INTO ").append(quoteIdentifier(table)).append('(');
                    for (int i = 0; i < columns.size(); i++)
                    {
                        if (i > 0)
                        {
                            sql.append(',');
                        }
                        sql.append(quoteIdentifier(columns.get(i)));
                    }
                    sql.append(") VALUES(");
                    // 逐值渲染：前导负号与数字是一个值（中间不插逗号）
                    int valueIndex = 0;
                    for (int i = 0; i < values.size(); i++)
                    {
                        Token token = values.get(i);
                        if (token.isSymbol('-'))
                        {
                            continue;
                        }
                        if (valueIndex > 0)
                        {
                            sql.append(',');
                        }
                        if (i > 0 && values.get(i - 1).isSymbol('-'))
                        {
                            sql.append('-');
                        }
                        sql.append(renderValue(token));
                        valueIndex++;
                    }
                    sql.append(')');
                    break;
            }
            return sql.toString();
        }
    }

    /** INSERT 值允许的数字字面量形态：整数/小数/hex（U1 导出物的确定形态） */
    private static final Pattern NUMERIC_WORD = Pattern
            .compile("0[xX][0-9a-fA-F]+|[0-9]+(\\.[0-9]+)?([eE][0-9]+)?|\\.[0-9]+");

    /**
     * CREATE 语句括号体/表选项内禁止出现的裸词（大写比较）：纯结构定义不会合法出现这些
     * "数据流/控制流"词——命中即视为 CREATE...SELECT 等形态伪装，整体拒绝。字符串字面量
     * （COMMENT '...'）与反引号标识符（列名/索引名）不受影响。
     */
    private static final Set<String> DANGEROUS_WORDS = new HashSet<>(Arrays.asList(
            "SELECT", "FROM", "INTO", "OUTFILE", "DUMPFILE", "UNION", "JOIN", "WHERE",
            "GRANT", "REVOKE", "TRUNCATE", "ALTER", "RENAME", "SLEEP", "BENCHMARK",
            "LOAD_FILE", "KILL", "SHUTDOWN", "PREPARE", "EXECUTE", "CALL", "HANDLER",
            "DO", "LOCK", "UNLOCK", "INSTALL", "UNINSTALL"));

    /**
     * 词法级解析并逐语句回调消费（流式）：单引号串含 '' 与 \x 转义、反引号标识符、双引号串、
     * # 行注释、-- 行注释（后跟空白）、块注释、/*!..*&#47; 可执行注释展开——fail-closed。
     *
     * @param fileDesc      文件描述（异常消息定位用）
     * @param reader        SQL 文件字符流（调用方负责关闭）
     * @param allowedTables 表清单（小写），语句中的表名必须在其内
     * @param consumer      每条白名单语句的消费者（渲染执行/统计）
     */
    public static void parseAndConsume(String fileDesc, Reader reader, Set<String> allowedTables,
            Consumer<ParsedStatement> consumer)
    {
        Tokenizer tokenizer = new Tokenizer(new CharSource(reader), fileDesc);
        List<Token> statement = new ArrayList<>();
        int index = 0;
        while (true)
        {
            Token token;
            try
            {
                token = tokenizer.next();
            }
            catch (RejectException e)
            {
                // 词法层 fail-closed（未闭合串/-- 混淆/未识别字符等）同样整体拒绝
                throw new ServiceException("备份包[" + fileDesc + "]未通过SQL白名单校验（词法）：" + e.getMessage());
            }
            if (token == null)
            {
                if (!statement.isEmpty())
                {
                    consumer.accept(parseOne(fileDesc, ++index, statement, allowedTables));
                }
                return;
            }
            if (token.isSymbol(';'))
            {
                if (!statement.isEmpty())
                {
                    consumer.accept(parseOne(fileDesc, ++index, statement, allowedTables));
                    statement = new ArrayList<>();
                }
            }
            else
            {
                statement.add(token);
            }
        }
    }

    /**
     * 单条语句白名单形态校验与解析（fail-closed：任何不匹配形态整体拒绝）
     */
    private static ParsedStatement parseOne(String fileDesc, int index, List<Token> tokens,
            Set<String> allowedTables)
    {
        try
        {
            if (tokens.get(0).isWord("DROP"))
            {
                return parseDrop(tokens, allowedTables);
            }
            if (tokens.get(0).isWord("CREATE"))
            {
                return parseCreate(tokens, allowedTables);
            }
            if (tokens.get(0).isWord("INSERT"))
            {
                return parseInsert(tokens, allowedTables);
            }
            throw reject("语句形态不在白名单（首词: " + tokens.get(0).text + "）");
        }
        catch (RejectException e)
        {
            // 异常消息不带原文（防包内容外泄），整体 fail-closed 拒绝
            throw new ServiceException("备份包[" + fileDesc + "]第" + index + "条语句未通过SQL白名单校验："
                    + e.getMessage());
        }
    }

    /** DROP TABLE [IF EXISTS] `表` EOF —— 表 ∈ manifest 清单 */
    private static ParsedStatement parseDrop(List<Token> tokens, Set<String> allowedTables)
    {
        int i = 1;
        boolean ifExists = false;
        expectWord(tokens, i++, "TABLE");
        if (i < tokens.size() && tokens.get(i).isWord("IF"))
        {
            ifExists = true;
            i++;
            if (i < tokens.size() && tokens.get(i).isWord("NOT"))
            {
                // DROP TABLE IF NOT EXISTS 非法语法，fail-closed 拒绝
                throw reject("DROP 语句不允许 IF NOT EXISTS");
            }
            expectWord(tokens, i++, "EXISTS");
        }
        String table = expectTable(tokens, i++, allowedTables);
        expectEnd(tokens, i);
        return new ParsedStatement(Type.DROP_TABLE, table, ifExists, null, null, null, null);
    }

    /** CREATE TABLE [IF NOT EXISTS] `表` ( 括号体 ) 表选项EOF —— 拒 CREATE...SELECT 等伪装 */
    private static ParsedStatement parseCreate(List<Token> tokens, Set<String> allowedTables)
    {
        int i = 1;
        boolean ifNotExists = false;
        expectWord(tokens, i++, "TABLE");
        if (i < tokens.size() && tokens.get(i).isWord("IF"))
        {
            ifNotExists = true;
            i++;
            expectWord(tokens, i++, "NOT");
            expectWord(tokens, i++, "EXISTS");
        }
        String table = expectTable(tokens, i++, allowedTables);
        if (i >= tokens.size() || !tokens.get(i).isSymbol('('))
        {
            throw reject("CREATE TABLE 后必须是括号列定义（拒 CREATE TABLE...SELECT）");
        }
        // 括号体收集到匹配的右括号（嵌套深度计数，如 DECIMAL(10,2)/KEY idx(col)）
        int depth = 0;
        List<Token> structure = new ArrayList<>();
        for (; i < tokens.size(); i++)
        {
            Token token = tokens.get(i);
            if (token.isSymbol('('))
            {
                depth++;
                if (depth > 1)
                {
                    structure.add(token);
                }
                continue;
            }
            if (token.isSymbol(')'))
            {
                depth--;
                if (depth == 0)
                {
                    i++;
                    break;
                }
                structure.add(token);
                continue;
            }
            assertCreateTokenAllowed(token);
            structure.add(token);
        }
        if (depth != 0)
        {
            throw reject("CREATE TABLE 括号不闭合");
        }
        // 表选项尾段：仅允许 WORD/STRING/IDENTIFIER/= /,（拒再次出现括号——分区子句等复杂形态）
        List<Token> tail = new ArrayList<>();
        for (; i < tokens.size(); i++)
        {
            Token token = tokens.get(i);
            if (token.isSymbol('(') || token.isSymbol(')') || token.isSymbol(';'))
            {
                throw reject("CREATE TABLE 表选项段不允许括号");
            }
            assertCreateTokenAllowed(token);
            tail.add(token);
        }
        return new ParsedStatement(Type.CREATE_TABLE, table, ifNotExists, structure, tail, null, null);
    }

    /** CREATE 括号体/表选项 token 校验：裸词禁危险词（SELECT/FROM/INTO 等） */
    private static void assertCreateTokenAllowed(Token token)
    {
        if (token.type == TokenType.WORD
                && DANGEROUS_WORDS.contains(token.text.toUpperCase(Locale.ROOT)))
        {
            throw reject("CREATE 语句含禁用词: " + token.text);
        }
    }

    /** INSERT INTO `表`(`列`...) VALUES(纯字面量/NULL/数值) EOF —— 拒 SELECT/函数值/表达式 */
    private static ParsedStatement parseInsert(List<Token> tokens, Set<String> allowedTables)
    {
        int i = 1;
        expectWord(tokens, i++, "INTO");
        String table = expectTable(tokens, i++, allowedTables);
        if (i >= tokens.size() || !tokens.get(i).isSymbol('('))
        {
            throw reject("INSERT INTO 表名后必须是列清单括号");
        }
        i++;
        List<String> columns = new ArrayList<>();
        while (i < tokens.size() && tokens.get(i).type == TokenType.IDENTIFIER)
        {
            columns.add(tokens.get(i).text);
            i++;
            if (i < tokens.size() && tokens.get(i).isSymbol(','))
            {
                i++;
                continue;
            }
            break;
        }
        if (columns.isEmpty())
        {
            throw reject("INSERT 列清单为空或非反引号标识符");
        }
        if (i >= tokens.size() || !tokens.get(i).isSymbol(')'))
        {
            throw reject("INSERT 列清单未闭合");
        }
        i++;
        expectWord(tokens, i++, "VALUES");
        if (i >= tokens.size() || !tokens.get(i).isSymbol('('))
        {
            throw reject("VALUES 后必须是字面量元组括号（拒 INSERT...SELECT/函数值）");
        }
        i++;
        List<Token> values = new ArrayList<>();
        while (i < tokens.size())
        {
            Token token = tokens.get(i);
            if (token.isSymbol(')'))
            {
                i++;
                break;
            }
            if (token.type == TokenType.STRING || token.type == TokenType.WORD)
            {
                if (token.type == TokenType.WORD && !isAllowedValueWord(token.text))
                {
                    throw reject("INSERT 值仅支持纯字面量/NULL/数值（拒函数值/表达式）: " + token.text);
                }
                values.add(token);
                i++;
            }
            else if (token.isSymbol('-') && i + 1 < tokens.size()
                    && tokens.get(i + 1).type == TokenType.WORD
                    && isAllowedValueWord(tokens.get(i + 1).text))
            {
                // 前导负号数字（U1 导出负数形态）
                values.add(token);
                values.add(tokens.get(i + 1));
                i += 2;
            }
            else
            {
                throw reject("INSERT 值形态不合法（拒函数调用/子查询/表达式）");
            }
            if (i < tokens.size() && tokens.get(i).isSymbol(','))
            {
                i++;
            }
            else if (i < tokens.size() && !tokens.get(i).isSymbol(')'))
            {
                throw reject("INSERT 值元组分隔符非法");
            }
        }
        if (values.isEmpty())
        {
            throw reject("INSERT 值元组为空");
        }
        // 值数量与列数量必须一致（前导负号与数字算一个值）
        int valueCount = 0;
        for (Token token : values)
        {
            if (!token.isSymbol('-'))
            {
                valueCount++;
            }
        }
        if (valueCount != columns.size())
        {
            throw reject("INSERT 列数与值数不一致");
        }
        expectEnd(tokens, i);
        return new ParsedStatement(Type.INSERT, table, false, null, null, columns, values);
    }

    /** INSERT 值允许的裸词：NULL/TRUE/FALSE 或数字字面量 */
    private static boolean isAllowedValueWord(String text)
    {
        return "NULL".equalsIgnoreCase(text) || "TRUE".equalsIgnoreCase(text)
                || "FALSE".equalsIgnoreCase(text)
                || NUMERIC_WORD.matcher(text).matches();
    }

    /** 期望指定裸词（缺失即拒绝） */
    private static void expectWord(List<Token> tokens, int index, String word)
    {
        if (index >= tokens.size() || !tokens.get(index).isWord(word))
        {
            throw reject("缺少关键词 " + word);
        }
    }

    /** 期望表名（反引号标识符）且 ∈ manifest 表清单 */
    private static String expectTable(List<Token> tokens, int index, Set<String> allowedTables)
    {
        if (index >= tokens.size() || tokens.get(index).type != TokenType.IDENTIFIER)
        {
            throw reject("表名必须是反引号标识符");
        }
        String table = tokens.get(index).text;
        if (!allowedTables.contains(table.toLowerCase(Locale.ROOT)))
        {
            throw reject("表不在备份包清单内: " + table);
        }
        return table;
    }

    /** 期望语句结束（多余 token 即形态不符） */
    private static void expectEnd(List<Token> tokens, int index)
    {
        if (index != tokens.size())
        {
            throw reject("语句末尾存在多余内容（形态不在白名单）");
        }
    }

    /** fail-closed 拒绝（内部信号，parseOne 统一包装为 ServiceException） */
    private static RejectException reject(String reason)
    {
        return new RejectException(reason);
    }

    /** 内部拒绝信号 */
    private static final class RejectException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        RejectException(String reason)
        {
            super(reason);
        }
    }

    /**
     * 滑动窗口字符源：Reader 流式读取 + 小窗口 lookahead，堆内存 O(窗口) 而非 O(文件)
     */
    private static final class CharSource
    {
        private static final int COMPACT_THRESHOLD = 1 << 16;

        private static final int CHUNK_SIZE = 8192;

        private final Reader reader;

        private final char[] chunk = new char[CHUNK_SIZE];

        private final StringBuilder window = new StringBuilder();

        /** window 内当前消费位置 */
        private int pos;

        private boolean eof;

        CharSource(Reader reader)
        {
            this.reader = reader;
        }

        /** 预读相对当前位置 offset 的字符（offset &lt;= 2 语义下使用）；EOF 返回 -1 */
        int peek(int offset)
        {
            while (pos + offset >= window.length() && !eof)
            {
                fill();
            }
            int index = pos + offset;
            return index < window.length() ? window.charAt(index) : -1;
        }

        /** 消费并返回当前字符；EOF 返回 -1 */
        int read()
        {
            int c = peek(0);
            if (c >= 0)
            {
                pos++;
            }
            return c;
        }

        private void fill()
        {
            try
            {
                int n = reader.read(chunk);
                if (n < 0)
                {
                    eof = true;
                    return;
                }
                window.append(chunk, 0, n);
                // 消费过的前缀超过阈值时收缩窗口
                if (pos > COMPACT_THRESHOLD)
                {
                    window.delete(0, pos);
                    pos = 0;
                }
            }
            catch (IOException e)
            {
                throw new ServiceException("备份包SQL文件读取失败");
            }
        }
    }

    /**
     * 词法 token 扫描状态机（流式单 token 产出，循环消费防注释链深层递归）。
     * <p>
     * fail-closed：串未闭合、{@code --} 后非空白、未识别字符等均抛 RejectException。
     * {@code /*!..*&#47;} 可执行注释的内容<strong>视为 SQL 内容</strong>展开参与白名单校验
     * （MySQL 会执行其内容，朴素解析器按注释跳过即为逃逸面）。
     */
    private static final class Tokenizer
    {
        private final CharSource source;

        /** 可执行注释展开的子 token 待发队列（内容含注释边界即拒绝，深度恒 1） */
        private final List<Token> pending = new ArrayList<>();

        private int pendingIndex = 0;

        Tokenizer(CharSource source, String fileDesc)
        {
            this.source = source;
        }

        /** 下一 token；EOF 返回 null */
        Token next()
        {
            while (true)
            {
                if (pendingIndex < pending.size())
                {
                    return pending.get(pendingIndex++);
                }
                if (pendingIndex >= pending.size() && !pending.isEmpty())
                {
                    pending.clear();
                    pendingIndex = 0;
                }
                int ci = source.read();
                if (ci < 0)
                {
                    return null;
                }
                char c = (char) ci;
                if (isWhitespace(c))
                {
                    continue;
                }
                if (c == '\'')
                {
                    return readQuoted('\'', true);
                }
                if (c == '"')
                {
                    // KTD2：双引号按字符串处理（MySQL 默认非 ANSI_QUOTES）
                    return readQuoted('"', true);
                }
                if (c == '`')
                {
                    return readQuoted('`', false);
                }
                if (c == '#')
                {
                    skipToLineEnd();
                    continue;
                }
                if (c == '-' && source.peek(0) == '-')
                {
                    // -- 行注释：MySQL 要求 -- 后跟空白才构成注释，否则 fail-closed 拒绝
                    int after = source.peek(1);
                    if (after < 0 || isWhitespace((char) after))
                    {
                        skipToLineEnd();
                        continue;
                    }
                    throw new RejectException("-- 后未跟空白，无法归类（疑似注释混淆）");
                }
                if (c == '/' && source.peek(0) == '*')
                {
                    handleComment();
                    continue;
                }
                if (isWordChar(c))
                {
                    return readWord(c);
                }
                if (c == '(' || c == ')' || c == ',' || c == ';' || c == '=' || c == '-')
                {
                    return new Token(TokenType.SYMBOL, String.valueOf(c));
                }
                throw new RejectException("未识别字符: '" + c + "'");
            }
        }

        /** 块注释/可执行注释处理：普通块注释流式丢弃；/*!..*&#47; 内容收集后递归 tokenize 展开 */
        private void handleComment()
        {
            boolean executable = source.peek(1) == '!';
            if (!executable)
            {
                // 普通块注释：流式跳过（尺寸不限，不驻留内存）
                while (true)
                {
                    int ci = source.read();
                    if (ci < 0)
                    {
                        throw new RejectException("块注释未闭合");
                    }
                    if (ci == '*' && source.peek(0) == '/')
                    {
                        source.read();
                        return;
                    }
                }
            }
            // 可执行注释：内容视为 SQL（MySQL 执行其内容），收集后展开参与白名单校验
            source.read();
            source.read();
            StringBuilder inner = new StringBuilder();
            while (true)
            {
                int ci = source.read();
                if (ci < 0)
                {
                    throw new RejectException("块注释未闭合");
                }
                if (ci == '*' && source.peek(0) == '/')
                {
                    source.read();
                    break;
                }
                inner.append((char) ci);
                if (inner.length() > MAX_EXEC_COMMENT_LENGTH)
                {
                    throw new RejectException("可执行注释内容超长");
                }
            }
            if (inner.indexOf("/*") >= 0 || inner.indexOf("*/") >= 0)
            {
                throw new RejectException("可执行注释内容含注释边界");
            }
            Tokenizer innerTokenizer = new Tokenizer(
                    new CharSource(new StringReader(inner.toString())), null);
            Token t;
            while ((t = innerTokenizer.next()) != null)
            {
                pending.add(t);
            }
        }

        /** 读引号包裹 token：quote 为 ' " `；反引号标识符无 \x 转义（双反引号转义） */
        private Token readQuoted(char quote, boolean backslashEscapes)
        {
            StringBuilder value = new StringBuilder();
            while (true)
            {
                int ci = source.read();
                if (ci < 0)
                {
                    throw new RejectException("引号未闭合: " + quote);
                }
                char c = (char) ci;
                if (backslashEscapes && quote != '`' && c == '\\')
                {
                    int ei = source.read();
                    if (ei < 0)
                    {
                        throw new RejectException("引号未闭合: " + quote);
                    }
                    value.append(decodeBackslashEscape((char) ei));
                    continue;
                }
                if (c == quote)
                {
                    if (source.peek(0) == quote)
                    {
                        // 同引号双写转义：'' '、"" "、``
                        value.append(quote);
                        source.read();
                        continue;
                    }
                    return new Token(quote == '`' ? TokenType.IDENTIFIER : TokenType.STRING,
                            value.toString());
                }
                value.append(c);
            }
        }

        /** MySQL 反斜杠转义解码：已知映射按语义，未知 \x 与 \' \" \\ 均取字面第二字符 */
        private static char decodeBackslashEscape(char c)
        {
            switch (c)
            {
                case '0':
                    return '\0';
                case 'b':
                    return '\b';
                case 'n':
                    return '\n';
                case 'r':
                    return '\r';
                case 't':
                    return '\t';
                case 'Z':
                    return (char) 0x1A;
                default:
                    return c;
            }
        }

        /** 连续裸词读取：[A-Za-z0-9_$.] 与非 ASCII（数字小数、0x hex、Unicode 无害面） */
        private Token readWord(char first)
        {
            StringBuilder word = new StringBuilder(16);
            word.append(first);
            while (true)
            {
                int ci = source.peek(0);
                if (ci < 0 || !isWordChar((char) ci))
                {
                    return new Token(TokenType.WORD, word.toString());
                }
                word.append((char) source.read());
            }
        }

        private void skipToLineEnd()
        {
            while (source.read() >= 0)
            {
                if (source.peek(0) == '\n')
                {
                    return;
                }
            }
        }

        private static boolean isWhitespace(char c)
        {
            return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f';
        }

        private static boolean isWordChar(char c)
        {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '$' || c == '.' || c > 0x7F;
        }
    }

    /** 标识符重渲染：反引号包裹 + 内部反引号双写 */
    private static String quoteIdentifier(String identifier)
    {
        return "`" + identifier.replace("`", "``") + "`";
    }

    /** 字符串逻辑值重渲染：统一 MySQL 转义（对齐 U1 导出格式），\0 \n \r \Z 单引号 反斜杠 */
    private static String renderValue(Token value)
    {
        if (value.type == TokenType.STRING)
        {
            StringBuilder sb = new StringBuilder(value.text.length() + 2);
            sb.append('\'');
            for (int i = 0; i < value.text.length(); i++)
            {
                char c = value.text.charAt(i);
                switch (c)
                {
                    case '\0':
                        sb.append("\\0");
                        break;
                    case '\n':
                        sb.append("\\n");
                        break;
                    case '\r':
                        sb.append("\\r");
                        break;
                    case 0x1a:
                        sb.append("\\Z");
                        break;
                    case '\'':
                        sb.append("''");
                        break;
                    case '\\':
                        sb.append("\\\\");
                        break;
                    default:
                        sb.append(c);
                }
            }
            sb.append('\'');
            return sb.toString();
        }
        return value.text;
    }

    /** 结构 token 序列渲染：token 间单空格（标识符/字符串按转义规则重建） */
    private static String renderTokens(List<Token> tokens)
    {
        StringBuilder sb = new StringBuilder();
        for (Token token : tokens)
        {
            if (sb.length() > 0)
            {
                sb.append(' ');
            }
            if (token.type == TokenType.IDENTIFIER)
            {
                sb.append(quoteIdentifier(token.text));
            }
            else if (token.type == TokenType.STRING)
            {
                sb.append(renderValue(token));
            }
            else
            {
                sb.append(token.text);
            }
        }
        return sb.toString();
    }
}
