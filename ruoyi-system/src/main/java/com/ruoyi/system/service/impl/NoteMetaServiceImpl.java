package com.ruoyi.system.service.impl;

import java.util.List;
import com.ruoyi.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.system.mapper.NoteMetaMapper;
import com.ruoyi.system.domain.NoteMeta;
import com.ruoyi.system.service.INoteMetaService;

/**
 * 元数据Service业务层处理
 * 
 * @author liuyanghe
 * @date 2023-05-13
 */
@Service
public class NoteMetaServiceImpl implements INoteMetaService 
{
    @Autowired
    private NoteMetaMapper noteMetaMapper;

    /**
     * 查询元数据
     * 
     * @param id 元数据主键
     * @return 元数据
     */
    @Override
    public NoteMeta selectNoteMetaById(Long id)
    {
        return noteMetaMapper.selectNoteMetaById(id);
    }

    /**
     * 获取笔记元数据
     *
     * @param noteId 笔记主键
     * @return 元数据
     */
    @Override
    public NoteMeta selectNoteMetaByNoteId(Long noteId)
    {
        return noteMetaMapper.selectNoteMetaByNoteId(noteId);
    }

    /**
     * 查询元数据列表
     * 
     * @param noteMeta 元数据
     * @return 元数据
     */
    @Override
    public List<NoteMeta> selectNoteMetaList(NoteMeta noteMeta)
    {
        return noteMetaMapper.selectNoteMetaList(noteMeta);
    }

    /**
     * 新增元数据
     * 
     * @param noteMeta 元数据
     * @return 结果
     */
    @Override
    public int insertNoteMeta(NoteMeta noteMeta)
    {
        noteMeta.setCreateTime(DateUtils.getNowDate());
        return noteMetaMapper.insertNoteMeta(noteMeta);
    }

    /**
     * 修改元数据
     * 
     * @param noteMeta 元数据
     * @return 结果
     */
    @Override
    public int updateNoteMeta(NoteMeta noteMeta)
    {
        return noteMetaMapper.updateNoteMeta(noteMeta);
    }

    /**
     * 批量删除元数据
     * 
     * @param ids 需要删除的元数据主键
     * @return 结果
     */
    @Override
    public int deleteNoteMetaByIds(Long[] ids)
    {
        return noteMetaMapper.deleteNoteMetaByIds(ids);
    }

    /**
     * 删除元数据信息
     * 
     * @param id 元数据主键
     * @return 结果
     */
    @Override
    public int deleteNoteMetaById(Long id)
    {
        return noteMetaMapper.deleteNoteMetaById(id);
    }
}
