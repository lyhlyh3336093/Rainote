package com.ruoyi.system.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 元组对象 note_tuple
 * 
 * @author liuyanghe
 * @date 2023-05-07
 */
public class NoteTuple extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 元组id */
    private Long id;

    /** 双向关联关系元组 */
    @Excel(name = "双向关联关系元组")
    private String tuple;

    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }
    public void setTuple(String tuple) 
    {
        this.tuple = tuple;
    }

    public String getTuple() 
    {
        return tuple;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("tuple", getTuple())
            .toString();
    }
}
