package com.ruoyi.system.service.impl;

import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.system.mapper.NoteNotelinkMapper;
import com.ruoyi.system.domain.NoteNotelink;
import com.ruoyi.system.service.INoteNotelinkService;

/**
 * 笔记链接Service业务层处理
 *
 * @author liuyanghe
 * @date 2026-02-08
 */
@Service
public class NoteNotelinkServiceImpl implements INoteNotelinkService
{
    private static final Logger log = LoggerFactory.getLogger(NoteNotelinkServiceImpl.class);

    @Autowired
    private NoteNotelinkMapper noteNotelinkMapper;

    /**
     * 查询笔记链接
     *
     * @param id 笔记链接主键
     * @return 笔记链接
     */
    @Override
    public NoteNotelink selectNoteNotelinkById(Long id)
    {
        return noteNotelinkMapper.selectNoteNotelinkById(id);
    }

    /**
     * 查询笔记链接列表
     *
     * @param noteNotelink 笔记链接
     * @return 笔记链接
     */
    @Override
    public List<NoteNotelink> selectNoteNotelinkList(NoteNotelink noteNotelink)
    {
        return noteNotelinkMapper.selectNoteNotelinkList(noteNotelink);
    }

    /**
     * U2: 按 cell 聚合查询(供 R6 单元格展示)
     * 按 (linkColumnId + linkItemId) 查所有 NoteNotelink,按 id 升序返回。
     * 异常时返回空列表,不抛出,避免打断单元格渲染流程。
     */
    @Override
    public List<NoteNotelink> selectNoteNotelinkByCell(Long linkColumnId, Long linkItemId)
    {
        try {
            return noteNotelinkMapper.selectNoteNotelinkByCell(linkColumnId, linkItemId);
        } catch (Exception e) {
            log.error("[NoteNotelink] selectNoteNotelinkByCell failed, linkColumnId={}, linkItemId={}",
                    linkColumnId, linkItemId, e);
            return Collections.emptyList();
        }
    }

    /**
     * U5: 按 noteId 查询引用列表(供 R9 追溯面板)
     * 查所有引用该笔记的 NoteNotelink,按 id 升序返回。
     * 异常时返回空列表,不抛出,避免打断面板渲染流程。
     */
    @Override
    public List<NoteNotelink> selectNoteNotelinkByNoteId(Long noteId)
    {
        try {
            return noteNotelinkMapper.selectNoteNotelinkByNoteId(noteId);
        } catch (Exception e) {
            log.error("[NoteNotelink] selectNoteNotelinkByNoteId failed, noteId={}", noteId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 新增笔记链接
     *
     * @param noteNotelink 笔记链接
     * @return 结果
     */
    @Override
    public int insertNoteNotelink(NoteNotelink noteNotelink)
    {
        return noteNotelinkMapper.insertNoteNotelink(noteNotelink);
    }

    /**
     * 修改笔记链接
     *
     * @param noteNotelink 笔记链接
     * @return 结果
     */
    @Override
    public int updateNoteNotelink(NoteNotelink noteNotelink)
    {
        return noteNotelinkMapper.updateNoteNotelink(noteNotelink);
    }

    /**
     * 批量删除笔记链接
     *
     * @param ids 需要删除的笔记链接主键
     * @return 结果
     */
    @Override
    public int deleteNoteNotelinkByIds(Long[] ids)
    {
        return noteNotelinkMapper.deleteNoteNotelinkByIds(ids);
    }

    /**
     * U2: 按 linkColumnId 级联删除(供 deleteDataWhenLink 调用)
     * 删除语义关联列时清理孤儿 NoteNotelink 记录。
     * 异常时记录日志但不抛出,避免阻断列删除主流程。
     */
    @Override
    public int deleteNoteNotelinkByColumnId(Long linkColumnId)
    {
        try {
            return noteNotelinkMapper.deleteNoteNotelinkByColumnId(linkColumnId);
        } catch (Exception e) {
            log.error("[NoteNotelink] deleteNoteNotelinkByColumnId failed, linkColumnId={}",
                    linkColumnId, e);
            return 0;
        }
    }
}
