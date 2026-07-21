package com.ruoyi.system.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * friendrequest对象 note_friend_request
 * 
 * @author liuyanghe
 * @date 2024-08-17
 */
public class NoteFriendRequest extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 好友请求主键 */
    private Long id;

    /** 用户主键 */
    private Long userId;

    /** 请求id */
    @Excel(name = "请求id")
    private Long requestId;

    /** 请求人名称 */
    @Excel(name = "请求人名称")
    private String requestName;

    /** 请求时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "请求时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date requestTime;

    /** 请求附加信息 */
    @Excel(name = "请求附加信息")
    private String description;

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

    public void setRequestId(Long requestId)
    {
        this.requestId = requestId;
    }

    public Long getRequestId()
    {
        return requestId;
    }
    public void setRequestName(String requestName) 
    {
        this.requestName = requestName;
    }

    public String getRequestName() 
    {
        return requestName;
    }
    public void setRequestTime(Date requestTime) 
    {
        this.requestTime = requestTime;
    }

    public Date getRequestTime() 
    {
        return requestTime;
    }
    public void setDescription(String description)
    {
        this.description = description;
    }

    public String getDescription()
    {
        return description;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("userId", getUserId())
            .append("requestId", getRequestId())
            .append("requestName", getRequestName())
            .append("requestTime", getRequestTime())
            .append("description", getDescription())
            .toString();
    }
}
