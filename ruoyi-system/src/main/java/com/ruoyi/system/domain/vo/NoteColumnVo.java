package com.ruoyi.system.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;
import com.ruoyi.common.utils.myHashMap;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import java.util.List;
import java.util.Map;

public class NoteColumnVo extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 列id */
    private Long id;

    /** 列名称 */
    @Excel(name = "列名称")
    private String name;

    /** 列类型 */
    @Excel(name = "列类型")
    private Long type;

    /** 排序 */
    @Excel(name = "排序")
    private Long sort;

    /** 多维表格数据表ID */
    @Excel(name = "多维表格数据表ID")
    private Long dwtableId;

    /** 配置信息 */
    @Excel(name = "配置信息")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private myHashMap<String, Object> property;


    /** 排序信息 */
    @Excel(name = "排序信息")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<Map<String, Object>> sorts;


    /** 是否显示 */
    @Excel(name = "是否显示")
    private Long isShow;


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

    public Long getIsShow() {
        return isShow;
    }

    public void setIsShow(Long isShow) {
        this.isShow = isShow;
    }

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
    public void setDwtableId(Long dwtableId)
    {
        this.dwtableId = dwtableId;
    }

    public Long getDwtableId()
    {
        return dwtableId;
    }

    public myHashMap<String, Object> getProperty() {
        return property;
    }

    public void setProperty(myHashMap<String, Object> property) {
        this.property = property;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
                .append("id", getId())
                .append("name", getName())
                .append("type", getType())
                .append("dwtableId", getDwtableId())
                .append("property", getProperty())
                .append("isShow",getIsShow())
                .append("sort",getSort())
                .toString();
    }
}