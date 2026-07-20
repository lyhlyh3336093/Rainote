package com.ruoyi.system.mapper;

import java.util.List;

import com.ruoyi.system.domain.NoteDwtable;
import com.ruoyi.system.domain.NoteNote;

/**
 * 笔记Mapper接口
 * 
 * @author ruoyi
 * @date 2023-03-09
 */
public interface NoteNoteMapper 
{
    /**
     * 查询笔记
     * 
     * @param id 笔记主键
     * @return 笔记
     */
    public NoteNote selectNoteNoteById(Long id);


    /**
     * 根据 parentId 查询直接子笔记（不过滤 delFlag，跨状态查询）
     * 用于文件夹级联删除/恢复时递归收集子孙节点
     *
     * @param parentId 父笔记 ID
     * @return 直接子笔记列表（含 delFlag=0 和 delFlag=1 的子节点）
     */
    public List<NoteNote> selectNoteNoteChildrenByParentId(Long parentId);


    /**
     * 查询默认笔记
     *
     * @return 笔记
     */
    public List<NoteNote> selectNoteDefaultNote();


    /**
     * 查询笔记列表
     * 
     * @param noteNote 笔记
     * @return 笔记集合
     */
    public List<NoteNote> selectNoteNoteList(NoteNote noteNote);


    /**
     * 查询多维笔记数据表
     *
     * @param id 多维表格数据表id
     * @return 多维笔记数据表集合
     */
    public List<NoteDwtable> selectNoteNoteList(Long id);


    /**
     * 清空所有信息
     *
     */
    public int removeAll();


    /**
     * 重置id到50
     *
     */
    public int resettingIdTo50();


    /**
     * 清空所有笔记
     *
     */
    public int linkRecordId();

    /**
     * 查询笔记回收站列表
     *
     * @param noteNote 笔记
     * @return 笔记集合
     */
    public List<NoteNote> selectNoteGarbageList(NoteNote noteNote);


    /**
     * 查询模板列表
     *
     * @param noteNote 笔记
     * @return 笔记集合
     */
    public List<NoteNote> selectNoteTemplateList(NoteNote noteNote);

    /**
     * 查询笔记收藏列表
     *
     * @param noteNote 笔记
     * @return 笔记集合
     */
    public List<NoteNote> selectCollectionList(NoteNote noteNote);


    /**
     * 查询所有笔记文件夹列表
     *
     * @return 笔记集合
     */
    public List<NoteNote> selectNoteMenuList();


    /**
     * 查询所有笔记列表
     *
     * @return 笔记集合
     */
    public List<NoteNote> selectAllNoteList();


    /**
     * 查询所有笔记列表
     *
     * @return 笔记集合
     */
    public List<NoteNote> selectAllMenuList();

    /**
     * 根据用户id查询所有笔记文件夹列表
     *
     * @param userId 用户ID
     * @return 笔记集合
     */
    public List<NoteNote> selectNoteMenuListByUserId(Long userId);

    /**
     * 根据用户id查询所有笔记列表
     *
     * @param userId 用户ID
     * @return 笔记集合
     */
    public List<NoteNote> selectNoteListByUserId(Long userId);

    /**
     * 根据用户id查询所有用户创作的笔记列表
     *
     * @param authId 用户ID
     * @return 笔记集合
     */
    public List<NoteNote> selectNoteListByAuthId(Long authId);

    /**
     * 新增笔记
     * 
     * @param noteNote 笔记
     * @return 结果
     */
    public int insertNoteNote(NoteNote noteNote);

    /**
     * 修改笔记
     * 
     * @param noteNote 笔记
     * @return 结果
     */
    public int updateNoteNote(NoteNote noteNote);

    /**
     * 删除笔记
     * 
     * @param id 笔记主键
     * @return 结果
     */
    public int deleteNoteNoteById(Long id);

    /**
     * 批量删除笔记
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteNoteNoteByIds(String[] ids);

    /**
     * 彻底删除笔记
     *
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteNoteFromGarbageByIds(String[] ids);

    /**
     * 从回收站恢复笔记
     *
     * @param ids 需要恢复的笔记主键集合
     * @return 结果
     */
    public int recoverNoteNoteByIds(String[] ids);

    /**
     * 取消收藏笔记
     *
     * @param id 需要取消的笔记主键集合
     * @return 结果
     */
    public int cancelCollectionById(String id);

    /**
     * 收藏笔记
     *
     * @param id 需要收藏的笔记主键集合
     * @return 结果
     */
    public int collectionNoteById(String id);


    /**
     * 设为模板
     *
     * @param id 需要设为模板的笔记主键集合
     * @return 结果
     */
    public int setTemplate(String id);

    /**
     * 撤销模板
     *
     * @param id 需要撤销模板的笔记主键集合
     * @return 结果
     */
    public int removeTemplate(String id);

    /**
     * 管理员显示所有菜单信息
     *
     * @return 结果
     */
    List<NoteNote> selectAllMenuAuthList();



    /**
     * 查询笔记系统菜单和按钮权限列表
     * @param userId 用户ID
     * @return 结果
     */
    List<NoteNote> selectNoteMenuAuthListByUserId(Long userId);

    /**
     * 根绝角色ID查询角色下的菜单和按钮权限
     * @param roleId 角色ID
     * @return 结果
     */
    List<NoteNote> selectNoteMenuAuthListByRoleId(Long roleId);

    /**
     * 所有菜单和按钮
     * @return 结果
     */
    List<NoteNote> selectNoteMenuAuthList();
}
