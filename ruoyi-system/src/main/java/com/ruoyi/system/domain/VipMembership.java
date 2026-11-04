package com.ruoyi.system.domain;

import java.io.Serializable;
import java.util.Date;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

/**
 * VIP会员身份对象 vip_membership
 * <p>
 * 每用户一行，expire_time 即 VIP 到期时间；到期判断惰性比较（now >= expire 即普通用户），
 * 无需定时降级任务。
 *
 * @author ruoyi
 * @date 2026-11-04
 */
public class VipMembership implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 用户ID（sys_user.user_id） */
    private Long userId;

    /** 用户账号（联表展示） */
    private String userName;

    /** 用户昵称（联表展示） */
    private String nickName;

    /** VIP到期时间 */
    private Date expireTime;

    /** 更新者 */
    private String updateBy;

    /** 更新时间 */
    private Date updateTime;

    public void setUserId(Long userId)
    {
        this.userId = userId;
    }

    public Long getUserId()
    {
        return userId;
    }

    public void setUserName(String userName)
    {
        this.userName = userName;
    }

    public String getUserName()
    {
        return userName;
    }

    public void setNickName(String nickName)
    {
        this.nickName = nickName;
    }

    public String getNickName()
    {
        return nickName;
    }

    public void setExpireTime(Date expireTime)
    {
        this.expireTime = expireTime;
    }

    public Date getExpireTime()
    {
        return expireTime;
    }

    public void setUpdateBy(String updateBy)
    {
        this.updateBy = updateBy;
    }

    public String getUpdateBy()
    {
        return updateBy;
    }

    public void setUpdateTime(Date updateTime)
    {
        this.updateTime = updateTime;
    }

    public Date getUpdateTime()
    {
        return updateTime;
    }

    @Override
    public String toString()
    {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("userId", getUserId())
            .append("expireTime", getExpireTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}
