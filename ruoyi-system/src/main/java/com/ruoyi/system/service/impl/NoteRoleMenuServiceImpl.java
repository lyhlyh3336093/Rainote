package com.ruoyi.system.service.impl;

import java.util.*;
import java.util.stream.Collectors;

import com.ruoyi.common.annotation.DataScope;

import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.domain.NoteNote;
import com.ruoyi.system.domain.NoteRole;
import com.ruoyi.system.domain.NoteTreeSelect;
import com.ruoyi.system.mapper.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.system.domain.NoteRoleMenu;
import com.ruoyi.system.service.INoteRoleMenuService;

/**
 * 笔记系统角色和菜单关联Service业务层处理
 * 
 * @author liuyanghe
 * @date 2023-08-28
 */
@Service
public class NoteRoleMenuServiceImpl implements INoteRoleMenuService 
{
    @Autowired
    private NoteRoleMenuMapper noteRoleMenuMapper;


    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private NoteNoteMapper noteNoteMapper;

    @Autowired
    private NoteUserRoleMapper noteUserRoleMapper;

    @Autowired
    private NoteRoleMapper noteRoleMapper;

    /**
     * 查询笔记系统角色和菜单关联
     * 
     * @param roleId 笔记系统角色和菜单关联主键
     * @return 笔记系统角色和菜单关联
     */
    @Override
    public NoteRoleMenu selectNoteRoleMenuByRoleId(Long roleId)
    {
        return noteRoleMenuMapper.selectNoteRoleMenuByRoleId(roleId);
    }

    @Override
    public List<NoteNote> selectNoteMenuList(Long userId) {
        List<NoteNote> menus = null;
        if (SecurityUtils.isAdmin(userId))
        {
            menus = noteNoteMapper.selectNoteMenuList();
        }
        else
        {
            menus = noteNoteMapper.selectNoteMenuListByUserId(userId);
        }
//        return getChildPerms(menus, 0);
        return menus;
    }


    /**
     * 根据父节点的ID获取所有子节点
     *
     * @param list 分类表
     * @param parentId 传入的父节点ID
     * @return String
     */
    public List<NoteNote> getChildPerms(List<NoteNote> list, int parentId)
    {
        List<NoteNote> returnList = new ArrayList<NoteNote>();
        for (Iterator<NoteNote> iterator = list.iterator(); iterator.hasNext();)
        {
            NoteNote t = (NoteNote) iterator.next();
            // 一、根据传入的某个父节点ID,遍历该父节点的所有子节点
            if (t.getParentId() == parentId)
            {
                recursionFn(list, t);
                returnList.add(t);
            }
        }
        return returnList;
    }

    @Override
    public List<Long> selectNoteRoleMenuListByRoleId(Long roleId) {
        NoteRole role = noteRoleMapper.selectNoteRoleById(roleId);
        return noteRoleMenuMapper.selectNoteMenuListByRoleId(roleId,role.getMenuCheckStrictly());
//        return null;

    }

    @Override
    public List<Long> selectNoteMenuListByUserId(Long userId) {

        return null;
//        return noteNoteMapper.selectNoteMenuListByUserId(userId);
    }


    @Override
    public List<NoteTreeSelect> buildNoteRoleMenuTreeSelect(List<NoteNote> menus) {
        List<NoteNote> menuTrees = buildNoteMenuTree(menus);
        return menuTrees.stream().map(NoteTreeSelect::new).collect(Collectors.toList());
    }


    /**
     * 根据条件分页查询未分配用户角色列表
     *
     * @param user 用户信息
     * @return 用户信息集合信息
     */
    @Override
    @DataScope(deptAlias = "d", userAlias = "u")
    public List<SysUser> selectNoteUnallocatedList(SysUser user)
    {
        return userMapper.selectNoteUnallocatedList(user);
    }



    /**
     * 构建前端所需要树结构
     *
     * @param menus 菜单列表
     * @return 树结构列表
     */
    @Override
    public List<NoteNote> buildNoteMenuTree(List<NoteNote> menus)
    {
        List<NoteNote> returnList = new ArrayList<NoteNote>();
        List<Long> tempList = menus.stream().map(NoteNote::getId).collect(Collectors.toList());
        for (Iterator<NoteNote> iterator = menus.iterator(); iterator.hasNext();)
        {
            NoteNote menu = (NoteNote) iterator.next();
            // 如果是顶级节点, 遍历该父节点的所有子节点
            if (!tempList.contains(menu.getParentId()))
            {
                recursionFn(menus, menu);
                returnList.add(menu);
            }
        }
        if (returnList.isEmpty())
        {
            returnList = menus;
        }
        return returnList;
    }

