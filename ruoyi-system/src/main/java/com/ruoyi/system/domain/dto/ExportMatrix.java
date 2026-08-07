package com.ruoyi.system.domain.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 单张数据表的导出矩阵（EAV pivot 后的行×列结构）。
 * <p>
 * 含表名、有序列定义（{@link ExportColumn}）、有序行数据。每行为一个有序的单元格值列表，
 * 与列定义一一对应。关联/派生类双列在行数据中占两个相邻位置（ID 值在前，文本值在后）。
 * <p>
 * 空表（无记录）仍含列定义，行数据为空列表（R20）。
 *
 * @author ruoyi
 */
public class ExportMatrix
{
    /** 数据表 id（NoteDwtable.id） */
    private Long dwtableId;

    /** 数据表名（NoteDwtable.name，未经标识符合法化） */
    private String tableName;

    /** 有序列定义（首位为记录 id 列） */
    private List<ExportColumn> columns = new ArrayList<>();

    /**
     * 有序行数据，每行为一个有序的单元格值列表（String，与列定义顺序对应）。
     * 双列在行数据中占两个相邻位置。空表时为空列表。
     */
    private List<List<String>> rows = new ArrayList<>();

    public ExportMatrix()
    {
    }

    public Long getDwtableId()
    {
        return dwtableId;
    }

    public void setDwtableId(Long dwtableId)
    {
        this.dwtableId = dwtableId;
    }

    public String getTableName()
    {
        return tableName;
    }

    public void setTableName(String tableName)
    {
        this.tableName = tableName;
    }

    public List<ExportColumn> getColumns()
    {
        return columns;
    }

    public void setColumns(List<ExportColumn> columns)
    {
        this.columns = columns;
    }

    public List<List<String>> getRows()
    {
        return rows;
    }

    public void setRows(List<List<String>> rows)
    {
        this.rows = rows;
    }
}
