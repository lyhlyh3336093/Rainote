package com.ruoyi.system.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 笔记链接对象 note_notelink
 * 
 * @author liuyanghe
 * @date 2026-02-08
 */
public class NoteNotelink extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 笔记链接主键 */
    private Long id;

    /** 笔记内容 */
    @Excel(name = "笔记内容")
    private String contextText;

    /** 所属笔记id */
    @Excel(name = "所属笔记id")
    private Long noteId;

    /** 所属块id */
    @Excel(name = "所属块id")
    private Long blockId;

    /** 关联多维表格id */
    @Excel(name = "关联多维表格id")
    private Long linkNoteId;

    /** 关联数据表id */
    @Excel(name = "关联数据表id")
    private Long linkDwTableId;

    /** 关联行id */
    @Excel(name = "关联行id")
    private Long linkRecordId;

    /** 关联列id */
    @Excel(name = "关联列id")
    private Long linkColumnId;

    /** 关联单元格id */
    @Excel(name = "关联单元格id")
    private Long linkItemId;

    /** 单元格显示内容 */
    @Excel(name = "单元格显示内容")
    private String itemValue;

    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }
    public void setContextText(String contextText) 
    {
        this.contextText = contextText;
    }

    public String getContextText() 
    {
        return contextText;
    }
    public void setNoteId(Long noteId) 
    {
        this.noteId = noteId;
    }

    public Long getNoteId() 
    {
        return noteId;
    }
    public void setBlockId(Long blockId) 
    {
        this.blockId = blockId;
    }

    public Long getBlockId() 
    {
        return blockId;
    }
    public void setLinkNoteId(Long linkNoteId) 
    {
        this.linkNoteId = linkNoteId;
    }

    public Long getLinkNoteId() 
    {
        return linkNoteId;
    }
    public void setLinkDwTableId(Long linkDwTableId) 
    {
        this.linkDwTableId = linkDwTableId;
    }

    public Long getLinkDwTableId() 
    {
        return linkDwTableId;
    }
    public void setLinkRecordId(Long linkRecordId) 
    {
        this.linkRecordId = linkRecordId;
    }

    public Long getLinkRecordId() 
    {
        return linkRecordId;
    }
    public void setLinkColumnId(Long linkColumnId) 
    {
        this.linkColumnId = linkColumnId;
    }

    public Long getLinkColumnId() 
    {
        return linkColumnId;
    }
    public void setLinkItemId(Long linkItemId) 
    {
        this.linkItemId = linkItemId;
    }

    public Long getLinkItemId() 
    {
        return linkItemId;
    }
    public void setItemValue(String itemValue) 
    {
        this.itemValue = itemValue;
    }

    public String getItemValue() 
    {
        return itemValue;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("contextText", getContextText())
            .append("noteId", getNoteId())
            .append("blockId", getBlockId())
            .append("linkNoteId", getLinkNoteId())
            .append("linkDwTableId", getLinkDwTableId())
            .append("linkRecordId", getLinkRecordId())
            .append("linkColumnId", getLinkColumnId())
            .append("linkItemId", getLinkItemId())
            .append("itemValue", getItemValue())
            .toString();
    }
}
