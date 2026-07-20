package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.NoteDwtable;
import com.ruoyi.system.domain.NoteMeta;
import com.ruoyi.system.domain.NoteNote;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.INoteRoleMenuService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NoteNoteServiceImpl 文件夹级联删除/恢复单元测试
 * 覆盖：
 * - collectDescendantNotes 递归收集 helper（T1/T2）
 * - deleteNoteNoteByIds 文件夹级联软删除（T3/T4）
 * - deleteNoteFromGarbageByIds 文件夹级联物理删除（T5/T6）
 * - recoverNoteNoteByIds 文件夹级联恢复（T7/T8）
 * - 空文件夹 edge case（T9）
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class NoteNoteServiceImplTest
{
    @Mock
    private NoteNoteMapper noteNoteMapper;

    @Mock
    private NoteDwtableMapper noteDwtableMapper;

    @Mock
    private NoteDwtableItemMapper noteDwtableItemMapper;

    @Mock
    private NoteBlockMapper noteBlockMapper;

    @Mock
    private NoteColumnMapper noteColumnMapper;

    @Mock
    private NoteRecordMapper noteRecordMapper;

    @Mock
    private NoteViewMapper noteViewMapper;

    @Mock
    private NoteMetaMapper noteMetaMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private INoteRoleMenuService noteRoleMenuService;

    @Mock
    private NoteDwtableServiceImpl noteDwtableServiceImpl;

    @InjectMocks
    private NoteNoteServiceImpl noteNoteService;

    /** 反射调用 private collectDescendantNotes 方法 */
    @SuppressWarnings("unchecked")
    private List<NoteNote> invokeCollectDescendantNotes(Long folderId) throws Exception {
        Method method = NoteNoteServiceImpl.class.getDeclaredMethod("collectDescendantNotes", Long.class);
        method.setAccessible(true);
        return (List<NoteNote>) method.invoke(noteNoteService, folderId);
    }

    /** 构造 NoteNote 测试对象 */
    private NoteNote buildNote(Long id, Long parentId, Long noteType, Long delFlag) {
        NoteNote note = new NoteNote();
        note.setId(id);
        note.setParentId(parentId);
        note.setNoteType(noteType);
        note.setDelFlag(delFlag);
        return note;
    }

    // ==================== T1: collectDescendantNotes 3 层嵌套收集 ====================

    /**
     * T1: 验证 collectDescendantNotes 能递归收集 3 层嵌套的所有子孙 NoteNote 对象
     * 结构：folder(1) → subfolder(2) → doc(3)
     *       folder(1) → doc(4)
     * 预期：返回 [subfolder(2), doc(4), doc(3)]（顺序由 DFS 决定，断言用 contains）
     */
    @Test
    public void T1_collectDescendantNotes_3层嵌套_收集所有子孙() throws Exception {
        Long folderId = 1L;
        NoteNote folder = buildNote(folderId, 0L, 1L, 0L);
        NoteNote subfolder = buildNote(2L, folderId, 1L, 0L);
        NoteNote docInFolder = buildNote(4L, folderId, 2L, 0L);
        NoteNote docInSubfolder = buildNote(3L, 2L, 2L, 0L);

        when(noteNoteMapper.selectNoteNoteChildrenByParentId(folderId))
                .thenReturn(Arrays.asList(subfolder, docInFolder));
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(2L))
                .thenReturn(Collections.singletonList(docInSubfolder));
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(4L))
                .thenReturn(Collections.emptyList());
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(3L))
                .thenReturn(Collections.emptyList());

        List<NoteNote> result = invokeCollectDescendantNotes(folderId);

        assertEquals(3, result.size(), "应收集到 3 个子孙");
        // 验证所有子孙都被收集（顺序不固定，用 id 集合断言）
        List<Long> resultIds = new ArrayList<>();
        for (NoteNote n : result) {
            resultIds.add(n.getId());
        }
        assertTrue(resultIds.containsAll(Arrays.asList(2L, 3L, 4L)), "应包含 subfolder(2), doc(3), doc(4)");
        // 验证返回的是 NoteNote 对象（携带 noteType）
        for (NoteNote n : result) {
            assertNotNull(n.getNoteType(), "返回对象应携带 noteType");
        }
    }

    /**
     * T1 补充：跨 delFlag 状态收集
     * 子孙中部分 delFlag=0，部分 delFlag=1，全部应被收集
     */
    @Test
    public void T1b_collectDescendantNotes_跨delFlag状态_全部收集() throws Exception {
        Long folderId = 10L;
        NoteNote child1_normal = buildNote(11L, folderId, 2L, 0L);
        NoteNote child2_deleted = buildNote(12L, folderId, 2L, 1L);

        when(noteNoteMapper.selectNoteNoteChildrenByParentId(folderId))
                .thenReturn(Arrays.asList(child1_normal, child2_deleted));
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(11L))
                .thenReturn(Collections.emptyList());
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(12L))
                .thenReturn(Collections.emptyList());

        List<NoteNote> result = invokeCollectDescendantNotes(folderId);

        assertEquals(2, result.size(), "应收集到 2 个子孙（含 delFlag=1 的）");
        List<Long> resultIds = new ArrayList<>();
        for (NoteNote n : result) {
            resultIds.add(n.getId());
        }
        assertTrue(resultIds.containsAll(Arrays.asList(11L, 12L)), "应包含 delFlag=0 和 delFlag=1 的子孙");
    }

    /**
     * T1 补充：空文件夹（无子节点）返回空列表
     */
    @Test
    public void T1c_collectDescendantNotes_空文件夹_返回空列表() throws Exception {
        Long folderId = 20L;
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(folderId))
                .thenReturn(Collections.emptyList());

        List<NoteNote> result = invokeCollectDescendantNotes(folderId);

        assertTrue(result.isEmpty(), "空文件夹应返回空列表");
    }

    /**
     * T1 补充：单层扁平文件夹（多个直接子节点无嵌套）全部被收集
     */
    @Test
    public void T1d_collectDescendantNotes_扁平文件夹_全部收集() throws Exception {
        Long folderId = 30L;
        NoteNote c1 = buildNote(31L, folderId, 2L, 0L);
        NoteNote c2 = buildNote(32L, folderId, 3L, 0L);
        NoteNote c3 = buildNote(33L, folderId, 4L, 0L);

        when(noteNoteMapper.selectNoteNoteChildrenByParentId(folderId))
                .thenReturn(Arrays.asList(c1, c2, c3));
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(31L))
                .thenReturn(Collections.emptyList());
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(32L))
                .thenReturn(Collections.emptyList());
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(33L))
                .thenReturn(Collections.emptyList());

        List<NoteNote> result = invokeCollectDescendantNotes(folderId);

        assertEquals(3, result.size(), "应收集到 3 个直接子节点");
    }

    // ==================== T2: collectDescendantNotes 环防御 ====================

    /**
     * T2: 验证 parentId 形成环时迭代不无限循环，环节点被跳过
     * 结构：A(1) → B(2) → A(1)（环）
     * 预期：返回 [B(2)]（A 已 visited，被跳过），不抛异常
     */
    @Test
    public void T2_collectDescendantNotes_环防御_不无限循环() throws Exception {
        Long aId = 1L;
        Long bId = 2L;
        NoteNote b = buildNote(bId, aId, 2L, 0L);
        NoteNote a = buildNote(aId, bId, 2L, 0L);  // A 的 parentId 指向 B（形成环）

        when(noteNoteMapper.selectNoteNoteChildrenByParentId(aId))
                .thenReturn(Collections.singletonList(b));
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(bId))
                .thenReturn(Collections.singletonList(a));

        List<NoteNote> result = invokeCollectDescendantNotes(aId);

        // A 是起点（已 visited），B 被收集，再次回到 A 时被跳过
        assertEquals(1, result.size(), "环场景下应只收集到 1 个节点（B）");
        assertEquals(bId, result.get(0).getId(), "应收集到 B");
        // 验证没有抛异常（能正常返回即说明没死循环）
    }

    // ==================== T3: deleteNoteNoteByIds 文件夹级联软删除（含 1 层子节点） ====================

    /**
     * T3: 删除含 1 层子节点（1 个文档 + 1 个多维表格）的文件夹
     * 预期：
     * - 子文档的 NoteMeta.deleteFlag=1
     * - 子多维表格的 NoteDwtable 软删（deleteNoteDwtableByNoteId 被调用）
     * - 子笔记 delFlag=1（noteNoteMapper.deleteNoteNoteByIds 被调用，参数包含 folderId + 子孙 ID）
     */
    @Test
    public void T3_deleteNoteNoteByIds_含1层子节点_级联软删除() {
        Long folderId = 100L;
        Long docId = 101L;
        Long dwtableNoteId = 102L;

        NoteNote folder = buildNote(folderId, 0L, 1L, 0L);
        NoteNote doc = buildNote(docId, folderId, 2L, 0L);
        NoteNote dwtableNote = buildNote(dwtableNoteId, folderId, 4L, 0L);

        NoteMeta docMeta = new NoteMeta();
        docMeta.setNoteId(docId);
        docMeta.setDeleteFlag(0L);

        when(noteNoteMapper.selectNoteNoteById(folderId)).thenReturn(folder);
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(folderId))
                .thenReturn(Arrays.asList(doc, dwtableNote));
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(docId))
                .thenReturn(Collections.emptyList());
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(dwtableNoteId))
                .thenReturn(Collections.emptyList());
        when(noteMetaMapper.selectNoteMetaByNoteId(docId)).thenReturn(docMeta);

        int result = noteNoteService.deleteNoteNoteByIds(new String[]{folderId.toString()});

        assertEquals(1, result);
        // 验证子文档 NoteMeta.deleteFlag=1
        assertEquals(1L, docMeta.getDeleteFlag(), "子文档的 NoteMeta.deleteFlag 应被置为 1");
        verify(noteMetaMapper).updateNoteMeta(docMeta);
        // 验证子多维表格被软删（对所有子孙调用 deleteNoteDwtableByNoteId）
        verify(noteDwtableMapper).deleteNoteDwtableByNoteId(docId);
        verify(noteDwtableMapper).deleteNoteDwtableByNoteId(dwtableNoteId);
        // 验证 noteNoteMapper.deleteNoteNoteByIds 被调用，参数包含 folderId + 所有子孙 ID
        ArgumentCaptor<String[]> idsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(noteNoteMapper).deleteNoteNoteByIds(idsCaptor.capture());
        List<String> capturedIds = Arrays.asList(idsCaptor.getValue());
        assertTrue(capturedIds.contains(folderId.toString()), "批量软删除应包含 folderId");
        assertTrue(capturedIds.contains(docId.toString()), "批量软删除应包含 docId");
        assertTrue(capturedIds.contains(dwtableNoteId.toString()), "批量软删除应包含 dwtableNoteId");
    }

    // ==================== T4: deleteNoteNoteByIds 3 层嵌套文件夹 ====================

    /**
     * T4: 删除含 3 层嵌套（folder→subfolder→doc）的文件夹
     * 预期：所有子孙被批量软删除
     */
    @Test
    public void T4_deleteNoteNoteByIds_3层嵌套_所有子孙被软删除() {
        Long folderId = 200L;
        Long subfolderId = 201L;
        Long docId = 202L;

        NoteNote folder = buildNote(folderId, 0L, 1L, 0L);
        NoteNote subfolder = buildNote(subfolderId, folderId, 1L, 0L);
        NoteNote doc = buildNote(docId, subfolderId, 2L, 0L);

        when(noteNoteMapper.selectNoteNoteById(folderId)).thenReturn(folder);
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(folderId))
                .thenReturn(Collections.singletonList(subfolder));
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(subfolderId))
                .thenReturn(Collections.singletonList(doc));
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(docId))
                .thenReturn(Collections.emptyList());
        when(noteMetaMapper.selectNoteMetaByNoteId(docId)).thenReturn(null);

        int result = noteNoteService.deleteNoteNoteByIds(new String[]{folderId.toString()});

        assertEquals(1, result);
        ArgumentCaptor<String[]> idsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(noteNoteMapper).deleteNoteNoteByIds(idsCaptor.capture());
        List<String> capturedIds = Arrays.asList(idsCaptor.getValue());
        assertTrue(capturedIds.contains(folderId.toString()), "应包含 folderId");
        assertTrue(capturedIds.contains(subfolderId.toString()), "应包含 subfolderId");
        assertTrue(capturedIds.contains(docId.toString()), "应包含 docId");
        // NoteMeta 为 null 时不抛 NPE（防御性 if(noteMeta!=null) 检查）
        verify(noteMetaMapper, never()).updateNoteMeta(any());
    }

    // ==================== T5: deleteNoteFromGarbageByIds 文件夹级联物理删除（含 1 层子节点） ====================

    /**
     * T5: 物理清空含 1 层子节点（1 文档 + 1 多维表格）的文件夹
     * 预期：
     * - 子文档的 NoteMeta.deleteFlag=2
     * - 子多维表格的 noteDwtableServiceImpl.deleteNoteDwtableById 被调用（物理删 view/record/item(type=21)/column(type=21)/dwtable）
     * - noteNoteMapper.deleteNoteFromGarbageByIds 被调用，参数包含 folderId + 子孙 ID
     */
    @Test
    public void T5_deleteNoteFromGarbageByIds_含1层子节点_级联物理删除() {
        Long folderId = 300L;
        Long docId = 301L;
        Long dwtableNoteId = 302L;
        Long dwtableId = 3020L;  // NoteDwtable 表的主键 id

        NoteNote folder = buildNote(folderId, 0L, 1L, 1L);  // 文件夹已在回收站
        NoteNote doc = buildNote(docId, folderId, 2L, 1L);
        NoteNote dwtableNote = buildNote(dwtableNoteId, folderId, 4L, 1L);

        NoteMeta docMeta = new NoteMeta();
        docMeta.setNoteId(docId);
        docMeta.setDeleteFlag(1L);

        NoteDwtable dwtable = new NoteDwtable();
        dwtable.setId(dwtableId);
        dwtable.setNoteId(dwtableNoteId);

        when(noteNoteMapper.selectNoteNoteById(folderId)).thenReturn(folder);
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(folderId))
                .thenReturn(Arrays.asList(doc, dwtableNote));
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(docId))
                .thenReturn(Collections.emptyList());
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(dwtableNoteId))
                .thenReturn(Collections.emptyList());
        when(noteMetaMapper.selectNoteMetaByNoteId(docId)).thenReturn(docMeta);
        when(noteDwtableMapper.selectDWTableList(dwtableNoteId))
                .thenReturn(Collections.singletonList(dwtable));
        when(noteNoteMapper.deleteNoteFromGarbageByIds(any(String[].class))).thenReturn(1);

        int result = noteNoteService.deleteNoteFromGarbageByIds(new String[]{folderId.toString()});

        assertEquals(1, result);
        // 验证子文档 NoteMeta.deleteFlag=2
        assertEquals(2L, docMeta.getDeleteFlag(), "子文档的 NoteMeta.deleteFlag 应被置为 2");
        verify(noteMetaMapper).updateNoteMeta(docMeta);
        // 验证子多维表格被物理删除（noteDwtableServiceImpl.deleteNoteDwtableById 被调用）
        verify(noteDwtableServiceImpl).deleteNoteDwtableById(dwtableId);
        // 验证 noteNoteMapper.deleteNoteFromGarbageByIds 被调用，参数包含 folderId + 子孙 ID
        ArgumentCaptor<String[]> idsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(noteNoteMapper).deleteNoteFromGarbageByIds(idsCaptor.capture());
        List<String> capturedIds = Arrays.asList(idsCaptor.getValue());
        assertTrue(capturedIds.contains(folderId.toString()), "批量物理删除应包含 folderId");
        assertTrue(capturedIds.contains(docId.toString()), "批量物理删除应包含 docId");
        assertTrue(capturedIds.contains(dwtableNoteId.toString()), "批量物理删除应包含 dwtableNoteId");
    }

    // ==================== T6: deleteNoteFromGarbageByIds 3 层嵌套 ====================

    /**
     * T6: 物理清空含 3 层嵌套的文件夹，所有子孙被批量物理删除
     */
    @Test
    public void T6_deleteNoteFromGarbageByIds_3层嵌套_所有子孙被物理删除() {
        Long folderId = 400L;
        Long subfolderId = 401L;
        Long docId = 402L;

        NoteNote folder = buildNote(folderId, 0L, 1L, 1L);
        NoteNote subfolder = buildNote(subfolderId, folderId, 1L, 1L);
        NoteNote doc = buildNote(docId, subfolderId, 2L, 1L);

        when(noteNoteMapper.selectNoteNoteById(folderId)).thenReturn(folder);
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(folderId))
                .thenReturn(Collections.singletonList(subfolder));
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(subfolderId))
                .thenReturn(Collections.singletonList(doc));
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(docId))
                .thenReturn(Collections.emptyList());
        when(noteMetaMapper.selectNoteMetaByNoteId(docId)).thenReturn(null);
        when(noteNoteMapper.deleteNoteFromGarbageByIds(any(String[].class))).thenReturn(1);

        int result = noteNoteService.deleteNoteFromGarbageByIds(new String[]{folderId.toString()});

        assertEquals(1, result);
        ArgumentCaptor<String[]> idsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(noteNoteMapper).deleteNoteFromGarbageByIds(idsCaptor.capture());
        List<String> capturedIds = Arrays.asList(idsCaptor.getValue());
        assertTrue(capturedIds.contains(folderId.toString()), "应包含 folderId");
        assertTrue(capturedIds.contains(subfolderId.toString()), "应包含 subfolderId");
        assertTrue(capturedIds.contains(docId.toString()), "应包含 docId");
    }

    // ==================== T7: recoverNoteNoteByIds 文件夹级联恢复（含 1 层子节点） ====================

    /**
     * T7: 恢复含 1 层子节点（1 文档 + 1 多维表格）的文件夹
     * 预期：
     * - 子文档的 NoteMeta.deleteFlag=0
     * - 子多维表格的 recoverNoteDwtableByNoteId 被调用（软恢复）
     * - noteNoteMapper.recoverNoteNoteByIds 被调用，参数包含 folderId + 子孙 ID
     */
    @Test
    public void T7_recoverNoteNoteByIds_含1层子节点_级联恢复() {
        Long folderId = 500L;
        Long docId = 501L;
        Long dwtableNoteId = 502L;

        NoteNote folder = buildNote(folderId, 0L, 1L, 1L);  // 文件夹在回收站
        NoteNote doc = buildNote(docId, folderId, 2L, 1L);
        NoteNote dwtableNote = buildNote(dwtableNoteId, folderId, 4L, 1L);

        NoteMeta docMeta = new NoteMeta();
        docMeta.setNoteId(docId);
        docMeta.setDeleteFlag(1L);

        when(noteNoteMapper.selectNoteNoteById(folderId)).thenReturn(folder);
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(folderId))
                .thenReturn(Arrays.asList(doc, dwtableNote));
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(docId))
                .thenReturn(Collections.emptyList());
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(dwtableNoteId))
                .thenReturn(Collections.emptyList());
        when(noteMetaMapper.selectNoteMetaByNoteId(docId)).thenReturn(docMeta);
        when(noteNoteMapper.recoverNoteNoteByIds(any(String[].class))).thenReturn(1);

        int result = noteNoteService.recoverNoteNoteByIds(new String[]{folderId.toString()});

        assertEquals(1, result);
        // 验证子文档 NoteMeta.deleteFlag=0
        assertEquals(0L, docMeta.getDeleteFlag(), "子文档的 NoteMeta.deleteFlag 应被置为 0");
        verify(noteMetaMapper).updateNoteMeta(docMeta);
        // 验证子多维表格被软恢复（对所有子孙调用 recoverNoteDwtableByNoteId）
        verify(noteDwtableMapper).recoverNoteDwtableByNoteId(docId);
        verify(noteDwtableMapper).recoverNoteDwtableByNoteId(dwtableNoteId);
        // 验证 noteNoteMapper.recoverNoteNoteByIds 被调用，参数包含 folderId + 子孙 ID
        ArgumentCaptor<String[]> idsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(noteNoteMapper).recoverNoteNoteByIds(idsCaptor.capture());
        List<String> capturedIds = Arrays.asList(idsCaptor.getValue());
        assertTrue(capturedIds.contains(folderId.toString()), "批量恢复应包含 folderId");
        assertTrue(capturedIds.contains(docId.toString()), "批量恢复应包含 docId");
        assertTrue(capturedIds.contains(dwtableNoteId.toString()), "批量恢复应包含 dwtableNoteId");
    }

    // ==================== T8: recoverNoteNoteByIds 3 层嵌套 ====================

    /**
     * T8: 恢复含 3 层嵌套（folder→subfolder→doc）的文件夹，所有子孙被批量恢复
     */
    @Test
    public void T8_recoverNoteNoteByIds_3层嵌套_所有子孙被恢复() {
        Long folderId = 600L;
        Long subfolderId = 601L;
        Long docId = 602L;

        NoteNote folder = buildNote(folderId, 0L, 1L, 1L);
        NoteNote subfolder = buildNote(subfolderId, folderId, 1L, 1L);
        NoteNote doc = buildNote(docId, subfolderId, 2L, 1L);

        NoteMeta docMeta = new NoteMeta();
        docMeta.setNoteId(docId);
        docMeta.setDeleteFlag(1L);

        when(noteNoteMapper.selectNoteNoteById(folderId)).thenReturn(folder);
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(folderId))
                .thenReturn(Collections.singletonList(subfolder));
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(subfolderId))
                .thenReturn(Collections.singletonList(doc));
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(docId))
                .thenReturn(Collections.emptyList());
        when(noteMetaMapper.selectNoteMetaByNoteId(docId)).thenReturn(docMeta);
        when(noteNoteMapper.recoverNoteNoteByIds(any(String[].class))).thenReturn(1);

        int result = noteNoteService.recoverNoteNoteByIds(new String[]{folderId.toString()});

        assertEquals(1, result);
        ArgumentCaptor<String[]> idsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(noteNoteMapper).recoverNoteNoteByIds(idsCaptor.capture());
        List<String> capturedIds = Arrays.asList(idsCaptor.getValue());
        assertTrue(capturedIds.contains(folderId.toString()), "应包含 folderId");
        assertTrue(capturedIds.contains(subfolderId.toString()), "应包含 subfolderId");
        assertTrue(capturedIds.contains(docId.toString()), "应包含 docId");
    }

    // ==================== T9: 空文件夹 edge case（覆盖 U2/U3/U4） ====================

    /**
     * T9: 空文件夹（无子孙）的删除/清空/恢复行为与现状一致——仅操作文件夹自身
     * 覆盖 U2/U3/U4 的 edge case
     */
    @Test
    public void T9_空文件夹_删除清空恢复_仅操作文件夹自身() {
        Long folderId = 700L;
        NoteNote folder = buildNote(folderId, 0L, 1L, 0L);

        when(noteNoteMapper.selectNoteNoteById(folderId)).thenReturn(folder);
        when(noteNoteMapper.selectNoteNoteChildrenByParentId(folderId))
                .thenReturn(Collections.emptyList());
        when(noteNoteMapper.deleteNoteFromGarbageByIds(any(String[].class))).thenReturn(1);
        when(noteNoteMapper.recoverNoteNoteByIds(any(String[].class))).thenReturn(1);

        // 软删除空文件夹
        noteNoteService.deleteNoteNoteByIds(new String[]{folderId.toString()});
        ArgumentCaptor<String[]> softDeleteCaptor = ArgumentCaptor.forClass(String[].class);
        verify(noteNoteMapper).deleteNoteNoteByIds(softDeleteCaptor.capture());
        assertEquals(1, softDeleteCaptor.getValue().length, "空文件夹软删除应只包含 folderId 自身");
        assertEquals(folderId.toString(), softDeleteCaptor.getValue()[0]);

        // 物理清空空文件夹
        noteNoteService.deleteNoteFromGarbageByIds(new String[]{folderId.toString()});
        ArgumentCaptor<String[]> physicalDeleteCaptor = ArgumentCaptor.forClass(String[].class);
        verify(noteNoteMapper).deleteNoteFromGarbageByIds(physicalDeleteCaptor.capture());
        assertEquals(1, physicalDeleteCaptor.getValue().length, "空文件夹物理清空应只包含 folderId 自身");
        assertEquals(folderId.toString(), physicalDeleteCaptor.getValue()[0]);

        // 恢复空文件夹
        noteNoteService.recoverNoteNoteByIds(new String[]{folderId.toString()});
        ArgumentCaptor<String[]> recoverCaptor = ArgumentCaptor.forClass(String[].class);
        verify(noteNoteMapper).recoverNoteNoteByIds(recoverCaptor.capture());
        assertEquals(1, recoverCaptor.getValue().length, "空文件夹恢复应只包含 folderId 自身");
        assertEquals(folderId.toString(), recoverCaptor.getValue()[0]);

        // 空文件夹不应触发任何子孙关联数据处理
        verify(noteMetaMapper, never()).updateNoteMeta(any());
        verify(noteDwtableServiceImpl, never()).deleteNoteDwtableById(anyLong());
    }
}
