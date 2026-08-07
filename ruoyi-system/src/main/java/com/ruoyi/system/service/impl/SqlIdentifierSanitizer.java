package com.ruoyi.system.service.impl;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MySQL 标识符（表名/列名）合法化工具。
 * <p>
 * 导出物为离线 .sql 脚本，标识符不可参数化——表名/列名由用户输入派生
 * （NoteDwtable.name / NoteColumn.name），必须净化以防注入（KTD9）。
 * <p>
 * MySQL 8.0 支持反引号包裹的 Unicode 标识符（含中文），因此中文表名/列名
 * 原样保留，仅清理真正危险的字符（空格、控制符、路径分隔符等）。
 * <p>
 * 规则：
 * <ul>
 *   <li>白名单正则 {@code ^[\p{L}_][\p{L}\p{N}_]{0,62}$} 命中（支持 Unicode 字母数字）→ 反引号包裹</li>
 *   <li>MySQL 保留字（内置集合）→ 强制反引号</li>
 *   <li>含空格/特殊字符/超长 → 替换非法字符为 _、截断到 64 字符、反引号包裹</li>
 *   <li>冲突（同表两列归一化后同名）→ 追加 _col{原列id} 后缀</li>
 * </ul>
 *
 * @author ruoyi
 */
public final class SqlIdentifierSanitizer
{
    private SqlIdentifierSanitizer()
    {
    }

    /**
     * 合法标识符白名单正则：Unicode 字母或下划线开头，后接 Unicode 字母/数字/下划线，长度 1-63。
     * <p>
     * 使用 {@code \p{L}}（任何语言的字母，含中文）和 {@code \p{N}}（任何语言的数字），
     * 让"经验树"、"数据表23333"等中文标识符原样保留。
     */
    private static final Pattern VALID_PATTERN = Pattern.compile("^[\\p{L}_][\\p{L}\\p{N}_]{0,62}$");

    /** MySQL 8.0 常见保留字（子集，覆盖日常使用） */
    private static final Set<String> RESERVED_WORDS = new HashSet<>(java.util.Arrays.asList(
            "accessible", "add", "all", "alter", "analyze", "and", "as", "asc", "asensitive",
            "before", "between", "bigint", "binary", "blob", "both", "by", "call", "cascade",
            "case", "change", "char", "character", "check", "collate", "column", "condition",
            "constraint", "continue", "convert", "create", "cross", "cube", "cume_dist",
            "current_date", "current_time", "current_timestamp", "current_user", "cursor",
            "database", "databases", "day_hour", "day_microsecond", "day_minute", "day_second",
            "dec", "decimal", "declare", "default", "delayed", "delete", "dense_rank", "desc",
            "describe", "deterministic", "distinct", "distinctrow", "div", "double", "drop",
            "dual", "each", "else", "elseif", "empty", "enclosed", "escaped", "except", "exists",
            "exit", "explain", "false", "fetch", "first_value", "float", "float4", "float8",
            "for", "force", "foreign", "from", "fulltext", "function", "generated", "get",
            "grant", "group", "grouping", "groups", "having", "high_priority", "hour_microsecond",
            "hour_minute", "hour_second", "if", "ignore", "in", "index", "infile", "inner",
            "inout", "insensitive", "insert", "int", "int1", "int2", "int3", "int4", "int8",
            "integer", "interval", "into", "io_after_gtids", "io_before_gtids", "is", "iterate",
            "join", "json_table", "key", "keys", "kill", "lag", "last_value", "lateral", "lead",
            "leading", "leave", "left", "like", "limit", "linear", "lines", "load", "localtime",
            "localtimestamp", "lock", "long", "longblob", "longtext", "loop", "low_priority",
            "master_bind", "master_ssl_verify_server_cert", "match", "maxvalue", "mediumblob",
            "mediumint", "mediumtext", "middleint", "minute_microsecond", "minute_second", "mod",
            "modifies", "natural", "not", "no_write_to_binlog", "nth_value", "ntile", "null",
            "numeric", "of", "on", "optimize", "optimizer_costs", "option", "optionally", "or",
            "order", "out", "outer", "outfile", "over", "partition", "percent_rank", "precision",
            "primary", "procedure", "purge", "range", "rank", "read", "reads", "read_write",
            "real", "recursive", "references", "regexp", "release", "rename", "repeat", "replace",
            "require", "resignal", "restrict", "return", "revoke", "right", "rlike", "row",
            "rows", "row_number", "schema", "schemas", "second_microsecond", "select", "sensitive",
            "separator", "set", "show", "signal", "smallint", "spatial", "specific", "sql",
            "sqlexception", "sqlstate", "sqlwarning", "sql_big_result", "sql_calc_found_rows",
            "sql_small_result", "sqlexception", "sqlstate", "sqlwarning", "ssl", "starting",
            "stored", "straight_join", "system", "table", "terminated", "then", "tinyblob",
            "tinyint", "tinytext", "to", "trailing", "trigger", "true", "undo", "union",
            "unique", "unlock", "unsigned", "update", "usage", "use", "using", "utc_date",
            "utc_time", "utc_timestamp", "values", "varbinary", "varchar", "varcharacter",
            "varying", "virtual", "when", "where", "while", "window", "with", "write", "xor",
            "year_month", "zerofill"
    ));

    /** 最大标识符长度（MySQL 64 字符） */
    private static final int MAX_LENGTH = 64;

    /**
     * 净化标识符为合法 MySQL 标识符并反引号包裹。
     * <p>
     * 白名单命中或保留字 → 直接反引号包裹原值；
     * 含非法字符 → 替换为 _、截断到 64 字符后反引号包裹。
     *
     * @param raw      原始标识符（表名/列名）
     * @param columnId 原始列 id（用于冲突场景追加后缀，可为 null）
     * @return 形如 {@code `identifier`} 的合法标识符
     */
    public static String sanitize(String raw, Long columnId)
    {
        if (raw == null || raw.isEmpty())
        {
            return "`col" + (columnId != null ? columnId : "") + "`";
        }
        String trimmed = raw.trim();
        // 白名单命中：直接反引号包裹
        if (VALID_PATTERN.matcher(trimmed).matches())
        {
            return "`" + trimmed + "`";
        }
        // 保留字：反引号包裹原值
        if (RESERVED_WORDS.contains(trimmed.toLowerCase()))
        {
            return "`" + trimmed + "`";
        }
        // 含非法字符：替换为 _、截断
        // 注意：用 \p{L}\p{N} 保留 Unicode 字母数字（含中文），仅替换空格、标点、控制符等
        String sanitized = trimmed.replaceAll("[^\\p{L}\\p{N}_]", "_");
        // 确保不以数字开头（Unicode 数字也不允许，MySQL 标识符必须字母或下划线开头）
        if (!sanitized.isEmpty() && Character.isDigit(sanitized.charAt(0)))
        {
            sanitized = "_" + sanitized;
        }
        // 截断到 64 字符（按 Java char 计数，BMP 内 Unicode 字符等同字符数）
        if (sanitized.length() > MAX_LENGTH)
        {
            sanitized = sanitized.substring(0, MAX_LENGTH);
        }
        // 确保非空
        if (sanitized.isEmpty())
        {
            sanitized = "col" + (columnId != null ? columnId : "");
        }
        return "`" + sanitized + "`";
    }

    /**
     * 净化表名标识符（无 columnId，冲突时无后缀）。
     *
     * @param raw 原始表名
     * @return 形如 {@code `table_name`} 的合法标识符
     */
    public static String sanitizeTable(String raw)
    {
        return sanitize(raw, null);
    }
}
