package com.ruoyi.system.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * friend对象 note_friend
 * 
 * @author liuyanghe
 * @date 2024-08-17
 */
public class NoteFriend extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 好友主键 */
    private String id;

    /** 用户id */
    @Excel(name = "用户id")
    private Long userId;

    /** 好友id */
    @Excel(name = "好友id")
    private Long friendId;

    /** 状态（0：好友，1：拉黑） */
    @Excel(name = "状态", readConverterExp = "0=：好友，1：拉黑")
    private String status;

    /** 添加好友时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "添加好友时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date creatTime;

    public void setId(String id) 
    {
        this.id = id;
    }

    public String getId() 
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
    public void setFriendId(Long friendId) 
    {
        this.friendId = friendId;
    }

    public Long getFriendId() 
    {
        return friendId;
    }
    public void setStatus(String status) 
    {
        this.status = status;
    }

    public String getStatus() 
    {
        return status;
    }
    public void setCreatTime(Date creatTime) 
    {
        this.creatTime = creatTime;
    }

    public Date getCreatTime() 
    {
        return creatTime;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("userId", getUserId())
            .append("friendId", getFriendId())
            .append("status", getStatus())
            .append("creatTime", getCreatTime())
            .toString();
    }
}
