package com.ruoyi.system.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 元数据对象 note_meta
 * 
 * @author liuyanghe
 * @date 2023-05-13
 */
public class NoteMeta extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Long id;

    /** 笔记id */
    @Excel(name = "笔记id")
    private Long noteId;

    /** 创建日期 */
    @Excel(name = "创建日期")
    private String createDate;

    /** 创建者open_id */
    @Excel(name = "创建者open_id")
    private String creator;

    /** 创建者用户名 */
    @Excel(name = "创建者用户名")
    private String createName;

    /** 删除标志，0表示正常访问未删除，1表示在回收站，2表示已经彻底删除 */
    @Excel(name = "删除标志，0表示正常访问未删除，1表示在回收站，2表示已经彻底删除")
    private Long deleteFlag;

    /** 最后编辑时间戳 */
    @Excel(name = "最后编辑时间戳")
    private Long editTime;


    /** 最后编辑日期 */
    @Excel(name = "最后编辑日期")
    private String editDate;

    /** 最后编辑者用户名 */
    @Excel(name = "最后编辑者用户名")
    private String editName;

    /** 是否外部文档 */
    @Excel(name = "是否外部文档")
    private Long isExternal;

    /** 是否在接口调用者目录里快速访问 */
    @Excel(name = "是否在接口调用者目录里快速访问")
    private Long isPined;

    /** 是否在接口调用者目录里收藏 */
    @Excel(name = "是否在接口调用者目录里收藏")
    private Long isStared;

    /** 文档类型，固定是doc */
    @Excel(name = "文档类型，固定是doc")
    private String objType;

    /** 当前所有者open_id */
    @Excel(name = "当前所有者open_id")
    private String owner;

    /** 当前所有者用户名 */
    @Excel(name = "当前所有者用户名")
    private String ownerName;

    /** 处理请求时的服务器时间戳 */
    @Excel(name = "处理请求时的服务器时间戳")
    private Long serverTime;

    /** 文档名称 */
    @Excel(name = "文档名称")
    private String title;

    /** 文档类型，固定是2 */
    @Excel(name = "文档类型，固定是2")
    private Long type;

    /** 文档url */
    @Excel(name = "文档url")
    private String url;


    public String getEditDate() {
        return editDate;
    }

    public void setEditDate(String editDate) {
        this.editDate = editDate;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }
    public void setNoteId(Long noteId) 
    {
        this.noteId = noteId;
    }

    public Long getNoteId() 
    {
        return noteId;
    }
    public void setCreateDate(String createDate) 
    {
        this.createDate = createDate;
    }

    public String getCreateDate() 
    {
        return createDate;
    }
    public void setCreator(String creator) 
    {
        this.creator = creator;
    }

    public String getCreator() 
    {
        return creator;
    }
    public void setCreateName(String createName) 
    {
        this.createName = createName;
    }

    public String getCreateName() 
    {
        return createName;
    }
    public void setDeleteFlag(Long deleteFlag) 
    {
        this.deleteFlag = deleteFlag;
    }

    public Long getDeleteFlag() 
    {
        return deleteFlag;
    }
    public void setEditTime(Long editTime) 
    {
        this.editTime = editTime;
    }

    public Long getEditTime() 
    {
        return editTime;
    }
    public void setEditName(String editName) 
    {
        this.editName = editName;
    }

    public String getEditName() 
    {
        return editName;
    }
    public void setIsExternal(Long isExternal) 
    {
        this.isExternal = isExternal;
    }

    public Long getIsExternal() 
    {
        return isExternal;
    }
    public void setIsPined(Long isPined) 
    {
        this.isPined = isPined;
    }

    public Long getIsPined() 
    {
        return isPined;
    }
    public void setIsStared(Long isStared) 
    {
        this.isStared = isStared;
    }

    public Long getIsStared() 
    {
        return isStared;
    }
    public void setObjType(String objType) 
    {
        this.objType = objType;
    }

    public String getObjType() 
    {
        return objType;
    }
    public void setOwner(String owner) 
    {
        this.owner = owner;
    }

    public String getOwner() 
    {
        return owner;
    }
    public void setOwnerName(String ownerName) 
    {
        this.ownerName = ownerName;
    }

    public String getOwnerName() 
    {
        return ownerName;
    }
    public void setServerTime(Long serverTime) 
    {
        this.serverTime = serverTime;
    }

    public Long getServerTime() 
    {
        return serverTime;
    }
    public void setTitle(String title) 
    {
        this.title = title;
    }

    public String getTitle() 
    {
        return title;
    }
    public void setType(Long type) 
    {
        this.type = type;
    }

    public Long getType() 
    {
        return type;
    }
    public void setUrl(String url) 
    {
        this.url = url;
    }

    public String getUrl() 
    {
        return url;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("noteId", getNoteId())
            .append("createTime", getCreateTime())
            .append("createDate", getCreateDate())
            .append("creator", getCreator())
            .append("createName", getCreateName())
            .append("deleteFlag", getDeleteFlag())
            .append("editTime", getEditTime())
            .append("editName", getEditName())
            .append("isExternal", getIsExternal())
            .append("isPined", getIsPined())
            .append("isStared", getIsStared())
            .append("objType", getObjType())
            .append("owner", getOwner())
            .append("ownerName", getOwnerName())
            .append("serverTime", getServerTime())
            .append("title", getTitle())
            .append("type", getType())
            .append("url", getUrl())
            .toString();
    }
}
