package com.ruoyi.system.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 记录对象 note_record
 * 
 * @author liuyanghe
 * @date 2023-04-12
 */
public class NoteRecord extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Long id;

    /** 所属视图id */
    @Excel(name = "所属视图id")
    private Long viewId;

    /** 属性设置 */
    @Excel(name = "属性设置")
    private String property;

    /** 关联的记录id */
    @Excel(name = "关联的记录id")
    private String linkRecordId;

    /** 序号 */
    @Excel(name = "序号")
    private Long sort;

    /** 显示名称 */
    @Excel(name = "显示名称")
    private String name;

    /** 数据表id */
    @Excel(name = "数据表id")
    private Long dwtableId;

    /** 关联记录名称 */
    @Excel(name = "关联记录名称")
    private String linkName;


    public void setLinkRecordId(String linkRecordId) {
        this.linkRecordId = linkRecordId;
    }

    public String getLinkRecordId() {
        return linkRecordId;
    }

    public Long getSort() {
        return sort;
    }

    public void setSort(Long sort) {
        this.sort = sort;
    }

    public Long getDwtableId() {
        return dwtableId;
    }

    public void setDwtableId(Long dwtableId) {
        this.dwtableId = dwtableId;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }
    public void setViewId(Long viewId) 
    {
        this.viewId = viewId;
    }

    public Long getViewId() 
    {
        return viewId;
    }
    public void setProperty(String property) 
    {
        this.property = property;
    }

    public String getProperty() 
    {
        return property;
    }

    public void setName(String name) 
    {
        this.name = name;
    }

    public String getName() 
    {
        return name;
    }
    public void setLinkName(String linkName) 
    {
        this.linkName = linkName;
    }

    public String getLinkName() 
    {
        return linkName;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("viewId", getViewId())
            .append("property", getProperty())
            .append("linkRecordId", getLinkRecordId())
            .append("name", getName())
            .append("linkName", getLinkName())
            .append("dwtableId",getDwtableId())
            .append("sort",getSort())
            .toString();
    }
}
