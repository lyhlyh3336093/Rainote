package com.ruoyi.system.service.impl;

import java.util.List;

import com.ruoyi.common.core.domain.entity.SysUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.system.mapper.NoteTaskMapper;
import com.ruoyi.system.domain.NoteTask;
import com.ruoyi.system.service.INoteTaskService;

/**
 * 待办事项Service业务层处理
 * 
 * @author liuyanghe
 * @date 2024-06-27
 */
@Service
public class NoteTaskServiceImpl implements INoteTaskService 
{
    @Autowired
    private NoteTaskMapper noteTaskMapper;

    /**
     * 查询待办事项
     * 
     * @param id 待办事项主键
     * @return 待办事项
     */
    @Override
    public NoteTask selectNoteTaskById(Long id)
    {
        return noteTaskMapper.selectNoteTaskById(id);
    }

    /**
     * 查询待办事项列表
     * 
     * @param noteTask 待办事项
     * @return 待办事项
     */
    @Override
    public List<NoteTask> selectNoteTaskList(NoteTask noteTask)
    {
        return noteTaskMapper.selectNoteTaskList(noteTask);
    }

    @Override
    public List<NoteTask> selectNoteEndTaskList() {
        return noteTaskMapper.getAllEndTasks();
    }
    @Override
    public List<NoteTask> getAllTasks() {
        return noteTaskMapper.getAllTasks();
    }

    /**
     * 新增待办事项
     * 
     * @param noteTask 待办事项
     * @return 结果
     */
    @Override
    public int insertNoteTask(NoteTask noteTask)
    {
        //添加默认项目
        noteTask.setDelFlag(0L);
        return noteTaskMapper.insertNoteTask(noteTask);
    }

    /**
     * 修改待办事项
     * 
     * @param noteTask 待办事项
     * @return 结果
     */
    @Override
    public int updateNoteTask(NoteTask noteTask)
    {
        return noteTaskMapper.updateNoteTask(noteTask);
    }

    /**
     * 批量删除待办事项
     * 
     * @param ids 需要删除的待办事项主键
     * @return 结果
     */
    @Override
    public int deleteNoteTaskByIds(String[] ids)
    {
        return noteTaskMapper.deleteNoteTaskByIds(ids);
    }

    /**
     * 删除待办事项信息
     * 
     * @param id 待办事项主键
     * @return 结果
     */
    @Override
    public int deleteNoteTaskById(Long id)
    {
        return noteTaskMapper.deleteNoteTaskById(id);
    }



    /**
     * 修改待办事项
     *
     * @param noteTask 待办事项
     * @return 结果
     */
    @Override
    public int receiveNoteTask(NoteTask noteTask) {
        return noteTaskMapper.receiveNoteTask(noteTask);
    }

    @Override
    public List<NoteTask> selectTaskbar(NoteTask noteTask) {
        return noteTaskMapper.selectTaskbar(noteTask);
    }

    @Override
    public List<NoteTask> selectReceiveTaskList(NoteTask noteTask) {
        return noteTaskMapper.selectReceiveTaskList(noteTask);
    }

    @Override
    public List<SysUser> selectTeamMembers(NoteTask noteTask) {
        return null;
    }


}
