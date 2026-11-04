package com.ruoyi.system.domain;

import java.io.Serializable;
import java.util.Date;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

/**
 * 用户上传容量记录对象 sys_upload_record
 * <p>
 * 头像与通用上传均写入本表计入总容量（KTD3）；支付凭证与收款码走独立通道不写本表。
 *
 * @author ruoyi
 * @date 2026-11-04
 */
public class SysUploadRecord implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 记录ID */
    private Long id;

    /** 上传用户ID */
    private Long userId;

    /** 原始文件名 */
    private String fileName;

    /** 落盘相对路径 */
    private String filePath;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 业务类型（common/avatar） */
    private String bizType;

    /** 上传时间 */
    private Date createTime;

    public void setId(Long id)
    {
        this.id = id;
    }

    public Long getId()
    {
        return id;
    }

    public void setUserId(Long userId)
    {
        this.userId = userId;
    }

    public Long getUserId()
    {
        return userId;
    }

    public void setFileName(String fileName)
    {
        this.fileName = fileName;
    }

    public String getFileName()
    {
        return fileName;
    }

    public void setFilePath(String filePath)
    {
        this.filePath = filePath;
    }

    public String getFilePath()
    {
        return filePath;
    }

    public void setFileSize(Long fileSize)
    {
        this.fileSize = fileSize;
    }

    public Long getFileSize()
    {
        return fileSize;
    }

    public void setBizType(String bizType)
    {
        this.bizType = bizType;
    }

    public String getBizType()
    {
        return bizType;
    }

    public void setCreateTime(Date createTime)
    {
        this.createTime = createTime;
    }

    public Date getCreateTime()
    {
        return createTime;
    }

    @Override
    public String toString()
    {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("userId", getUserId())
            .append("fileName", getFileName())
            .append("filePath", getFilePath())
            .append("fileSize", getFileSize())
            .append("bizType", getBizType())
            .append("createTime", getCreateTime())
            .toString();
    }
}
