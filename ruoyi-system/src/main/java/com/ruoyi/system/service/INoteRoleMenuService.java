package com.ruoyi.system.service;

import java.util.List;
import java.util.Set;

import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.system.domain.NoteNote;
import com.ruoyi.system.domain.NoteRoleMenu;
import com.ruoyi.system.domain.NoteTreeSelect;

/**
 * 笔记系统角色和菜单关联Service接口
 * 
 * @author liuyanghe
 * @date 2023-08-28
 */
public interface INoteRoleMenuService 
{
    /**
     * 查询笔记系统角色和菜单关联
     * 
     * @param roleId 笔记系统角色和菜单关联主键
     * @return 笔记系统角色和菜单关联
     */
    public NoteRoleMenu selectNoteRoleMenuByRoleId(Long roleId);


    /**
     * 根据用户查询系统菜单列表
     *
     * @param userId 用户ID
     * @return 菜单列表
     */
    public List<NoteNote> selectNoteMenuList(Long userId);

    /**
     * 根据角色ID查询菜单树信息
     *
     * @param roleId 角色ID
     * @return 选中菜单列表
     */
    public List<Long> selectNoteRoleMenuListByRoleId(Long roleId);

    /**
     * 根据用户ID查询名下的分级菜单
     *
     * @param userId 用户ID
     * @return 菜单ID集合
     */
    public List<Long> selectNoteMenuListByUserId(Long userId);


    /**
     * 构建前端所需要下拉树结构
     *
     * @param menus 菜单列表
     * @return 下拉树结构列表
     */
    public List<NoteTreeSelect> buildNoteRoleMenuTreeSelect(List<NoteNote> menus);

    /**
     * 查询笔记系统中未分配用户角色列表
     *
     * @param user 用户信息
     * @return List<SysUser> 未分配用户list
     */
    public List<SysUser> selectNoteUnallocatedList(SysUser user);

    /**
     * 构建前端所需要树结构
     *
     * @param menus 菜单列表
     * @return 树结构列表
     */
    public List<NoteNote> buildNoteMenuTree(List<NoteNote> menus);

    /**
     * 查询笔记系统角色和菜单关联列表
     * 
     * @param noteRoleMenu 笔记系统角色和菜单关联
     * @return 笔记系统角色和菜单关联集合
     */
    public List<NoteRoleMenu> selectNoteRoleMenuList(NoteRoleMenu noteRoleMenu);

    /**
     * 新增笔记系统角色和菜单关联
     * 
     * @param noteRoleMenu 笔记系统角色和菜单关联
     * @return 结果
     */
    public int insertNoteRoleMenu(NoteRoleMenu noteRoleMenu);

    /**
     * 修改笔记系统角色和菜单关联
     * 
     * @param noteRoleMenu 笔记系统角色和菜单关联
     * @return 结果
     */
    public int updateNoteRoleMenu(NoteRoleMenu noteRoleMenu);

    /**
     * 根据角色ID查询拥有的笔记菜单权限
     *
     * @param roleId 角色ID
     * @return 权限列表
     */
    public Set<String> selectNotePermsByRoleId(Long roleId);


    /**
     * 根据用户ID查询拥有的笔记菜单权限
     *
     * @param userId 用户ID
     * @return 权限列表
     */
    public Set<String> selectNotePermsByUserId(Long userId);


    /**
     * 批量删除笔记系统角色和菜单关联
     * 
     * @param roleIds 需要删除的笔记系统角色和菜单关联主键集合
     * @return 结果
     */
    public int deleteNoteRoleMenuByRoleIds(String[] roleIds);

    /**
     * 删除笔记系统角色和菜单关联信息
     *
     * @param roleId 笔记系统角色和菜单关联主键
     * @return 结果
     */
    public int deleteNoteRoleMenuByRoleId(Long roleId);

    /**
     * 获取某个角色的菜单和按钮权限树
     *
     * @param roleId 笔记系统角色和菜单关联主键
     * @return 结果
     */
    List<NoteNote> selectNoteMenuAuthList(Long roleId);

    /**
     * 获取所有可分配的菜单和按钮
     *
     * @return 结果
     */
    List<NoteNote> selectNoteMenuAuthList();
}
