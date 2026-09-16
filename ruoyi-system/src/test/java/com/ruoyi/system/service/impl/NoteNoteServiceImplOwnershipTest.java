package com.ruoyi.system.service.impl;

import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.agent.security.AgentOwnershipChecker;
import com.ruoyi.system.domain.NoteNote;
import com.ruoyi.system.mapper.NoteBlockMapper;
import com.ruoyi.system.mapper.NoteColumnMapper;
import com.ruoyi.system.mapper.NoteDwtableItemMapper;
import com.ruoyi.system.mapper.NoteDwtableMapper;
import com.ruoyi.system.mapper.NoteMetaMapper;
import com.ruoyi.system.mapper.NoteNoteMapper;
import com.ruoyi.system.mapper.NoteRecordMapper;
import com.ruoyi.system.mapper.NoteViewMapper;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.system.service.INoteRoleMenuService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * NoteNoteServiceImpl 归属校验拦截测试（U5）。
 * <p>
 * 使用真实的 {@link AgentOwnershipChecker} + 真实的 {@link NoteNoteServiceImpl}，
 * 通过 {@link SecurityContextHolder} 模拟登录用户，验证完整的归属校验拦截链：
 * <pre>
 *   ServiceImpl.deleteNoteNoteByIds
 *     → SecurityUtils.getUserId()           （从 SecurityContext 获取当前用户）
 *     → ownershipChecker.checkNoteOwnership  （查 note_note.auth 比对 userId）
 *     → 命中他人数据 → 抛 ServiceException   （拦截，删除逻辑不执行）
 * </pre>
 * {@link SecurityUtils#isAdmin(Long)} 判定 userId == 1L，故管理员 ID 固定为 1L。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NoteNoteServiceImplOwnershipTest
{
    private static final Long ADMIN_ID = 1L;
    private static final Long USER_A = 2L;
    private static final Long USER_B = 3L;
    private static final Long NOTE_ID = 100L;

    @Mock private NoteNoteMapper noteNoteMapper;
    @Mock private NoteDwtableMapper noteDwtableMapper;
    @Mock private NoteDwtableItemMapper noteDwtableItemMapper;
    @Mock private NoteBlockMapper noteBlockMapper;
    @Mock private NoteColumnMapper noteColumnMapper;
    @Mock private NoteRecordMapper noteRecordMapper;
    @Mock private NoteViewMapper noteViewMapper;
    @Mock private NoteMetaMapper noteMetaMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private INoteRoleMenuService noteRoleMenuService;
    @Mock private NoteDwtableServiceImpl noteDwtableServiceImpl;

    /** 真实归属校验器（注入 mock mapper，测试完整层级链逻辑） */
    private AgentOwnershipChecker ownershipChecker;

    /** 真实 Service（注入 mock 依赖 + 真实 ownershipChecker） */
    private NoteNoteServiceImpl noteNoteService;

    @BeforeEach
    void setUp()
    {
        // 构建真实 AgentOwnershipChecker，注入 mock mapper
        ownershipChecker = new AgentOwnershipChecker();
        injectField(ownershipChecker, "noteNoteMapper", noteNoteMapper);
        injectField(ownershipChecker, "noteDwtableMapper", noteDwtableMapper);
        injectField(ownershipChecker, "noteColumnMapper", noteColumnMapper);
        injectField(ownershipChecker, "noteRecordMapper", noteRecordMapper);

        // 构建真实 NoteNoteServiceImpl，注入全部 mock 依赖 + 真实 ownershipChecker
        noteNoteService = new NoteNoteServiceImpl();
        injectField(noteNoteService, "noteNoteMapper", noteNoteMapper);
        injectField(noteNoteService, "noteDwtableMapper", noteDwtableMapper);
        injectField(noteNoteService, "noteDwtableItemMapper", noteDwtableItemMapper);
        injectField(noteNoteService, "noteBlockMapper", noteBlockMapper);
        injectField(noteNoteService, "noteColumnMapper", noteColumnMapper);
        injectField(noteNoteService, "noteRecordMapper", noteRecordMapper);
        injectField(noteNoteService, "noteViewMapper", noteViewMapper);
        injectField(noteNoteService, "noteMetaMapper", noteMetaMapper);
        injectField(noteNoteService, "sysUserMapper", sysUserMapper);
        injectField(noteNoteService, "noteRoleMenuService", noteRoleMenuService);
        injectField(noteNoteService, "noteDwtableServiceImpl", noteDwtableServiceImpl);
        injectField(noteNoteService, "ownershipChecker", ownershipChecker);
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
    }

    // ===== 核心场景：普通用户删除他人笔记 → 拦截 =====

    @Test
    void test_deleteOthersNote_blockedByOwnershipCheck()
    {
        // 模拟普通用户 A 登录
        loginAs(USER_A);

        // 笔记属于用户 B（auth = USER_B）
        NoteNote note = new NoteNote();
        note.setId(NOTE_ID);
        note.setAuth(USER_B);
        when(noteNoteMapper.selectNoteNoteById(NOTE_ID)).thenReturn(note);

        // 用户 A 尝试删除用户 B 的笔记
        ServiceException ex = assertThrows(ServiceException.class,
                () -> noteNoteService.deleteNoteNoteByIds(new String[]{NOTE_ID.toString()}),
                "普通用户删除他人笔记应抛 ServiceException");

        // 验证异常消息
        assertTrue(ex.getMessage().contains("无权操作"),
                "异常消息应包含「无权操作」，实际: " + ex.getMessage());

        // 验证实际删除操作从未执行（拦截在删除逻辑之前生效）
        verify(noteNoteMapper, never()).deleteNoteNoteByIds(any(String[].class));
        verify(noteDwtableMapper, never()).deleteNoteDwtableByNoteId(anyLong());

        // 验证归属校验确实查询了笔记（确认拦截来自 ownershipChecker 而非其他原因）
        verify(noteNoteMapper).selectNoteNoteById(NOTE_ID);
    }

    // ===== 对比场景1：普通用户删除自己的笔记 → 通过 =====

    @Test
    void test_deleteOwnNote_passesOwnershipCheck()
    {
        loginAs(USER_A);

        // 笔记属于用户 A 自己（auth = USER_A）
        NoteNote note = new NoteNote();
        note.setId(NOTE_ID);
        note.setAuth(USER_A);
        note.setNoteType(0L); // 普通笔记，非文件夹/元数据
        when(noteNoteMapper.selectNoteNoteById(NOTE_ID)).thenReturn(note);
        when(noteNoteMapper.deleteNoteNoteByIds(any(String[].class))).thenReturn(1);

        // 用户 A 删除自己的笔记 → 不抛异常
        int result = assertDoesNotThrow(
                () -> noteNoteService.deleteNoteNoteByIds(new String[]{NOTE_ID.toString()}),
                "用户删除自己的笔记不应被拦截");

        assertEquals(1, result);
        // 验证实际删除已执行
        verify(noteNoteMapper).deleteNoteNoteByIds(any(String[].class));
    }

    // ===== 对比场景2：管理员删除他人笔记 → 通过 =====

    @Test
    void test_adminDeleteOthersNote_passesOwnershipCheck()
    {
        loginAs(ADMIN_ID);

        // 笔记属于用户 B
        NoteNote note = new NoteNote();
        note.setId(NOTE_ID);
        note.setAuth(USER_B);
        note.setNoteType(0L);
        when(noteNoteMapper.selectNoteNoteById(NOTE_ID)).thenReturn(note);
        when(noteNoteMapper.deleteNoteNoteByIds(any(String[].class))).thenReturn(1);

        // 管理员删除用户 B 的笔记 → 不抛异常
        assertDoesNotThrow(
                () -> noteNoteService.deleteNoteNoteByIds(new String[]{NOTE_ID.toString()}),
                "管理员应可删除任意用户笔记");

        verify(noteNoteMapper).deleteNoteNoteByIds(any(String[].class));
    }

    // ===== 批量场景：混合自己的和他人笔记 → 在他人笔记处拦截 =====

    @Test
    void test_batchDelete_mixedOwnAndOthers_blockedAtOthers()
    {
        loginAs(USER_A);

        Long ownNoteId = 101L;
        Long othersNoteId = 102L;

        // 笔记1属于用户 A
        NoteNote ownNote = new NoteNote();
        ownNote.setId(ownNoteId);
        ownNote.setAuth(USER_A);

        // 笔记2属于用户 B
        NoteNote othersNote = new NoteNote();
        othersNote.setId(othersNoteId);
        othersNote.setAuth(USER_B);

        when(noteNoteMapper.selectNoteNoteById(ownNoteId)).thenReturn(ownNote);
        when(noteNoteMapper.selectNoteNoteById(othersNoteId)).thenReturn(othersNote);

        // 批量删除：第一个是自己的（通过），第二个是他人的（拦截）
        ServiceException ex = assertThrows(ServiceException.class,
                () -> noteNoteService.deleteNoteNoteByIds(
                        new String[]{ownNoteId.toString(), othersNoteId.toString()}),
                "批量删除中混入他人笔记应在他人笔记处拦截");

        assertTrue(ex.getMessage().contains("无权操作"));

        // 验证实际删除操作从未执行（即使第一个笔记归属校验通过，也不应执行删除）
        verify(noteNoteMapper, never()).deleteNoteNoteByIds(any(String[].class));
    }

    // ===== 辅助方法 =====

    /**
     * 模拟指定用户登录，设置 SecurityContext。
     * {@link SecurityUtils#getUserId()} 从 SecurityContext 获取当前用户 ID。
     */
    private void loginAs(Long userId)
    {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(userId);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(loginUser, null, java.util.Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void injectField(Object target, String fieldName, Object value)
    {
        try
        {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        }
        catch (Exception e)
        {
            throw new RuntimeException("注入失败: " + fieldName, e);
        }
    }
}
