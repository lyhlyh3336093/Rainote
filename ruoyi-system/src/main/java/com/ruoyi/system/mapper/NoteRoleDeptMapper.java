package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.NoteRoleDept;

/**
 * 笔记角色和部门关联Mapper接口
 * 
 * @author liuyanghe
 * @date 2023-09-15
 */
public interface NoteRoleDeptMapper 
{
    /**
     * 根据角色id查出角色所在得所有部门
     * 
     * @param roleId 笔记角色和部门关联主键
     * @return 笔记角色和部门关联
     */
    public List<Long> selectNoteRoleDeptByRoleId(Long roleId);

    /**
     * 查询笔记角色和部门关联列表
     * 
     * @param noteRoleDept 笔记角色和部门关联
     * @return 笔记角色和部门关联集合
     */
    public List<NoteRoleDept> selectNoteRoleDeptList(NoteRoleDept noteRoleDept);

    /**
     * 新增笔记角色和部门关联
     * 
     * @param noteRoleDept 笔记角色和部门关联
     * @return 结果
     */
    public int insertNoteRoleDept(NoteRoleDept noteRoleDept);

    /**
     * 批量新增笔记角色和部门关联
     *
     * @param list 笔记角色和部门关联集合
     * @return 结果
     */
    public int batchNoteRoleDept(List<NoteRoleDept> list);

    /**
     * 修改笔记角色和部门关联
     * 
     * @param noteRoleDept 笔记角色和部门关联
     * @return 结果
     */
    public int updateNoteRoleDept(NoteRoleDept noteRoleDept);

    /**
     * 删除笔记角色和部门关联
     * 
     * @param roleId 笔记角色和部门关联主键
     * @return 结果
     */
    public int deleteNoteRoleDeptByRoleId(Long roleId);

    /**
     * 批量删除笔记角色和部门关联
     * 
     * @param roleIds 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteNoteRoleDeptByRoleIds(Long[] roleIds);

    /**
     * 批量删除笔记角色和部门关联
     *
     * @param roleId 需要删除的角色主键
     * @return 结果
     */
    public int deleteRoleDeptByRoleId(Long roleId);
}
