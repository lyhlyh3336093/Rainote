package com.ruoyi.system.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * userInfo对象 user_info
 * 
 * @author liuyanghe
 * @date 2026-01-13
 */
public class UserInfo extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /**  */
    private Long id;

    /** 姓名 */
    @Excel(name = "姓名")
    private String userName;

    /** 年龄 */
    @Excel(name = "年龄")
    private Long age;

    /** 3D扫描信息地址 */
    @Excel(name = "3D扫描信息地址")
    private String threedDataPath;

    /** 人物技能证书 */
    @Excel(name = "人物技能证书")
    private String skill;

    /** 标签 */
    @Excel(name = "标签")
    private String tag;

    /** 知识图谱 */
    @Excel(name = "知识图谱")
    private String knowledgeMap;

    /** 待办事项 */
    @Excel(name = "待办事项")
    private String todolist;

    /** 物品 */
    @Excel(name = "物品")
    private String item;

    /** 通讯录 */
    @Excel(name = "通讯录")
    private String directory;

    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }
    public void setUserName(String userName) 
    {
        this.userName = userName;
    }

    public String getUserName() 
    {
        return userName;
    }
    public void setAge(Long age) 
    {
        this.age = age;
    }

    public Long getAge() 
    {
        return age;
    }
    public void setThreedDataPath(String threedDataPath) 
    {
        this.threedDataPath = threedDataPath;
    }

    public String getThreedDataPath() 
    {
        return threedDataPath;
    }
    public void setSkill(String skill) 
    {
        this.skill = skill;
    }

    public String getSkill() 
    {
        return skill;
    }
    public void setTag(String tag) 
    {
        this.tag = tag;
    }

    public String getTag() 
    {
        return tag;
    }
    public void setKnowledgeMap(String knowledgeMap) 
    {
        this.knowledgeMap = knowledgeMap;
    }

    public String getKnowledgeMap() 
    {
        return knowledgeMap;
    }
    public void setTodolist(String todolist) 
    {
        this.todolist = todolist;
    }

    public String getTodolist() 
    {
        return todolist;
    }
    public void setItem(String item) 
    {
        this.item = item;
    }

    public String getItem() 
    {
        return item;
    }
    public void setDirectory(String directory) 
    {
        this.directory = directory;
    }

    public String getDirectory() 
    {
        return directory;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("userName", getUserName())
            .append("age", getAge())
            .append("threedDataPath", getThreedDataPath())
            .append("skill", getSkill())
            .append("tag", getTag())
            .append("knowledgeMap", getKnowledgeMap())
            .append("todolist", getTodolist())
            .append("item", getItem())
            .append("directory", getDirectory())
            .append("remark", getRemark())
            .toString();
    }
}
