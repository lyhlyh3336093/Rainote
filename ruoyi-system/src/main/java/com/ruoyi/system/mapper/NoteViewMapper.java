package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.NoteView;

/**
 * 视图Mapper接口
 * 
 * @author liuyanghe
 * @date 2023-04-10
 */
public interface NoteViewMapper 
{
    /**
     * 查询视图
     * 
     * @param id 视图主键
     * @return 视图
     */
    public NoteView selectNoteViewById(Long id);


    /**
     * 清空所有
     *
     */
    public int removeAll();

    /**
     * 查询视图列表
     * 
     * @param noteView 视图
     * @return 视图集合
     */
    public List<NoteView> selectNoteViewList(NoteView noteView);


    /**
     * 根据数据表id查询视图列表
     *
     * @param dwtableId 数据表id
     * @return 视图集合
     */
    public List<NoteView> selectNoteViewListByDwtableId(Long dwtableId);


    /**
     * 新增视图
     * 
     * @param noteView 视图
     * @return 结果
     */
    public int insertNoteView(NoteView noteView);

    /**
     * 修改视图
     * 
     * @param noteView 视图
     * @return 结果
     */
    public int updateNoteView(NoteView noteView);

    /**
     * 删除视图
     * 
     * @param id 视图主键
     * @return 结果
     */
    public int deleteNoteViewById(Long id);

    /**
     * 根据数据表id删除视图
     *
     * @param dwtableId 视图主键
     * @return 结果
     */
    public int deleteNoteViewByDwtableId(Long dwtableId);


    /**
     * 批量删除视图
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteNoteViewByIds(String[] ids);
}
