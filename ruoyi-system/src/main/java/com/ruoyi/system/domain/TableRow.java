package com.ruoyi.system.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import java.util.Date;

/**
 * 数据库表的列对象
 * 
 * @author liuyanghe
 * @date 2025-11-03
 */
public class TableRow extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 列ID */
    private Long id;


    /** 列名称 */
    @Excel(name = "列名称")
    private String rowName;

    /** 数据库表名称 */
    @Excel(name = "数据库表名称")
    private String tableName;

    /** 列类型 */
    @Excel(name = "列类型")
    private String type;

    /** 长度限制 */
    @Excel(name = "长度限制")
    private Long rowLimit;

    /** 删除标志(0-存在,1-删除) */
    private Integer delFlag;

    /** 是否为外键 */
    @Excel(name = "是否为外键")
    private String isKey;



    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public String getRowName() {
        return rowName;
    }

    public void setRowName(String rowName) {
        this.rowName = rowName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Long getRowLimit() {
        return rowLimit;
    }

    public void setRowLimit(Long rowLimit) {
        this.rowLimit = rowLimit;
    }

    public Integer getDelFlag() {
        return delFlag;
    }

    public void setDelFlag(Integer delFlag) {
        this.delFlag = delFlag;
    }

    public String getIsKey() {
        return isKey;
    }

    public void setIsKey(String isKey) {
        this.isKey = isKey;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("rowName",getRowName())
            .append("tableName",getTableName())
            .append("type",getType())
            .append("rowLimit",getRowLimit())
            .append("isKey",getIsKey())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .append("delFlag", getDelFlag())
            .toString();
    }
}
