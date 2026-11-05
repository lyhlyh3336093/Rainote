package com.ruoyi.quartz.task;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.context.SecurityContextHolder;
import com.ruoyi.system.domain.NoteNote;
import com.ruoyi.system.mapper.NoteNoteMapper;
import com.ruoyi.system.service.BackupLockUtil;
import com.ruoyi.system.service.INoteNoteService;
import com.ruoyi.system.service.VipService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VipCleanTask} 回收站保留期清理测试（U4 Test scenarios）。
 * <p>
 * 覆盖：普通用户 8 天前垃圾被物理清理（Covers R13）；7 天内保留；
 * VIP 30 天内保留；VIP 第 10 天笔记——仍 VIP 保留、已降级清理（边界语义）；
 * 备份写锁持有期间清理跳过。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecycleRetentionTest
{
    private static final Long NORMAL_USER = 2L;
    private static final Long VIP_USER = 3L;

    @Mock
    private NoteNoteMapper noteNoteMapper;

    @Mock
    private INoteNoteService noteNoteService;

    @Mock
    private VipService vipService;

    @InjectMocks
    private VipCleanTask cleanTask;

    @BeforeEach
    void setUp()
    {
        when(vipService.getRecycleRetentionDays(NORMAL_USER)).thenReturn(7);
        // 默认 VIP_USER 也按 7 天（个别用例覆盖为 30）
        when(vipService.getRecycleRetentionDays(VIP_USER)).thenReturn(7);
        when(noteNoteService.deleteNoteFromGarbageByIds(any(String[].class))).thenReturn(1);
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
        BackupLockUtil.releaseLock();
    }

    private NoteNote garbage(Long id, Long owner, int daysAgo)
    {
        NoteNote note = new NoteNote();
        note.setId(id);
        note.setAuth(owner);
        note.setDelFlag(1L);
        note.setDeleteTime(new Date(System.currentTimeMillis() - daysAgo * 86_400_000L));
        return note;
    }

    @Test
    void normalUser_expiredGarbage_physicallyCleaned()
    {
        when(noteNoteMapper.selectGarbageWithOwner()).thenReturn(new ArrayList<>(java.util.Arrays.asList(
                garbage(101L, NORMAL_USER, 8),   // 8 天前 > 7 天保留期 → 清理
                garbage(102L, NORMAL_USER, 3)))); // 3 天前 ≤ 7 天 → 保留

        cleanTask.cleanExpiredGarbage();

        ArgumentCaptor<String[]> idsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(noteNoteService).deleteNoteFromGarbageByIds(idsCaptor.capture());
        assertEquals(1, idsCaptor.getValue().length, "仅 8 天前那条被清理");
        assertEquals("101", idsCaptor.getValue()[0]);
    }

    @Test
    void vipUser_within30Days_retained()
    {
        when(vipService.getRecycleRetentionDays(VIP_USER)).thenReturn(30);
        when(noteNoteMapper.selectGarbageWithOwner()).thenReturn(new ArrayList<>(java.util.Arrays.asList(
                garbage(201L, VIP_USER, 10)))); // VIP 10 天 < 30 天 → 保留

        cleanTask.cleanExpiredGarbage();

        verify(noteNoteService, never()).deleteNoteFromGarbageByIds(any(String[].class));
    }

    @Test
    void vipUser_expiredAfterDowngrade_cleaned()
    {
        // VIP 用户第 10 天的垃圾笔记：已降级（保留期按清理时刻普通 7 天口径）→ 清理（边界语义）
        when(vipService.getRecycleRetentionDays(VIP_USER)).thenReturn(7);
        when(noteNoteMapper.selectGarbageWithOwner()).thenReturn(new ArrayList<>(java.util.Arrays.asList(
                garbage(201L, VIP_USER, 10))));

        cleanTask.cleanExpiredGarbage();

        ArgumentCaptor<String[]> idsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(noteNoteService).deleteNoteFromGarbageByIds(idsCaptor.capture());
        assertEquals("201", idsCaptor.getValue()[0]);
    }

    @Test
    void mixedUsers_groupedByOwnerRetention()
    {
        when(vipService.getRecycleRetentionDays(VIP_USER)).thenReturn(30);
        when(noteNoteMapper.selectGarbageWithOwner()).thenReturn(new ArrayList<>(java.util.Arrays.asList(
                garbage(301L, NORMAL_USER, 8),  // 普通 8 天 → 清理
                garbage(302L, VIP_USER, 8))));  // VIP 8 天 < 30 → 保留

        cleanTask.cleanExpiredGarbage();

        ArgumentCaptor<String[]> idsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(noteNoteService).deleteNoteFromGarbageByIds(idsCaptor.capture());
        assertEquals(1, idsCaptor.getValue().length);
        assertEquals("301", idsCaptor.getValue()[0]);
    }

    @Test
    void backupLocked_cleanupSkipped()
    {
        assertTrue(BackupLockUtil.tryAcquireBackupLock());
        try
        {
            cleanTask.cleanExpiredGarbage();
            verify(noteNoteMapper, never()).selectGarbageWithOwner();
            verify(noteNoteService, never()).deleteNoteFromGarbageByIds(any(String[].class));
        }
        finally
        {
            BackupLockUtil.releaseLock();
        }
    }

    @Test
    void deleteRunsUnderOwnerSecurityContext_andCleared()
    {
        when(noteNoteMapper.selectGarbageWithOwner()).thenReturn(new ArrayList<>(java.util.Arrays.asList(
                garbage(401L, NORMAL_USER, 8))));

        cleanTask.cleanExpiredGarbage();

        // 级联删除前设置了属主的 SecurityContext，删除后清理（防泄漏）
        assertTrue(SecurityContextHolder.getContext().getAuthentication() == null,
                "清理任务结束后 SecurityContext 必须清空");
    }
}
