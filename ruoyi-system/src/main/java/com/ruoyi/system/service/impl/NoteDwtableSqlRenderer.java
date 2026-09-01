package com.ruoyi.system.service.impl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.ruoyi.system.domain.dto.ExportColumn;
import com.ruoyi.system.domain.dto.ExportFile;
import com.ruoyi.system.domain.dto.ExportMatrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SQL 渲染器：把 {@link ExportMatrix} 渲染为 MySQL .sql 文件。
 * <p>
 * 每表一 .sql 文件；超 2000 行触发按行分片，每片 2000 行（R25）。
 * 首片含 CREATE TABLE + 前 2000 条 INSERT；后续片为纯 INSERT。
 * <p>
 * 安全（KTD9）：
 * <ul>
 *   <li>所有 INSERT 字符串值经 {@link SqlValueEscaper#escape(String)} 转义</li>
 *   <li>数值列值先 {@link SqlValueEscaper#tryParseNumber(String)} 校验，失败写 NULL</li>
 *   <li>表名/列名经 {@link SqlIdentifierSanitizer#sanitize(String, Long)} 净化</li>
 * </ul>
 *
 * @author ruoyi
 */
@Component
public class NoteDwtableSqlRenderer
{
    private static final Logger log = LoggerFactory.getLogger(NoteDwtableSqlRenderer.class);

    /** 分片行数阈值（R21） */
    public static final int SHARD_SIZE = 2000;

    /**
     * 把一张数据表的矩阵渲染为 SQL 文件列表（超限表多文件分片）。
     *
     * @param matrix 单表导出矩阵
     * @return SQL 文件列表（合格表 1 个文件，超限表多个分片文件）
     */
    public List<ExportFile> render(ExportMatrix matrix)
    {
        List<ExportFile> files = new ArrayList<>();
        if (matrix == null)
        {
            return files;
        }
        String tableName = matrix.getTableName() != null ? matrix.getTableName() : "table_" + matrix.getDwtableId();
        // 文件名追加数据表 id 做区分（dwtableId 对应 NoteDwtable.id）
        String idSuffix = matrix.getDwtableId() != null ? "_" + matrix.getDwtableId() : "";
        List<List<String>> rows = matrix.getRows();
        int totalRows = rows != null ? rows.size() : 0;

        if (totalRows <= SHARD_SIZE)
        {
            // 合格表：单文件含 CREATE TABLE + INSERT
            String sql = buildCreateTable(matrix, tableName) + buildInserts(matrix, 0, totalRows);
            String fileName = tableName + idSuffix + ".sql";
            files.add(new ExportFile(fileName, sql.getBytes(StandardCharsets.UTF_8)));
        }
        else
        {
            // 超限表：按行分片
            int shardCount = (totalRows + SHARD_SIZE - 1) / SHARD_SIZE;
            for (int s = 0; s < shardCount; s++)
            {
                int from = s * SHARD_SIZE;
                int to = Math.min(from + SHARD_SIZE, totalRows);
                StringBuilder sb = new StringBuilder();
                if (s == 0)
                {
                    // 首片含 CREATE TABLE
                    sb.append(buildCreateTable(matrix, tableName));
                }
                sb.append(buildInserts(matrix, from, to));
                String fileName = tableName + idSuffix + "_p" + (s + 1) + ".sql";
                files.add(new ExportFile(fileName, sb.toString().getBytes(StandardCharsets.UTF_8)));
            }
        }
        log.debug("[SQL-RENDER] table={}, rows={}, files={}", tableName, totalRows, files.size());
        return files;
    }

    /**
     * 构造 CREATE TABLE 语句（含列定义与类型映射）。
     * 双列（关联/派生类）展开为 名_ID + 名_文本 两列，与行数据的双单元格布局对齐。
     */
    private String buildCreateTable(ExportMatrix matrix, String tableName)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(SqlIdentifierSanitizer.sanitizeTable(tableName)).append(" (\n");
        List<ExportColumn> columns = matrix.getColumns();
        List<String> colDefs = new ArrayList<>();
        for (ExportColumn col : columns)
        {
            String sqlType = mapSqlType(col);
            if (col.isDualColumn())
            {
                colDefs.add("  " + SqlIdentifierSanitizer.sanitize(col.getName() + "_ID", col.getColumnId()) + " " + sqlType);
                colDefs.add("  " + SqlIdentifierSanitizer.sanitize(col.getName() + "_文本", col.getColumnId()) + " " + sqlType);
            }
            else
            {
                colDefs.add("  " + SqlIdentifierSanitizer.sanitize(col.getName(), col.getColumnId()) + " " + sqlType);
            }
        }
        sb.append(String.join(",\n", colDefs));
        sb.append("\n);\n\n");
        return sb.toString();
    }

    /**
     * 构造 INSERT 语句（rows[from..to) 范围）。
     * 双列展开为 名_ID + 名_文本 两列；行数据中双列占两个相邻单元格（ID + 文本），
     * 值循环用独立数据索引消费——列索引与数据索引在首个双列后即错位，不可复用。
     */
    private String buildInserts(ExportMatrix matrix, int from, int to)
    {
        if (from >= to)
        {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        List<ExportColumn> columns = matrix.getColumns();
        List<List<String>> rows = matrix.getRows();
        // 构造输出列名列表（双列展开）
        List<String> outColNames = new ArrayList<>();
        for (ExportColumn col : columns)
        {
            if (col.isDualColumn())
            {
                outColNames.add(SqlIdentifierSanitizer.sanitize(col.getName() + "_ID", col.getColumnId()));
                outColNames.add(SqlIdentifierSanitizer.sanitize(col.getName() + "_文本", col.getColumnId()));
            }
            else
            {
                outColNames.add(SqlIdentifierSanitizer.sanitize(col.getName(), col.getColumnId()));
            }
        }
        String colNames = "(" + String.join(", ", outColNames) + ")";

        for (int r = from; r < to; r++)
        {
            List<String> row = rows.get(r);
            sb.append("INSERT INTO ")
                    .append(SqlIdentifierSanitizer.sanitizeTable(matrix.getTableName() != null ? matrix.getTableName() : "table_" + matrix.getDwtableId()))
                    .append(" ").append(colNames).append(" VALUES (");
            // 数据索引独立推进：普通列消费 1 个单元格，双列消费 2 个（ID + 文本）
            int dataIdx = 0;
            boolean first = true;
            for (ExportColumn col : columns)
            {
                if (col.isDualColumn())
                {
                    String idValue = dataIdx < row.size() ? row.get(dataIdx) : null;
                    String textValue = dataIdx + 1 < row.size() ? row.get(dataIdx + 1) : null;
                    dataIdx += 2;
                    if (!first)
                    {
                        sb.append(", ");
                    }
                    sb.append(escapeValue(idValue, col)).append(", ").append(escapeValue(textValue, col));
                }
                else
                {
                    String value = dataIdx < row.size() ? row.get(dataIdx) : null;
                    dataIdx++;
                    if (!first)
                    {
                        sb.append(", ");
                    }
                    sb.append(escapeValue(value, col));
                }
                first = false;
            }
            sb.append(");\n");
        }
        return sb.toString();
    }

    /**
     * 按列类型转义值：
     * <ul>
     *   <li>记录 id 列 → 数值校验后原样输出（BIGINT 锚点）</li>
     *   <li>数字列 → tryParseNumber 校验，成功原样输出，失败写 NULL</li>
     *   <li>其他列 → 字符串转义</li>
     * </ul>
     */
    private String escapeValue(String value, ExportColumn col)
    {
        if (value == null || value.isEmpty())
        {
            // 空字符串作为 NULL 处理（区别于经转义的空字符串字面量）
            return "NULL";
        }
        Long type = col.getType();
        if (col.isRecordIdColumn())
        {
            // 记录 id 列：数值校验后原样输出
            if (SqlValueEscaper.tryParseNumber(value))
            {
                return value;
            }
            log.warn("[SQL-RENDER] 记录 id 列值非数值 col={}, value={}, 写 NULL", col.getName(), value);
            return "NULL";
        }
        if (type != null && type == 2L)
        {
            // 数字列：校验后原样输出
            if (SqlValueEscaper.tryParseNumber(value))
            {
                return value;
            }
            log.warn("[SQL-RENDER] 数字列值非数值 col={}, value={}, 写 NULL", col.getName(), value);
            return "NULL";
        }
        // 其他列：字符串转义
        return SqlValueEscaper.escape(value);
    }

    /**
     * SQL 类型映射（KTD3）：
     * 记录 id 列 → BIGINT；数字 → INT/DECIMAL；文本 → VARCHAR/TEXT；
     * 大文本 → TEXT/LONGTEXT；日期 → DATETIME；关联列 ID 与文本列 → VARCHAR。
     */
    private String mapSqlType(ExportColumn col)
    {
        if (col.isRecordIdColumn())
        {
            return "BIGINT";
        }
        Long type = col.getType();
        if (type == null)
        {
            return "VARCHAR(255)";
        }
        switch (type.intValue())
        {
            case 2: // 数字
                return "DECIMAL(20,6)";
            case 5: // 日期
                return "DATETIME";
            case 1: // 多行文本
                return "TEXT";
            case 17: // 附件
                return "VARCHAR(2000)";
            case 11: // 人员
                return "VARCHAR(255)";
            case 1001: // 创建时间
            case 1002: // 最后更新时间
                return "DATETIME";
            case 1003: // 创建人
            case 1004: // 修改人
                return "VARCHAR(64)";
            case 1005: // 自动编号
                return "BIGINT";
            // 关联/派生类列的 ID 列与文本列 → VARCHAR（逗号分隔多值，仅 FIND_IN_SET）
            case 18:
            case 21:
            case 24:
            case 25:
            case 26:
                return "VARCHAR(2000)";
            default:
                return "VARCHAR(255)";
        }
    }
}
