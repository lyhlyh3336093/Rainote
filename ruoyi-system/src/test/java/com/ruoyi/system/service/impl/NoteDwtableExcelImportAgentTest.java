package com.ruoyi.system.service.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.agent.security.AgentOwnershipChecker;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.NoteDwtable;
import com.ruoyi.system.domain.NoteDwtableItem;
import com.ruoyi.system.domain.NoteRecord;
import com.ruoyi.system.domain.dto.ExcelImportAgentResult;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult;
import com.ruoyi.system.domain.vo.NoteRecordVo;
import com.ruoyi.system.mapper.NoteColumnMapper;
import com.ruoyi.system.mapper.NoteDwtableItemMapper;
import com.ruoyi.system.mapper.NoteDwtableMapper;
import com.ruoyi.system.mapper.NoteRecordMapper;
import com.ruoyi.system.service.INoteRecordService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code dwtable.importExcel} agent 操作单元测试。
 * <p>
 * 覆盖：无缺参成功导入、columnValues 覆盖缺参（含数字归一化）、缺参未覆盖失败态
 * （零写入 + 结构化清单）、关联未命中失败清单、歧义预选成功报告、
 * 路径穿越拒绝、非 profile 前缀拒绝、文件不存在、归属失败穿透。
 *
 * @author ruoyi
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NoteDwtableExcelImportAgentTest
{
    private static final Long NOTE_ID = 50L;

    private static final Long DWTABLE_ID = 60L;

    private static final Long USER_ID = 70L;

    private static final Long RELATED_TABLE_ID = 80L;

    @Mock
    private NoteDwtableMapper noteDwtableMapper;

    @Mock
    private NoteColumnMapper noteColumnMapper;

    @Mock
    private NoteRecordMapper noteRecordMapper;

    @Mock
    private NoteDwtableItemMapper noteDwtableItemMapper;

    @Mock
    private AgentOwnershipChecker agentOwnershipChecker;

    @Mock
    private INoteRecordService noteRecordService;

    @InjectMocks
    private NoteDwtableExcelImportServiceImpl service;

    @Captor
    private ArgumentCaptor<List<NoteDwtableItem>> itemsCaptor;

    @TempDir
    Path profileRoot;

    /** 模拟 NoteRecordMapper.insertNoteRecord 的 useGeneratedKeys 自增 id 回填 */
    private final AtomicLong recordIdSequence = new AtomicLong(1000L);

    @BeforeEach
    void setUp()
    {
        new RuoYiConfig().setProfile(profileRoot.toString());
        NoteDwtable dwtable = new NoteDwtable();
        dwtable.setId(DWTABLE_ID);
        dwtable.setNoteId(NOTE_ID);
        dwtable.setName("目标表");
        when(noteDwtableMapper.selectNoteDwtableById(DWTABLE_ID)).thenReturn(dwtable);
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class)))
                .thenReturn(Collections.emptyList());
        service.setSelf(service);
        when(noteRecordMapper.insertNoteRecord(any(NoteRecord.class))).thenAnswer(inv -> {
            NoteRecord record = inv.getArgument(0);
            record.setId(recordIdSequence.getAndIncrement());
            return 1;
        });
        when(noteRecordMapper.selectMaxSortByDwtableId(DWTABLE_ID)).thenReturn(5L);
        when(noteDwtableItemMapper.insertNoteDwtableItems(anyList())).thenAnswer(inv -> {
            List<NoteDwtableItem> items = inv.getArgument(0);
            return items.size();
        });
    }

    // ===== 构造工具 =====

    private NoteColumn column(Long id, String name, Long type, String property)
    {
        NoteColumn col = new NoteColumn();
        col.setId(id);
        col.setName(name);
        col.setType(type);
        col.setProperty(property);
        col.setIsShow(0L);
        col.setDwtableId(DWTABLE_ID);
        return col;
    }

    private NoteRecord record(Long id, String name, Long sort)
    {
        NoteRecord rec = new NoteRecord();
        rec.setId(id);
        rec.setName(name);
        rec.setSort(sort);
        rec.setDwtableId(RELATED_TABLE_ID);
        return rec;
    }

    private void mockRelatedTable()
    {
        NoteDwtable related = new NoteDwtable();
        related.setId(RELATED_TABLE_ID);
        related.setName("地点表");
        related.setNoteId(NOTE_ID);
        when(noteDwtableMapper.selectNoteDwtableById(RELATED_TABLE_ID)).thenReturn(related);
    }

    private void mockRelatedRecords(List<NoteRecord> records)
    {
        when(noteRecordMapper.selectNoteRecordList(any(NoteRecordVo.class))).thenAnswer(inv -> {
            NoteRecordVo query = inv.getArgument(0);
            if (RELATED_TABLE_ID.equals(query.getDwtableId()))
            {
                return new ArrayList<>(records);
            }
            return new ArrayList<NoteRecord>();
        });
    }

    private byte[] workbookBytes(Consumer<XSSFWorkbook> customizer)
    {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            customizer.accept(workbook);
            workbook.write(out);
            return out.toByteArray();
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 填充单 sheet（对齐现有测试 fillSheet 模式）：行引用一次创建后逐单元格填充——
     * POI 的 createRow(rowNum) 对已存在行会移除重建，重复调用同行会丢弃先前的单元格。
     */
    private byte[] singleSheetBytes(String[] headers, List<String[]> rows)
    {
        return workbookBytes(workbook -> {
            XSSFSheet sheet = workbook.createSheet("目标表");
            org.apache.poi.ss.usermodel.Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++)
            {
                headerRow.createCell(i).setCellValue(headers[i]);
            }
            for (int r = 0; r < rows.size(); r++)
            {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r + 1);
                String[] cells = rows.get(r);
                for (int c = 0; c < cells.length; c++)
                {
                    row.createCell(c).setCellValue(cells[c]);
                }
            }
        });
    }

    /** 写入 profile/upload 下的测试文件，返回 agent 的 filePath 形态 */
    private String writeUploadFile(String relative, byte[] bytes) throws IOException
    {
        Path target = profileRoot.resolve("upload").resolve(relative);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        return "/profile/upload/" + relative;
    }

    private byte[] twoRowWorkbook()
    {
        return workbookBytes(workbook -> {
            XSSFSheet sheet = workbook.createSheet("目标表");
            sheet.createRow(0).createCell(0).setCellValue("名称");
            sheet.createRow(1).createCell(0).setCellValue("张三");
            sheet.createRow(2).createCell(0).setCellValue("李四");
        });
    }

    // ===== 场景 =====

    @Test
    void importExcelShouldImportAllRowsWhenNoMissingParams() throws IOException
    {
        String filePath = writeUploadFile("2026/09/16/无缺参.xlsx", twoRowWorkbook());
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class)))
                .thenReturn(Collections.singletonList(column(101L, "名称", 1L, null)));

        ExcelImportAgentResult result = service.importExcel(
                NOTE_ID, DWTABLE_ID, filePath, null, USER_ID);

        assertTrue(result.isSuccess());
        assertEquals(Integer.valueOf(2), result.getImportedCount());
        assertNotNull(result.getMessage());
        verify(noteRecordMapper, times(2)).insertNoteRecord(any(NoteRecord.class));
        verify(noteDwtableItemMapper).insertNoteDwtableItems(itemsCaptor.capture());
        List<NoteDwtableItem> items = itemsCaptor.getValue();
        assertEquals(2, items.size());
    }

    @Test
    void importExcelShouldFillFromColumnValuesWhenCovered() throws IOException
    {
        // xlsx 只有名称列；数量列缺列，columnValues 提供数字字面量（归一化 toString）
        byte[] bytes = workbookBytes(workbook -> {
            XSSFSheet sheet = workbook.createSheet("目标表");
            sheet.createRow(0).createCell(0).setCellValue("名称");
            sheet.createRow(1).createCell(0).setCellValue("张三");
        });
        String filePath = writeUploadFile("2026/09/16/缺列.xlsx", bytes);
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class)))
                .thenReturn(java.util.Arrays.asList(
                        column(101L, "名称", 1L, null),
                        column(102L, "数量", 1L, null)));
        Map<String, Object> columnValues = new LinkedHashMap<>();
        columnValues.put("数量", Integer.valueOf(123));

        ExcelImportAgentResult result = service.importExcel(
                NOTE_ID, DWTABLE_ID, filePath, columnValues, USER_ID);

        assertTrue(result.isSuccess());
        assertEquals(Integer.valueOf(1), result.getImportedCount());
        verify(noteDwtableItemMapper).insertNoteDwtableItems(itemsCaptor.capture());
        List<NoteDwtableItem> items = itemsCaptor.getValue();
        // 名称列 + 数量列（缺列补参）各一条 item
        assertEquals(2, items.size());
        NoteDwtableItem fillItem = items.stream()
                .filter(i -> Long.valueOf(102L).equals(i.getColumnId())).findFirst().orElse(null);
        assertNotNull(fillItem);
        assertEquals("123", fillItem.getValue());
    }

    @Test
    void importExcelShouldReturnUncoveredListWithoutWriting() throws IOException
    {
        byte[] bytes = workbookBytes(workbook -> {
            XSSFSheet sheet = workbook.createSheet("目标表");
            sheet.createRow(0).createCell(0).setCellValue("名称");
            sheet.createRow(1).createCell(0).setCellValue("张三");
        });
        String filePath = writeUploadFile("2026/09/16/未覆盖.xlsx", bytes);
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class)))
                .thenReturn(java.util.Arrays.asList(
                        column(101L, "名称", 1L, null),
                        column(102L, "备注", 1L, null)));

        ExcelImportAgentResult result = service.importExcel(
                NOTE_ID, DWTABLE_ID, filePath, null, USER_ID);

        assertFalse(result.isSuccess());
        assertEquals(1, result.getMissingParams().size());
        ExcelImportPrecheckResult.MissingParam missing = result.getMissingParams().get(0);
        assertEquals("备注", missing.getColumnName());
        assertEquals(ExcelImportPrecheckResult.KIND_MISSING_COLUMN, missing.getKind());
        assertTrue(result.getMessage().contains("未执行"));
        // 失败态零写入
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
        verify(noteDwtableItemMapper, never()).insertNoteDwtableItems(anyList());
    }

    @Test
    void importExcelShouldFailReportOnLinkMiss() throws IOException
    {
        mockRelatedTable();
        mockRelatedRecords(Collections.singletonList(record(814L, "家", 1L)));
        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本"},
                Collections.singletonList(new String[] {"张三", "公司"}));
        String filePath = writeUploadFile("2026/09/16/未命中.xlsx", bytes);
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class)))
                .thenReturn(java.util.Arrays.asList(
                        column(101L, "名称", 1L, null),
                        column(104L, "关联", 21L, "{\"table_id\":80}")));

        ExcelImportAgentResult result = service.importExcel(
                NOTE_ID, DWTABLE_ID, filePath, null, USER_ID);

        // 关联未命中无法文本补值 → 失败清单（即便提供 columnValues 也不会覆盖）
        ExcelImportAgentResult resultWithFill = service.importExcel(
                NOTE_ID, DWTABLE_ID, filePath, Collections.singletonMap("备注", "x"), USER_ID);

        assertFalse(result.isSuccess());
        assertEquals(1, result.getMissingParams().size());
        ExcelImportPrecheckResult.MissingParam missing = result.getMissingParams().get(0);
        assertEquals("关联", missing.getColumnName());
        assertEquals(ExcelImportPrecheckResult.KIND_LINK_MISS, missing.getKind());
        assertTrue(result.getMessage().contains("界面导入"));
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
        assertFalse(resultWithFill.isSuccess());
    }

    @Test
    void importExcelShouldReportAmbiguityAsApplied() throws IOException
    {
        // 被关联表同名两条（sort 1/2）→ 歧义按 sort 靠前预选，不阻断导入
        mockRelatedTable();
        mockRelatedRecords(java.util.Arrays.asList(record(814L, "家", 1L), record(815L, "家", 2L)));
        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本"},
                Collections.singletonList(new String[] {"张三", "家"}));
        String filePath = writeUploadFile("2026/09/16/歧义.xlsx", bytes);
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class)))
                .thenReturn(java.util.Arrays.asList(
                        column(101L, "名称", 1L, null),
                        column(104L, "关联", 18L, "{\"select\":80}")));

        ExcelImportAgentResult result = service.importExcel(
                NOTE_ID, DWTABLE_ID, filePath, null, USER_ID);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getAmbiguityApplied().size());
        assertEquals("家", result.getAmbiguityApplied().get(0).getName());
        assertEquals(Long.valueOf(814L), result.getAmbiguityApplied().get(0).getPreselectedRecordId());
        assertTrue(result.getMessage().contains("预选"));
        // 预选记录 814 落库（18 列无配对列，linkColumnId 保持 null）
        verify(noteDwtableItemMapper).insertNoteDwtableItems(itemsCaptor.capture());
        NoteDwtableItem linkItem = itemsCaptor.getValue().stream()
                .filter(i -> Long.valueOf(104L).equals(i.getColumnId())).findFirst().orElse(null);
        assertNotNull(linkItem);
        assertEquals("814", linkItem.getLinkRecordId());
        assertEquals("家", linkItem.getValue());
    }

    @Test
    void importExcelShouldRejectPathTraversal() throws IOException
    {
        writeUploadFile("2026/09/16/正常.xlsx", twoRowWorkbook());
        ServiceException exception = assertThrows(ServiceException.class, () -> service.importExcel(
                NOTE_ID, DWTABLE_ID, "/profile/upload/../../etc/passwd", null, USER_ID));
        assertTrue(exception.getMessage().contains("非法路径"));
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
    }

    @Test
    void importExcelShouldRejectNonProfilePrefix() throws IOException
    {
        ServiceException exception = assertThrows(ServiceException.class, () -> service.importExcel(
                NOTE_ID, DWTABLE_ID, "D:/secret/数据.xlsx", null, USER_ID));
        assertTrue(exception.getMessage().contains("/profile/upload/"));
    }

    @Test
    void importExcelShouldRejectMissingFile()
    {
        ServiceException exception = assertThrows(ServiceException.class, () -> service.importExcel(
                NOTE_ID, DWTABLE_ID, "/profile/upload/2026/09/16/不存在.xlsx", null, USER_ID));
        assertTrue(exception.getMessage().contains("不存在"));
    }

    @Test
    void importExcelShouldPassThroughOwnershipFailure() throws IOException
    {
        doThrow(new ServiceException("无权访问该多维表")).when(agentOwnershipChecker)
                .checkDwtableOwnership(DWTABLE_ID, USER_ID);
        String filePath = writeUploadFile("2026/09/16/归属.xlsx", twoRowWorkbook());

        ServiceException exception = assertThrows(ServiceException.class, () -> service.importExcel(
                NOTE_ID, DWTABLE_ID, filePath, null, USER_ID));
        assertEquals("无权访问该多维表", exception.getMessage());
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
    }
}

