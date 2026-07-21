package com.ruoyi.system.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 应用对象 cloud_app
 * 
 * @author liuyanghe
 * @date 2025-03-09
 */
public class CloudApp extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 应用名称 */
    @Excel(name = "应用名称")
    private String name;

    /** 域名 */
    @Excel(name = "域名")
    private String link;

    /** 描述 */
    @Excel(name = "描述")
    private String description;

    /** 排序 */
    @Excel(name = "排序")
    private Long sort;

    /** 权重 */
    @Excel(name = "权重")
    private Long weight;

    /** 创建者 */
    @Excel(name = "创建者")
    private String creater;

    /** 应用所属公司 */
    @Excel(name = "应用所属公司")
    private String company;

    /** logo图 */
    @Excel(name = "logo图")
    private String logo;


    /** 删除标识 */
    @Excel(name = "删除标识")
    private Long delFlag;

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
    public void setLink(String link) 
    {
        this.link = link;
    }

    public String getLink() 
    {
        return link;
    }
    public void setDescription(String description)
    {
        this.description = description;
    }

    public String getDescription()
    {
        return description;
    }
    public void setSort(Long sort) 
    {
        this.sort = sort;
    }

    public Long getSort() 
    {
        return sort;
    }
    public void setWeight(Long weight) 
    {
        this.weight = weight;
    }

    public Long getWeight() 
    {
        return weight;
    }
    public void setCreater(String creater) 
    {
        this.creater = creater;
    }

    public String getCreater() 
    {
        return creater;
    }
    public void setCompany(String company) 
    {
        this.company = company;
    }

    public String getCompany() 
    {
        return company;
    }
    public void setLogo(String logo) 
    {
        this.logo = logo;
    }

    public String getLogo() 
    {
        return logo;
    }

    public Long getDelFlag() {
        return delFlag;
    }

    public void setDelFlag(Long delFlag) {
        this.delFlag = delFlag;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("name", getName())
            .append("link", getLink())
            .append("description", getDescription())
            .append("sort", getSort())
            .append("weight", getWeight())
            .append("createTime", getCreateTime())
            .append("creater", getCreater())
            .append("company", getCompany())
            .append("logo", getLogo())
            .toString();
    }
}
