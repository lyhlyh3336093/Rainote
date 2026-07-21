package com.ruoyi.system.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ruoyi.common.core.domain.entity.SysDept;

import java.io.Serializable;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Treeselect树结构实体类
 * 
 * @author ruoyi
 */
public class NoteTreeSelect implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 节点ID */
    private Long id;

    /** 节点名称 */
    private String label;

    /** 子节点 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<NoteTreeSelect> children;

    public NoteTreeSelect()
    {

    }

    public NoteTreeSelect(SysDept dept)
    {
        this.id = dept.getDeptId();
        this.label = dept.getDeptName();
        this.children = dept.getChildren().stream().map(NoteTreeSelect::new).collect(Collectors.toList());
    }

    public NoteTreeSelect(NoteNote menu)
    {
        this.id = menu.getId();
        this.label = menu.getTitle();
        this.children = menu.getChildren().stream().map(NoteTreeSelect::new).collect(Collectors.toList());
    }

    public Long getId()
    {
        return id;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public String getLabel()
    {
        return label;
    }

    public void setLabel(String label)
    {
        this.label = label;
    }

    public List<NoteTreeSelect> getChildren()
    {
        return children;
    }

    public void setChildren(List<NoteTreeSelect> children)
    {
        this.children = children;
    }
}
