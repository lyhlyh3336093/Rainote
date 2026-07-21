package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.NoteView;

/**
 * 视图Service接口
 * 
 * @author liuyanghe
 * @date 2023-04-10
 */
public interface INoteViewService 
{
    /**
     * 查询视图
     * 
     * @param id 视图主键
     * @return 视图
     */
    public NoteView selectNoteViewById(Long id);

    /**
     * 查询视图列表
     * 
     * @param noteView 视图
     * @return 视图集合
     */
    public List<NoteView> selectNoteViewList(NoteView noteView);

    /**
     * 新增视图
     * 
     * @param noteView 视图
     * @return 结果
     */
    public Long insertNoteView(NoteView noteView);

    /**
     * 修改视图
     * 
     * @param noteView 视图
     * @return 结果
     */
    public int updateNoteView(NoteView noteView);

    /**
     * 批量删除视图
     * 
     * @param ids 需要删除的视图主键集合
     * @return 结果
     */
    public int deleteNoteViewByIds(String[] ids);

    /**
     * 删除视图信息
     * 
     * @param id 视图主键
     * @return 结果
     */
    public int deleteNoteViewById(Long id);
}
