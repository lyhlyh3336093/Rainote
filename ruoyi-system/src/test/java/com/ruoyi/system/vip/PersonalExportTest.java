package com.ruoyi.system.vip;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.NoteDwtable;
import com.ruoyi.system.domain.NoteDwtableItem;
import com.ruoyi.system.domain.NoteNote;
import com.ruoyi.system.domain.NoteRecord;
import com.ruoyi.system.domain.NoteView;
import com.ruoyi.system.domain.SysUploadRecord;
import com.ruoyi.system.domain.vo.NoteRecordVo;
import com.ruoyi.system.mapper.NoteColumnMapper;
import com.ruoyi.system.mapper.NoteDwtableItemMapper;
import com.ruoyi.system.mapper.NoteDwtableMapper;
import com.ruoyi.system.mapper.NoteNoteMapper;
import com.ruoyi.system.mapper.NoteRecordMapper;
import com.ruoyi.system.mapper.NoteViewMapper;
import com.ruoyi.system.mapper.SysUploadRecordMapper;
import com.ruoyi.system.service.impl.PersonalExportServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PersonalExportServiceImpl} VIP 个人数据导出测试（U9 Test scenarios）。
 * <p>
 * 覆盖：导出内容只含该用户本人数据（AE-P3）；无笔记用户空导出不报错（含 manifest）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PersonalExportTest
{
    private static final Long USER_A = 2L;
    private static final Long USER_B = 3L;

    @Mock
    private NoteNoteMapper noteNoteMapper;

    @Mock
    private NoteDwtableMapper noteDwtableMapper;

    @Mock
    private NoteColumnMapper noteColumnMapper;

    @Mock
    private NoteViewMapper noteViewMapper;

    @Mock
    private NoteRecordMapper noteRecordMapper;

    @Mock
    private NoteDwtableItemMapper noteDwtableItemMapper;

    @Mock
    private SysUploadRecordMapper sysUploadRecordMapper;

    @InjectMocks
    private PersonalExportServiceImpl exportService;

    @BeforeEach
    void setUp()
    {
        when(noteDwtableMapper.selectDWTableList(any(Long.class))).thenReturn(Collections.emptyList());
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Collections.emptyList());
        when(noteViewMapper.selectNoteViewListByDwtableId(any(Long.class))).thenReturn(Collections.emptyList());
        when(noteRecordMapper.selectNoteRecordList(any(NoteRecordVo.class))).thenReturn(Collections.emptyList());
        when(noteDwtableItemMapper.selectNoteDwtableItemList(any(NoteDwtableItem.class))).thenReturn(Collections.emptyList());
        when(sysUploadRecordMapper.selectSysUploadRecordByUserId(any(Long.class))).thenReturn(Collections.emptyList());
    }

    @Test
    void export_userANotesOnly_neverContainsUserBData() throws Exception
    {
        // 用户 A 一篇笔记；用户 B 的笔记绝不在 A 的归集结果中（selectNoteListByAuthId 以属主过滤）
        NoteNote noteA = new NoteNote();
        noteA.setId(1L);
        noteA.setTitle("A的笔记");
        noteA.setAuth(USER_A);
        when(noteNoteMapper.selectNoteListByAuthId(USER_A))
                .thenReturn(new ArrayList<>(java.util.Arrays.asList(noteA)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportService.writeZipTo(USER_A, out);

        // 数据归集只查 A 的笔记列表（从未查 B）
        verify(noteNoteMapper).selectNoteListByAuthId(USER_A);
        byte[] zipBytes = out.toByteArray();
        assertTrue(zipBytes.length > 0, "导出包应有内容");
        // notes.json 只含 A 的笔记标题
        String notesJson = readEntry(zipBytes, "notes.json");
        assertTrue(notesJson.contains("A的笔记"));
        assertFalse(notesJson.contains("B"), "A 的导出包不得包含 B 的任何数据（AE-P3）");
    }

    @Test
    void export_emptyUser_producesManifestWithoutError() throws Exception
    {
        when(noteNoteMapper.selectNoteListByAuthId(USER_B)).thenReturn(Collections.emptyList());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportService.writeZipTo(USER_B, out);

        String manifest = readEntry(out.toByteArray(), "manifest.json");
        assertTrue(manifest.contains("\"userId\":" + USER_B));
        assertTrue(manifest.contains("\"noteCount\":0"));
    }

    /** 读取 zip 内指定文本条目内容 */
    private String readEntry(byte[] zipBytes, String entryName) throws Exception
    {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes)))
        {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null)
            {
                if (entry.getName().equals(entryName))
                {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] chunk = new byte[1024];
                    int len;
                    while ((len = zip.read(chunk)) > 0)
                    {
                        buffer.write(chunk, 0, len);
                    }
                    return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("zip 内缺少条目: " + entryName);
    }
}
