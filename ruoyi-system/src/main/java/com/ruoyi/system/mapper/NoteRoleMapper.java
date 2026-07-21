package com.ruoyi.system.mapper;

import java.util.List;

import com.ruoyi.system.domain.NoteRole;

/**
 * 笔记角色Mapper接口
 * 
 * @author liuyanghe
 * @date 2023-08-26
 */
public interface NoteRoleMapper 
{
    /**
     * 查询笔记角色
     * 
     * @param id 笔记角色主键
     * @return 笔记角色
     */
    public NoteRole selectNoteRoleById(Long id);

    /**
     * 查询笔记角色列表
     * 
     * @param noteRole 笔记角色
     * @return 笔记角色集合
     */
    public List<NoteRole> selectNoteRoleList(NoteRole noteRole);


    /**
     * 根据用户id查询笔记角色权限列表
     *
     * @param userId 用户id
     * @return 笔记角色集合
     */
    public List<NoteRole> selectNoteRolePermissionByUserId(Long userId);


    /**
     * 新增笔记角色
     * 
     * @param noteRole 笔记角色
     * @return 结果
     */
    public int insertNoteRole(NoteRole noteRole);

    /**
     * 修改笔记角色
     * 
     * @param noteRole 笔记角色
     * @return 结果
     */
    public int updateNoteRole(NoteRole noteRole);


    /**
     * 查询笔记角色列表
     *
     * @param noteRole 笔记角色
     * @return 结果
     */
    public List<NoteRole> selectNoteRoleAll(NoteRole noteRole);


    /**
     * 校验角色名称是否唯一
     *
     * @param roleName 角色名称
     * @return 角色信息
     */
    public NoteRole checkNoteRoleNameUnique(String roleName);

    /**
     * 校验角色权限是否唯一
     *
     * @param roleKey 角色权限
     * @return 角色信息
     */
    public NoteRole checkNoteRoleKeyUnique(String roleKey);

    /**
     * 删除笔记角色
     * 
     * @param id 笔记角色主键
     * @return 结果
     */
    public int deleteNoteRoleById(Long id);

    /**
     * 批量删除笔记角色
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteNoteRoleByIds(String[] ids);
}
