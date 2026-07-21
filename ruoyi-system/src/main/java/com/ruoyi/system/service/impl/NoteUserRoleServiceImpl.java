package com.ruoyi.system.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.ruoyi.common.utils.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.system.mapper.NoteUserRoleMapper;
import com.ruoyi.system.domain.NoteUserRole;
import com.ruoyi.system.service.INoteUserRoleService;

/**
 * 用户和笔记系统角色关联Service业务层处理
 * 
 * @author liuyanghe
 * @date 2023-08-29
 */
@Service
public class NoteUserRoleServiceImpl implements INoteUserRoleService 
{
    @Autowired
    private NoteUserRoleMapper noteUserRoleMapper;

    /**
     * 查询用户和笔记系统角色关联
     * 
     * @param userId 用户和笔记系统角色关联主键
     * @return 用户和笔记系统角色关联
     */
    @Override
    public NoteUserRole selectNoteUserRoleByUserId(Long userId)
    {
        return noteUserRoleMapper.selectNoteUserRoleByUserId(userId);
    }

    /**
     * 查询用户和笔记系统角色关联列表
     * 
     * @param noteUserRole 用户和笔记系统角色关联
     * @return 用户和笔记系统角色关联
     */
    @Override
    public List<NoteUserRole> selectNoteUserRoleList(NoteUserRole noteUserRole)
    {
        return noteUserRoleMapper.selectNoteUserRoleList(noteUserRole);
    }

    /**
     * 新增用户和笔记系统角色关联
     * 
     * @param noteUserRole 用户和笔记系统角色关联
     * @return 结果
     */
    @Override
    public int insertNoteUserRole(NoteUserRole noteUserRole)
    {
        return noteUserRoleMapper.insertNoteUserRole(noteUserRole);
    }


    /**
     * 给笔记系统用户授权角色
     *
     * @param userId 用户id
     * @param roleIds 角色id组
     */
    @Override
    public void insertNoteUserAuth(Long userId, Long[] roleIds){

        noteUserRoleMapper.deleteNoteUserRoleByUserId(userId);

        if (StringUtils.isNotEmpty(roleIds))
        {
            // 新增用户与角色管理
            List<NoteUserRole> list = new ArrayList<NoteUserRole>(roleIds.length);
            for (Long roleId : roleIds)
            {
                NoteUserRole ur = new NoteUserRole();
                ur.setUserId(userId);
                ur.setRoleId(roleId);
                list.add(ur);
            }
            noteUserRoleMapper.batchNoteUserRole(list);
        }
    }

    /**
     * 修改用户和笔记系统角色关联
     * 
     * @param noteUserRole 用户和笔记系统角色关联
     * @return 结果
     */
    @Override
    public int updateNoteUserRole(NoteUserRole noteUserRole)
    {
        return noteUserRoleMapper.updateNoteUserRole(noteUserRole);
    }

    /**
     * 根据用户ID获取所拥有的角色ID集合
     *
     * @param userId 用户Id
     * @return
     */
    @Override
    public Set<String> getNoteRoleIdByUserId(Long userId) {
        return noteUserRoleMapper.getNoteRoleIdByUserId(userId);
    }

    /**
     * 批量删除用户和笔记系统角色关联
     * 
     * @param userIds 需要删除的用户和笔记系统角色关联主键
     * @return 结果
     */
    @Override
    public int deleteNoteUserRoleByUserIds(String[] userIds)
    {
        return noteUserRoleMapper.deleteNoteUserRoleByUserIds(userIds);
    }

    /**
     * 删除用户和笔记系统角色关联信息
     * 
     * @param userId 用户和笔记系统角色关联主键
     * @return 结果
     */
    @Override
    public int deleteNoteUserRoleByUserId(Long userId)
    {
        return noteUserRoleMapper.deleteNoteUserRoleByUserId(userId);
    }
}
