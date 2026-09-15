package com.ruoyi.system.service.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.agent.security.AgentOwnershipChecker;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.NoteDwtable;
import com.ruoyi.system.domain.NoteDwtableItem;
import com.ruoyi.system.domain.NoteRecord;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.AmbiguityItem;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.DefaultValueFill;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.MissingParam;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.MissName;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.NewOption;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.RelationCandidates;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.SkippedHeader;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.SymmetricWriteImpact;
import com.ruoyi.system.domain.vo.NoteRecordVo;
import com.ruoyi.system.mapper.NoteColumnMapper;
import com.ruoyi.system.mapper.NoteDwtableItemMapper;
import com.ruoyi.system.mapper.NoteDwtableMapper;
import com.ruoyi.system.mapper.NoteRecordMapper;
import com.ruoyi.system.service.INoteRecordService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NoteDwtableExcelImportServiceImpl} 预检（阶段一，U4）+ 导入写入（阶段二，U5）单元测试。
 * <p>
 * U4 场景：AE1 预检无缺参（完整影响面 + 零写库）、AE2 缺列有默认值、
 * AE3 缺值无默认值、AE4 歧义预选 sort 靠前、AE5 未命中名清单（关联空单元格不算缺参）、
 * KTD4 整串优先、对称写入影响 distinct 计数、R23 被关联表归属失败阻断（含列名）、
 * 损坏 xlsx 中文提示、KTD5 文件指纹、关联缺列/类型违规/新选项创建/缺值默认填充。
 * <p>
 * U5 场景：三源合并（AE3 导入部分 + 复选框 '0'/'1' 映射）、双链对称写入四字段语义
 * （AE6 + KTD3 两处修正偏离断言）、同名新记录 value 等长、末批 &lt;500 零缓冲、
 * 配对 item 不存在 upsert、双向闭合、多源聚合单次 update、18 列 link 字段落库、
 * 选项自动创建（AE7 + 上限 100）、重算编排时序（KTD8）、失败回滚（AE8）、
 * 反向漂移 fail-fast（KTD5 全触发集）、补参校验（R26）、补参优先语义、params 非法、
 * sort 追加与 name 派生。
 *
 * @author ruoyi
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NoteDwtableExcelImportServiceImplTest
{
    private static final Long NOTE_ID = 50L;

    private static final Long DWTABLE_ID = 60L;

    private static final Long USER_ID = 70L;

    private static final Long RELATED_TABLE_ID = 80L;

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

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
    private NoteDwtableExcelImportServiceImpl precheckService;

    @Captor
    private ArgumentCaptor<List<NoteDwtableItem>> itemsCaptor;

    @Captor
    private ArgumentCaptor<NoteDwtableItem> itemCaptor;

    @Captor
    private ArgumentCaptor<NoteColumn> columnCaptor;

    @Captor
    private ArgumentCaptor<NoteRecord> recordCaptor;

    /** 模拟 NoteRecordMapper.insertNoteRecord 的 useGeneratedKeys 自增 id 回填 */
    private final AtomicLong recordIdSequence = new AtomicLong(1000L);

    /** 模拟 insertNoteDwtableItem(s) 的 useGeneratedKeys 自增 id 回填（零缓冲不变量依赖） */
    private final AtomicLong itemIdSequence = new AtomicLong(5000L);

    @BeforeEach
    void setUp()
    {
        NoteDwtable dwtable = new NoteDwtable();
        dwtable.setId(DWTABLE_ID);
        dwtable.setNoteId(NOTE_ID);
        dwtable.setName("目标表");
        when(noteDwtableMapper.selectNoteDwtableById(DWTABLE_ID)).thenReturn(dwtable);
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class)))
                .thenReturn(Collections.emptyList());

        // ===== U5 导入写入段公共桩 =====
        precheckService.setSelf(precheckService);
        when(noteRecordMapper.insertNoteRecord(any(NoteRecord.class))).thenAnswer(inv -> {
            NoteRecord record = inv.getArgument(0);
            record.setId(recordIdSequence.getAndIncrement());
            return 1;
        });
        when(noteRecordMapper.selectMaxSortByDwtableId(DWTABLE_ID)).thenReturn(5L);
        when(noteDwtableItemMapper.insertNoteDwtableItems(anyList())).thenAnswer(inv -> {
            List<NoteDwtableItem> items = inv.getArgument(0);
            for (NoteDwtableItem item : items)
            {
                item.setId(itemIdSequence.getAndIncrement());
            }
            return items.size();
        });
        when(noteDwtableItemMapper.insertNoteDwtableItem(any(NoteDwtableItem.class))).thenAnswer(inv -> {
            NoteDwtableItem item = inv.getArgument(0);
            item.setId(itemIdSequence.getAndIncrement());
            return 1;
        });
    }

    // ===== 构造工具 =====

    private NoteColumn column(Long id, String name, Long type, String property, Long isShow)
    {
        NoteColumn column = new NoteColumn();
        column.setId(id);
        column.setName(name);
        column.setType(type);
        column.setProperty(property);
        column.setIsShow(isShow);
        column.setDwtableId(DWTABLE_ID);
        return column;
    }

    private NoteRecord record(Long id, String name, Long sort)
    {
        NoteRecord record = new NoteRecord();
        record.setId(id);
        record.setName(name);
        record.setSort(sort);
        record.setDwtableId(RELATED_TABLE_ID);
        return record;
    }

    private NoteDwtable relatedTable(String name, Long noteId)
    {
        NoteDwtable related = new NoteDwtable();
        related.setId(RELATED_TABLE_ID);
        related.setName(name);
        related.setNoteId(noteId);
        return related;
    }

    private void mockRelatedTable(NoteDwtable related)
    {
        when(noteDwtableMapper.selectNoteDwtableById(related.getId())).thenReturn(related);
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

    /** 单 sheet（名"目标表"）+ 表头 + 数据行 */
    private byte[] singleSheetBytes(String[] headers, List<String[]> rows)
    {
        return workbookBytes(workbook -> fillSheet(workbook.createSheet("目标表"), headers, rows));
    }

    /** 双 sheet：目标表 + 将被忽略的"其他表" */
    private byte[] twoSheetBytes(String[] headers, List<String[]> rows)
    {
        return workbookBytes(workbook -> {
            fillSheet(workbook.createSheet("目标表"), headers, rows);
            fillSheet(workbook.createSheet("其他表"),
                    new String[] {"杂项"}, Collections.emptyList());
        });
    }

    private void fillSheet(XSSFSheet sheet, String[] headers, List<String[]> rows)
    {
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++)
        {
            headerRow.createCell(i).setCellValue(headers[i]);
        }
        for (int r = 0; r < rows.size(); r++)
        {
            Row row = sheet.createRow(r + 1);
            String[] cells = rows.get(r);
            for (int c = 0; c < cells.length; c++)
            {
                row.createCell(c).setCellValue(cells[c]);
            }
        }
    }

    private MockMultipartFile xlsxFile(byte[] bytes)
    {
        return new MockMultipartFile("file", "import.xlsx", XLSX_CONTENT_TYPE, bytes);
    }

    private String md5Hex(byte[] bytes)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("MD5").digest(bytes);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte b : digest)
            {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }

    // ===== AE1：预检无缺参，完整影响面，零写库 =====

    @Test
    void precheck_noMissingParams_returnsFullImpactAndZeroWrites()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(102L, "数量", 2L, "{\"default\":\"0\"}", 0L),
                column(103L, "状态", 3L, "{\"select\":\"进行中,已完成\"}", 0L),
                column(104L, "关联", 21L, "{\"table_id\":80}", 0L),
                column(105L, "隐藏列", 1L, null, 1L),
                column(106L, "公式", 20L, null, 0L)));
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Arrays.asList(record(301L, "张三", 1L), record(302L, "李四", 2L)));

        byte[] bytes = twoSheetBytes(
                new String[] {"record_id", "名称", "状态", "关联_文本", "未知列"},
                Arrays.asList(
                        new String[] {"1", "a", "进行中", "张三", "x"},
                        new String[] {"2", "b", "紧急", "李四", "y"}));

        ExcelImportPrecheckResult result =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);

        // 无缺参、无歧义 → 不阻断（R13 分流：直接导入）
        assertTrue(result.getMissingParams().isEmpty());
        assertTrue(result.getAmbiguityItems().isEmpty());
        assertFalse(result.isHasBlockingIssues());

        // 缺列有默认值 → 默认值填充项（行数=全部行数），不进缺参清单（AE2 同源断言）
        assertEquals(1, result.getDefaultValueFills().size());
        DefaultValueFill fill = result.getDefaultValueFills().get(0);
        assertEquals(Long.valueOf(102L), fill.getColumnId());
        assertEquals("数量", fill.getColumnName());
        assertEquals("0", fill.getDefaultValue());
        assertEquals(2, fill.getRowCount());

        // 单选未命中选项 → 新选项创建项（R19）
        assertEquals(1, result.getNewOptions().size());
        NewOption option = result.getNewOptions().get(0);
        assertEquals(Long.valueOf(103L), option.getColumnId());
        assertEquals("紧急", option.getOptionText());
        assertEquals(1, option.getOccurrences());

        // 21 列命中 2 条 distinct 记录 → 对称写入影响（R11）
        assertEquals(1, result.getSymmetricWriteImpact().size());
        SymmetricWriteImpact impact = result.getSymmetricWriteImpact().get(0);
        assertEquals("被关联表", impact.getTableName());
        assertEquals(2, impact.getAffectedRecordCount());

        // 忽略 sheet（R2）与跳过表头（R5/R6：record_id 源列 + 未匹配表头）
        assertEquals(Collections.singletonList("其他表"), result.getIgnoredSheets());
        assertEquals(2, result.getSkippedHeaders().size());
        assertEquals("record_id", result.getSkippedHeaders().get(0).getHeaderName());
        assertNotNull(result.getSkippedHeaders().get(0).getReason());
        assertEquals("未知列", result.getSkippedHeaders().get(1).getHeaderName());
        assertNotNull(result.getSkippedHeaders().get(1).getReason());

        // 选择器候选（KTD7：sort 升序前 100 条，已过 R23 归属校验）
        assertEquals(1, result.getRelationCandidates().size());
        RelationCandidates candidates = result.getRelationCandidates().get(0);
        assertEquals(Long.valueOf(104L), candidates.getColumnId());
        assertEquals(2, candidates.getCandidates().size());
        assertEquals(Long.valueOf(301L), candidates.getCandidates().get(0).getRecordId());
        assertEquals("张三", candidates.getCandidates().get(0).getName());
        assertEquals(Long.valueOf(302L), candidates.getCandidates().get(1).getRecordId());

        // 文件指纹（KTD5）
        assertEquals(bytes.length, result.getFileFingerprint().getSize());
        assertEquals(md5Hex(bytes), result.getFileFingerprint().getMd5());

        // R23：被关联表归属校验执行
        verify(agentOwnershipChecker, times(1)).checkDwtableOwnership(RELATED_TABLE_ID, USER_ID);
        // 预检零写路径（R9：不写库）
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
        verify(noteRecordMapper, never()).updateNoteRecord(any(NoteRecord.class));
        verify(noteColumnMapper, never()).insertNoteColumn(any(NoteColumn.class));
        verify(noteColumnMapper, never()).updateNoteColumn(any(NoteColumn.class));
        verify(noteDwtableMapper, never()).insertNoteDwtable(any(NoteDwtable.class));
        verify(noteDwtableMapper, never()).updateNoteDwtable(any(NoteDwtable.class));
    }

    // ===== AE2：缺列有默认值 → 默认值填充项 =====

    @Test
    void precheck_missingColumnWithDefault_goesToDefaultFill()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(102L, "数量", 2L, "{\"default\":\"0\"}", 0L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称"},
                Collections.singletonList(new String[] {"a"}));

        ExcelImportPrecheckResult result =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);

        // 不进缺参清单，进默认值填充项（行数=总行数）
        assertTrue(result.getMissingParams().isEmpty());
        assertFalse(result.isHasBlockingIssues());
        assertEquals(1, result.getDefaultValueFills().size());
        DefaultValueFill fill = result.getDefaultValueFills().get(0);
        assertEquals("数量", fill.getColumnName());
        assertEquals("0", fill.getDefaultValue());
        assertEquals(1, fill.getRowCount());
    }

    // ===== AE3：缺值无默认值 → 缺参项含行号集 =====

    @Test
    void precheck_missingValueWithoutDefault_missingParamWithRowNumbers()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(107L, "备注", 1L, null, 0L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称", "备注"}, Arrays.asList(
                new String[] {"a", "x"},
                new String[] {"b", ""},
                new String[] {"c", "y"}));

        ExcelImportPrecheckResult result =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);

        assertEquals(1, result.getMissingParams().size());
        MissingParam param = result.getMissingParams().get(0);
        assertEquals(Long.valueOf(107L), param.getColumnId());
        assertEquals("备注", param.getColumnName());
        assertEquals(Long.valueOf(1L), param.getColumnType());
        assertEquals(ExcelImportPrecheckResult.KIND_MISSING_VALUE, param.getKind());
        // 第 2 个数据行（Excel 第 3 行）空单元格
        assertEquals(Collections.singletonList(3), param.getRowNumbers());
        assertTrue(result.isHasBlockingIssues());
    }

    // ===== 类型违规 → 缺参项（附原因，走补值链路） =====

    @Test
    void precheck_numberColumnViolation_missingParamTypeViolation()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(102L, "数量", 2L, null, 0L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称", "数量"}, Arrays.asList(
                new String[] {"a", "abc"},
                new String[] {"b", "12"}));

        ExcelImportPrecheckResult result =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);

        assertEquals(1, result.getMissingParams().size());
        MissingParam param = result.getMissingParams().get(0);
        assertEquals("数量", param.getColumnName());
        assertEquals(ExcelImportPrecheckResult.KIND_TYPE_VIOLATION, param.getKind());
        assertEquals(Collections.singletonList(2), param.getRowNumbers());
        assertEquals("数字格式非法", param.getReason());
        assertTrue(result.isHasBlockingIssues());
    }

    // ===== 同列缺值+类型违规合并为一条缺参（rowNumbers 并集、kind=类型违规、reason 保留违规描述） =====

    @Test
    void precheck_sameColumnEmptyAndViolation_mergedSingleMissingParam()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(102L, "数量", 2L, null, 0L)));

        // 数字列：第 3 行空、第 4 行 "abc" 违规
        byte[] bytes = singleSheetBytes(new String[] {"名称", "数量"}, Arrays.asList(
                new String[] {"a", "12"},
                new String[] {"b", ""},
                new String[] {"c", "abc"}));

        ExcelImportPrecheckResult result =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);

        assertEquals(1, result.getMissingParams().size());
        MissingParam param = result.getMissingParams().get(0);
        assertEquals("数量", param.getColumnName());
        assertEquals(ExcelImportPrecheckResult.KIND_TYPE_VIOLATION, param.getKind());
        assertEquals(Arrays.asList(3, 4), param.getRowNumbers());
        assertTrue(param.getReason().contains("数字格式非法"));
        assertTrue(result.isHasBlockingIssues());
    }

    // ===== 空行过滤 + 物理行号：样式空行/物理空行跳过，行号与 Excel UI 一致 =====

    @Test
    void precheck_skipsEmptyRows_reportsPhysicalRowNumbers()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(102L, "数量", 2L, null, 0L)));

        // 第 2 行有效；第 3 行样式空行（有行有空白单元格）；第 4 行物理空行（未创建）；第 5 行违规
        byte[] bytes = workbookBytes(workbook -> {
            XSSFSheet sheet = workbook.createSheet("目标表");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("名称");
            header.createCell(1).setCellValue("数量");
            Row row2 = sheet.createRow(1);
            row2.createCell(0).setCellValue("a");
            row2.createCell(1).setCellValue("1");
            Row row3 = sheet.createRow(2);
            row3.createCell(0).setBlank();
            row3.createCell(1).setCellValue("");
            Row row5 = sheet.createRow(4);
            row5.createCell(0).setCellValue("c");
            row5.createCell(1).setCellValue("abc");
        });

        ExcelImportPrecheckResult result =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);

        // 空行被跳过：无缺值项；违规行号为物理行号 5（与 Excel UI 一致）
        assertEquals(1, result.getMissingParams().size());
        MissingParam param = result.getMissingParams().get(0);
        assertEquals("数量", param.getColumnName());
        assertEquals(ExcelImportPrecheckResult.KIND_TYPE_VIOLATION, param.getKind());
        assertEquals(Collections.singletonList(5), param.getRowNumbers());
    }

    // ===== 多选未命中选项 → 新选项创建项 =====

    @Test
    void precheck_multiSelectUnknownOptions_newOptionsCreated()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Collections.singletonList(
                column(108L, "标签", 4L, "{\"select\":\"高,中\"}", 0L)));

        byte[] bytes = singleSheetBytes(new String[] {"标签"}, Arrays.asList(
                new String[] {"高,紧急"},
                new String[] {"中,紧急"}));

        ExcelImportPrecheckResult result =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);

        assertTrue(result.getMissingParams().isEmpty());
        assertEquals(1, result.getNewOptions().size());
        NewOption option = result.getNewOptions().get(0);
        assertEquals("标签", option.getColumnName());
        assertEquals("紧急", option.getOptionText());
        assertEquals(2, option.getOccurrences());
    }

    // ===== 缺值有默认值 → 默认值填充项（空行数），不进弹框 =====

    @Test
    void precheck_missingValueWithDefault_defaultFillCountsEmptyRows()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(102L, "数量", 2L, "{\"default\":\"0\"}", 0L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称", "数量"}, Arrays.asList(
                new String[] {"a", "1"},
                new String[] {"b", ""},
                new String[] {"c", "3"}));

        ExcelImportPrecheckResult result =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);

        assertTrue(result.getMissingParams().isEmpty());
        assertFalse(result.isHasBlockingIssues());
        assertEquals(1, result.getDefaultValueFills().size());
        DefaultValueFill fill = result.getDefaultValueFills().get(0);
        assertEquals("数量", fill.getColumnName());
        assertEquals(1, fill.getRowCount());
    }

    // ===== AE4：歧义 → 预选 sort 靠前 =====

    @Test
    void precheck_ambiguousName_preselectsLowerSortRecord()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(104L, "关联", 21L, "{\"table_id\":80}", 0L)));
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        // 同名两记录：sort=5 在前插入，sort=2 在后 → 预选应取 sort=2
        mockRelatedRecords(Arrays.asList(record(201L, "张三", 5L), record(202L, "张三", 2L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本"},
                Collections.singletonList(new String[] {"a", "张三"}));

        ExcelImportPrecheckResult result =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);

        assertEquals(1, result.getAmbiguityItems().size());
        AmbiguityItem item = result.getAmbiguityItems().get(0);
        assertEquals(Long.valueOf(104L), item.getColumnId());
        assertEquals("关联", item.getColumnName());
        assertEquals("张三", item.getName());
        assertEquals(2, item.getCandidateCount());
        assertEquals(Long.valueOf(202L), item.getPreselectedRecordId());
        // 影响面只计预选记录（distinct 1 条）
        assertEquals(1, result.getSymmetricWriteImpact().get(0).getAffectedRecordCount());
        assertTrue(result.isHasBlockingIssues());
    }

    // ===== AE5：未命中 → 未命中名清单；关联空单元格不进任何缺参 =====

    @Test
    void precheck_unmatchedName_missNamesWithRowNumbers()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(104L, "关联", 21L, "{\"table_id\":80}", 0L)));
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Collections.singletonList(record(301L, "张三", 1L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本"}, Arrays.asList(
                new String[] {"a", "王五"},
                new String[] {"b", ""}));

        ExcelImportPrecheckResult result =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);

        // 仅一条缺参：关联未命中（空单元格不算缺参不算未命中，R15）
        assertEquals(1, result.getMissingParams().size());
        MissingParam param = result.getMissingParams().get(0);
        assertEquals(ExcelImportPrecheckResult.KIND_LINK_MISS, param.getKind());
        assertEquals("关联", param.getColumnName());
        assertEquals(1, param.getMissNames().size());
        MissName miss = param.getMissNames().get(0);
        assertEquals("王五", miss.getName());
        assertEquals(Collections.singletonList(2), miss.getRowNumbers());
        // 未命中无影响面
        assertTrue(result.getSymmetricWriteImpact().isEmpty());
        // 候选仍内嵌（供 U6 选择器）
        assertEquals(1, result.getRelationCandidates().size());
        assertEquals("张三", result.getRelationCandidates().get(0).getCandidates().get(0).getName());
        assertTrue(result.isHasBlockingIssues());
    }

    // ===== KTD4：整串优先 =====

    @Test
    void precheck_fullStringPreferred_recordNameWithCommaNotSplit()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(104L, "关联", 21L, "{\"table_id\":80}", 0L)));
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Arrays.asList(
                record(301L, "Smith, John", 1L),
                record(302L, "张三", 2L),
                record(303L, "李四", 3L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本"}, Arrays.asList(
                new String[] {"a", "Smith, John"},
                new String[] {"b", "张三,李四"}));

        ExcelImportPrecheckResult result =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);

        // "Smith, John" 整串命中（不被拆分）；"张三,李四" 整串未命中 → 拆分后各自命中
        assertTrue(result.getMissingParams().isEmpty());
        assertTrue(result.getAmbiguityItems().isEmpty());
        assertFalse(result.isHasBlockingIssues());
        // 3 条 distinct 命中记录
        assertEquals(1, result.getSymmetricWriteImpact().size());
        assertEquals(3, result.getSymmetricWriteImpact().get(0).getAffectedRecordCount());
        // 候选含含逗号记录名
        assertEquals("Smith, John", result.getRelationCandidates().get(0).getCandidates().get(0).getName());
    }

    // ===== 对称写入影响：distinct 记录数 =====

    @Test
    void precheck_symmetricImpact_countsDistinctRecords()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(104L, "关联", 21L, "{\"table_id\":80}", 0L)));
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Arrays.asList(
                record(301L, "张三", 1L),
                record(302L, "李四", 2L),
                record(303L, "王五", 3L)));

        // 4 行命中 3 条 distinct 记录（张三重复）
        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本"}, Arrays.asList(
                new String[] {"a", "张三"},
                new String[] {"b", "李四"},
                new String[] {"c", "王五"},
                new String[] {"d", "张三"}));

        ExcelImportPrecheckResult result =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);

        assertEquals(1, result.getSymmetricWriteImpact().size());
        SymmetricWriteImpact impact = result.getSymmetricWriteImpact().get(0);
        assertEquals("被关联表", impact.getTableName());
        assertEquals(3, impact.getAffectedRecordCount());
    }

    // ===== 关联缺列：缺参项 kind=关联缺列，不做 R23/不查记录 =====

    @Test
    void precheck_linkColumnMissingFromHeader_missingParamKindLinkMissingColumn()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(104L, "关联", 21L, "{\"table_id\":80}", 0L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称"},
                Collections.singletonList(new String[] {"a"}));

        ExcelImportPrecheckResult result =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);

        assertEquals(1, result.getMissingParams().size());
        MissingParam param = result.getMissingParams().get(0);
        assertEquals("关联", param.getColumnName());
        assertEquals(ExcelImportPrecheckResult.KIND_LINK_MISSING_COLUMN, param.getKind());
        assertEquals(Integer.valueOf(1), param.getTotalRows());
        // 未映射的关联列不触发 R23 校验与 KTD4 预解析，也不附候选
        verify(noteDwtableMapper, never()).selectNoteDwtableById(RELATED_TABLE_ID);
        verify(noteRecordMapper, never()).selectNoteRecordList(any(NoteRecordVo.class));
        assertTrue(result.getRelationCandidates().isEmpty());
        assertTrue(result.isHasBlockingIssues());
    }

    // ===== R23：被关联表不同 noteId → 阻断（含列名） =====

    @Test
    void precheck_relatedTableInDifferentNote_blockedWithColumnName()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(104L, "关联", 21L, "{\"table_id\":80}", 0L)));
        mockRelatedTable(relatedTable("其他笔记的表", 999L));

        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本"},
                Collections.singletonList(new String[] {"a", "张三"}));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID));

        assertTrue(ex.getMessage().contains("关联"));
        assertTrue(ex.getMessage().contains("不在当前笔记内"));
        // 阻断：未走到记录预解析
        verify(noteRecordMapper, never()).selectNoteRecordList(any(NoteRecordVo.class));
    }

    // ===== R23：被关联表归属校验失败 → 阻断（含列名与原因） =====

    @Test
    void precheck_relatedTableOwnershipFails_blockedWithColumnName()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(104L, "关联", 21L, "{\"table_id\":80}", 0L)));
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        doThrow(new ServiceException("无权操作他人数据")).when(agentOwnershipChecker)
                .checkDwtableOwnership(RELATED_TABLE_ID, USER_ID);

        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本"},
                Collections.singletonList(new String[] {"a", "张三"}));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID));

        assertTrue(ex.getMessage().contains("关联"));
        assertTrue(ex.getMessage().contains("无权操作他人数据"));
        verify(noteRecordMapper, never()).selectNoteRecordList(any(NoteRecordVo.class));
    }

    // ===== 损坏 xlsx → ServiceException 中文提示 =====

    @Test
    void precheck_corruptedFile_throwsChineseServiceException()
    {
        MockMultipartFile file = new MockMultipartFile("file", "bad.xlsx",
                XLSX_CONTENT_TYPE, "not an excel file".getBytes(StandardCharsets.UTF_8));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> precheckService.precheck(NOTE_ID, DWTABLE_ID, file, USER_ID));

        assertTrue(ex.getMessage().contains("Excel 文件解析失败"));
    }

    // ===== KTD5：文件指纹稳定且与内容匹配 =====

    @Test
    void precheck_fileFingerprint_stableAndMatchesContent()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Collections.singletonList(
                column(101L, "名称", 1L, null, 0L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称"},
                Collections.singletonList(new String[] {"a"}));
        MultipartFile file = xlsxFile(bytes);

        ExcelImportPrecheckResult first = precheckService.precheck(NOTE_ID, DWTABLE_ID, file, USER_ID);
        ExcelImportPrecheckResult second = precheckService.precheck(NOTE_ID, DWTABLE_ID, file, USER_ID);

        assertEquals(bytes.length, first.getFileFingerprint().getSize());
        assertEquals(md5Hex(bytes), first.getFileFingerprint().getMd5());
        assertEquals(first.getFileFingerprint().getMd5(), second.getFileFingerprint().getMd5());
        assertEquals(first.getFileFingerprint().getSize(), second.getFileFingerprint().getSize());
        // md5 为 32 位十六进制
        assertEquals(32, first.getFileFingerprint().getMd5().length());
    }

    // ==================================================================
    // 阶段二：导入写入（U5）
    // ==================================================================

    // ===== U5 构造工具 =====

    /** 构造 params JSON：指纹取自 fingerprintBytes；各段为 JSON 数组/对象字面量（null → 空缺省） */
    private String paramsJson(byte[] fingerprintBytes, String missingColumns, String missNames,
            String ambiguity, String columnValues, String relationSelections)
    {
        JSONObject root = new JSONObject();
        JSONObject fingerprint = new JSONObject();
        fingerprint.put("size", (long) fingerprintBytes.length);
        fingerprint.put("md5", md5Hex(fingerprintBytes));
        root.put("fileFingerprint", fingerprint);
        JSONObject baseline = new JSONObject();
        baseline.put("missingColumns", JSONArray.parseArray(missingColumns == null ? "[]" : missingColumns));
        baseline.put("missNames", JSONArray.parseArray(missNames == null ? "[]" : missNames));
        baseline.put("ambiguity", JSONArray.parseArray(ambiguity == null ? "[]" : ambiguity));
        root.put("precheckBaseline", baseline);
        root.put("columnValues", JSONObject.parseObject(columnValues == null ? "{}" : columnValues));
        root.put("relationSelections",
                JSONArray.parseArray(relationSelections == null ? "[]" : relationSelections));
        return root.toJSONString();
    }

    private int runImport(byte[] bytes, String params)
    {
        return precheckService.importExcelData(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), params, USER_ID);
    }

    /** 已存在的 B 表配对 item（r100 × columnId） */
    private NoteDwtableItem pairedItem(Long id, Long columnId, String linkRecordId, String linkItemId, String value)
    {
        NoteDwtableItem paired = new NoteDwtableItem();
        paired.setId(id);
        paired.setDwtId(RELATED_TABLE_ID);
        paired.setRecordId(100L);
        paired.setColumnId(columnId);
        paired.setLinkRecordId(linkRecordId);
        paired.setLinkItemId(linkItemId);
        paired.setValue(value);
        return paired;
    }

    /** 目标表标准列：名称(1) + 关联(21, table_id=80, back_field_id=502)；被关联表含配对列 502 */
    private void mockDoubleLinkColumns()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenAnswer(inv -> {
            NoteColumn query = inv.getArgument(0);
            if (RELATED_TABLE_ID.equals(query.getDwtableId()))
            {
                // #13：事务段配对列存在性校验依赖被关联表列清单（区分表查询分流）
                return Collections.singletonList(relatedColumn(502L, "关联的双向链接"));
            }
            return Arrays.asList(
                    column(101L, "名称", 1L, null, 0L),
                    column(104L, "关联", 21L, "{\"table_id\":80,\"back_field_id\":502}", 0L));
        });
    }

    /** 被关联表（tableId=80）列 */
    private NoteColumn relatedColumn(Long id, String name)
    {
        NoteColumn related = column(id, name, 21L, null, 0L);
        related.setDwtableId(RELATED_TABLE_ID);
        return related;
    }

    // ===== 三源合并（AE3 导入部分）+ 复选框映射 + sort/name 派生 =====

    @Test
    void import_threeSourceMerge_andSortAndNameDerivation()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(102L, "数量", 2L, "{\"default\":\"0\"}", 0L),
                column(107L, "备注", 1L, null, 0L),
                column(109L, "勾选", 7L, null, 0L),
                column(110L, "完成", 7L, "{\"default\":\"true\"}", 0L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称", "数量", "备注", "勾选"}, Arrays.asList(
                new String[] {"a", "1", "x", "true"},
                new String[] {"b", "", "", "false"},
                new String[] {"c", "3", "y", "xx"}));

        int count = runImport(bytes, paramsJson(bytes,
                "[\"备注\",\"勾选\"]", null, null, "{\"备注\":\"待补充\",\"勾选\":\"false\"}", null));

        assertEquals(3, count);

        // sort=maxSort+1 递增（6/7/8），name 按首个 type=1 列（名称）最终值派生
        verify(noteRecordMapper, times(3)).insertNoteRecord(recordCaptor.capture());
        for (int i = 0; i < 3; i++)
        {
            assertEquals(DWTABLE_ID, recordCaptor.getAllValues().get(i).getDwtableId());
            assertEquals(Long.valueOf(6L + i), recordCaptor.getAllValues().get(i).getSort());
            assertEquals(Arrays.asList("a", "b", "c").get(i), recordCaptor.getAllValues().get(i).getName());
        }

        // 3 行 × 5 列 = 15 个 item（密集模型，含缺列"完成"）
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        List<NoteDwtableItem> items = itemsCaptor.getValue();
        assertEquals(15, items.size());
        // rowIndex → columnId → item 索引
        Map<Long, NoteDwtableItem> itemByColumnRow0 = new LinkedHashMap<>();
        Map<Long, NoteDwtableItem> itemByColumnRow1 = new LinkedHashMap<>();
        Map<Long, NoteDwtableItem> itemByColumnRow2 = new LinkedHashMap<>();
        for (NoteDwtableItem item : items)
        {
            if (item.getRecordId().equals(recordCaptor.getAllValues().get(0).getId()))
            {
                itemByColumnRow0.put(item.getColumnId(), item);
            }
            else if (item.getRecordId().equals(recordCaptor.getAllValues().get(1).getId()))
            {
                itemByColumnRow1.put(item.getColumnId(), item);
            }
            else
            {
                itemByColumnRow2.put(item.getColumnId(), item);
            }
        }
        // 行1：Excel 值优先（复选框 true→'0'）
        assertEquals("a", itemByColumnRow0.get(101L).getValue());
        assertEquals("1", itemByColumnRow0.get(102L).getValue());
        assertEquals("x", itemByColumnRow0.get(107L).getValue());
        assertEquals("0", itemByColumnRow0.get(109L).getValue());
        // 行2：数量空 → 默认值 "0"；备注空 → 用户补参 "待补充"；复选框 false→'1'；缺列"完成" → 默认 true→'0'
        assertEquals("b", itemByColumnRow1.get(101L).getValue());
        assertEquals("0", itemByColumnRow1.get(102L).getValue());
        assertEquals("待补充", itemByColumnRow1.get(107L).getValue());
        assertEquals("1", itemByColumnRow1.get(109L).getValue());
        assertEquals("0", itemByColumnRow1.get(110L).getValue());
        // 行3：勾选 "xx" 类型违规 → 不用 Excel 值，用补参 "false"→'1'
        assertEquals("c", itemByColumnRow2.get(101L).getValue());
        assertEquals("3", itemByColumnRow2.get(102L).getValue());
        assertEquals("y", itemByColumnRow2.get(107L).getValue());
        assertEquals("1", itemByColumnRow2.get(109L).getValue());

        // 全量最终 flush 单批（15 < 500）；无对称写入与选项追加
        verify(noteDwtableItemMapper, never()).updateNoteDwtableItem(any(NoteDwtableItem.class));
        verify(noteColumnMapper, never()).updateNoteColumn(any(NoteColumn.class));
    }

    // ===== 对称写入：四字段语义对齐 UI 路径（AE6）+ 双向闭合 =====

    @Test
    void import_doubleLink_symmetricWrite_fourFieldsAndBidirectionalClosure()
    {
        mockDoubleLinkColumns();
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Collections.singletonList(record(100L, "B记录", 1L)));

        NoteDwtableItem paired = pairedItem(900L, 502L, "500", "800", "旧记录");
        paired.setLinkColumnId(999L);
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumnForUpdate(any(NoteDwtableItem.class)))
                .thenReturn(paired);

        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本"},
                Collections.singletonList(new String[] {"a", "B记录"}));
        int count = runImport(bytes, paramsJson(bytes, null, null, null, null, null));

        assertEquals(1, count);

        // 时序：批量 insert（最终 flush）→ FOR UPDATE 锁定 → 配对 update → 源 item 回写
        InOrder inOrder = inOrder(noteDwtableItemMapper);
        inOrder.verify(noteDwtableItemMapper).insertNoteDwtableItems(anyList());
        inOrder.verify(noteDwtableItemMapper)
                .selectNoteDwtableItemByRecordAndColumnForUpdate(any(NoteDwtableItem.class));
        inOrder.verify(noteDwtableItemMapper, times(2)).updateNoteDwtableItem(any(NoteDwtableItem.class));

        // 批量 insert：源 item 带 linkRecordId/value/linkColumnId（linkItemId 留待回写）
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        NoteDwtableItem sourceInserted = null;
        for (NoteDwtableItem item : itemsCaptor.getValue())
        {
            if (item.getColumnId().equals(104L))
            {
                sourceInserted = item;
            }
        }
        assertNotNull(sourceInserted);
        assertEquals("100", sourceInserted.getLinkRecordId());
        assertEquals("B记录", sourceInserted.getValue());
        assertEquals(Long.valueOf(502L), sourceInserted.getLinkColumnId());
        // linkItemId 不在批量 insert 时写入（配对 item id 锁定后才知道），
        // 由下方对称写入的单次 update 回填（同一对象被原地补字段，最终状态见 sourceUpdate 断言）
        Long sourceItemId = sourceInserted.getId();
        Long newRecordId = sourceInserted.getRecordId();
        assertNotNull(sourceItemId, "零缓冲不变量：最终 flush 后源 item 有自增 id");
        assertNotNull(newRecordId);

        // 两次 update：第一次配对 item（追加四字段），第二次源 item（回写 linkItemId）
        verify(noteDwtableItemMapper, times(2)).updateNoteDwtableItem(itemCaptor.capture());
        NoteDwtableItem pairedUpdate = itemCaptor.getAllValues().get(0);
        NoteDwtableItem sourceUpdate = itemCaptor.getAllValues().get(1);

        // 配对 item 四字段（对齐 UI 路径 L485-560 语义）：追加新记录 id / 源 item id / 新记录 name / linkColumnId=源列 id
        assertEquals("500," + newRecordId, pairedUpdate.getLinkRecordId());
        assertEquals("800," + sourceItemId, pairedUpdate.getLinkItemId());
        assertEquals("旧记录,a", pairedUpdate.getValue());
        assertEquals(Long.valueOf(104L), pairedUpdate.getLinkColumnId());
        assertEquals(Long.valueOf(100L), pairedUpdate.getRecordId());
        assertEquals(Long.valueOf(502L), pairedUpdate.getColumnId());

        // 源 item 回写：linkItemId=配对 item id，linkRecordId/value/linkColumnId 保持
        assertEquals("900", sourceUpdate.getLinkItemId());
        assertEquals("100", sourceUpdate.getLinkRecordId());
        assertEquals("B记录", sourceUpdate.getValue());
        assertEquals(Long.valueOf(502L), sourceUpdate.getLinkColumnId());

        // 双向闭合：A 新 item.linkRecordId=100 与 B 配对 item.linkRecordId 含 newRecordId 互指，两侧 value 一致
        assertTrue(pairedUpdate.getLinkRecordId().contains(String.valueOf(newRecordId)));
        assertEquals(sourceUpdate.getValue(), "B记录");
        assertTrue(pairedUpdate.getValue().endsWith(",a"));
    }

    // ===== 同名新记录：value 与 linkRecordId 等长（KTD3 修正① recordId 去重键） =====

    @Test
    void import_sameNameRecords_valueLengthMatchesLinkRecordId()
    {
        mockDoubleLinkColumns();
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Collections.singletonList(record(100L, "B记录", 1L)));
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumnForUpdate(any(NoteDwtableItem.class)))
                .thenReturn(pairedItem(900L, 502L, null, null, null));

        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本"}, Arrays.asList(
                new String[] {"重名", "B记录"},
                new String[] {"重名", "B记录"}));
        runImport(bytes, paramsJson(bytes, null, null, null, null, null));

        verify(noteDwtableItemMapper, times(3)).updateNoteDwtableItem(itemCaptor.capture());
        NoteDwtableItem pairedUpdate = itemCaptor.getAllValues().get(0);
        // 两条同名新记录都追加（value 去重键=recordId，非记录名）
        String[] recordIds = pairedUpdate.getLinkRecordId().split(",");
        String[] values = pairedUpdate.getValue().split(",");
        assertEquals(2, recordIds.length);
        assertEquals(2, values.length);
        assertEquals("重名", values[0]);
        assertEquals("重名", values[1]);
        assertNotEquals(recordIds[0], recordIds[1]);
    }

    // ===== 末批 <500：总单元格数非 500 整数倍时零缓冲不变量 =====

    @Test
    void import_finalBatchBeyondLimit_symmetricFieldsComplete()
    {
        mockDoubleLinkColumns();
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Collections.singletonList(record(100L, "B记录", 1L)));
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumnForUpdate(any(NoteDwtableItem.class)))
                .thenReturn(pairedItem(900L, 502L, null, null, null));

        // 251 行 × 2 列 = 502 单元格 → 首批 500 + 末批 2
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < 251; i++)
        {
            rows.add(new String[] {"r" + i, "B记录"});
        }
        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本"}, rows);

        int count = runImport(bytes, paramsJson(bytes, null, null, null, null, null));

        assertEquals(251, count);
        verify(noteDwtableItemMapper, times(2)).insertNoteDwtableItems(itemsCaptor.capture());
        List<List<NoteDwtableItem>> batches = itemsCaptor.getAllValues();
        assertEquals(500, batches.get(0).size());
        assertEquals(2, batches.get(1).size());
        // 零缓冲不变量：末批源 item 已有自增 id 与 recordId（对称写入 linkItemId 依赖）
        for (NoteDwtableItem item : batches.get(1))
        {
            assertNotNull(item.getId());
            assertNotNull(item.getRecordId());
        }
        // 配对 item 聚合单次 update：251 个新记录 id 全部追加（含末批行），value 等长
        verify(noteDwtableItemMapper, times(252)).updateNoteDwtableItem(itemCaptor.capture());
        NoteDwtableItem pairedUpdate = itemCaptor.getAllValues().get(0);
        assertEquals(251, pairedUpdate.getLinkRecordId().split(",").length);
        assertEquals(251, pairedUpdate.getValue().split(",").length);
    }

    // ===== 配对 item 不存在：upsert 创建且四字段完整（KTD3 修正②） =====

    @Test
    void import_pairedItemMissing_upsertCreatedWithFourFields()
    {
        mockDoubleLinkColumns();
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Collections.singletonList(record(100L, "B记录", 1L)));
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumnForUpdate(any(NoteDwtableItem.class)))
                .thenReturn(null);

        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本"},
                Collections.singletonList(new String[] {"a", "B记录"}));
        runImport(bytes, paramsJson(bytes, null, null, null, null, null));

        // upsert：insertNoteDwtableItem 创建配对 item，四字段完整
        verify(noteRecordMapper, times(1)).insertNoteRecord(recordCaptor.capture());
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItem(itemCaptor.capture());
        NoteDwtableItem created = itemCaptor.getValue();
        assertEquals(RELATED_TABLE_ID, created.getDwtId());
        assertEquals(Long.valueOf(100L), created.getRecordId());
        assertEquals(Long.valueOf(502L), created.getColumnId());
        assertEquals(recordCaptor.getAllValues().get(0).getId().toString(), created.getLinkRecordId());
        assertTrue(created.getLinkItemId().matches("\\d+"));
        assertEquals("a", created.getValue());
        assertEquals(Long.valueOf(104L), created.getLinkColumnId());

        // 源 item 回写 linkItemId=新建配对 item 的 id
        verify(noteDwtableItemMapper, times(1)).updateNoteDwtableItem(itemCaptor.capture());
        assertEquals(created.getId().toString(), itemCaptor.getValue().getLinkItemId());
    }

    // ===== 聚合：多个源 21 列（同一 back_field_id）+ 同一目标记录 → 单次 update 无覆盖丢失 =====

    @Test
    void import_multipleSourceColumns_aggregatedSingleUpdate()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenAnswer(inv -> {
            NoteColumn query = inv.getArgument(0);
            if (RELATED_TABLE_ID.equals(query.getDwtableId()))
            {
                // #13：被关联表列清单含配对列 502（事务段配对列存在性校验）
                return Collections.singletonList(relatedColumn(502L, "关联的双向链接"));
            }
            return Arrays.asList(
                    column(101L, "名称", 1L, null, 0L),
                    column(104L, "关联A", 21L, "{\"table_id\":80,\"back_field_id\":502}", 0L),
                    column(105L, "关联B", 21L, "{\"table_id\":80,\"back_field_id\":502}", 0L));
        });
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Collections.singletonList(record(100L, "B记录", 1L)));
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumnForUpdate(any(NoteDwtableItem.class)))
                .thenReturn(pairedItem(900L, 502L, null, null, null));

        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联A_文本", "关联B_文本"},
                Collections.singletonList(new String[] {"a", "B记录", "B记录"}));
        runImport(bytes, paramsJson(bytes, null, null, null, null, null));

        // 配对 item (100, 502) 仅一次 update；两个源 item 各回写一次 → 共 3 次 update
        verify(noteDwtableItemMapper, times(3)).updateNoteDwtableItem(itemCaptor.capture());
        NoteDwtableItem pairedUpdate = itemCaptor.getAllValues().get(0);
        // 新记录 id 追加一次（recordId 去重），两个源 item id 都追加，value 追加一次（无覆盖丢失）
        assertEquals(1, pairedUpdate.getLinkRecordId().split(",").length);
        assertEquals(2, pairedUpdate.getLinkItemId().split(",").length);
        assertEquals(1, pairedUpdate.getValue().split(",").length);
        // linkColumnId=最后一次追加的源列 id（对齐 UI 路径逐次覆盖的 last-write-wins 语义）
        assertEquals(Long.valueOf(105L), pairedUpdate.getLinkColumnId());
        // 两个源 item 均回写 linkItemId=配对 item id
        assertEquals("900", itemCaptor.getAllValues().get(1).getLinkItemId());
        assertEquals("900", itemCaptor.getAllValues().get(2).getLinkItemId());
    }

    // ===== 单向(18)列：link 字段经批量 insert 落库（P0 不丢弃），property.select 表 id 兼容 =====

    @Test
    void import_singleLink_linkFieldsPersistedViaBatchInsert()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                // 前端建列时 18 列 property 无 table_id，被关联表 id 存于 select 键
                column(105L, "单向", 18L, "{\"select\":80}", 0L)));
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Collections.singletonList(record(301L, "张三", 1L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称", "单向_文本"},
                Collections.singletonList(new String[] {"a", "张三"}));
        runImport(bytes, paramsJson(bytes, null, null, null, null, null));

        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        NoteDwtableItem linkItem = null;
        for (NoteDwtableItem item : itemsCaptor.getValue())
        {
            if (item.getColumnId().equals(105L))
            {
                linkItem = item;
            }
        }
        assertNotNull(linkItem, "18 列 item 必须经批量 insert 落库");
        assertEquals("301", linkItem.getLinkRecordId());
        assertEquals("张三", linkItem.getValue());
        assertNull(linkItem.getLinkColumnId(), "18 列无配对列（UI 落库形态 linkColumnId=null）");
        assertNull(linkItem.getLinkItemId());

        // 18 列无对称写入：零 FOR UPDATE / 零 update / 零单条 insert
        verify(noteDwtableItemMapper, never()).selectNoteDwtableItemByRecordAndColumnForUpdate(any(NoteDwtableItem.class));
        verify(noteDwtableItemMapper, never()).updateNoteDwtableItem(any(NoteDwtableItem.class));
        verify(noteDwtableItemMapper, never()).insertNoteDwtableItem(any(NoteDwtableItem.class));
        // R23：被关联表归属校验执行
        verify(agentOwnershipChecker, times(1)).checkDwtableOwnership(RELATED_TABLE_ID, USER_ID);
    }

    // ===== 选项自动创建（AE7）：单选整格 / 多选拆分追加，上限 100 中止 =====

    @Test
    void import_unknownOptions_appendedToSelectProperty()
    {
        NoteColumn singleSelect = column(103L, "状态", 3L, "{\"select\":\"进行中,已完成\"}", 0L);
        NoteColumn multiSelect = column(108L, "标签", 4L, "{\"select\":\"高,中\"}", 0L);
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class)))
                .thenReturn(Arrays.asList(singleSelect, multiSelect));
        when(noteColumnMapper.selectNoteColumnByIdForUpdate(103L)).thenReturn(singleSelect);
        when(noteColumnMapper.selectNoteColumnByIdForUpdate(108L)).thenReturn(multiSelect);

        byte[] bytes = singleSheetBytes(new String[] {"状态", "标签"}, Arrays.asList(
                new String[] {"进行中", "高,紧急"},
                new String[] {"紧急", "中"}));
        runImport(bytes, paramsJson(bytes, null, null, null, null, null));

        // 两个列的 select 字符串各追加一个未知选项（去重）
        verify(noteColumnMapper, times(2)).updateNoteColumn(columnCaptor.capture());
        for (NoteColumn updated : columnCaptor.getAllValues())
        {
            if (updated.getId().equals(103L))
            {
                assertEquals("进行中,已完成,紧急", parseSelectOf(updated));
            }
            else
            {
                assertEquals("高,中,紧急", parseSelectOf(updated));
            }
        }
        // item 值保持单元格文本原样
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        boolean hasUrgentValue = false;
        for (NoteDwtableItem item : itemsCaptor.getValue())
        {
            if ("紧急".equals(item.getValue()))
            {
                hasUrgentValue = true;
            }
        }
        assertTrue(hasUrgentValue);
    }

    private String parseSelectOf(NoteColumn column)
    {
        return JSONObject.parseObject(column.getProperty()).getString("select");
    }

    @Test
    void import_tooManyNewOptions_aborted()
    {
        NoteColumn singleSelect = column(103L, "状态", 3L, "{\"select\":\"进行中\"}", 0L);
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class)))
                .thenReturn(Collections.singletonList(singleSelect));
        when(noteColumnMapper.selectNoteColumnByIdForUpdate(103L)).thenReturn(singleSelect);

        // 101 个 distinct 未知选项（101 行 × 1 列 = 101 单元格 < 500，选项追加先于最终 flush）
        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i <= 101; i++)
        {
            rows.add(new String[] {"选项" + i});
        }
        byte[] bytes = singleSheetBytes(new String[] {"状态"}, rows);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, paramsJson(bytes, null, null, null, null, null)));
        assertTrue(ex.getMessage().contains("超过上限"));

        // 中止于选项追加（回滚语义）：零 item 落库、零选项落库
        verify(noteDwtableItemMapper, never()).insertNoteDwtableItems(anyList());
        verify(noteColumnMapper, never()).updateNoteColumn(any(NoteColumn.class));
    }

    // ===== 回滚（AE8，失败发生在 flush 前）：第 50 行 × 2 列 = 100 单元格 < 500，
    // 批量 flush 尚未发生 → 目标表零写入 + B 表存量 item 零修改 + 选项零追加 =====

    @Test
    void import_rowFailureBeforeFlush_zeroWritesAfterRollback()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenAnswer(inv -> {
            NoteColumn query = inv.getArgument(0);
            if (RELATED_TABLE_ID.equals(query.getDwtableId()))
            {
                // #13：被关联表列清单含配对列 502（事务段配对列存在性校验）
                return Collections.singletonList(relatedColumn(502L, "关联的双向链接"));
            }
            return Arrays.asList(
                    column(101L, "名称", 1L, null, 0L),
                    column(104L, "关联", 21L, "{\"table_id\":80,\"back_field_id\":502}", 0L));
        });
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Collections.singletonList(record(100L, "B记录", 1L)));

        AtomicLong insertCalls = new AtomicLong();
        when(noteRecordMapper.insertNoteRecord(any(NoteRecord.class))).thenAnswer(inv -> {
            if (insertCalls.incrementAndGet() == 50L)
            {
                throw new ServiceException("第 50 行写入失败");
            }
            NoteRecord record = inv.getArgument(0);
            record.setId(recordIdSequence.getAndIncrement());
            return 1;
        });

        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++)
        {
            rows.add(new String[] {"r" + i, "B记录"});
        }
        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本"}, rows);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, paramsJson(bytes, null, null, null, null, null)));
        assertEquals("第 50 行写入失败", ex.getMessage());

        // 100 单元格 < 500：批量 flush 未发生 → 目标表零 item 写入
        verify(noteDwtableItemMapper, never()).insertNoteDwtableItems(anyList());
        // B 表存量 item 零修改（零 FOR UPDATE / 零 update / 零 upsert insert）
        verify(noteDwtableItemMapper, never()).selectNoteDwtableItemByRecordAndColumnForUpdate(any(NoteDwtableItem.class));
        verify(noteDwtableItemMapper, never()).updateNoteDwtableItem(any(NoteDwtableItem.class));
        verify(noteDwtableItemMapper, never()).insertNoteDwtableItem(any(NoteDwtableItem.class));
        // 选项零追加、重算零调用
        verify(noteColumnMapper, never()).updateNoteColumn(any(NoteColumn.class));
        verify(noteRecordService, never()).recomputeLookupColumnValues(any(NoteColumn.class));
        verify(noteRecordService, never()).recomputeSetOperationColumn(any(NoteColumn.class));
    }

    // ===== 反向漂移 fail-fast（KTD5）：预检全命中、阶段二被关联记录被删 → 整体拒绝零写入 =====

    @Test
    void import_drift_newMissName_rejected()
    {
        mockDoubleLinkColumns();
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        // 预检时存在"李四"，阶段二已被删（只剩张三）
        mockRelatedRecords(Collections.singletonList(record(100L, "张三", 1L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本"},
                Collections.singletonList(new String[] {"a", "李四"}));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, paramsJson(bytes, null, null, null, null, null)));
        assertTrue(ex.getMessage().contains("数据已变化，请重新预检"));
        assertTrue(ex.getMessage().contains("李四"));

        // 整体拒绝：零写入
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
        verify(noteDwtableItemMapper, never()).insertNoteDwtableItems(anyList());
        verify(noteDwtableItemMapper, never()).updateNoteDwtableItem(any(NoteDwtableItem.class));
    }

    // ===== 文件指纹不一致拒绝 =====

    @Test
    void import_fingerprintMismatch_rejected()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Collections.singletonList(
                column(101L, "名称", 1L, null, 0L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称"},
                Collections.singletonList(new String[] {"a"}));
        byte[] otherBytes = singleSheetBytes(new String[] {"名称"},
                Collections.singletonList(new String[] {"b"}));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, paramsJson(otherBytes, null, null, null, null, null)));
        assertEquals("文件与预检时不一致，请重新预检", ex.getMessage());
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
    }

    // ===== 反向漂移：预检外新缺参列拒绝 =====

    @Test
    void import_drift_newMissingColumn_rejected()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(102L, "数量", 2L, null, 0L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称", "数量"},
                Arrays.asList(new String[] {"a", "1"}, new String[] {"b", ""}));

        // 基线缺参列为空（伪造/过期）：阶段二出现缺值"数量" → 漂移
        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, paramsJson(bytes, null, null, null, null, null)));
        assertTrue(ex.getMessage().contains("数据已变化，请重新预检"));
        assertTrue(ex.getMessage().contains("数量"));
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
    }

    // ===== 反向漂移：歧义预选变化拒绝 =====

    @Test
    void import_drift_ambiguityPreselectChanged_rejected()
    {
        mockDoubleLinkColumns();
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        // 同名两记录：sort=2 在前 → 阶段二预选 202
        mockRelatedRecords(Arrays.asList(record(201L, "张三", 5L), record(202L, "张三", 2L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本"},
                Collections.singletonList(new String[] {"a", "张三"}));

        // 基线预选 201 ≠ 阶段二预选 202 → 漂移
        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, paramsJson(bytes, null, null,
                        "[{\"columnName\":\"关联\",\"name\":\"张三\",\"preselectedRecordId\":201}]",
                        null, null)));
        assertTrue(ex.getMessage().contains("数据已变化，请重新预检"));
        assertTrue(ex.getMessage().contains("预选记录已变化"));
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
    }

    // ===== 补参校验（R26）：伪造 recordId（不属于被关联表/不存在）拒绝 =====

    @Test
    void import_supplement_forgedRecordId_rejected()
    {
        mockDoubleLinkColumns();
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Collections.singletonList(record(100L, "张三", 1L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本"},
                Collections.singletonList(new String[] {"a", "王五"}));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, paramsJson(bytes, "[\"关联\"]",
                        "[{\"columnName\":\"关联\",\"name\":\"王五\"}]", null, null,
                        "[{\"columnName\":\"关联\",\"name\":\"王五\",\"recordId\":999}]")));
        assertTrue(ex.getMessage().contains("数据已变化，请重新预检"));
        assertTrue(ex.getMessage().contains("999"));
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
    }

    // ===== 补参校验（R26）：伪造文本补值类型非法拒绝 =====

    @Test
    void import_supplement_invalidTextValue_rejected()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(102L, "数量", 2L, null, 0L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称", "数量"},
                Collections.singletonList(new String[] {"a", ""}));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, paramsJson(bytes, "[\"数量\"]", null, null,
                        "{\"数量\":\"abc\"}", null)));
        assertTrue(ex.getMessage().contains("补参值类型非法"));
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
    }

    // ===== 补参优先语义（KTD5）：显式留空后重新匹配命中也不建立关联 =====

    @Test
    void import_supplementPriority_explicitNullBeatsStage2Match()
    {
        mockDoubleLinkColumns();
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        // 阶段二"王五"已存在（预检后新增），但补参显式留空
        mockRelatedRecords(Arrays.asList(record(100L, "张三", 1L), record(300L, "王五", 2L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本"},
                Collections.singletonList(new String[] {"a", "王五"}));

        runImport(bytes, paramsJson(bytes, null,
                "[{\"columnName\":\"关联\",\"name\":\"王五\"}]", null, null,
                "[{\"columnName\":\"关联\",\"name\":\"王五\",\"recordId\":null}]"));

        // 不建立关联：item 空值无 link 字段，B 表零修改
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        for (NoteDwtableItem item : itemsCaptor.getValue())
        {
            if (item.getColumnId().equals(104L))
            {
                assertEquals("", item.getValue());
                assertNull(item.getLinkRecordId());
                assertNull(item.getLinkColumnId());
            }
        }
        verify(noteDwtableItemMapper, never()).selectNoteDwtableItemByRecordAndColumnForUpdate(any(NoteDwtableItem.class));
        verify(noteDwtableItemMapper, never()).updateNoteDwtableItem(any(NoteDwtableItem.class));
    }

    // ===== 补参优先语义（KTD5）：歧义改选后以补参为准 =====

    @Test
    void import_supplementPriority_reselectionWinsOverPreselect()
    {
        mockDoubleLinkColumns();
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        // 同名两记录：sort=2 的 202 为预选；补参改选 201
        mockRelatedRecords(Arrays.asList(record(201L, "张三", 5L), record(202L, "张三", 2L)));
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumnForUpdate(any(NoteDwtableItem.class)))
                .thenAnswer(inv -> {
                    NoteDwtableItem query = inv.getArgument(0);
                    return Long.valueOf(201L).equals(query.getRecordId())
                            ? pairedItem(900L, 502L, null, null, null) : null;
                });

        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本"},
                Collections.singletonList(new String[] {"a", "张三"}));

        runImport(bytes, paramsJson(bytes, null, null,
                "[{\"columnName\":\"关联\",\"name\":\"张三\",\"preselectedRecordId\":202}]", null,
                "[{\"columnName\":\"关联\",\"name\":\"张三\",\"recordId\":201}]"));

        // 改选生效：源 item linkRecordId=201（非预选 202），配对 item 锁定在 (201, 502)
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        for (NoteDwtableItem item : itemsCaptor.getValue())
        {
            if (item.getColumnId().equals(104L))
            {
                assertEquals("201", item.getLinkRecordId());
            }
        }
        verify(noteDwtableItemMapper, times(1))
                .selectNoteDwtableItemByRecordAndColumnForUpdate(itemCaptor.capture());
        assertEquals(Long.valueOf(201L), itemCaptor.getValue().getRecordId());
    }

    // ===== params JSON 非法：解析失败明确拒绝 =====

    @Test
    void import_invalidParamsJson_rejectedWithClearMessage()
    {
        byte[] bytes = singleSheetBytes(new String[] {"名称"},
                Collections.singletonList(new String[] {"a"}));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, "{invalid json"));
        assertTrue(ex.getMessage().contains("格式错误"));
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
    }

    // ===== 重算编排（KTD8）：目标表 lookup 两步级联 + 直接引用 18/21 的集合运算列 + B 表派生列，时序在最终 flush 后 =====

    @Test
    void import_recomputeOrchestration_orderedAfterFinalFlush()
    {
        NoteColumn nameCol = column(101L, "名称", 1L, null, 0L);
        NoteColumn linkCol = column(104L, "关联", 21L, "{\"table_id\":80,\"back_field_id\":502}", 0L);
        NoteColumn targetLookup = column(130L, "引用", 26L,
                "{\"double_link_column_id\":\"104\",\"source_column_id\":\"101\"}", 0L);
        NoteColumn targetSetColumn = column(131L, "运算", 24L,
                "{\"columnAId\":\"104\",\"columnBId\":\"101\",\"calcType\":\"union\"}", 0L);
        // B 表：配对列 P + 锚定列指向源列 C(104) 的 lookup + columnA 引用 P(502) 的集合运算列
        NoteColumn pairedColumn = column(502L, "关联的双向链接", 21L,
                "{\"table_id\":60,\"back_field_id\":104}", 0L);
        NoteColumn relatedLookup = column(140L, "B引用", 26L,
                "{\"double_link_column_id\":\"104\",\"source_column_id\":\"510\"}", 0L);
        NoteColumn relatedSetColumn = column(141L, "B运算", 24L,
                "{\"columnAId\":\"502\",\"columnBId\":\"510\",\"calcType\":\"union\"}", 0L);
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenAnswer(inv -> {
            NoteColumn query = inv.getArgument(0);
            if (DWTABLE_ID.equals(query.getDwtableId()))
            {
                return Arrays.asList(nameCol, linkCol, targetLookup, targetSetColumn);
            }
            if (RELATED_TABLE_ID.equals(query.getDwtableId()))
            {
                return Arrays.asList(pairedColumn, relatedLookup, relatedSetColumn);
            }
            return new ArrayList<NoteColumn>();
        });
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Collections.singletonList(record(100L, "B记录", 1L)));
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumnForUpdate(any(NoteDwtableItem.class)))
                .thenReturn(pairedItem(900L, 502L, null, null, null));

        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本"},
                Collections.singletonList(new String[] {"a", "B记录"}));
        runImport(bytes, paramsJson(bytes, null, null, null, null, null));

        // 编排清单与时序：最终 flush → 对称写入 → 目标表 lookup 两步级联 →
        // 目标表直接引用 21 列的集合运算列 → B 表 lookup（锚定源列 C）两步级联 → B 表引用 P 的集合运算列
        InOrder inOrder = inOrder(noteDwtableItemMapper, noteRecordService);
        inOrder.verify(noteDwtableItemMapper).insertNoteDwtableItems(anyList());
        inOrder.verify(noteDwtableItemMapper, times(2)).updateNoteDwtableItem(any(NoteDwtableItem.class));
        inOrder.verify(noteRecordService).recomputeLookupColumnValues(targetLookup);
        inOrder.verify(noteRecordService).recomputeSetOperationsForLookup(targetLookup);
        inOrder.verify(noteRecordService).recomputeSetOperationColumn(targetSetColumn);
        inOrder.verify(noteRecordService).recomputeLookupColumnValues(relatedLookup);
        inOrder.verify(noteRecordService).recomputeSetOperationsForLookup(relatedLookup);
        inOrder.verify(noteRecordService).recomputeSetOperationColumn(relatedSetColumn);

        // 无重复重算
        verify(noteRecordService, times(2)).recomputeLookupColumnValues(any(NoteColumn.class));
        verify(noteRecordService, times(2)).recomputeSetOperationsForLookup(any(NoteColumn.class));
        verify(noteRecordService, times(2)).recomputeSetOperationColumn(any(NoteColumn.class));
    }

    // ==================================================================
    // 审查修复批：列名 trim 两阶段链路 / 隐藏 name 列 / 参数条目上限 / flush 后失败 /
    // 防御分支 / 多选全角逗号
    // ==================================================================

    // ===== #4：列名带首尾空格的两阶段链路（预检 DTO trim 输出 → 基线回传不漂移、补参生效） =====

    @Test
    void precheckAndImport_columnNameWithSpaces_trimmedEndToEnd()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(103L, " 状态 ", 1L, null, 0L)));

        // 第 2 行" 状态 "列空（同行名称列有值，行本身非全空行）
        byte[] bytes = singleSheetBytes(new String[] {"名称", "状态"}, Arrays.asList(
                new String[] {"a", "x"},
                new String[] {"b", ""}));

        // 预检：缺值列名输出为 trim 后的"状态"（与阶段二漂移比对键、补参取值键口径一致）
        ExcelImportPrecheckResult result =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);
        assertEquals(1, result.getMissingParams().size());
        assertEquals("状态", result.getMissingParams().get(0).getColumnName());

        // 导入：基线/补参键用 trim 后列名 → 无漂移，补参对空单元格生效
        int count = runImport(bytes, paramsJson(bytes, "[\"状态\"]", null, null, "{\"状态\":\"补值\"}", null));

        assertEquals(2, count);
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        List<String> values = new ArrayList<>();
        for (NoteDwtableItem item : itemsCaptor.getValue())
        {
            values.add(item.getValue());
        }
        // 行序 × 列序（名称, 状态）：行2 的" 状态 "列 = 补参"补值"
        assertEquals(Arrays.asList("a", "x", "b", "补值"), values);
    }

    // ===== #6：隐藏最左 type=1 列 → name 派生取首个可见 type=1 列 =====

    @Test
    void import_hiddenFirstNameColumn_nameDerivedFromFirstVisibleType1()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(150L, "隐藏名", 1L, null, 1L),
                column(101L, "名称", 1L, null, 0L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称"},
                Collections.singletonList(new String[] {"a"}));

        runImport(bytes, paramsJson(bytes, null, null, null, null, null));

        verify(noteRecordMapper, times(1)).insertNoteRecord(recordCaptor.capture());
        assertEquals("a", recordCaptor.getValue().getName());
    }

    // ===== #9：导入参数条目数超限（columnValues / relationSelections 各 10000）拒绝 =====

    @Test
    void import_paramsOverLimit_rejected()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Collections.singletonList(
                column(101L, "名称", 1L, null, 0L)));
        byte[] bytes = singleSheetBytes(new String[] {"名称"},
                Collections.singletonList(new String[] {"a"}));

        // columnValues 10001 条 > 10000
        JSONObject overLimitColumnValues = paramsRootOf(bytes);
        JSONObject columnValues = new JSONObject();
        for (int i = 0; i <= 10000; i++)
        {
            columnValues.put("列" + i, "v");
        }
        overLimitColumnValues.put("columnValues", columnValues);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, overLimitColumnValues.toJSONString()));
        assertTrue(ex.getMessage().contains("导入参数条目数超限"));
        assertTrue(ex.getMessage().contains("columnValues"));

        // relationSelections 10001 条 > 10000
        JSONObject overLimitSelections = paramsRootOf(bytes);
        JSONArray selections = new JSONArray();
        for (int i = 0; i <= 10000; i++)
        {
            JSONObject selection = new JSONObject();
            selection.put("columnName", "关联");
            selection.put("name", "n" + i);
            selections.add(selection);
        }
        overLimitSelections.put("relationSelections", selections);
        ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, overLimitSelections.toJSONString()));
        assertTrue(ex.getMessage().contains("导入参数条目数超限"));
        assertTrue(ex.getMessage().contains("relationSelections"));

        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
    }

    /** 构造带合法指纹与空基线的 params 根对象（超限测试用） */
    private JSONObject paramsRootOf(byte[] bytes)
    {
        JSONObject root = new JSONObject();
        JSONObject fingerprint = new JSONObject();
        fingerprint.put("size", (long) bytes.length);
        fingerprint.put("md5", md5Hex(bytes));
        root.put("fileFingerprint", fingerprint);
        JSONObject baseline = new JSONObject();
        baseline.put("missingColumns", new JSONArray());
        baseline.put("missNames", new JSONArray());
        baseline.put("ambiguity", new JSONArray());
        root.put("precheckBaseline", baseline);
        root.put("columnValues", new JSONObject());
        root.put("relationSelections", new JSONArray());
        return root;
    }

    // ===== #15：≥501 单元格中途失败（首批已 flush 后）→ 异常传播且失败点后零后续写入 =====

    @Test
    void import_rowFailureAfterFlush_zeroSubsequentMapperWrites()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(102L, "数量", 2L, null, 0L),
                column(107L, "备注", 1L, null, 0L)));

        // 200 行 × 3 列 = 600 单元格：第 167 行末首批 501 单元格 flush，第 200 行注入失败
        AtomicLong insertCalls = new AtomicLong();
        when(noteRecordMapper.insertNoteRecord(any(NoteRecord.class))).thenAnswer(inv -> {
            if (insertCalls.incrementAndGet() == 200L)
            {
                throw new ServiceException("第 200 行写入失败");
            }
            NoteRecord record = inv.getArgument(0);
            record.setId(recordIdSequence.getAndIncrement());
            return 1;
        });

        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < 200; i++)
        {
            rows.add(new String[] {"r" + i, "1", "x"});
        }
        byte[] bytes = singleSheetBytes(new String[] {"名称", "数量", "备注"}, rows);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, paramsJson(bytes, null, null, null, null, null)));
        assertEquals("第 200 行写入失败", ex.getMessage());

        // 失败点前：恰好一次首批 flush（501 单元格）；失败点后：零后续 mapper 写调用
        verify(noteRecordMapper, times(200)).insertNoteRecord(any(NoteRecord.class));
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        assertEquals(501, itemsCaptor.getValue().size());
        verify(noteDwtableItemMapper, never()).updateNoteDwtableItem(any(NoteDwtableItem.class));
        verify(noteDwtableItemMapper, never()).insertNoteDwtableItem(any(NoteDwtableItem.class));
        verify(noteColumnMapper, never()).updateNoteColumn(any(NoteColumn.class));
        verify(noteRecordService, never()).recomputeLookupColumnValues(any(NoteColumn.class));
        verify(noteRecordService, never()).recomputeSetOperationColumn(any(NoteColumn.class));
    }

    // ===== #16：checkDrift 新增同名记录分支（基线歧义为空、阶段二两条同名 → 拒绝） =====

    @Test
    void import_drift_newSameNameRecord_rejected()
    {
        mockDoubleLinkColumns();
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        // 预检后新增同名"张三"记录：阶段二出现两条同名 → 基线（歧义为空）外的歧义
        mockRelatedRecords(Arrays.asList(record(201L, "张三", 1L), record(202L, "张三", 2L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本"},
                Collections.singletonList(new String[] {"a", "张三"}));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, paramsJson(bytes, null, null, null, null, null)));
        assertTrue(ex.getMessage().contains("数据已变化，请重新预检"));
        assertTrue(ex.getMessage().contains("新的同名记录"));
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
    }

    // ===== #16：columnValues 含不存在列名 → 拒绝 =====

    @Test
    void import_supplement_unknownColumnName_rejected()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(102L, "数量", 2L, null, 0L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称", "数量"},
                Collections.singletonList(new String[] {"a", "1"}));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, paramsJson(bytes, null, null, null, "{\"不存在列\":\"x\"}", null)));
        assertTrue(ex.getMessage().contains("在目标数据表中不存在"));
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
    }

    // ===== #16：columnValues 键为 21 关联列 → "不是基础列"拒绝 =====

    @Test
    void import_supplement_nonBasicColumn_rejected()
    {
        mockDoubleLinkColumns();
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Collections.singletonList(record(100L, "张三", 1L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本"},
                Collections.singletonList(new String[] {"a", "张三"}));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, paramsJson(bytes, null, null, null, "{\"关联\":\"x\"}", null)));
        assertTrue(ex.getMessage().contains("不是基础列"));
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
    }

    // ===== #16：relationSelections 列未映射（缺列关联列）→ "未参与本次导入"拒绝 =====

    @Test
    void import_supplement_relationColumnNotImported_rejected()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(104L, "关联", 21L, "{\"table_id\":80}", 0L)));
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Collections.singletonList(record(100L, "张三", 1L)));

        // 关联列不在 Excel 表头中（关联缺列）→ 不在 headerIndexByColumn
        byte[] bytes = singleSheetBytes(new String[] {"名称"},
                Collections.singletonList(new String[] {"a"}));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, paramsJson(bytes, "[\"关联\"]", null, null, null,
                        "[{\"columnName\":\"关联\",\"name\":\"张三\",\"recordId\":100}]")));
        assertTrue(ex.getMessage().contains("未参与本次导入"));
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
    }

    // ===== #19：多选单元格全角逗号归一拆分（预检拆分命中 + 阶段二选项追加） =====

    @Test
    void precheckAndImport_multiSelectFullWidthComma_splitPerOption()
    {
        NoteColumn multiSelect = column(108L, "标签", 4L, "{\"select\":\"高,中\"}", 0L);
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class)))
                .thenReturn(Collections.singletonList(multiSelect));
        when(noteColumnMapper.selectNoteColumnByIdForUpdate(108L)).thenReturn(multiSelect);

        // "高，紧急"（全角逗号）：归一后拆分 → "高"命中选项、"紧急"进新选项（不整串污染）
        byte[] bytes = singleSheetBytes(new String[] {"标签"},
                Collections.singletonList(new String[] {"高，紧急"}));

        ExcelImportPrecheckResult result =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);
        assertTrue(result.getMissingParams().isEmpty());
        assertEquals(1, result.getNewOptions().size());
        assertEquals("紧急", result.getNewOptions().get(0).getOptionText());

        runImport(bytes, paramsJson(bytes, null, null, null, null, null));
        verify(noteColumnMapper, times(1)).updateNoteColumn(columnCaptor.capture());
        assertEquals("高,中,紧急", parseSelectOf(columnCaptor.getValue()));
    }

    // ==================================================================
    // 审查修复批二（#13）：两阶段间隙列删除漂移漏网
    // ==================================================================

    // ===== #13：目标表参与列在事务段被删（前置段快照含、重查不含）→ 拒绝零写入 =====

    @Test
    void import_drift_participantColumnDeletedInTransaction_rejected()
    {
        // 前置段首次列查询返回"名称+数量"，事务段入口重查不含"数量"（模拟预检/前置段后被删）
        AtomicInteger targetColumnQueries = new AtomicInteger();
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenAnswer(inv -> {
            NoteColumn query = inv.getArgument(0);
            if (DWTABLE_ID.equals(query.getDwtableId()) && targetColumnQueries.incrementAndGet() > 1)
            {
                return Collections.singletonList(column(101L, "名称", 1L, null, 0L));
            }
            return Arrays.asList(
                    column(101L, "名称", 1L, null, 0L),
                    column(102L, "数量", 2L, null, 0L));
        });

        byte[] bytes = singleSheetBytes(new String[] {"名称", "数量"}, Arrays.asList(
                new String[] {"a", "1"},
                new String[] {"b", "2"}));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, paramsJson(bytes, null, null, null, null, null)));
        assertTrue(ex.getMessage().contains("数据已变化，请重新预检"));
        assertTrue(ex.getMessage().contains("数量"));
        assertTrue(ex.getMessage().contains("已被删除"));

        // fail-fast 于任何写入之前：拒绝零写入
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
        verify(noteDwtableItemMapper, never()).insertNoteDwtableItems(anyList());
        verify(noteDwtableItemMapper, never()).updateNoteDwtableItem(any(NoteDwtableItem.class));
        verify(noteDwtableItemMapper, never()).insertNoteDwtableItem(any(NoteDwtableItem.class));
    }

    // ===== #13：backFieldId 配对列不在被关联表列清单（property 残留引用）→ 拒绝零写入 =====

    @Test
    void import_drift_backFieldColumnDeleted_rejected()
    {
        // 源列 property.back_field_id=502 残留（列删除不清理 back_field_id），
        // 被关联表现存列清单不含 502 → 对称写入前显式校验拒绝
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenAnswer(inv -> {
            NoteColumn query = inv.getArgument(0);
            if (RELATED_TABLE_ID.equals(query.getDwtableId()))
            {
                return Collections.singletonList(relatedColumn(501L, "其他列"));
            }
            return Arrays.asList(
                    column(101L, "名称", 1L, null, 0L),
                    column(104L, "关联", 21L, "{\"table_id\":80,\"back_field_id\":502}", 0L));
        });
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Collections.singletonList(record(100L, "B记录", 1L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本"},
                Collections.singletonList(new String[] {"a", "B记录"}));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, paramsJson(bytes, null, null, null, null, null)));
        assertTrue(ex.getMessage().contains("数据已变化，请重新预检"));
        assertTrue(ex.getMessage().contains("关联"));
        assertTrue(ex.getMessage().contains("502"));
        assertTrue(ex.getMessage().contains("配对列"));

        // fail-fast 于任何写入之前（事务段入口，配对 upsert/orphan item 零发生）
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
        verify(noteDwtableItemMapper, never()).insertNoteDwtableItems(anyList());
        verify(noteDwtableItemMapper, never()).updateNoteDwtableItem(any(NoteDwtableItem.class));
        verify(noteDwtableItemMapper, never()).insertNoteDwtableItem(any(NoteDwtableItem.class));
        verify(noteDwtableItemMapper, never())
                .selectNoteDwtableItemByRecordAndColumnForUpdate(any(NoteDwtableItem.class));
    }
}
