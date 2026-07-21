package com.ruoyi.system.domain.vo;

import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import javax.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

/**
 * 笔记对象 note_note
 * 
 * @author ruoyi
 * @date 2023-03-09
 */
public class NoteNoteVo extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 笔记id */
    private Long id;

    /** 标题 */
    @Excel(name = "标题")
    private String title;

    /** 描述 */
    @Excel(name = "描述")
    private String remark;

    /** 版本id */
    @Excel(name = "版本id")
    private Long revisionId;

    /** 上级id */
    @Excel(name = "上级id")
    private Long parentId;

    /** 类型
     * 1.文件夹
     * 2.笔记文档
     * 3.表格
     * 4.多维表格数据表
     * 5.权限字符
     * */
    @Excel(name = "类型")
    private Long noteType;

    /** 删除标识 */
    @Excel(name = "删除标识")
    private Long delFlag;

    /** 收藏标识 */
    @Excel(name = "收藏标识")
    private Long collectionFlag;

    /** 所属用户id */
    @Excel(name = "所属用户id")
    private Long auth;





    /** 权限字符串 */
    private String perms;

    /** 富文本正文内容 */
    @Excel(name = "富文本正文内容")
    private String content;

    /** 子菜单 */
    private List<NoteNoteVo> children = new ArrayList<NoteNoteVo>();

    /** 模板标识 */
    @Excel(name = "模板标识")
    private Long templateFlag;

    public Long getTemplateFlag() {
        return templateFlag;
    }

    public void setTemplateFlag(Long templateFlag) {
        this.templateFlag = templateFlag;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getCollectionFlag() {
        return collectionFlag;
    }

    public void setCollectionFlag(Long collectionFlag) {
        this.collectionFlag = collectionFlag;
    }

    public Long getAuth() {
        return auth;
    }

    public void setAuth(Long auth) {
        this.auth = auth;
    }


    @Size(min = 0, max = 100, message = "权限标识长度不能超过100个字符")
    public String getPerms()
    {
        return perms;
    }

    public void setPerms(String perms)
    {
        this.perms = perms;
    }
    public Long getDelFlag() {
        return delFlag;
    }

    public void setDelFlag(Long delFlag) {
        this.delFlag = delFlag;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }
    public void setTitle(String title) 
    {
        this.title = title;
    }

    public String getTitle() 
    {
        return title;
    }
    public void setRemark(String remark)
    {
        this.remark = remark;
    }

    public String getRemark()
    {
        return remark;
    }
    public void setRevisionId(Long revisionId) 
    {
        this.revisionId = revisionId;
    }

    public Long getRevisionId() 
    {
        return revisionId;
    }
    public void setParentId(Long parentId) 
    {
        this.parentId = parentId;
    }

    public Long getParentId() 
    {
        return parentId;
    }
    public void setNoteType(Long noteType)
    {
        this.noteType = noteType;
    }

    public Long getNoteType()
    {
        return noteType;
    }

    public List<NoteNoteVo> getChildren() {
        return children;
    }

    public void setChildren(List<NoteNoteVo> children) {
        this.children = children;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
                .append("id", getId())
                .append("title", getTitle())
                .append("remark", getRemark())
                .append("revisionId", getRevisionId())
                .append("parentId", getParentId())
                .append("perms", getPerms())
                .append("noteType", getNoteType())
                .append("content", getContent())
                .append("perms",getPerms())
                .append("templateFlag",getTemplateFlag())
                .toString();
    }
}
