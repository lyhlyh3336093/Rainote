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
import java.util.List;
import java.util.function.Consumer;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.agent.security.AgentOwnershipChecker;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.NoteDwtable;
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
import com.ruoyi.system.mapper.NoteDwtableMapper;
import com.ruoyi.system.mapper.NoteRecordMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NoteDwtableExcelImportServiceImpl} 预检（阶段一）单元测试。
 * <p>
 * 覆盖 U4 场景：AE1 预检无缺参（完整影响面 + 零写库）、AE2 缺列有默认值、
 * AE3 缺值无默认值、AE4 歧义预选 sort 靠前、AE5 未命中名清单（关联空单元格不算缺参）、
 * KTD4 整串优先、对称写入影响 distinct 计数、R23 被关联表归属失败阻断（含列名）、
 * 损坏 xlsx 中文提示、KTD5 文件指纹、关联缺列/类型违规/新选项创建/缺值默认填充。
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
    private AgentOwnershipChecker agentOwnershipChecker;

    @InjectMocks
    private NoteDwtableExcelImportServiceImpl precheckService;

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
}
