package com.ruoyi.system.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 多维表格数据表内容对象 note_dwtable_item
 * 
 * @author liuyanghe
 * @date 2023-04-03
 */
public class NoteDwtableItem extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 多维表格数据表详情id */
    private Long id;

    /** 多维表格数据表id */
    @Excel(name = "多维表格数据表id")
    private Long dwtId;

    /** 列id */
    @Excel(name = "列id")
    private Long columnId;

    /** 记录id */
    @Excel(name = "记录id")
    private Long recordId;

    /** 值 */
    @Excel(name = "值")
    private String value;


    /** 关联记录id集合 */
    @Excel(name = "关联记录id集合")
    private String linkRecordId;


    /** 关联数据id集合 */
    @Excel(name = "关联数据id集合")
    private String linkItemId;


    /** 关联列id */
    @Excel(name = "关联列id")
    private Long linkColumnId;


    /** 关联块id */
    @Excel(name = "关联块id")
    private Long linkBlockId;



    /** 关联笔记id */
    @Excel(name = "关联笔记id")
    private Long linkNoteId;

    public String getLinkItemId() {
        return linkItemId;
    }

    public void setLinkItemId(String linkItemId) {
        this.linkItemId = linkItemId;
    }

    public Long getLinkColumnId() {
        return linkColumnId;
    }

    public void setLinkColumnId(Long linkColumnId) {
        this.linkColumnId = linkColumnId;
    }

    public String getLinkRecordId() {
        return linkRecordId;
    }

    public void setLinkRecordId(String linkRecordId) {
        this.linkRecordId = linkRecordId;
    }

    public Long getRecordId() {
        return recordId;
    }

    public void setRecordId(Long recordId) {
        this.recordId = recordId;
    }

    public Long getLinkBlockId() {
        return linkBlockId;
    }

    public void setLinkBlockId(Long linkBlockId) {
        this.linkBlockId = linkBlockId;
    }


    public Long getLinkNoteId() {
        return linkNoteId;
    }

    public void setLinkNoteId(Long linkNoteId) {
        this.linkNoteId = linkNoteId;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }
    public void setDwtId(Long dwtId) 
    {
        this.dwtId = dwtId;
    }

    public Long getDwtId() 
    {
        return dwtId;
    }
    public void setColumnId(Long columnId) 
    {
        this.columnId = columnId;
    }

    public Long getColumnId() 
    {
        return columnId;
    }
    public void setValue(String value) 
    {
        this.value = value;
    }

    public String getValue() 
    {
        return value;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("dwtId", getDwtId())
            .append("columnId", getColumnId())
            .append("value", getValue())
            .append("recordId", getRecordId())
            .append("linkRecordId", getLinkRecordId())
            .append("linkItemId", getLinkItemId())
            .append("linkColumnId", getLinkColumnId())
            .append("linkBlockId",getLinkBlockId())
            .append("linkNoteId",getLinkNoteId())
            .toString();
    }
}
