package com.ruoyi.system.service.impl;

import java.util.ArrayList;
import java.util.List;

import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.*;
import com.ruoyi.system.mapper.NoteRoleDeptMapper;
import com.ruoyi.system.mapper.NoteRoleMenuMapper;
import com.ruoyi.system.mapper.SysUserRoleMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.system.mapper.NoteRoleMapper;
import com.ruoyi.system.service.INoteRoleService;

/**
 * 笔记角色Service业务层处理
 * 
 * @author liuyanghe
 * @date 2023-08-26
 */
@Service
public class NoteRoleServiceImpl implements INoteRoleService 
{
    @Autowired
    private NoteRoleMapper noteRoleMapper;

    @Autowired
    private NoteRoleMenuMapper noteRoleMenuMapper;

    @Autowired
    private NoteRoleDeptMapper noteRoleDeptMapper;

    @Autowired
    private SysUserRoleMapper userRoleMapper;

    /**
     * 查询笔记角色
     * 
     * @param id 笔记角色主键
     * @return 笔记角色
     */
    @Override
    public NoteRole selectNoteRoleById(Long id)
    {
        return noteRoleMapper.selectNoteRoleById(id);
    }

    /**
     * 查询笔记角色列表
     * 
     * @param noteRole 笔记角色
     * @return 笔记角色
     */
    @Override
    public List<NoteRole> selectNoteRoleList(NoteRole noteRole)
    {
        return noteRoleMapper.selectNoteRoleList(noteRole);
    }


    /**
     * 根据用户id查询笔记角色列表
     *
     * @param userId 用户id
     * @return 笔记角色
     */
    @Override
    public List<NoteRole> selectNoteRoleListByUserId(NoteRole noteRole,Long userId)
    {
        List<NoteRole> userRoles = noteRoleMapper.selectNoteRolePermissionByUserId(userId);
        List<NoteRole> roles = noteRoleMapper.selectNoteRoleAll(noteRole);
        for (NoteRole role : roles)
        {
            for (NoteRole userRole : userRoles)
            {
                if (role.getId() == userRole.getId())
                {
                    role.setFlag(0L);
                    break;
                }
            }
        }

        return roles;
    }

    /**
     * 新增笔记角色
     * 
     * @param noteRole 笔记角色
     * @return 结果
     */
    @Override
    public int insertNoteRole(NoteRole noteRole)
    {
        noteRole.setCreateTime(DateUtils.getNowDate());
        noteRoleMapper.insertNoteRole(noteRole);
        return insertNoteRoleMenu(noteRole);
    }


    /**
     * 修改笔记角色
     * 
     * @param noteRole 笔记角色
     * @return 结果
     */
    @Override
    public int updateNoteRole(NoteRole noteRole)
    {
        noteRole.setUpdateTime(DateUtils.getNowDate());
        // 修改角色信息
        noteRoleMapper.updateNoteRole(noteRole);
        // 删除角色与菜单关联
        noteRoleMenuMapper.deleteNoteRoleMenuByRoleId(noteRole.getId());
        return insertNoteRoleMenu(noteRole);
//        return noteRoleMapper.updateNoteRole(noteRole);
    }



    /**
     * 新增角色菜单信息
     *
     * @param role 角色对象
     */
    public int insertNoteRoleMenu(NoteRole role)
    {
        int rows = 1;
        // 新增用户与角色管理
        List<NoteRoleMenu> list = new ArrayList<NoteRoleMenu>();
        //todo:这里想要维护对象里面的menuids字段,但是数据库表没有,不知道是不是没加还是废案,新增的部分应该不是这里而另有他用,
        //看看新增的时候这个menuids怎么来的
        for (Long menuId : role.getMenuIds())
        {
            NoteRoleMenu rm = new NoteRoleMenu();
            rm.setRoleId(role.getId());
            rm.setMenuId(menuId);
            list.add(rm);
        }
        if (list.size() > 0)
        {
            rows = noteRoleMenuMapper.batchNoteRoleMenu(list);
        }
        return rows;
    }


