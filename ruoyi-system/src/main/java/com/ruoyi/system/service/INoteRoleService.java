package com.ruoyi.system.service;

import java.util.List;

import com.ruoyi.system.domain.NoteRole;

/**
 * 笔记角色Service接口
 * 
 * @author liuyanghe
 * @date 2023-08-26
 */
public interface INoteRoleService 
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
     * 根据用户id查询笔记角色列表
     *
     * @param noteRole 笔记角色
     * @param userId 用户id
     * @return 笔记角色集合
     */
    public List<NoteRole> selectNoteRoleListByUserId(NoteRole noteRole, Long userId);

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
     * 修改数据权限信息
     *
     * @param role 角色信息
     * @return 结果
     */
    public int authDataScope(NoteRole role);


    /**
     * 修改角色状态
     *
     * @param role 角色信息
     * @return 结果
     */
    public int updateRoleStatus(NoteRole role);


    /**
     * 查询所有角色
     *
     * @return 角色列表
     */
    public List<NoteRole> selectNoteRoleAll();


    /**
     * 批量删除笔记角色
     * 
     * @param ids 需要删除的笔记角色主键集合
     * @return 结果
     */
    public int deleteNoteRoleByIds(String[] ids);

    /**
     * 删除笔记角色信息
     * 
     * @param id 笔记角色主键
     * @return 结果
     */
    public int deleteNoteRoleById(Long id);

    /**
     * 校验角色名称是否唯一
     *
     * @param role 角色信息
     * @return 结果
     */
    public String checkNoteRoleNameUnique(NoteRole role);

    /**
     * 校验角色权限是否唯一
     *
     * @param role 角色信息
     * @return 结果
     */
    public String checkNoteRoleKeyUnique(NoteRole role);

    /**
     * 校验角色是否允许操作
     *
     * @param role 角色信息
     */
    public void checkRoleAllowed(NoteRole role);


    /**
     * 校验角色是否有数据权限
     *
     * @param roleId 角色id
     */
    public void checkRoleDataScope(Long roleId);


    /**
     * 取消授权用户角色
     *
     * @param userRole 用户和角色关联信息
     * @return 结果
     */
//    public int deleteAuthUser(NoteUserRole userRole);

    /**
     * 批量取消授权用户角色
     *
     * @param roleId 角色ID
     * @param userIds 需要取消授权的用户数据ID
     * @return 结果
     */
    public int deleteAuthUsers(Long roleId, Long[] userIds);

    /**
     * 批量选择授权用户角色
     *
     * @param roleId 角色ID
     * @param userIds 需要删除的用户数据ID
     * @return 结果
     */
    public int insertAuthUsers(Long roleId, Long[] userIds);


    /**
     * 通过角色ID查询角色使用数量
     *
     * @param roleId 角色ID
     * @return 结果
     */
    public int countNoteUserRoleByRoleId(Long roleId);

}
