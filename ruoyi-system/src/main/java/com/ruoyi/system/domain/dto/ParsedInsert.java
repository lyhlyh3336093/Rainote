package com.ruoyi.system.domain.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SQL INSERT 解析产物。
 * <p>
 * 一条 {@code INSERT INTO ... VALUES (...)} 解析后得到本对象，包含：
 * <ul>
 *   <li>{@code recordId}：源记录 id（SQL 首列 {@code record_id} 的值，BIGINT 锚点）</li>
 *   <li>{@code columnValues}：列名→值映射（{@code NULL} 已转为 Java {@code null}，
 *       数值/日期保留为字符串由导入服务按列类型处理）</li>
 * </ul>
 * 仅承载解析数据，不含业务逻辑。
 *
 * @see com.ruoyi.system.service.impl.SqlInsertParser
 * @author ruoyi
 */
public class ParsedInsert
{
    /** 源记录 id（SQL 首列 record_id 的值，导入时不映射到 NoteRecord.id，仅用于行内分组） */
    private Long recordId;

    /** 列名→值映射，保留解析顺序；NULL 转为 Java null */
    private Map<String, String> columnValues;

    public ParsedInsert()
    {
    }

    public ParsedInsert(Long recordId, Map<String, String> columnValues)
    {
        this.recordId = recordId;
        // 用 LinkedHashMap 保留顺序，便于调试与可预测的遍历
        this.columnValues = columnValues != null
                ? new LinkedHashMap<>(columnValues)
                : new LinkedHashMap<>();
    }

    public Long getRecordId()
    {
        return recordId;
    }

    public void setRecordId(Long recordId)
    {
        this.recordId = recordId;
    }

    /**
     * 获取列名→值映射（不可变视图，防止外部意外修改）。
     *
     * @return 不可变 Map
     */
    public Map<String, String> getColumnValues()
    {
        return Collections.unmodifiableMap(columnValues);
    }

    public void setColumnValues(Map<String, String> columnValues)
    {
        this.columnValues = columnValues != null
                ? new LinkedHashMap<>(columnValues)
                : new LinkedHashMap<>();
    }

    /**
     * 添加一列值（内部使用，便于解析器逐步构建）。
     *
     * @param columnName 列名（已剥离反引号 + trim）
     * @param value      值（NULL 已转为 Java null）
     */
    public void putColumn(String columnName, String value)
    {
        if (columnValues == null)
        {
            columnValues = new LinkedHashMap<>();
        }
        columnValues.put(columnName, value);
    }
}
