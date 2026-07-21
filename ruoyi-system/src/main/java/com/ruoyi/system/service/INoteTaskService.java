package com.ruoyi.system.service;

import java.util.List;

import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.system.domain.NoteTask;

/**
 * 待办事项Service接口
 * 
 * @author liuyanghe
 * @date 2024-06-27
 */
public interface INoteTaskService 
{
    /**
     * 查询待办事项
     * 
     * @param id 待办事项主键
     * @return 待办事项
     */
    public NoteTask selectNoteTaskById(Long id);

    /**
     * 查询待办事项列表
     * 
     * @param noteTask 待办事项
     * @return 待办事项集合
     */
    public List<NoteTask> selectNoteTaskList(NoteTask noteTask);



    /**
     * 获取所有任务
     *
     * @return 待办事项集合
     */
    public List<NoteTask> getAllTasks();

    /**
     * 获取所有已完成任务
     *
     * @return 事项集合
     */
    public List<NoteTask> selectNoteEndTaskList();


    /**
     * 新增待办事项
     * 
     * @param noteTask 待办事项
     * @return 结果
     */
    public int insertNoteTask(NoteTask noteTask);

    /**
     * 修改待办事项
     * 
     * @param noteTask 待办事项
     * @return 结果
     */
    public int updateNoteTask(NoteTask noteTask);

    /**
     * 批量删除待办事项
     * 
     * @param ids 需要删除的待办事项主键集合
     * @return 结果
     */
    public int deleteNoteTaskByIds(String[] ids);

    /**
     * 删除待办事项信息
     * 
     * @param id 待办事项主键
     * @return 结果
     */
    public int deleteNoteTaskById(Long id);

    /**
     * 接收任务
     *
     * @param noteTask 待办事项
     * @return 结果
     */
    public int receiveNoteTask(NoteTask noteTask);


    /**
     * 查询任务栏
     *
     * @param noteTask 待办事项
     * @return 待办事项集合
     */
    List<NoteTask> selectTaskbar(NoteTask noteTask);



    /**
     * 查询已接收任务列表
     *
     * @param noteTask 待办事项
     * @return 已接收任务列表
     */
    List<NoteTask> selectReceiveTaskList(NoteTask noteTask);

    List<SysUser> selectTeamMembers(NoteTask noteTask);
}
