package com.ruoyi.system.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 块元素对象 note_block
 * 
 * @author liuyanghe
 * @date 2023-05-17
 */
public class NoteBlock extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 块主键 */
    private Long id;

    /** 父id */
    @Excel(name = "父id")
    private Long parentId;

    /** 子模块id集合 */
    @Excel(name = "子模块id集合")
    private String childId;

    /** 块类型 */
    @Excel(name = "块类型")
    private Long blockType;

    /** 配置信息 */
    @Excel(name = "配置信息")
    private String property;


    /** 文件上传路径 */
    @Excel(name = "文件上传路径")
    private String filePath;


    /** 排序 */
    @Excel(name = "排序")
    private Long sort;


    public Long getSort() {
        return sort;
    }

    public void setSort(Long sort) {
        this.sort = sort;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }
    public void setParentId(Long parentId) 
    {
        this.parentId = parentId;
    }

    public Long getParentId() 
    {
        return parentId;
    }
    public void setChildId(String childId) 
    {
        this.childId = childId;
    }

    public String getChildId() 
    {
        return childId;
    }
    public void setBlockType(Long blockType) 
    {
        this.blockType = blockType;
    }

    public Long getBlockType() 
    {
        return blockType;
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
            .append("parentId", getParentId())
            .append("childId", getChildId())
            .append("blockType", getBlockType())
            .append("property", getProperty())
            .append("sort",getSort())
            .append("filePath",getFilePath())
            .toString();
    }
}
