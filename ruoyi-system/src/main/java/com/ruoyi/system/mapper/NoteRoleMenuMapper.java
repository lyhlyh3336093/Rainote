package com.ruoyi.system.mapper;

import java.util.List;
import java.util.Set;

import com.ruoyi.system.domain.NoteRoleMenu;
import com.ruoyi.system.domain.SysRoleMenu;
import org.apache.ibatis.annotations.Param;

/**
 * 笔记系统角色和菜单关联Mapper接口
 * 
 * @author liuyanghe
 * @date 2023-08-28
 */
public interface NoteRoleMenuMapper 
{
    /**
     * 查询笔记系统角色和菜单关联
     * 
     * @param roleId 笔记系统角色和菜单关联主键
     * @return 笔记系统角色和菜单关联
     */
    public NoteRoleMenu selectNoteRoleMenuByRoleId(Long roleId);


    /**
     * 查询笔记系统角色和菜单关联
     *
     * @param roleId 角色ID
     * @param menuCheckStrictly 菜单树选择项是否关联显示
     * @return 笔记系统角色和菜单关联
     */
    public List<Long> selectNoteMenuListByRoleId(@Param("roleId") Long roleId, @Param("menuCheckStrictly") Integer menuCheckStrictly);

    /**
     * 查询笔记系统角色和菜单关联列表
     * 
     * @param noteRoleMenu 笔记系统角色和菜单关联
     * @return 笔记系统角色和菜单关联集合
     */
    public List<NoteRoleMenu> selectNoteRoleMenuList(NoteRoleMenu noteRoleMenu);


    /**
     * 根据角色ID查询拥有的菜单权限
     *
     * @param roleId 角色ID
     * @return 权限列表
     */
    public Set<String> selectNotePermsByRoleId(Long roleId);

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
     * 通过角色id删除笔记系统角色和菜单关联
     * 
     * @param roleId 笔记系统角色和菜单关联主键
     * @return 结果
     */
    public int deleteNoteRoleMenuByRoleId(Long roleId);

    /**
     * 批量删除笔记系统角色和菜单关联
     * 
     * @param roleIds 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteNoteRoleMenuByRoleIds(String[] roleIds);

    /**
     * 批量新增角色菜单信息
     *
     * @param noteRoleMenuList 角色菜单列表
     * @return 结果
     */
    public int batchNoteRoleMenu(List<NoteRoleMenu> noteRoleMenuList);

}
