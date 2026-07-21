package com.ruoyi.system.service;

import java.util.List;
import java.util.Set;

import com.ruoyi.system.domain.NoteUserRole;

/**
 * 用户和笔记系统角色关联Service接口
 * 
 * @author liuyanghe
 * @date 2023-08-29
 */
public interface INoteUserRoleService 
{
    /**
     * 查询用户和笔记系统角色关联
     * 
     * @param userId 用户和笔记系统角色关联主键
     * @return 用户和笔记系统角色关联
     */
    public NoteUserRole selectNoteUserRoleByUserId(Long userId);

    /**
     * 查询用户和笔记系统角色关联列表
     * 
     * @param noteUserRole 用户和笔记系统角色关联
     * @return 用户和笔记系统角色关联集合
     */
    public List<NoteUserRole> selectNoteUserRoleList(NoteUserRole noteUserRole);

    /**
     * 新增用户和笔记系统角色关联
     * 
     * @param noteUserRole 用户和笔记系统角色关联
     * @return 结果
     */
    public int insertNoteUserRole(NoteUserRole noteUserRole);


    /**
     * 给笔记系统用户授权角色
     *
     * @param userId 用户id
     * @param roleIds 角色id组
     */
    public void insertNoteUserAuth(Long userId, Long[] roleIds);

    /**
     * 修改用户和笔记系统角色关联
     * 
     * @param noteUserRole 用户和笔记系统角色关联
     * @return 结果
     */
    public int updateNoteUserRole(NoteUserRole noteUserRole);


    /**
     * 根据用户ID获取所拥有的角色ID集合
     *
     * @param userId 用户Id
     * @return
     */
    public Set<String> getNoteRoleIdByUserId(Long userId);

    /**
     * 批量删除用户和笔记系统角色关联
     * 
     * @param userIds 需要删除的用户和笔记系统角色关联主键集合
     * @return 结果
     */
    public int deleteNoteUserRoleByUserIds(String[] userIds);

    /**
     * 删除用户和笔记系统角色关联信息
     * 
     * @param userId 用户和笔记系统角色关联主键
     * @return 结果
     */
    public int deleteNoteUserRoleByUserId(Long userId);
}
