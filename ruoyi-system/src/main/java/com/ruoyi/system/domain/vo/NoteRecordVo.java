package com.ruoyi.system.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import java.util.List;
import java.util.Map;

/**
 * 记录对象 note_record
 * 
 * @author liuyanghe
 * @date 2023-04-12
 */
public class NoteRecordVo extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Long id;

    /** 所属视图id */
    @Excel(name = "所属视图id")
    private Long viewId;

    /** 配置信息 */
    @Excel(name = "配置信息")
    private String property;

    /** 关联的记录id */
    @Excel(name = "关联的记录id")
    private String linkRecordId;

    /** 排序 */
    @Excel(name = "排序")
    private Long sort;

    /** 显示名称 */
    @Excel(name = "显示名称")
    private String name;

    /** 关联记录名称 */
    @Excel(name = "关联记录名称")
    private String linkName;

    /** 表格数据信息 */
    @Excel(name = "表格数据信息")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<Map<String, Object>> items;

    /** 排序信息 */
    @Excel(name = "表格数据信息")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<Map<String, Object>> sorts;


    /** 数据表id */
    @Excel(name = "数据表id")
    private Long dwtableId;


    public Long getDwtableId() {
        return dwtableId;
    }

    public void setDwtableId(Long dwtableId) {
        this.dwtableId = dwtableId;
    }

    public Long getSort() {
        return sort;
    }

    public void setSort(Long sort) {
        this.sort = sort;
    }

    public List<Map<String, Object>> getSorts() {
        return sorts;
    }

    public void setSorts(List<Map<String, Object>> sorts) {
        this.sorts = sorts;
    }

    public List<Map<String, Object>> getItems() {
        return items;
    }

    public void setItems(List<Map<String, Object>> items) {
        this.items = items;
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

    public String getProperty() {
        return property;
    }

    public void setProperty(String property) {
        this.property = property;
    }

    public void setLinkRecordId(String linkRecordId)
    {
        this.linkRecordId = linkRecordId;
    }

    public String getLinkRecordId()
    {
        return linkRecordId;
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
            .append("dwtableId", getDwtableId())
            .append("property", getProperty())
            .append("linkRecordId", getLinkRecordId())
            .append("name", getName())
            .append("linkName", getLinkName())
            .append("items", getItems())
            .append("sort",getSort())
            .toString();
    }
}
