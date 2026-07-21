package com.ruoyi.system.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;
import com.ruoyi.common.utils.myHashMap;
import com.ruoyi.system.domain.NoteBlock;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import java.io.IOException;

/**
 * 块元素对象 note_block
 * 
 * @author liuyanghe
 * @date 2023-05-17
 */
public class NoteBlockVo
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
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private myHashMap<String, Object> property;

    /** 关联记录Id */
    @Excel(name = "关联记录Id")
    private Long recordId;

    /** 笔记ID*/
    private Long sourceId;

    /** 多维表格ID*/
    private Long targetId;

    /** 发起链接的块主键 */
    private Long blockId;


    /** 被关联的数据表id */
    private Long tableId;


    /** 关联内容 */
    @Excel(name = "关联内容")
    private String contextText;


    /** 笔记标题 */
    @Excel(name = "笔记标题")
    private String noteTitle;


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

    public myHashMap<String, Object> getProperty() {
        return property;
    }

    public void setProperty(myHashMap<String, Object> property) {
        this.property = property;
    }


    public Long getRecordId() {
        return recordId;
    }

    public void setRecordId(Long recordId) {
        this.recordId = recordId;
    }


    public Long getSourceId() {
        return sourceId;
    }

    public void setSourceId(Long sourceId) {
        this.sourceId = sourceId;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public Long getBlockId() {
        return blockId;
    }

    public void setBlockId(Long blockId) {
        this.blockId = blockId;
    }

    public Long getTableId() {
        return tableId;
    }

    public void setTableId(Long tableId) {
        this.tableId = tableId;
    }


    public String getContextText() {
        return contextText;
    }

    public void setContextText(String contextText) {
        this.contextText = contextText;
    }

    public String getNoteTitle() {
        return noteTitle;
    }

    public void setNoteTitle(String noteTitle) {
        this.noteTitle = noteTitle;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("parentId", getParentId())
            .append("childId", getChildId())
            .append("blockType", getBlockType())
            .append("property", getProperty())
            .append("tableId", getTableId())
            .append("blockId", getBlockId())
            .append("sourceId", getSourceId())
            .append("targetId", getTargetId())
            .append("recordId", getRecordId())
            .append("contextText",getContextText())
            .append("noteTitle",getNoteTitle())
            .toString();
    }

//    public NoteBlockVo(String json) throws IOException {
//        NoteBlockVo param = new ObjectMapper().readValue(json, NoteBlockVo.class);
//        this.id=param.getId();
//        this.parentId = param.getParentId();
//        this.childId = param.getChildId();
//        this.blockType = param.getBlockType();
//        this.property = param.getProperty();
//        this.tableId = param.getTableId();
//        this.blockId = param.getBlockId();
//        this.noteId = param.getNoteId();
//        this.recordId = param.getRecordId();
//    }

    public NoteBlockVo() {
    }
    public NoteBlockVo(Long tableId,Long blockId,Long sourceId,Long targetId,Long recordId,String contextText,String noteTitle){
        this.tableId=tableId;
        this.blockId=blockId;
        this.sourceId=sourceId;
        this.targetId=targetId;
        this.recordId=recordId;
        this.contextText=contextText;
        this.noteTitle=noteTitle;
    }

}
