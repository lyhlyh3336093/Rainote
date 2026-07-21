package com.ruoyi.system.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 视图对象 note_view
 * 
 * @author liuyanghe
 * @date 2023-04-10
 */
public class NoteView extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 视图id */
    private Long id;

    /** 视图名称 */
    @Excel(name = "视图名称")
    private String name;

    /** 视图类型 */
    @Excel(name = "视图类型(1.表格视图,2.甘特视图,3.图表视图,4.关联视图)")
    private Long type;

    /** 隐藏字段id集合 */
    @Excel(name = "隐藏字段id集合")
    private String hiddenFields;

    /** 视图属性 */
    @Excel(name = "视图属性")
    private String property;

    /** 所属数据表id */
    @Excel(name = "所属数据表id")
    private Long dwtableId;

    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }
    public void setName(String name) 
    {
        this.name = name;
    }

    public String getName() 
    {
        return name;
    }
    public void setType(Long type)
    {
        this.type = type;
    }

    public Long getType()
    {
        return type;
    }
    public void setHiddenFields(String hiddenFields) 
    {
        this.hiddenFields = hiddenFields;
    }

    public String getHiddenFields() 
    {
        return hiddenFields;
    }
    public void setProperty(String property) 
    {
        this.property = property;
    }

    public String getProperty() 
    {
        return property;
    }
    public void setDwtableId(Long dwtableId) 
    {
        this.dwtableId = dwtableId;
    }

    public Long getDwtableId() 
    {
        return dwtableId;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("name", getName())
            .append("type", getType())
            .append("hiddenFields", getHiddenFields())
            .append("property", getProperty())
            .append("dwtableId", getDwtableId())
            .toString();
    }
}
