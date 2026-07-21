package com.ruoyi.system.service.impl;


import java.util.List;



import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.system.mapper.NoteRoleDeptMapper;
import com.ruoyi.system.domain.NoteRoleDept;
import com.ruoyi.system.service.INoteRoleDeptService;

/**
 * 笔记角色和部门关联Service业务层处理
 * 
 * @author liuyanghe
 * @date 2023-09-15
 */
@Service
public class NoteRoleDeptServiceImpl implements INoteRoleDeptService 
{
    @Autowired
    private NoteRoleDeptMapper noteRoleDeptMapper;

    /**
     * 根据角色id查出角色所在得所有部门
     * 
     * @param roleId 笔记角色和部门关联主键
     * @return 笔记角色和部门关联
     */
    @Override
    public List<Long> selectNoteRoleDeptByRoleId(Long roleId)
    {
        return noteRoleDeptMapper.selectNoteRoleDeptByRoleId(roleId);
    }

    /**
     * 查询笔记角色和部门关联列表
     * 
     * @param noteRoleDept 笔记角色和部门关联
     * @return 笔记角色和部门关联
     */
    @Override
    public List<NoteRoleDept> selectNoteRoleDeptList(NoteRoleDept noteRoleDept)
    {
        return noteRoleDeptMapper.selectNoteRoleDeptList(noteRoleDept);
    }



    /**
     * 新增笔记角色和部门关联
     * 
     * @param noteRoleDept 笔记角色和部门关联
     * @return 结果
     */
    @Override
    public int insertNoteRoleDept(NoteRoleDept noteRoleDept)
    {
        return noteRoleDeptMapper.insertNoteRoleDept(noteRoleDept);
    }

    /**
     * 修改笔记角色和部门关联
     * 
     * @param noteRoleDept 笔记角色和部门关联
     * @return 结果
     */
    @Override
    public int updateNoteRoleDept(NoteRoleDept noteRoleDept)
    {
        return noteRoleDeptMapper.updateNoteRoleDept(noteRoleDept);
    }

    /**
     * 批量删除笔记角色和部门关联
     * 
     * @param roleIds 需要删除的笔记角色和部门关联主键
     * @return 结果
     */
    @Override
    public int deleteNoteRoleDeptByRoleIds(Long[] roleIds)
    {
        return noteRoleDeptMapper.deleteNoteRoleDeptByRoleIds(roleIds);
    }

    /**
     * 删除笔记角色和部门关联信息
     * 
     * @param roleId 笔记角色和部门关联主键
     * @return 结果
     */
    @Override
    public int deleteNoteRoleDeptByRoleId(Long roleId)
    {
        return noteRoleDeptMapper.deleteNoteRoleDeptByRoleId(roleId);
    }
}
