package com.ruoyi.system.service.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.NoteDwtableItem;
import com.ruoyi.system.domain.NoteRecord;
import com.ruoyi.system.domain.dto.ParsedInsert;
import com.ruoyi.system.mapper.NoteColumnMapper;
import com.ruoyi.system.mapper.NoteDwtableItemMapper;
import com.ruoyi.system.mapper.NoteRecordMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NoteDwtableImportServiceImpl} 单元测试。
 * <p>
 * 覆盖 AE1 round-trip、AE2/AE3 列名宽松匹配、AE4/AE8 排除类型列、
 * AE5/AE6 失败回滚（异常传播）、AE7 多文件顺序追加、F2 sort=max+1 差异、
 * R15 NULL→空串、参数校验。
 *
 * @author ruoyi
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NoteDwtableImportServiceImplTest
{
    private static final Long NOTE_ID = 50L;
    private static final Long DWTABLE_ID = 60L;
    private static final Long USER_ID = 70L;

    @Mock
    private NoteColumnMapper noteColumnMapper;

    @Mock
    private NoteRecordMapper noteRecordMapper;

    @Mock
    private NoteDwtableItemMapper noteDwtableItemMapper;

    @InjectMocks
    private NoteDwtableImportServiceImpl importService;

    @Captor
    private ArgumentCaptor<List<NoteDwtableItem>> itemsCaptor;

    /** 模拟 NoteRecordMapper.insertNoteRecord 的 useGeneratedKeys 自增 id 回填 */
    private final AtomicLong idSequence = new AtomicLong(1000L);

    @BeforeEach
    void setUp()
    {
        when(noteRecordMapper.insertNoteRecord(any(NoteRecord.class))).thenAnswer(inv -> {
            NoteRecord record = inv.getArgument(0);
            record.setId(idSequence.getAndIncrement());
            return 1;
        });
        when(noteRecordMapper.selectMaxSortByDwtableId(DWTABLE_ID)).thenReturn(5L);
        when(noteDwtableItemMapper.insertNoteDwtableItems(anyList())).thenReturn(1);
    }

    private NoteColumn column(Long id, String name, Long type)
    {
        NoteColumn column = new NoteColumn();
        column.setId(id);
        column.setName(name);
        column.setType(type);
        return column;
    }

    private ParsedInsert parsed(Long sourceRecordId, Object... nameValuePairs)
    {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < nameValuePairs.length; i += 2)
        {
            values.put((String) nameValuePairs[i], (String) nameValuePairs[i + 1]);
        }
        return new ParsedInsert(sourceRecordId, values);
    }

    /** 目标表列：名称(type=1)、数量(type=2) */
    private void mockStandardColumns()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class)))
                .thenReturn(Arrays.asList(column(101L, "名称", 1L), column(102L, "数量", 2L)));
    }

    @Test
    void importData_roundTrip_createsRecordsAndItems()
    {
        mockStandardColumns();
        List<ParsedInsert> parsedList = Arrays.asList(
                parsed(10L, "名称", "foo", "数量", "1"),
                parsed(11L, "名称", "bar", "数量", "2"),
                parsed(12L, "名称", "baz", "数量", "3"));

        int count = importService.importData(NOTE_ID, DWTABLE_ID, parsedList, USER_ID);

        assertEquals(3, count);
        // AE1 + F2：3 条 NoteRecord，sort=max+1 起递增（6/7/8），name 由首个 type=1 列值派生，默认值 null
        ArgumentCaptor<NoteRecord> recordCaptor = ArgumentCaptor.forClass(NoteRecord.class);
        verify(noteRecordMapper, times(3)).insertNoteRecord(recordCaptor.capture());
        List<NoteRecord> records = recordCaptor.getAllValues();
        for (int i = 0; i < 3; i++)
        {
            assertEquals(DWTABLE_ID, records.get(i).getDwtableId());
            assertEquals(Long.valueOf(6L + i), records.get(i).getSort());
            // name 派生语义：首个 type=1 列（名称）的本行值
            assertEquals(Arrays.asList("foo", "bar", "baz").get(i), records.get(i).getName());
            assertNull(records.get(i).getViewId());
            assertNull(records.get(i).getProperty());
            assertNull(records.get(i).getLinkRecordId());
            assertNull(records.get(i).getLinkName());
        }
        // N+1 回归防护：整个导入仅查一次列定义（name 派生已内联，不再每行调用 deriveRecordName 重查）
        verify(noteColumnMapper, times(1)).selectNoteColumnList(any(NoteColumn.class));
        // AE1：items 批量写入，新 id（1000+）非源 record_id（10/11/12）
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        List<NoteDwtableItem> items = itemsCaptor.getValue();
        assertEquals(6, items.size());
        for (int i = 0; i < 3; i++)
        {
            NoteDwtableItem nameItem = items.get(i * 2);
            NoteDwtableItem countItem = items.get(i * 2 + 1);
            assertEquals(Long.valueOf(1000L + i), nameItem.getRecordId());
            assertEquals(Long.valueOf(1000L + i), countItem.getRecordId());
            assertEquals(DWTABLE_ID, nameItem.getDwtId());
            assertEquals(Long.valueOf(101L), nameItem.getColumnId());
            assertEquals(Long.valueOf(102L), countItem.getColumnId());
            // R16：link 字段不导入
            assertNull(nameItem.getLinkRecordId());
            assertNull(nameItem.getLinkColumnId());
            assertNull(nameItem.getLinkBlockId());
            assertNull(nameItem.getLinkNoteId());
        }
        assertEquals("foo", items.get(0).getValue());
        assertEquals("1", items.get(1).getValue());
        assertEquals("baz", items.get(4).getValue());
    }

    @Test
    void importData_sqlColumnNotInTable_skipped()
    {
        mockStandardColumns();
        // AE2：SQL 含目标表不存在的列"备注" → 跳过，仅写 名称/数量
        List<ParsedInsert> parsedList = Collections.singletonList(
                parsed(10L, "名称", "foo", "数量", "1", "备注", "extra"));

        int count = importService.importData(NOTE_ID, DWTABLE_ID, parsedList, USER_ID);

        assertEquals(1, count);
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        assertEquals(2, itemsCaptor.getValue().size());
    }

    @Test
    void importData_tableColumnNotInSql_usesDefault()
    {
        mockStandardColumns();
        // AE3：目标表列多于 SQL → 未提供的列不写 NoteDwtableItem（走默认值）
        List<ParsedInsert> parsedList = Collections.singletonList(
                parsed(10L, "名称", "foo"));

        int count = importService.importData(NOTE_ID, DWTABLE_ID, parsedList, USER_ID);

        assertEquals(1, count);
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        assertEquals(1, itemsCaptor.getValue().size());
        assertEquals(Long.valueOf(101L), itemsCaptor.getValue().get(0).getColumnId());
    }

    @Test
    void importData_excludedTypeColumnInSqlAndTable_skipped()
    {
        // AE4：目标表含双向链接列(type=21)"关联"，SQL 也提供值 → 双列整体跳过
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class)))
                .thenReturn(Arrays.asList(column(101L, "名称", 1L), column(103L, "关联", 21L)));
        List<ParsedInsert> parsedList = Collections.singletonList(
                parsed(10L, "名称", "foo", "关联", "1,2"));

        int count = importService.importData(NOTE_ID, DWTABLE_ID, parsedList, USER_ID);

        assertEquals(1, count);
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        assertEquals(1, itemsCaptor.getValue().size());
        assertEquals(Long.valueOf(101L), itemsCaptor.getValue().get(0).getColumnId());
    }

    @Test
    void importData_excludedTypeColumnOnlyInTable_noItemWritten()
    {
        // AE8：目标表含公式列(type=20)"小计"，SQL 无该列 → 不写 NoteDwtableItem
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class)))
                .thenReturn(Arrays.asList(column(101L, "名称", 1L), column(104L, "小计", 20L)));
        List<ParsedInsert> parsedList = Collections.singletonList(
                parsed(10L, "名称", "foo"));

        int count = importService.importData(NOTE_ID, DWTABLE_ID, parsedList, USER_ID);

        assertEquals(1, count);
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        assertEquals(1, itemsCaptor.getValue().size());
    }

    @Test
    void importData_recordInsertFails_exceptionPropagatesAndNoItemWritten()
    {
        // AE5：首行 NoteRecord 写入失败 → 异常传播（@Transactional rollbackFor=Exception 整体回滚）
        mockStandardColumns();
        when(noteRecordMapper.insertNoteRecord(any(NoteRecord.class)))
                .thenThrow(new ServiceException("写入失败"));

        assertThrows(ServiceException.class, () -> importService.importData(
                NOTE_ID, DWTABLE_ID, Collections.singletonList(parsed(10L, "名称", "foo")), USER_ID));
        verify(noteDwtableItemMapper, never()).insertNoteDwtableItems(anyList());
    }

    @Test
    void importData_midwayInsertFails_exceptionPropagatesAndBatchNotFlushed()
    {
        // AE6：第 2 行 insert 失败 → 异常传播，累积 batch 未 flush（事务回滚由 Spring 保证）
        mockStandardColumns();
        AtomicLong calls = new AtomicLong();
        when(noteRecordMapper.insertNoteRecord(any(NoteRecord.class))).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 2L)
            {
                throw new ServiceException("约束冲突");
            }
            NoteRecord record = inv.getArgument(0);
            record.setId(idSequence.getAndIncrement());
            return 1;
        });

        List<ParsedInsert> parsedList = Arrays.asList(
                parsed(10L, "名称", "foo"),
                parsed(11L, "名称", "bar"),
                parsed(12L, "名称", "baz"));

        assertThrows(ServiceException.class, () -> importService.importData(NOTE_ID, DWTABLE_ID, parsedList, USER_ID));
        verify(noteRecordMapper, times(2)).insertNoteRecord(any(NoteRecord.class));
        verify(noteDwtableItemMapper, never()).insertNoteDwtableItems(anyList());
    }

    @Test
    void importData_multiFileAppended_preservesOrderAndSortSequence()
    {
        // AE7：两个来源文件的 3+3 条合并列表 → 顺序追加，sort 连续递增
        mockStandardColumns();
        List<ParsedInsert> parsedList = new ArrayList<>();
        for (long i = 10L; i <= 15L; i++)
        {
            parsedList.add(parsed(i, "名称", "v" + i));
        }

        int count = importService.importData(NOTE_ID, DWTABLE_ID, parsedList, USER_ID);

        assertEquals(6, count);
        ArgumentCaptor<NoteRecord> recordCaptor = ArgumentCaptor.forClass(NoteRecord.class);
        verify(noteRecordMapper, times(6)).insertNoteRecord(recordCaptor.capture());
        for (int i = 0; i < 6; i++)
        {
            assertEquals(Long.valueOf(6L + i), recordCaptor.getAllValues().get(i).getSort());
        }
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        assertEquals(6, itemsCaptor.getValue().size());
        assertEquals("v10", itemsCaptor.getValue().get(0).getValue());
        assertEquals("v15", itemsCaptor.getValue().get(5).getValue());
    }

    @Test
    void importData_emptyTable_sortStartsAtOne()
    {
        // F2：空表（maxSort=null）→ 导入记录 sort=1,2,3 递增
        // （UI 新增路径 sort=null 是 insertNoteRecord 既有行为，与本导入路径有意 diverge）
        mockStandardColumns();
        when(noteRecordMapper.selectMaxSortByDwtableId(DWTABLE_ID)).thenReturn(null);
        List<ParsedInsert> parsedList = Arrays.asList(
                parsed(10L, "名称", "a"), parsed(11L, "名称", "b"), parsed(12L, "名称", "c"));

        importService.importData(NOTE_ID, DWTABLE_ID, parsedList, USER_ID);

        ArgumentCaptor<NoteRecord> recordCaptor = ArgumentCaptor.forClass(NoteRecord.class);
        verify(noteRecordMapper, times(3)).insertNoteRecord(recordCaptor.capture());
        assertEquals(Long.valueOf(1L), recordCaptor.getAllValues().get(0).getSort());
        assertEquals(Long.valueOf(2L), recordCaptor.getAllValues().get(1).getSort());
        assertEquals(Long.valueOf(3L), recordCaptor.getAllValues().get(2).getSort());
    }

    @Test
    void importData_nullValue_writtenAsEmptyString()
    {
        // R15：SQL NULL（Java null）→ item value 空串
        mockStandardColumns();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("名称", null);
        List<ParsedInsert> parsedList = Collections.singletonList(new ParsedInsert(10L, values));

        importService.importData(NOTE_ID, DWTABLE_ID, parsedList, USER_ID);

        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        assertEquals("", itemsCaptor.getValue().get(0).getValue());
    }

    @Test
    void importData_overBatchLimit_flushesItemsInBatches()
    {
        // KTD5：单批 500 单元格上限——251 行 × 2 列 = 502 单元格 → 2 次 flush（500 + 2）
        // 生产代码 flush 后 clear 同一 batch 引用，captor 看到的是终态，需在 stub 内快照
        mockStandardColumns();
        List<List<NoteDwtableItem>> flushed = new ArrayList<>();
        when(noteDwtableItemMapper.insertNoteDwtableItems(anyList())).thenAnswer(inv -> {
            flushed.add(new ArrayList<>(inv.getArgument(0)));
            return 1;
        });
        List<ParsedInsert> parsedList = new ArrayList<>();
        for (long i = 1L; i <= 251L; i++)
        {
            parsedList.add(parsed(i, "名称", "v" + i, "数量", String.valueOf(i)));
        }

        int count = importService.importData(NOTE_ID, DWTABLE_ID, parsedList, USER_ID);

        assertEquals(251, count);
        verify(noteDwtableItemMapper, times(2)).insertNoteDwtableItems(anyList());
        assertEquals(2, flushed.size());
        assertEquals(500, flushed.get(0).size());
        assertEquals(2, flushed.get(1).size());
        // 跨批顺序与 recordId 连续性
        assertEquals("v1", flushed.get(0).get(0).getValue());
        assertEquals("v250", flushed.get(0).get(498).getValue());
        assertEquals("v251", flushed.get(1).get(0).getValue());
        // 第一批最后一行（第 250 行）与第二批首行（第 251 行）的 recordId 连续
        assertEquals(Long.valueOf(1249L), flushed.get(0).get(499).getRecordId());
        assertEquals(Long.valueOf(1250L), flushed.get(1).get(0).getRecordId());
    }

    @Test
    void importData_sanitizedColumnName_matchesOriginalColumn()
    {
        // F3 修复：目标列 "C Name"（含空格）导出时被净化为 C_Name → 导入端净化名兜底键回中
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class)))
                .thenReturn(Collections.singletonList(column(101L, "C Name", 1L)));
        List<ParsedInsert> parsedList = Collections.singletonList(
                parsed(10L, "C_Name", "foo"));

        int count = importService.importData(NOTE_ID, DWTABLE_ID, parsedList, USER_ID);

        assertEquals(1, count);
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        assertEquals(1, itemsCaptor.getValue().size());
        assertEquals(Long.valueOf(101L), itemsCaptor.getValue().get(0).getColumnId());
        assertEquals("foo", itemsCaptor.getValue().get(0).getValue());
    }

    @Test
    void importData_originalNamePreferredOverSanitizedKey()
    {
        // 原名优先：目标表同时有 "col name"（净化名 col_name）与真名 "col_name" 列，
        // SQL 列 col_name 应命中真名列（102），而非净化兜底键（101）
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class)))
                .thenReturn(Arrays.asList(column(101L, "col name", 1L), column(102L, "col_name", 2L)));
        List<ParsedInsert> parsedList = Collections.singletonList(
                parsed(10L, "col_name", "v"));

        importService.importData(NOTE_ID, DWTABLE_ID, parsedList, USER_ID);

        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        assertEquals(1, itemsCaptor.getValue().size());
        assertEquals(Long.valueOf(102L), itemsCaptor.getValue().get(0).getColumnId());
    }

    @Test
    void importData_emptyParsedList_throwsServiceException()
    {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> importService.importData(NOTE_ID, DWTABLE_ID, Collections.emptyList(), USER_ID));
        assertTrue(ex.getMessage().contains("INSERT"));
    }

    @Test
    void importData_nullDwtableId_throwsServiceException()
    {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> importService.importData(NOTE_ID, null, Collections.singletonList(parsed(1L, "名称", "a")), USER_ID));
        assertTrue(ex.getMessage().contains("多维表格"));
    }
}
