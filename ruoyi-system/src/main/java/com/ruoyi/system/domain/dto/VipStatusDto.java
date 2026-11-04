package com.ruoyi.system.domain.dto;

import java.io.Serializable;
import java.util.Date;

/**
 * VIP 状态与配额信息（VipService.getStatus 返回值）
 * <p>
 * admin 豁免态：admin=true，限额数值按 VIP 口径返回（豁免即无限制，前端展示用）。
 *
 * @author ruoyi
 * @date 2026-11-04
 */
public class VipStatusDto implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 是否管理员（豁免所有限额） */
    private boolean admin;

    /** 是否 VIP（expire > now） */
    private boolean vip;

    /** VIP 到期时间（非会员为 null） */
    private Date expireTime;

    /** 笔记数上限（-1=不限） */
    private long noteLimit;

    /** 单文件上传上限（字节） */
    private long fileMaxBytes;

    /** 附件总容量上限（字节） */
    private long totalMaxBytes;

    /** AI 对话每日次数上限 */
    private int aiDailyLimit;

    /** 回收站保留天数 */
    private int recycleDays;

    public boolean isAdmin()
    {
        return admin;
    }

    public void setAdmin(boolean admin)
    {
        this.admin = admin;
    }

    public boolean isVip()
    {
        return vip;
    }

    public void setVip(boolean vip)
    {
        this.vip = vip;
    }

    public Date getExpireTime()
    {
        return expireTime;
    }

    public void setExpireTime(Date expireTime)
    {
        this.expireTime = expireTime;
    }

    public long getNoteLimit()
    {
        return noteLimit;
    }

    public void setNoteLimit(long noteLimit)
    {
        this.noteLimit = noteLimit;
    }

    public long getFileMaxBytes()
    {
        return fileMaxBytes;
    }

    public void setFileMaxBytes(long fileMaxBytes)
    {
        this.fileMaxBytes = fileMaxBytes;
    }

    public long getTotalMaxBytes()
    {
        return totalMaxBytes;
    }

    public void setTotalMaxBytes(long totalMaxBytes)
    {
        this.totalMaxBytes = totalMaxBytes;
    }

    public int getAiDailyLimit()
    {
        return aiDailyLimit;
    }

    public void setAiDailyLimit(int aiDailyLimit)
    {
        this.aiDailyLimit = aiDailyLimit;
    }

    public int getRecycleDays()
    {
        return recycleDays;
    }

    public void setRecycleDays(int recycleDays)
    {
        this.recycleDays = recycleDays;
    }
}
