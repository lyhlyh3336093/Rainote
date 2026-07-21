package com.ruoyi.system.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import java.util.Date;

/**
 * friend对象 note_friend
 * 
 * @author liuyanghe
 * @date 2024-08-17
 */
public class NoteFriendVo extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 用户id */
    @Excel(name = "用户id")
    private Long userId;

    /** 好友id */
    @Excel(name = "好友id")
    private Long friendId;

    /** 状态（0：好友，1：拉黑） */
    @Excel(name = "状态", readConverterExp = "0=：好友，1：拉黑")
    private String status;


    /** 是否在线（0：在线，1：离线） */
    @Excel(name = "是否在线", readConverterExp = "0=：在线，1：离线")
    private String ifOnline;


    /** 消息内容 */
    @Excel(name = "消息内容")
    private String message;


    /** 用户名 */
    @Excel(name = "用户名")
    private String userName;

    /** 添加好友时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "添加好友时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date creatTime;


    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
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


    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getIfOnline() {
        return ifOnline;
    }

    public void setIfOnline(String ifOnline) {
        this.ifOnline = ifOnline;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("userId", getUserId())
            .append("friendId", getFriendId())
            .append("status", getStatus())
            .append("creatTime", getCreatTime())
            .append("message", getMessage())
            .append("userName", getUserName())
            .append("ifOnline",getIfOnline())
            .toString();
    }
}
