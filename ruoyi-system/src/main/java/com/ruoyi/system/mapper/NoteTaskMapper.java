package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.NoteTask;

/**
 * 待办事项Mapper接口
 * 
 * @author liuyanghe
 * @date 2024-06-27
 */
public interface NoteTaskMapper 
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
     * 获取所有待办事项
     *
     * @return 待办事项集合
     */
    public List<NoteTask> getAllTasks();

    /**
     * 获取所有已完成事项
     *
     * @return 事项集合
     */
    public List<NoteTask> getAllEndTasks();

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
     * 删除待办事项
     * 
     * @param id 待办事项主键
     * @return 结果
     */
    public int deleteNoteTaskById(Long id);

    /**
     * 批量删除待办事项
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteNoteTaskByIds(String[] ids);


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
     * 查询已接收任务
     *
     * @param noteTask 待办事项
     * @return 待办事项集合
     */
    List<NoteTask> selectReceiveTaskList(NoteTask noteTask);
}
