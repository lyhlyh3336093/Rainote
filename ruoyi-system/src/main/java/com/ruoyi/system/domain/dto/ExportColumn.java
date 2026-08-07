package com.ruoyi.system.domain.dto;

/**
 * 导出矩阵中的列定义。
 * <p>
 * 描述一张数据表在导出物中的列形态：列名、原始列类型（{@link com.ruoyi.system.domain.NoteColumn#getType()}）、
 * 是否渲染为双列（关联/派生类列的 ID 列 + 文本列）、是否为记录 id 列（R6 标量锚点）。
 *
 * @see ExportMatrix
 * @author ruoyi
 */
public class ExportColumn
{
    /** 原始列 id（NoteColumn.id），用于标识符合法化冲突时追加后缀 */
    private Long columnId;

    /** 列名（NoteColumn.name，未经标识符合法化，渲染层负责净化） */
    private String name;

    /** 原始列类型（NoteColumn.type：1 多行文本、21 双向关联、26 lookup 等；系统列 1001-1005） */
    private Long type;

    /**
     * 是否双列：关联/派生类列（18/21/24/25/26）渲染为 ID 列 + 文本列两列。
     * 基础类型列与系统列为单列。
     */
    private boolean dualColumn;

    /**
     * 是否记录 id 列（对应 NoteRecord.id）。
     * 每表首位必出，SQL 中映射 BIGINT，是唯一可 JOIN 的标量锚点（R6）。
     */
    private boolean recordIdColumn;

    public ExportColumn()
    {
    }

    public ExportColumn(Long columnId, String name, Long type, boolean dualColumn, boolean recordIdColumn)
    {
        this.columnId = columnId;
        this.name = name;
        this.type = type;
        this.dualColumn = dualColumn;
        this.recordIdColumn = recordIdColumn;
    }

    public Long getColumnId()
    {
        return columnId;
    }

    public void setColumnId(Long columnId)
    {
        this.columnId = columnId;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public Long getType()
    {
        return type;
    }

    public void setType(Long type)
    {
        this.type = type;
    }

    public boolean isDualColumn()
    {
        return dualColumn;
    }

    public void setDualColumn(boolean dualColumn)
    {
        this.dualColumn = dualColumn;
    }

    public boolean isRecordIdColumn()
    {
        return recordIdColumn;
    }

    public void setRecordIdColumn(boolean recordIdColumn)
    {
        this.recordIdColumn = recordIdColumn;
    }
}
