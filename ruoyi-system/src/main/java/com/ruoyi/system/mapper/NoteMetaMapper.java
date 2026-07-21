package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.NoteMeta;

/**
 * 元数据Mapper接口
 * 
 * @author liuyanghe
 * @date 2023-05-13
 */
public interface NoteMetaMapper 
{
    /**
     * 查询元数据
     * 
     * @param id 元数据主键
     * @return 元数据
     */
    public NoteMeta selectNoteMetaById(Long id);


    /**
     * 清空所有
     *
     */
    public int removeAll();



    /**
     * 根据笔记ID查询元数据
     *
     * @param noteId 笔记id
     * @return 元数据
     */
    public NoteMeta selectNoteMetaByNoteId(Long noteId);


    /**
     * 查询元数据列表
     * 
     * @param noteMeta 元数据
     * @return 元数据集合
     */
    public List<NoteMeta> selectNoteMetaList(NoteMeta noteMeta);

    /**
     * 新增元数据
     * 
     * @param noteMeta 元数据
     * @return 结果
     */
    public int insertNoteMeta(NoteMeta noteMeta);

    /**
     * 修改元数据
     * 
     * @param noteMeta 元数据
     * @return 结果
     */
    public int updateNoteMeta(NoteMeta noteMeta);

    /**
     * 删除元数据
     * 
     * @param id 元数据主键
     * @return 结果
     */
    public int deleteNoteMetaById(Long id);

    /**
     * 批量删除元数据
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteNoteMetaByIds(Long[] ids);
}