    @Override
    public int authDataScope(NoteRole role) {
        // 修改角色信息
        noteRoleMapper.updateNoteRole(role);
        // 删除角色与部门关联
        noteRoleDeptMapper.deleteNoteRoleDeptByRoleId(role.getId());
        // 新增角色和部门信息（数据权限）
        return insertRoleDept(role);
    }


    /**
     * 新增角色部门信息(数据权限)
     *
     * @param role 角色对象
     */
    public int insertRoleDept(NoteRole role)
    {
        int rows = 1;
        // 新增角色与部门（数据权限）管理
        List<NoteRoleDept> list = new ArrayList<NoteRoleDept>();
        for (Long deptId : role.getDeptIds())
        {
            NoteRoleDept rd = new NoteRoleDept();
            rd.setRoleId(role.getId());
            rd.setDeptId(deptId);
            list.add(rd);
        }
        if (list.size() > 0)
        {
            rows = noteRoleDeptMapper.batchNoteRoleDept(list);
        }
        return rows;
    }
    @Override
    public int updateRoleStatus(NoteRole role) {
        return noteRoleMapper.updateNoteRole(role);
    }

    @Override
    public List<NoteRole> selectNoteRoleAll() {
        return noteRoleMapper.selectNoteRoleAll(new NoteRole());
    }

    /**
     * 批量删除笔记角色
     * 
     * @param ids 需要删除的笔记角色主键
     * @return 结果
     */
    @Override
    public int deleteNoteRoleByIds(String[] ids)
    {
        for (String roleIdStr : ids)
        {
            Long roleId = Long.parseLong(roleIdStr);
            checkRoleAllowed(new NoteRole(roleId));
            checkRoleDataScope(roleId);
            NoteRole role = selectNoteRoleById(roleId);
            if (countNoteUserRoleByRoleId(roleId) > 0)
            {
                throw new ServiceException(String.format("%1$s已分配,不能删除", role.getRoleName()));
            }
        }
        // 删除角色与菜单关联
        noteRoleMenuMapper.deleteNoteRoleMenuByRoleIds(ids);
        // 删除角色与部门关联
//        roleDeptMapper.deleteRoleDept(ids);
        return noteRoleMapper.deleteNoteRoleByIds(ids);
    }

    /**
     * 通过角色ID查询角色使用数量
     *
     * @param roleId 角色ID
     * @return 结果
     */
    @Override
    public int countNoteUserRoleByRoleId(Long roleId)
    {
        return userRoleMapper.countNoteUserRoleByRoleId(roleId);
    }


    /**
     * 删除笔记角色信息
     * 
     * @param id 笔记角色主键
     * @return 结果
     */
    @Override
    public int deleteNoteRoleById(Long id)
    {
        return noteRoleMapper.deleteNoteRoleById(id);
    }

    @Override
    public String checkNoteRoleNameUnique(NoteRole role) {
        Long roleId = StringUtils.isNull(role.getId()) ? -1L : role.getId();
        NoteRole info = noteRoleMapper.checkNoteRoleNameUnique(role.getRoleName());
        if (StringUtils.isNotNull(info) && info.getId().longValue() != roleId.longValue())
        {
            return UserConstants.NOT_UNIQUE;
        }
        return UserConstants.UNIQUE;
    }

    @Override
    public String checkNoteRoleKeyUnique(NoteRole role) {
        Long roleId = StringUtils.isNull(role.getId()) ? -1L : role.getId();
        NoteRole info = noteRoleMapper.checkNoteRoleKeyUnique(role.getRoleKey());
        if (StringUtils.isNotNull(info) && info.getId().longValue() != roleId.longValue())
        {
            return UserConstants.NOT_UNIQUE;
        }
        return UserConstants.UNIQUE;
    }

    @Override
    public void checkRoleAllowed(NoteRole role) {
        if (StringUtils.isNotNull(role.getId()) && role.isAdmin())
        {
            throw new ServiceException("不允许操作超级管理员角色");
        }
    }

    @Override
    public void checkRoleDataScope(Long roleId) {

    }

    @Override
    public int deleteAuthUsers(Long roleId, Long[] userIds) {
        return 0;
    }

    @Override
    public int insertAuthUsers(Long roleId, Long[] userIds) {
        return 0;
    }
}
