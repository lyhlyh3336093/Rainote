package com.ruoyi.system.agent.security;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.NoteDwtable;
import com.ruoyi.system.domain.NoteNote;
import com.ruoyi.system.domain.NoteRecord;
import com.ruoyi.system.mapper.NoteColumnMapper;
import com.ruoyi.system.mapper.NoteDwtableMapper;
import com.ruoyi.system.mapper.NoteNoteMapper;
import com.ruoyi.system.mapper.NoteRecordMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * AgentOwnershipChecker 单元测试（U5 步骤6）。
 * <p>
 * 覆盖 7 个场景：自有通过、他人拒绝、管理员通行、3 条层级链、实体不存在。
 * <p>
 * {@link com.ruoyi.common.utils.SecurityUtils#isAdmin(Long)} 判定 userId == 1L，
 * 故管理员 ID 固定为 1L。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentOwnershipCheckerTest
{
    private static final Long ADMIN_ID = 1L;
    private static final Long USER_A = 2L;
    private static final Long USER_B = 3L;

    @Mock
    private NoteNoteMapper noteNoteMapper;

    @Mock
    private NoteDwtableMapper noteDwtableMapper;

    @Mock
    private NoteColumnMapper noteColumnMapper;

    @Mock
    private NoteRecordMapper noteRecordMapper;

    @InjectMocks
    private AgentOwnershipChecker ownershipChecker;

    // ===== 场景1: 用户操作自己的笔记 → 通过 =====

    @Test
    void test_checkNoteOwnership_ownNote_passes()
    {
        NoteNote note = new NoteNote();
        note.setId(100L);
        note.setAuth(USER_A);

        when(noteNoteMapper.selectNoteNoteById(100L)).thenReturn(note);

        assertDoesNotThrow(() -> ownershipChecker.checkNoteOwnership(100L, USER_A));
    }

    // ===== 场景2: 用户操作他人笔记 → 抛 ServiceException =====

    @Test
    void test_checkNoteOwnership_othersNote_throwsServiceException()
    {
        NoteNote note = new NoteNote();
        note.setId(100L);
        note.setAuth(USER_B);

        when(noteNoteMapper.selectNoteNoteById(100L)).thenReturn(note);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> ownershipChecker.checkNoteOwnership(100L, USER_A));
        assertTrue(ex.getMessage().contains("无权操作"),
                "异常消息应提示无权操作");
    }

    // ===== 场景3: 管理员操作任意用户笔记 → 通过 =====

    @Test
    void test_checkNoteOwnership_adminOperatesOthersNote_passes()
    {
        NoteNote note = new NoteNote();
        note.setId(100L);
        note.setAuth(USER_B);

        when(noteNoteMapper.selectNoteNoteById(100L)).thenReturn(note);

        assertDoesNotThrow(() -> ownershipChecker.checkNoteOwnership(100L, ADMIN_ID),
                "管理员应可操作任意用户笔记");
    }

    // ===== 场景4: 多维表层级链校验（dwtableId → noteId → auth） =====

    @Test
    void test_checkDwtableOwnership_chainValidation()
    {
        NoteDwtable dwtable = new NoteDwtable();
        dwtable.setId(200L);
        dwtable.setNoteId(100L);

        NoteNote note = new NoteNote();
        note.setId(100L);
        note.setAuth(USER_A);

        when(noteDwtableMapper.selectNoteDwtableById(200L)).thenReturn(dwtable);
        when(noteNoteMapper.selectNoteNoteById(100L)).thenReturn(note);

        // 用户 A 操作自己的多维表 → 通过
        assertDoesNotThrow(() -> ownershipChecker.checkDwtableOwnership(200L, USER_A));

        // 用户 B 操作用户 A 的多维表 → 拒绝
        ServiceException ex = assertThrows(ServiceException.class,
                () -> ownershipChecker.checkDwtableOwnership(200L, USER_B));
        assertTrue(ex.getMessage().contains("无权操作"));
    }

    // ===== 场景5: 列层级链校验（columnId → dwtableId → noteId → auth） =====

    @Test
    void test_checkColumnOwnership_chainValidation()
    {
        NoteColumn column = new NoteColumn();
        column.setId(300L);
        column.setDwtableId(200L);

        NoteDwtable dwtable = new NoteDwtable();
        dwtable.setId(200L);
        dwtable.setNoteId(100L);

        NoteNote note = new NoteNote();
        note.setId(100L);
        note.setAuth(USER_A);

        when(noteColumnMapper.selectNoteColumnById(300L)).thenReturn(column);
        when(noteDwtableMapper.selectNoteDwtableById(200L)).thenReturn(dwtable);
        when(noteNoteMapper.selectNoteNoteById(100L)).thenReturn(note);

        // 用户 A 操作自己的列 → 通过
        assertDoesNotThrow(() -> ownershipChecker.checkColumnOwnership(300L, USER_A));

        // 用户 B 操作用户 A 的列 → 拒绝
        assertThrows(ServiceException.class,
                () -> ownershipChecker.checkColumnOwnership(300L, USER_B));
    }

    // ===== 场景6: 记录层级链校验（recordId → dwtableId → noteId → auth） =====

    @Test
    void test_checkRecordOwnership_chainValidation()
    {
        NoteRecord record = new NoteRecord();
        record.setId(400L);
        record.setDwtableId(200L);

        NoteDwtable dwtable = new NoteDwtable();
        dwtable.setId(200L);
        dwtable.setNoteId(100L);

        NoteNote note = new NoteNote();
        note.setId(100L);
        note.setAuth(USER_A);

        when(noteRecordMapper.selectNoteRecordById(400L)).thenReturn(record);
        when(noteDwtableMapper.selectNoteDwtableById(200L)).thenReturn(dwtable);
        when(noteNoteMapper.selectNoteNoteById(100L)).thenReturn(note);

        // 用户 A 操作自己的记录 → 通过
        assertDoesNotThrow(() -> ownershipChecker.checkRecordOwnership(400L, USER_A));

        // 用户 B 操作用户 A 的记录 → 拒绝
        assertThrows(ServiceException.class,
                () -> ownershipChecker.checkRecordOwnership(400L, USER_B));
    }

    // ===== 场景7: 实体不存在 → 抛异常 =====

    @Test
    void test_checkNoteOwnership_noteNotFound_throwsServiceException()
    {
        when(noteNoteMapper.selectNoteNoteById(999L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> ownershipChecker.checkNoteOwnership(999L, USER_A));
        assertTrue(ex.getMessage().contains("不存在"),
                "笔记不存在时应提示不存在");
    }

    @Test
    void test_checkDwtableOwnership_dwtableNotFound_throwsServiceException()
    {
        when(noteDwtableMapper.selectNoteDwtableById(999L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> ownershipChecker.checkDwtableOwnership(999L, USER_A));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    void test_checkColumnOwnership_columnNotFound_throwsServiceException()
    {
        when(noteColumnMapper.selectNoteColumnById(999L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> ownershipChecker.checkColumnOwnership(999L, USER_A));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    void test_checkRecordOwnership_recordNotFound_throwsServiceException()
    {
        when(noteRecordMapper.selectNoteRecordById(999L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> ownershipChecker.checkRecordOwnership(999L, USER_A));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    // ===== 边界: null ID → 抛异常 =====

    @Test
    void test_checkNoteOwnership_nullId_throwsServiceException()
    {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> ownershipChecker.checkNoteOwnership(null, USER_A));
        assertTrue(ex.getMessage().contains("不存在"),
                "null ID 应抛出操作目标不存在异常");
    }

    @Test
    void test_checkDwtableOwnership_nullId_throwsServiceException()
    {
        assertThrows(ServiceException.class,
                () -> ownershipChecker.checkDwtableOwnership(null, USER_A));
    }
}
