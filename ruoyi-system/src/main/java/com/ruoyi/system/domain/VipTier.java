package com.ruoyi.system.domain;

import java.io.Serializable;
import java.math.BigDecimal;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

/**
 * VIP时长档位对象 vip_tier
 * <p>
 * 单档 VIP 按时长定价（月/季/年），权益完全相同；管理端 CRUD。
 *
 * @author ruoyi
 * @date 2026-11-04
 */
public class VipTier implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 状态：启用 */
    public static final String STATUS_ENABLED = "0";
    /** 状态：停用 */
    public static final String STATUS_DISABLED = "1";

    /** 档位ID */
    private Long id;

    /** 档位名称（如月卡/季卡/年卡） */
    private String name;

    /** 时长（天） */
    private Integer days;

    /** 价格（元） */
    private BigDecimal price;

    /** 显示顺序 */
    private Integer sort;

    /** 状态（0启用 1停用） */
    private String status;

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

    public void setDays(Integer days)
    {
        this.days = days;
    }

    public Integer getDays()
    {
        return days;
    }

    public void setPrice(BigDecimal price)
    {
        this.price = price;
    }

    public BigDecimal getPrice()
    {
        return price;
    }

    public void setSort(Integer sort)
    {
        this.sort = sort;
    }

    public Integer getSort()
    {
        return sort;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getStatus()
    {
        return status;
    }

    @Override
    public String toString()
    {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("name", getName())
            .append("days", getDays())
            .append("price", getPrice())
            .append("sort", getSort())
            .append("status", getStatus())
            .toString();
    }
}
