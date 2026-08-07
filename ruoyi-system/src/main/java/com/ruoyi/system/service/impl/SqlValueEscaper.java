package com.ruoyi.system.service.impl;

/**
 * MySQL 字符串值转义工具。
 * <p>
 * 导出物为离线执行的 .sql 脚本，无法用 PreparedStatement 参数化——
 * 所有 INSERT 值必须以字面量写入 SQL 文本。本工具对字符串值做等价于
 * mysql_real_escape_string 的转义，防止 SQL 注入（KTD9）。
 * <p>
 * 转义规则：
 * <ul>
 *   <li>{@code \0} (NUL) → {@code \\0}</li>
 *   <li>{@code \n} → {@code \\n}</li>
 *   <li>{@code \r} → {@code \\r}</li>
 *   <li>{@code \u001a} (Ctrl-Z) → {@code \\Z}</li>
 *   <li>{@code "} → {@code \"}</li>
 *   <li>{@code '} → {@code ''}（SQL 标准，单引号转义）</li>
 *   <li>{@code \} → {@code \\}</li>
 * </ul>
 * 数值列值调用方应先用 {@link #tryParseNumber(String)} 校验，再原样输出。
 *
 * @author ruoyi
 */
public final class SqlValueEscaper
{
    private SqlValueEscaper()
    {
    }

    /**
     * 转义字符串值并包裹为 SQL 字面量（单引号包裹）。
     * null 返回 "NULL"。
     *
     * @param value 原始值
     * @return 形如 {@code 'escaped_value'} 或 {@code NULL}
     */
    public static String escape(String value)
    {
        if (value == null)
        {
            return "NULL";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('\'');
        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);
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
                case '"':
                    sb.append("\\\"");
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

    /**
     * 尝试解析为数值。成功返回 true，失败返回 false。
     * 用于数值列值校验，防止 "1; DROP TABLE--" 注入。
     *
     * @param value 待校验值
     * @return 是否为合法数值
     */
    public static boolean tryParseNumber(String value)
    {
        if (value == null || value.isEmpty())
        {
            return false;
        }
        try
        {
            Double.parseDouble(value);
            return true;
        }
        catch (NumberFormatException e)
        {
            return false;
        }
    }
}
