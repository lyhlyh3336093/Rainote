package com.ruoyi.system.service;

import java.io.InputStream;
import java.util.List;

import com.ruoyi.system.domain.NoteDwtable;
import com.ruoyi.system.domain.NoteMeta;
import com.ruoyi.system.domain.NoteNote;
import com.ruoyi.system.domain.vo.NoteNoteVo;

/**
 * 笔记Service接口
 * 
 * @author ruoyi
 * @date 2023-03-09
 */
public interface INoteNoteService 
{
    /**
     * 查询笔记
     * 
     * @param id 笔记主键
     * @return 笔记
     */
    public NoteNote selectNoteNoteById(Long id);

    /**
     * 查询笔记元信息
     *
     * @param noteId 笔记主键
     * @return 笔记
     */
    public NoteMeta selectNoteDataById(Long noteId);


    /**
     * 查询笔记列表
     *
     * @param noteNote 笔记
     * @return 笔记集合
     */
    public List<NoteNote> selectNoteNoteList(NoteNote noteNote,Long userId);


    /**
     * 查询笔记系统菜单列表
     *
     * @param noteNote 笔记
     * @return 笔记集合
     */
    public List<NoteNote> selectNoteMenuList(NoteNote noteNote,Long userId);


    /**
     * 查询笔记系统菜单和按钮权限列表
     *
     * @param noteNote 笔记
     * @return 笔记集合
     */
    public List<NoteNote> selectNoteMenuAuthList(NoteNote noteNote,Long userId);


    /**
     * 查询用户拥有的数据权限
     *
     * @param noteNote 笔记
     * @return 笔记集合
     */
    public List<NoteNote> selectUserNoteList(NoteNote noteNote,Long userId);


    /**
     * 查询多维表格数据表的数据表列表
     *
     * @param id 多维笔记id
     * @return 数据表集合
     */
    public List<NoteDwtable> selectDWTableList(Long id);


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
     * 查询收藏列表
     *
     * @param noteNote 笔记
     * @return 笔记集合
     */
    public List<NoteNote> selectCollectionList(NoteNote noteNote);


    /**
     * 导入笔记
     *
     * @param is 导入文件
     * @return 结果
     */
    public int importNote(InputStream is,String filePath);

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
     * 另存为新笔记
     *
     * @param noteNote 笔记
     * @return 结果
     */
    public int saveAsNewNote(NoteNote noteNote);



    /**
     * 更新用户首次登录标识
     *
     * @param userId 用户ID
     * @return 结果
     */
    public int updateFirstLogin(Long userId);



    /**
     * 批量删除笔记
     * 
     * @param ids 需要删除的笔记主键集合
     * @return 结果
     */
    public int deleteNoteNoteByIds(String[] ids);

    /**
     * 批量彻底删除
     *
     * @param ids 需要删除的笔记主键集合
     * @return 结果
     */
    public int deleteNoteFromGarbageByIds(String[] ids);


    /**
     * 重置系统
     *
     * @return 结果
     */
    public int removeAll();


    /**
     * 清空所有数据
     *
     * @return 结果
     */
    public int removeAllData();

    /**
     * 取消收藏
     *
     * @param id 需要取消收藏的笔记主键
     * @return 结果
     */
    public int cancelCollectionByIds(String id);

    /**
     * 收藏笔记
     *
     * @param id 需要收藏的笔记主键
     * @return 结果
     */
    public int collectionNoteByIds(String id);


    /**
     * 设为模板
     *
     * @param id 需要设置为模板的笔记主键
     * @return 结果
     */
    public int setTemplate(String id);


    /**
     * 撤销模板
     *
     * @param id 需要撤销的笔记主键
     * @return 结果
     */
    public int removeTemplate(String id);

    /**
     * 从回收站恢复
     *
     * @param ids 需要删除的笔记主键集合
     * @return 结果
     */
    public int recoverNoteNoteByIds(String[] ids);

    /**
     * 删除笔记信息
     * 
     * @param id 笔记主键
     * @return 结果
     */
    public int deleteNoteNoteById(Long id);


    /**
     * 搜索笔记
     *
     * @param noteNoteVo 笔记
     * @return 笔记集合
     */
    List<NoteNote> searchNoteList(NoteNoteVo noteNoteVo, Long userId);
}
