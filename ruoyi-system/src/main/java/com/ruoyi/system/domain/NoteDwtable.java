package com.ruoyi.system.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 多维表格数据表对象 note_dwtable
 * 
 * @author liuyanghe
 * @date 2023-04-03
 */
public class NoteDwtable extends BaseEntity
{
    private static final long serialVersionUID = 1L;



    /** 数据表ID */
    private Long id;

    /** 多维表格ID */
    private Long noteId;

    /** 名称 */
    @Excel(name = "名称")
    private String name;

    /** 链接地址 */
    @Excel(name = "链接地址")
    private String url;


    /** 删除标识 */
    @Excel(name = "删除标识")
    private Long delFlag;


    public Long getDelFlag() {
        return delFlag;
    }

    public void setDelFlag(Long delFlag) {
        this.delFlag = delFlag;
    }

    public Long getNoteId() {
        return noteId;
    }

    public void setNoteId(Long noteId) {
        this.noteId = noteId;
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
            .append("name", getName())
            .append("url", getUrl())
            .append("noteId", getNoteId())
            .toString();
    }
}
