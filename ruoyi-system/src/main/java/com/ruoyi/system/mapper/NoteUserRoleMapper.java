package com.ruoyi.system.mapper;

import java.util.List;
import java.util.Set;

import com.ruoyi.system.domain.NoteUserRole;
import com.ruoyi.system.domain.SysUserRole;

/**
 * 用户和笔记系统角色关联Mapper接口
 * 
 * @author liuyanghe
 * @date 2023-08-29
 */
public interface NoteUserRoleMapper 
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
     * 批量新增用户角色信息
     *
     * @param userRoleList 用户角色列表
     * @return 结果
     */
    public int batchNoteUserRole(List<NoteUserRole> userRoleList);

    /**
     * 删除用户和笔记系统角色关联
     * 
     * @param userId 用户和笔记系统角色关联主键
     * @return 结果
     */
    public int deleteNoteUserRoleByUserId(Long userId);

    /**
     * 批量删除用户和笔记系统角色关联
     * 
     * @param userIds 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteNoteUserRoleByUserIds(String[] userIds);
}
