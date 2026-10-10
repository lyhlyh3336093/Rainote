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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.agent.security.AgentOwnershipChecker;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.NoteDwtable;
import com.ruoyi.system.domain.NoteDwtableItem;
import com.ruoyi.system.domain.NoteNotelink;
import com.ruoyi.system.domain.NoteNote;
import com.ruoyi.system.domain.NoteRecord;
import com.ruoyi.system.domain.dto.ExcelImportAgentResult;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult;
import com.ruoyi.system.domain.vo.NoteRecordVo;
import com.ruoyi.system.mapper.NoteColumnMapper;
import com.ruoyi.system.mapper.NoteDwtableItemMapper;
import com.ruoyi.system.mapper.NoteDwtableMapper;
import com.ruoyi.system.mapper.NoteNotelinkMapper;
import com.ruoyi.system.mapper.NoteNoteMapper;
import com.ruoyi.system.mapper.NoteRecordMapper;
import com.ruoyi.system.mapper.NoteViewMapper;
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
 * {@code dwtable.importExcel} agent 操作单元测试（<b>替换式语义</b>）。
 * <p>
 * 覆盖：无缺参成功导入（含 replaceImpact 透传）、columnValues 覆盖缺参（含数字归一化）、
 * 表头未映射列的删除/新建 replaceImpact 行为（不再产生缺参）、成功结果替换摘要
 * （"替换：删除原 N 行"）与 agent 基线 replaceDeletedColumns、18 列关联未命中失败清单
 * （零写入 + 结构化清单）、21 列链接解析失败结构化清单（U3/R10：与界面共用 DTO、零写入）、
 * 链接失败与缺参并存的混合导入（U6/R10：链接失败优先结构化返回、无半写入）、
 * 25 列语义链接失败清单与 round-trip 行恢复（U4/R10：同 DTO、行 id 保留零写入）、
 * 歧义预选成功报告、路径穿越拒绝、非 profile 前缀拒绝、
 * 文件不存在、归属失败穿透。
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

    /** 替换式导入：被删列的孤儿语义链接清理 + 25 列重建（service 新注入，@InjectMocks 自动装配） */
    @Mock
    private NoteNotelinkMapper noteNotelinkMapper;

    /** 25 列名称回退的候选笔记标题来源（U4：service 新注入） */
    @Mock
    private NoteNoteMapper noteNoteMapper;

    /** 导入记录归属视图解析（service 新注入；网格 dataList 按视图 viewId 过滤记录） */
    @Mock
    private NoteViewMapper noteViewMapper;

    @Mock
    private AgentOwnershipChecker agentOwnershipChecker;

    @Mock
    private INoteRecordService noteRecordService;

    /** 共享名称解析组件（U2）：真实实例经 @Spy 注入（21 列名称回退用） */
    @Spy
    private LinkNameResolver linkNameResolver = new LinkNameResolver();

    @InjectMocks
    private NoteDwtableExcelImportServiceImpl service;

    @Captor
    private ArgumentCaptor<List<NoteDwtableItem>> itemsCaptor;

    /** 25 列重建的 notelink 行捕获（U4） */
    @Captor
    private ArgumentCaptor<NoteNotelink> notelinkCaptor;

    @TempDir
    Path profileRoot;

    /** 模拟 NoteRecordMapper.insertNoteRecord 的 useGeneratedKeys 自增 id 回填 */
    private final AtomicLong recordIdSequence = new AtomicLong(1000L);

    /** 模拟 insertNoteColumn 的 useGeneratedKeys 自增 id 回填（pending 列 items 组装依赖） */
    private final AtomicLong columnIdSequence = new AtomicLong(8000L);

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
        // 替换式导入：pending 新建列落库回填自增 id（useGeneratedKeys 语义）
        when(noteColumnMapper.insertNoteColumn(any(NoteColumn.class))).thenAnswer(inv -> {
            NoteColumn column = inv.getArgument(0);
            column.setId(columnIdSequence.getAndIncrement());
            return 1;
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
        // 替换式影响面随成功结果返回（无删除/新建时各清单为空）
        assertNotNull(result.getReplaceImpact());
        assertTrue(result.getReplaceImpact().getDeletedColumns().isEmpty());
        verify(noteRecordMapper, times(2)).insertNoteRecord(any(NoteRecord.class));
        verify(noteDwtableItemMapper).insertNoteDwtableItems(itemsCaptor.capture());
        List<NoteDwtableItem> items = itemsCaptor.getValue();
        assertEquals(2, items.size());
    }

    @Test
    void importExcelShouldIncludeReplaceImpactAndSummaryInSuccessResult() throws IOException
    {
        // 目标表现有 3 条记录 + 备注列不在 Excel → 删除行数/删除列进入 replaceImpact 与成功摘要
        when(noteRecordMapper.selectNoteRecordList(any(NoteRecordVo.class))).thenAnswer(inv -> {
            NoteRecordVo query = inv.getArgument(0);
            if (DWTABLE_ID.equals(query.getDwtableId()))
            {
                return java.util.Arrays.asList(
                        record(1L, "旧1", 1L), record(2L, "旧2", 2L), record(3L, "旧3", 3L));
            }
            return new ArrayList<NoteRecord>();
        });
        byte[] bytes = twoRowWorkbook();
        String filePath = writeUploadFile("2026/09/16/替换摘要.xlsx", bytes);
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class)))
                .thenReturn(java.util.Arrays.asList(
                        column(101L, "名称", 1L, null),
                        column(102L, "备注", 1L, null)));

        ExcelImportAgentResult result = service.importExcel(
                NOTE_ID, DWTABLE_ID, filePath, null, USER_ID);

        assertTrue(result.isSuccess());
        assertEquals(Integer.valueOf(2), result.getImportedCount());
        // 成功结果携带替换影响面
        assertNotNull(result.getReplaceImpact());
        assertEquals(3, result.getReplaceImpact().getDeletedRowCount());
        assertEquals(1, result.getReplaceImpact().getDeletedColumns().size());
        assertEquals("备注", result.getReplaceImpact().getDeletedColumns().get(0).getColumnName());
        // 成功消息含替换摘要分句
        assertTrue(result.getMessage().contains("替换：删除原 3 行"));
        assertTrue(result.getMessage().contains("删除 1 列"));
        // 备注（列删除）实际执行：agent 基线含 replaceDeletedColumns（否则导入段漂移拒绝），
        // 导入成功即证明基线正确携带
        verify(noteColumnMapper).deleteNoteColumnById(102L);
    }

    @Test
    void importExcelShouldFillFromColumnValuesWhenCovered() throws IOException
    {
        // 数量列在表头中但单元格为空（缺值），columnValues 提供数字字面量（归一化 toString）
        byte[] bytes = workbookBytes(workbook -> {
            XSSFSheet sheet = workbook.createSheet("目标表");
            org.apache.poi.ss.usermodel.Row headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue("名称");
            headerRow.createCell(1).setCellValue("数量");
            org.apache.poi.ss.usermodel.Row dataRow = sheet.createRow(1);
            dataRow.createCell(0).setCellValue("张三");
        });
        String filePath = writeUploadFile("2026/09/16/缺值.xlsx", bytes);
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
        // 名称列 + 数量列（缺值补参）各一条 item
        assertEquals(2, items.size());
        NoteDwtableItem fillItem = items.stream()
                .filter(i -> Long.valueOf(102L).equals(i.getColumnId())).findFirst().orElse(null);
        assertNotNull(fillItem);
        assertEquals("123", fillItem.getValue());
    }

    @Test
    void importExcelShouldReplaceUnmatchedColumnsInsteadOfReportingMissing() throws IOException
    {
        // 替换式语义：表头未映射列（备注）不再产生缺参，而是删除；Excel 独有表头（新列）自动新建
        byte[] bytes = singleSheetBytes(new String[] {"名称", "新列"},
                Collections.singletonList(new String[] {"张三", "v"}));
        String filePath = writeUploadFile("2026/09/16/替换列.xlsx", bytes);
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class)))
                .thenReturn(java.util.Arrays.asList(
                        column(101L, "名称", 1L, null),
                        column(102L, "备注", 1L, null)));

        ExcelImportAgentResult result = service.importExcel(
                NOTE_ID, DWTABLE_ID, filePath, null, USER_ID);

        // 无缺参 → 成功执行（替换式）
        assertTrue(result.isSuccess());
        assertTrue(result.getMissingParams().isEmpty());
        assertEquals(Integer.valueOf(1), result.getImportedCount());
        // replaceImpact：备注列删除 + 新列新建
        assertNotNull(result.getReplaceImpact());
        assertEquals(1, result.getReplaceImpact().getDeletedColumns().size());
        assertEquals("备注", result.getReplaceImpact().getDeletedColumns().get(0).getColumnName());
        assertEquals(Collections.singletonList("新列"), result.getReplaceImpact().getNewColumnNames());
        assertTrue(result.getMessage().contains("删除 1 列"));
        assertTrue(result.getMessage().contains("新建 1 列"));
        // 落库：备注列被删、新列 insert、行 items = 名称 + 新列
        verify(noteColumnMapper).deleteNoteColumnById(102L);
        verify(noteColumnMapper).insertNoteColumn(any(NoteColumn.class));
        verify(noteDwtableItemMapper).insertNoteDwtableItems(itemsCaptor.capture());
        assertEquals(2, itemsCaptor.getValue().size());
    }

    @Test
    void importExcelShouldFailReportOnLinkMiss() throws IOException
    {
        // 18 列关联未命中（U3 后 21 列未命中走链接失败清单，见下方专项测试；18 维持缺参清单）
        mockRelatedTable();
        mockRelatedRecords(Collections.singletonList(record(814L, "家", 1L)));
        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本"},
                Collections.singletonList(new String[] {"张三", "公司"}));
        String filePath = writeUploadFile("2026/09/16/未命中.xlsx", bytes);
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class)))
                .thenReturn(java.util.Arrays.asList(
                        column(101L, "名称", 1L, null),
                        column(104L, "关联", 18L, "{\"select\":80}")));

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

    // ===== U3（R8/R10）：21 列链接解析失败 → 结构化清单返回、零写入（与界面共用 DTO） =====

    @Test
    void importExcelShouldReturnLinkFailuresForDoubleLink() throws IOException
    {
        mockRelatedTable();
        mockRelatedRecords(Collections.singletonList(record(814L, "家", 1L)));
        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本"},
                Collections.singletonList(new String[] {"张三", "公司"}));
        String filePath = writeUploadFile("2026/09/16/链接失败.xlsx", bytes);
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class)))
                .thenReturn(java.util.Arrays.asList(
                        column(101L, "名称", 1L, null),
                        column(104L, "关联", 21L, "{\"table_id\":80,\"back_field_id\":502}")));

        ExcelImportAgentResult result = service.importExcel(
                NOTE_ID, DWTABLE_ID, filePath, null, USER_ID);

        // 强失败：结构化链接失败清单（单元格定位/原因/容错命中说明），不进缺参清单
        assertFalse(result.isSuccess());
        assertTrue(result.getMissingParams().isEmpty());
        assertEquals(1, result.getLinkFailures().size());
        ExcelImportPrecheckResult.LinkFailure failure = result.getLinkFailures().get(0);
        assertEquals(Long.valueOf(104L), failure.getColumnId());
        assertEquals("关联", failure.getColumnName());
        assertEquals(2, failure.getRowNumber());
        assertEquals("公司", failure.getEntryText());
        assertNotNull(failure.getReason());
        assertTrue(failure.getReason().contains("未命中"));
        assertNotNull(failure.getExplanation());
        assertTrue(result.getMessage().contains("链接解析失败"));
        assertTrue(result.getMessage().contains("零写入"));
        // 无半写入：预检即拒绝，零记录/零 item 落库、零跨表修改
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
        verify(noteDwtableItemMapper, never()).insertNoteDwtableItems(anyList());
        verify(noteDwtableItemMapper, never()).updateNoteDwtableItem(any(NoteDwtableItem.class));
        verify(noteDwtableItemMapper, never()).insertNoteDwtableItem(any(NoteDwtableItem.class));
    }

    // ===== U6（R8/R10）：链接失败与缺参并存的混合导入——链接失败优先结构化返回、无半写入 =====

    @Test
    void importExcelShouldReturnLinkFailuresFirstWhenMixedWithMissingParams() throws IOException
    {
        // 数量列空单元格（缺值缺参）+ 21 列名称未命中（链接失败）并存
        mockRelatedTable();
        mockRelatedRecords(Collections.singletonList(record(814L, "家", 1L)));
        byte[] bytes = singleSheetBytes(new String[] {"名称", "数量", "关联_文本"},
                Collections.singletonList(new String[] {"张三", "", "公司"}));
        String filePath = writeUploadFile("2026/09/16/混合失败.xlsx", bytes);
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(java.util.Arrays.asList(
                column(101L, "名称", 1L, null),
                column(102L, "数量", 2L, null),
                column(104L, "关联", 21L, "{\"table_id\":80,\"back_field_id\":502}")));

        ExcelImportAgentResult result = service.importExcel(
                NOTE_ID, DWTABLE_ID, filePath, null, USER_ID);

        // 链接失败优先返回（强失败无法补参覆盖）：结构化清单（单元格定位/原因/容错说明）
        // + 零写入指引；缺参清单不随本次返回（修正文件后如仍有缺参再单独返回）
        assertFalse(result.isSuccess());
        assertEquals(1, result.getLinkFailures().size());
        assertEquals("公司", result.getLinkFailures().get(0).getEntryText());
        assertEquals(2, result.getLinkFailures().get(0).getRowNumber());
        assertTrue(result.getMissingParams().isEmpty());
        assertTrue(result.getMessage().contains("链接解析失败"));
        assertTrue(result.getMessage().contains("零写入"));
        // 无半写入：零记录/零 item 落库、零跨表修改
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
        verify(noteDwtableItemMapper, never()).insertNoteDwtableItems(anyList());
        verify(noteDwtableItemMapper, never()).updateNoteDwtableItem(any(NoteDwtableItem.class));
    }

    // ===== U4（R8/R10）：25 列（语义关联）链接解析失败 → 结构化清单返回、零写入（同 DTO） =====

    @Test
    void importExcelShouldReturnLinkFailuresForSemanticLink() throws IOException
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(java.util.Arrays.asList(
                column(101L, "名称", 1L, null),
                column(104L, "语义", 25L, null)));
        when(noteNoteMapper.selectAllNoteList()).thenReturn(new ArrayList<>(
                Collections.singletonList(noteNote(40L, "引用笔记"))));

        // 空 `_ID` + 名称未命中（"不存在的笔记"）→ 强失败
        byte[] bytes = singleSheetBytes(new String[] {"record_id", "名称", "语义_ID", "语义_文本"},
                Collections.singletonList(new String[] {"1", "a", "", "不存在的笔记（锚词）"}));
        String filePath = writeUploadFile("2026/09/16/语义链接失败.xlsx", bytes);

        ExcelImportAgentResult result = service.importExcel(
                NOTE_ID, DWTABLE_ID, filePath, null, USER_ID);

        // 与界面共用 DTO 的结构化链接失败清单（单元格定位/原因/容错命中说明）
        assertFalse(result.isSuccess());
        assertTrue(result.getMissingParams().isEmpty());
        assertEquals(1, result.getLinkFailures().size());
        ExcelImportPrecheckResult.LinkFailure failure = result.getLinkFailures().get(0);
        assertEquals(Long.valueOf(104L), failure.getColumnId());
        assertEquals("语义", failure.getColumnName());
        assertEquals(2, failure.getRowNumber());
        assertEquals("不存在的笔记（锚词）", failure.getEntryText());
        assertNotNull(failure.getReason());
        assertTrue(failure.getReason().contains("未命中"));
        assertNotNull(failure.getExplanation());
        assertTrue(result.getMessage().contains("链接解析失败"));
        assertTrue(result.getMessage().contains("零写入"));
        // 无半写入：零记录/零 item 落库、零 notelink 重建（清旧行亦不执行）
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
        verify(noteDwtableItemMapper, never()).insertNoteDwtableItems(anyList());
        verify(noteNotelinkMapper, never()).deleteNoteNotelinkByColumnId(any(Long.class));
        verify(noteNotelinkMapper, never()).insertNoteNotelink(any(NoteNotelink.class));
        verify(noteNotelinkMapper, never()).updateNoteNotelinkCoordinates(any(NoteNotelink.class));
    }

    // ===== U4（R2/R6）：25 列 round-trip —— `_ID` 命中恢复原行（行 id 保留），agent 路径同写入 =====

    @Test
    void importExcelShouldRestoreSemanticLinkOnRoundTrip() throws IOException
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(java.util.Arrays.asList(
                column(101L, "名称", 1L, null),
                column(104L, "语义", 25L, null)));
        when(noteNoteMapper.selectAllNoteList()).thenReturn(new ArrayList<>(
                Collections.singletonList(noteNote(40L, "引用笔记"))));
        NoteNotelink existing = new NoteNotelink();
        existing.setId(901L);
        existing.setNoteId(40L);
        existing.setBlockId(701L);
        existing.setContextText("锚词");
        existing.setItemValue("锚词");
        when(noteNotelinkMapper.selectNoteNotelinkById(901L)).thenReturn(existing);
        // 25 列重建依赖 item 自增 id（行内坐标 linkItemId）——覆盖 setUp 桩补 useGeneratedKeys 回填
        java.util.concurrent.atomic.AtomicLong itemIdSequence = new java.util.concurrent.atomic.AtomicLong(5000L);
        when(noteDwtableItemMapper.insertNoteDwtableItems(anyList())).thenAnswer(inv -> {
            List<NoteDwtableItem> items = inv.getArgument(0);
            for (NoteDwtableItem item : items)
            {
                item.setId(itemIdSequence.getAndIncrement());
            }
            return items.size();
        });

        byte[] bytes = singleSheetBytes(new String[] {"record_id", "名称", "语义_ID", "语义_文本"},
                Collections.singletonList(new String[] {"1", "a", "901", "引用笔记（锚词）"}));
        String filePath = writeUploadFile("2026/09/16/语义往返.xlsx", bytes);

        ExcelImportAgentResult result = service.importExcel(
                NOTE_ID, DWTABLE_ID, filePath, null, USER_ID);

        assertTrue(result.isSuccess());
        assertEquals(Integer.valueOf(1), result.getImportedCount());
        assertTrue(result.getLinkFailures().isEmpty());
        // 25 列重建：清旧行 + 按行 id 恢复原行（行 id 与 blockId 保留，坐标指向本表新记录）
        verify(noteNotelinkMapper).deleteNoteNotelinkByColumnId(104L);
        verify(noteNotelinkMapper).updateNoteNotelinkCoordinates(notelinkCaptor.capture());
        NoteNotelink restored = notelinkCaptor.getValue();
        assertEquals(Long.valueOf(901L), restored.getId());
        assertEquals(Long.valueOf(40L), restored.getNoteId());
        assertEquals(Long.valueOf(701L), restored.getBlockId());
        assertEquals("锚词", restored.getContextText());
        assertEquals(NOTE_ID, restored.getLinkNoteId());
        assertEquals(DWTABLE_ID, restored.getLinkDwTableId());
        assertEquals(Long.valueOf(104L), restored.getLinkColumnId());
        assertNotNull(restored.getLinkItemId());
        assertNotNull(restored.getLinkRecordId());
        verify(noteNotelinkMapper, never()).insertNoteNotelink(any(NoteNotelink.class));
    }

    /** 笔记桩构造（25 列名称回退的候选标题来源） */
    private NoteNote noteNote(Long id, String title)
    {
        NoteNote note = new NoteNote();
        note.setId(id);
        note.setTitle(title);
        return note;
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

