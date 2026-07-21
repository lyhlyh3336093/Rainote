package com.ruoyi.system.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 列信息对象 note_column
 * 
 * @author liuyanghe
 * @date 2023-04-03
 */
public class NoteColumn extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 列id */
    private Long id;

    /** 列名称 */
    @Excel(name = "列名称")
    private String name;

    /** 列类型
     * 字段类型：
     * 1：多行文本
     * 2：数字
     * 3：单选
     * 4：多选
     * 5：日期
     * 7：复选框
     * 11：人员
     * 13：电话号码
     * 15：超链接
     * 17：附件
     * 18：单向关联
     * 19：查找引用
     * 20：公式
     * 21：双向关联
     * 22：地理位置
     * 23：数学公式
     * 24：集合运算
     * 25: 语义关联
     * 26：lookup
     * 1001：创建时间
     * 1002：最后更新时间
     * 1003：创建人
     * 1004：修改人
     * 1005：自动编号*/
    @Excel(name = "列类型")
    private Long type;

    /** 多维表格数据表ID */
    @Excel(name = "多维表格数据表ID")
    private Long dwtableId;

    /** 配置信息 */
    @Excel(name = "配置信息")
    private String property;


    /** 是否显示 */
    @Excel(name = "是否显示")
    private Long isShow;

    /** 排序 */
    @Excel(name = "排序")
    private Long sort;


    public Long getSort() {
        return sort;
    }

    public void setSort(Long sort) {
        this.sort = sort;
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
    public void setProperty(String property)
    {
        this.property = property;
    }

    public String getProperty()
    {
        return property;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("name", getName())
            .append("type", getType())
            .append("dwtableId", getDwtableId())
            .append("property", getProperty())
            .append("isShow", getIsShow())
            .append("sort",getSort())
            .toString();
    }
}
