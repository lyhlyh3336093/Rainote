package com.ruoyi.system.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 待办事项对象 note_task
 * 
 * @author liuyanghe
 * @date 2024-06-27
 */
public class NoteTask extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 任务id */
    private Long id;

    /** 任务名称 */
    @Excel(name = "任务名称")
    private String name;

    /** 任务详情 */
    @Excel(name = "任务详情")
    private String description;

    /** 开始时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "开始时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date startTime;

    /** 截止时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "截止时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date endTime;

    /** 状态 */
    @Excel(name = "状态")
    private String status;

    /** 发起人 */
    @Excel(name = "发起人")
    private String leader;

    /** 成员id */
    @Excel(name = "成员id")
    private String teams;

    /** 提醒方式 */
    @Excel(name = "提醒方式")
    private String remindType;

    /** 重要程度 */
    @Excel(name = "重要程度")
    private String importentLevel;

    /** 紧急程度 */
    @Excel(name = "紧急程度")
    private String urgentLevel;

    /** 重复方式 */
    @Excel(name = "重复方式")
    private String repeatType;

    /** 奖励 */
    @Excel(name = "奖励")
    private String rewards;

    /** 惩罚 */
    @Excel(name = "惩罚")
    private String punishment;

    /** 相关属性 */
    @Excel(name = "相关属性")
    private String relevantAttribute;

    /** 子任务id集合 */
    @Excel(name = "子任务id集合")
    private String childrenTasks;

    /** 父任务 */
    @Excel(name = "父任务")
    private String parentTask;

    /** 任务组ID */
    private Long groupId;

    /**  接收人ID */
    private Long receiverId;

    /** 任务类型
     * 任务类型：0，个人发送个人接收，1，个人发送群组接收，2，任务栏类型， */
    @Excel(name = "任务类型")
    private String taskType;

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
    public void setDescription(String description) 
    {
        this.description = description;
    }

    public String getDescription() 
    {
        return description;
    }
    public void setStartTime(Date startTime) 
    {
        this.startTime = startTime;
    }

    public Date getStartTime() 
    {
        return startTime;
    }
    public void setEndTime(Date endTime) 
    {
        this.endTime = endTime;
    }

    public Date getEndTime() 
    {
        return endTime;
    }
    public void setStatus(String status) 
    {
        this.status = status;
    }

    public String getStatus() 
    {
        return status;
    }
    public void setLeader(String leader) 
    {
        this.leader = leader;
    }

    public String getLeader() 
    {
        return leader;
    }
    public void setTeams(String teams) 
    {
        this.teams = teams;
    }

    public String getTeams() 
    {
        return teams;
    }
    public void setRemindType(String remindType) 
    {
        this.remindType = remindType;
    }

    public String getRemindType() 
    {
        return remindType;
    }
    public void setImportentLevel(String importentLevel) 
    {
        this.importentLevel = importentLevel;
    }

    public String getImportentLevel() 
    {
        return importentLevel;
    }
    public void setUrgentLevel(String urgentLevel) 
    {
        this.urgentLevel = urgentLevel;
    }

    public String getUrgentLevel() 
    {
        return urgentLevel;
    }
    public void setRepeatType(String repeatType) 
    {
        this.repeatType = repeatType;
    }

    public String getRepeatType() 
    {
        return repeatType;
    }
    public void setRewards(String rewards) 
    {
        this.rewards = rewards;
    }

    public String getRewards() 
    {
        return rewards;
    }
    public void setPunishment(String punishment) 
    {
        this.punishment = punishment;
    }

    public String getPunishment() 
    {
        return punishment;
    }
    public void setRelevantAttribute(String relevantAttribute) 
    {
        this.relevantAttribute = relevantAttribute;
    }

    public String getRelevantAttribute() 
    {
        return relevantAttribute;
    }
    public void setChildrenTasks(String childrenTasks) 
    {
        this.childrenTasks = childrenTasks;
    }

    public String getChildrenTasks() 
    {
        return childrenTasks;
    }
    public void setParentTask(String parentTask) 
    {
        this.parentTask = parentTask;
    }

    public String getParentTask() 
    {
        return parentTask;
    }


    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public Long getReceiverId() {
        return receiverId;
    }

    public void setReceiverId(Long receiverId) {
        this.receiverId = receiverId;
    }

    public String getTaskType() {
        return taskType;
    }

    public void setTaskType(String taskType) {
        this.taskType = taskType;
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
            .append("description", getDescription())
            .append("startTime", getStartTime())
            .append("endTime", getEndTime())
            .append("status", getStatus())
            .append("leader", getLeader())
            .append("teams", getTeams())
            .append("remindType", getRemindType())
            .append("importentLevel", getImportentLevel())
            .append("urgentLevel", getUrgentLevel())
            .append("repeatType", getRepeatType())
            .append("rewards", getRewards())
            .append("punishment", getPunishment())
            .append("relevantAttribute", getRelevantAttribute())
            .append("childrenTasks", getChildrenTasks())
            .append("parentTask", getParentTask())
            .append("groupId", getGroupId())
            .append("receiverId", getReceiverId())
            .append("taskType", getTaskType())
            .toString();
    }
}
