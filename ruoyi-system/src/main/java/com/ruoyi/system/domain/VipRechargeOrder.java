package com.ruoyi.system.domain;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * VIP充值申请订单对象 vip_recharge_order
 * <p>
 * 状态：0待审核 / 1已开通 / 2已驳回（含管理员留言）。
 * pending 唯一待审标记（待审核=1，已审=NULL），配合 UNIQUE(user_id, pending)
 * 实现"同一用户仅一条待审核"的数据库级约束。
 *
 * @author ruoyi
 * @date 2026-11-04
 */
public class VipRechargeOrder extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 状态：待审核 */
    public static final String STATUS_PENDING = "0";
    /** 状态：已开通 */
    public static final String STATUS_APPROVED = "1";
    /** 状态：已驳回 */
    public static final String STATUS_REJECTED = "2";

    /** 订单ID */
    private Long id;

    /** 申请用户ID */
    private Long userId;

    /** 申请用户名（联表展示） */
    private String userName;

    /** 档位ID */
    private Long tierId;

    /** 档位名称（联表展示） */
    private String tierName;

    /** 档位天数（联表展示） */
    private Integer tierDays;

    /** 支付金额（下单时档位价格快照） */
    private BigDecimal amount;

    /** 支付渠道（wechat/alipay） */
    private String payChannel;

    /** 支付凭证图片路径（私有目录相对路径） */
    private String proofUrl;

    /** 用户备注（付款尾号/时间等） */
    private String userRemark;

    /** 状态（0待审核 1已开通 2已驳回） */
    private String status;

    /** 唯一待审标记（待审核=1，已审=NULL） */
    private Integer pending;

    /** 管理员留言（驳回原因） */
    private String adminRemark;

    /** 审核人 */
    private String auditedBy;

    /** 审核时间 */
    private Date auditTime;

    /** 查询过滤：起始时间 */
    private Date beginTime;

    /** 查询过滤：结束时间 */
    private Date endTime;

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

    public void setUserName(String userName)
    {
        this.userName = userName;
    }

    public String getUserName()
    {
        return userName;
    }

    public void setTierId(Long tierId)
    {
        this.tierId = tierId;
    }

    public Long getTierId()
    {
        return tierId;
    }

    public void setTierName(String tierName)
    {
        this.tierName = tierName;
    }

    public String getTierName()
    {
        return tierName;
    }

    public void setTierDays(Integer tierDays)
    {
        this.tierDays = tierDays;
    }

    public Integer getTierDays()
    {
        return tierDays;
    }

    public void setAmount(BigDecimal amount)
    {
        this.amount = amount;
    }

    public BigDecimal getAmount()
    {
        return amount;
    }

    public void setPayChannel(String payChannel)
    {
        this.payChannel = payChannel;
    }

    public String getPayChannel()
    {
        return payChannel;
    }

    public void setProofUrl(String proofUrl)
    {
        this.proofUrl = proofUrl;
    }

    public String getProofUrl()
    {
        return proofUrl;
    }

    public void setUserRemark(String userRemark)
    {
        this.userRemark = userRemark;
    }

    public String getUserRemark()
    {
        return userRemark;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getStatus()
    {
        return status;
    }

    public void setPending(Integer pending)
    {
        this.pending = pending;
    }

    public Integer getPending()
    {
        return pending;
    }

    public void setAdminRemark(String adminRemark)
    {
        this.adminRemark = adminRemark;
    }

    public String getAdminRemark()
    {
        return adminRemark;
    }

    public void setAuditedBy(String auditedBy)
    {
        this.auditedBy = auditedBy;
    }

    public String getAuditedBy()
    {
        return auditedBy;
    }

    public void setAuditTime(Date auditTime)
    {
        this.auditTime = auditTime;
    }

    public Date getAuditTime()
    {
        return auditTime;
    }

    public void setBeginTime(Date beginTime)
    {
        this.beginTime = beginTime;
    }

    public Date getBeginTime()
    {
        return beginTime;
    }

    public void setEndTime(Date endTime)
    {
        this.endTime = endTime;
    }

    public Date getEndTime()
    {
        return endTime;
    }

    @Override
    public String toString()
    {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("userId", getUserId())
            .append("tierId", getTierId())
            .append("amount", getAmount())
            .append("payChannel", getPayChannel())
            .append("proofUrl", getProofUrl())
            .append("status", getStatus())
            .append("adminRemark", getAdminRemark())
            .append("createTime", getCreateTime())
            .toString();
    }
}
