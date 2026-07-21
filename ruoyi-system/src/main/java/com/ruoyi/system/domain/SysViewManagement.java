package com.ruoyi.system.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 视图管理对象 sys_view_management
 * 
 * @author liuyanghe
 * @date 2025-08-21
 */
public class SysViewManagement extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 视图ID */
    private Long id;

    /** 连接ID */
    @Excel(name = "连接ID")
    private Long connectionId;

    /** 视图名称 */
    @Excel(name = "视图名称")
    private String viewName;

    /** 视图定义SQL */
    @Excel(name = "视图定义SQL")
    private String viewDefinition;

    /** 视图描述 */
    @Excel(name = "视图描述")
    private String description;

    /** 分类ID */
    @Excel(name = "分类ID")
    private Long categoryId;

    /** 是否有效(0-无效,1-有效) */
    @Excel(name = "是否有效(0-无效,1-有效)")
    private Integer isValid;

    /** 最后验证时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "最后验证时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date lastValidated;

    /** 验证错误信息 */
    @Excel(name = "验证错误信息")
    private String validationError;

    /** 依赖关系 */
    @Excel(name = "依赖关系")
    private String dependencies;

    /** 版本号 */
    @Excel(name = "版本号")
    private Long version;

    /** 是否系统视图(0-用户,1-系统) */
    @Excel(name = "是否系统视图(0-用户,1-系统)")
    private Integer isSystem;

    /** 标签(逗号分隔) */
    @Excel(name = "标签(逗号分隔)")
    private String tags;

    /** 访问级别(0-私有,1-部门,2-公开) */
    @Excel(name = "访问级别(0-私有,1-部门,2-公开)")
    private Integer accessLevel;

    /** 创建用户ID */
    @Excel(name = "创建用户ID")
    private Long userId;

    /** 部门ID */
    @Excel(name = "部门ID")
    private Long deptId;

    /** 删除标志(0-存在,1-删除) */
    private Integer delFlag;

    /** 扩展属性(JSON格式) */
    @Excel(name = "扩展属性(JSON格式)")
    private String extendedProperties;

    /** 使用次数统计 */
    @Excel(name = "使用次数统计")
    private Long usageCount;

    /** 最后使用时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "最后使用时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date lastUsed;

    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }
    public void setConnectionId(Long connectionId) 
    {
        this.connectionId = connectionId;
    }

    public Long getConnectionId() 
    {
        return connectionId;
    }
    public void setViewName(String viewName) 
    {
        this.viewName = viewName;
    }

    public String getViewName() 
    {
        return viewName;
    }
    public void setViewDefinition(String viewDefinition) 
    {
        this.viewDefinition = viewDefinition;
    }

    public String getViewDefinition() 
    {
        return viewDefinition;
    }
    public void setDescription(String description) 
    {
        this.description = description;
    }

    public String getDescription() 
    {
        return description;
    }
    public void setCategoryId(Long categoryId) 
    {
        this.categoryId = categoryId;
    }

    public Long getCategoryId() 
    {
        return categoryId;
    }
    public void setIsValid(Integer isValid) 
    {
        this.isValid = isValid;
    }

    public Integer getIsValid() 
    {
        return isValid;
    }
    public void setLastValidated(Date lastValidated) 
    {
        this.lastValidated = lastValidated;
    }

    public Date getLastValidated() 
    {
        return lastValidated;
    }
    public void setValidationError(String validationError) 
    {
        this.validationError = validationError;
    }

    public String getValidationError() 
    {
        return validationError;
    }
    public void setDependencies(String dependencies) 
    {
        this.dependencies = dependencies;
    }

    public String getDependencies() 
    {
        return dependencies;
    }
    public void setVersion(Long version) 
    {
        this.version = version;
    }

    public Long getVersion() 
    {
        return version;
    }
    public void setIsSystem(Integer isSystem) 
    {
        this.isSystem = isSystem;
    }

    public Integer getIsSystem() 
    {
        return isSystem;
    }
    public void setTags(String tags) 
    {
        this.tags = tags;
    }

    public String getTags() 
    {
        return tags;
    }
    public void setAccessLevel(Integer accessLevel) 
    {
        this.accessLevel = accessLevel;
    }

    public Integer getAccessLevel() 
    {
        return accessLevel;
    }
    public void setUserId(Long userId) 
    {
        this.userId = userId;
    }

    public Long getUserId() 
    {
        return userId;
    }
    public void setDeptId(Long deptId) 
    {
        this.deptId = deptId;
    }

    public Long getDeptId() 
    {
        return deptId;
    }
    public void setDelFlag(Integer delFlag) 
    {
        this.delFlag = delFlag;
    }

    public Integer getDelFlag() 
    {
        return delFlag;
    }
    public void setExtendedProperties(String extendedProperties) 
    {
        this.extendedProperties = extendedProperties;
    }

    public String getExtendedProperties() 
    {
        return extendedProperties;
    }
    public void setUsageCount(Long usageCount) 
    {
        this.usageCount = usageCount;
    }

    public Long getUsageCount() 
    {
        return usageCount;
    }
    public void setLastUsed(Date lastUsed) 
    {
        this.lastUsed = lastUsed;
    }

    public Date getLastUsed() 
    {
        return lastUsed;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("connectionId", getConnectionId())
            .append("viewName", getViewName())
            .append("viewDefinition", getViewDefinition())
            .append("description", getDescription())
            .append("categoryId", getCategoryId())
            .append("isValid", getIsValid())
            .append("lastValidated", getLastValidated())
            .append("validationError", getValidationError())
            .append("dependencies", getDependencies())
            .append("version", getVersion())
            .append("isSystem", getIsSystem())
            .append("tags", getTags())
            .append("accessLevel", getAccessLevel())
            .append("userId", getUserId())
            .append("deptId", getDeptId())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .append("delFlag", getDelFlag())
            .append("extendedProperties", getExtendedProperties())
            .append("usageCount", getUsageCount())
            .append("lastUsed", getLastUsed())
            .toString();
    }
}
