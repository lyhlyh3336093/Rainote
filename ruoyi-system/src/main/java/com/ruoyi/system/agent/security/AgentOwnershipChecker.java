package com.ruoyi.system.agent.security;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.NoteDwtable;
import com.ruoyi.system.domain.NoteNote;
import com.ruoyi.system.domain.NoteRecord;
import com.ruoyi.system.mapper.NoteColumnMapper;
import com.ruoyi.system.mapper.NoteDwtableMapper;
import com.ruoyi.system.mapper.NoteNoteMapper;
import com.ruoyi.system.mapper.NoteRecordMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Agent 归属校验器。
 * <p>
 * 在 agent 执行路径和 Service 层加固中复用，统一校验操作目标实体的归属。
 * 归属根为 {@code note_note.auth} 字段（所属用户 ID），所有实体通过层级链
 * 最终追溯到 note_note.auth 判断归属。
 * <p>
 * 层级链：
 * <ul>
 *   <li>笔记 → 直接查 {@code note_note.auth}</li>
 *   <li>多维表 → {@code note_dwtable.noteId} → {@code note_note.auth}</li>
 *   <li>列 → {@code note_column.dwtableId} → {@code note_dwtable.noteId} → {@code note_note.auth}</li>
 *   <li>记录 → {@code note_record.dwtableId} → {@code note_dwtable.noteId} → {@code note_note.auth}</li>
 * </ul>
 * 管理员（{@link SecurityUtils#isAdmin(Long)}）可操作所有用户的实体。
 *
 * @see SecurityUtils
 */
@Component
public class AgentOwnershipChecker
{
    @Autowired
    private NoteNoteMapper noteNoteMapper;

    @Autowired
    private NoteDwtableMapper noteDwtableMapper;

    @Autowired
    private NoteColumnMapper noteColumnMapper;

    @Autowired
    private NoteRecordMapper noteRecordMapper;

    /**
     * 校验当前用户对指定笔记的归属权限。
     *
     * @param noteId 笔记ID
     * @param userId 当前用户ID
     * @throws ServiceException 实体不存在或无权操作
     */
    public void checkNoteOwnership(Long noteId, Long userId)
    {
        if (noteId == null)
        {
            throw new ServiceException("操作目标不存在");
        }
        NoteNote note = noteNoteMapper.selectNoteNoteById(noteId);
        if (note == null)
        {
            throw new ServiceException("笔记不存在");
        }
        checkAuth(note.getAuth(), userId);
    }

    /**
     * 校验当前用户对指定多维表的归属权限。
     *
     * @param dwtableId 多维表ID
     * @param userId    当前用户ID
     * @throws ServiceException 实体不存在或无权操作
     */
    public void checkDwtableOwnership(Long dwtableId, Long userId)
    {
        if (dwtableId == null)
        {
            throw new ServiceException("操作目标不存在");
        }
        NoteDwtable dwtable = noteDwtableMapper.selectNoteDwtableById(dwtableId);
        if (dwtable == null)
        {
            throw new ServiceException("多维表不存在");
        }
        checkNoteOwnership(dwtable.getNoteId(), userId);
    }

    /**
     * 校验当前用户对指定列的归属权限。
     *
     * @param columnId 列ID
     * @param userId   当前用户ID
     * @throws ServiceException 实体不存在或无权操作
     */
    public void checkColumnOwnership(Long columnId, Long userId)
    {
        if (columnId == null)
        {
            throw new ServiceException("操作目标不存在");
        }
        NoteColumn column = noteColumnMapper.selectNoteColumnById(columnId);
        if (column == null)
        {
            throw new ServiceException("列不存在");
        }
        checkDwtableOwnership(column.getDwtableId(), userId);
    }

    /**
     * 校验当前用户对指定记录的归属权限。
     *
     * @param recordId 记录ID
     * @param userId   当前用户ID
     * @throws ServiceException 实体不存在或无权操作
     */
    public void checkRecordOwnership(Long recordId, Long userId)
    {
        if (recordId == null)
        {
            throw new ServiceException("操作目标不存在");
        }
        NoteRecord record = noteRecordMapper.selectNoteRecordById(recordId);
        if (record == null)
        {
            throw new ServiceException("记录不存在");
        }
        checkDwtableOwnership(record.getDwtableId(), userId);
    }

    /**
     * 核心：校验 note_note.auth 与 userId 的归属关系。
     * 管理员通行，否则 auth 必须等于 userId。
     *
     * @param auth   笔记的归属用户ID
     * @param userId 当前操作用户ID
     * @throws ServiceException 无权操作
     */
    private void checkAuth(Long auth, Long userId)
    {
        if (SecurityUtils.isAdmin(userId))
        {
            return;
        }
        if (auth == null || !auth.equals(userId))
        {
            throw new ServiceException("无权操作他人数据");
        }
    }
}