    /**
     * 递归列表
     *
     * @param list 分类表
     * @param t 子节点
     */
    private void recursionFn(List<NoteNote> list, NoteNote t)
    {
        // 得到子节点列表
        List<NoteNote> childList = getChildList(list, t);
        t.setChildren(childList);
        for (NoteNote tChild : childList)
        {
            if (hasChild(list, tChild))
            {
                recursionFn(list, tChild);
            }
        }
    }

    /**
     * 得到子节点列表
     */
    private List<NoteNote> getChildList(List<NoteNote> list, NoteNote t)
    {
        List<NoteNote> tlist = new ArrayList<NoteNote>();
        Iterator<NoteNote> it = list.iterator();
        while (it.hasNext())
        {
            NoteNote n = (NoteNote) it.next();
            if (n.getParentId() == t.getId())
            {
                tlist.add(n);
            }
        }
        return tlist;
    }

    /**
     * 判断是否有子节点
     */
    private boolean hasChild(List<NoteNote> list, NoteNote t)
    {
        return getChildList(list, t).size() > 0;
    }


    /**
     * 查询笔记系统角色和菜单关联列表
     * 
     * @param noteRoleMenu 笔记系统角色和菜单关联
     * @return 笔记系统角色和菜单关联
     */
    @Override
    public List<NoteRoleMenu> selectNoteRoleMenuList(NoteRoleMenu noteRoleMenu)
    {
        return noteRoleMenuMapper.selectNoteRoleMenuList(noteRoleMenu);
    }

    /**
     * 新增笔记系统角色和菜单关联
     * 
     * @param noteRoleMenu 笔记系统角色和菜单关联
     * @return 结果
     */
    @Override
    public int insertNoteRoleMenu(NoteRoleMenu noteRoleMenu)
    {
        return noteRoleMenuMapper.insertNoteRoleMenu(noteRoleMenu);
    }

    /**
     * 修改笔记系统角色和菜单关联
     * 
     * @param noteRoleMenu 笔记系统角色和菜单关联
     * @return 结果
     */
    @Override
    public int updateNoteRoleMenu(NoteRoleMenu noteRoleMenu)
    {
        return noteRoleMenuMapper.updateNoteRoleMenu(noteRoleMenu);
    }

    @Override
    public Set<String> selectNotePermsByRoleId(Long roleId) {
        return noteRoleMenuMapper.selectNotePermsByRoleId(roleId);
    }



    @Override
    public Set<String> selectNotePermsByUserId(Long userId) {
        Set<String> perms = new HashSet<String>();
        //获取用户在笔记系统中的的角色集合
        Set<String> roles = noteUserRoleMapper.getNoteRoleIdByUserId(userId);
        for (String roleId:roles) {
            Set<String> rolePerms = noteRoleMenuMapper.selectNotePermsByRoleId(Long.parseLong(roleId));
            perms.addAll(rolePerms);
        }
        return perms;
    }

    /**
     * 批量删除笔记系统角色和菜单关联
     * 
     * @param roleIds 需要删除的笔记系统角色和菜单关联主键
     * @return 结果
     */
    @Override
    public int deleteNoteRoleMenuByRoleIds(String[] roleIds)
    {
        return noteRoleMenuMapper.deleteNoteRoleMenuByRoleIds(roleIds);
    }

    /**
     * 删除笔记系统角色和菜单关联信息
     * 
     * @param roleId 笔记系统角色和菜单关联主键
     * @return 结果
     */
    @Override
    public int deleteNoteRoleMenuByRoleId(Long roleId)
    {
        return noteRoleMenuMapper.deleteNoteRoleMenuByRoleId(roleId);
    }

    /**
     * 获取某个角色的菜单和按钮权限树
     *
     * @param roleId 笔记系统角色和菜单关联主键
     * @return 结果
     */
    @Override
    public List<NoteNote> selectNoteMenuAuthList(Long roleId) {
        List<NoteNote> notes = noteNoteMapper.selectNoteMenuAuthListByRoleId(roleId);

        return notes;
    }


    /**
     * 获取所有可分配的菜单和按钮
     *
     * @return 结果
     */
    @Override
    public List<NoteNote> selectNoteMenuAuthList() {
        List<NoteNote> notes = noteNoteMapper.selectNoteMenuAuthList();

        return notes;
    }
}
