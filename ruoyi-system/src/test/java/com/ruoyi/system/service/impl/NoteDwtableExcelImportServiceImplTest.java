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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.mockito.Spy;
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
import com.ruoyi.system.domain.NoteNotelink;
import com.ruoyi.system.domain.NoteNote;
import com.ruoyi.system.domain.NoteRecord;
import com.ruoyi.system.domain.NoteView;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.AmbiguityItem;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.DefaultValueFill;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.DeletedColumnInfo;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.LinkFailure;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.MissingParam;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.MissName;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.NewOption;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.RepointedLinkColumn;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.WillClearLinkEntry;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.RelationCandidates;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.SkippedHeader;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.SymmetricWriteImpact;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
 * <p>
 * 替换式语义（replace_ 前缀）：全链路（删行/删列/新建列/清 notelink）、同名列配置保留、
 * 删除 21 列级联删配对列、kept 21 清空配对引用、新建列空单元格、隐藏列匹配取消隐藏、
 * 他表 18 列跨表清空、仅表头 Excel（重算无条件执行）、空表头 guard、自引用配对列 guard、
 * pending 同名冲突 drift、name 从新文本列派生、替换删除漂移（checkDrift 第 4 触发集）。
 * <p>
 * U3（21 列 ID 优先重建，双轨制）：round-trip `_ID`+`_文本` 回读链接恢复与配对列对称（AE1）、
 * ID 命中名称被改写以 ID 为准、ID 未命中名称回退唯一命中、名称歧义/多值部分命中进链接失败
 * 清单（R8 并列阻断 + 阶段二漂移防御零写入）、死链回读（`_ID` 指向已删且名称空）跳过不失败、
 * 空 `_ID`+空名称无链接；18 列红线回归（`列名_文本` 机制、`_ID` 忽略、缺参记录选择器、
 * 同名取 sort 最小、补参优先语义——歧义/漂移/补参系列已转钉 18 列）。
 * <p>
 * U4（25 列从零建立，双轨制）：round-trip 行重建 + 坐标指向本表新记录 + 跳转参数完整（AE2）、
 * 行 id 保留（data-link-id 不断链）与 blockId/核心字段恢复、笔记名回退新建行（linkNoteId
 * 规范值、contextText=锚词、blockId 降级）、笔记重名歧义零写入、多锚点逐条目全命中才通过、
 * 悬空空值回读不建行不失败、仅表头清空场景清旧行、26 列等其余排除类型回归。
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

    /** 共享名称解析组件（U2）：真实实例经 @Spy 注入（纯逻辑无 DB 依赖，21 列名称回退用） */
    @Spy
    private LinkNameResolver linkNameResolver = new LinkNameResolver();

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

    /** 25 列重建的 notelink 行捕获（U4） */
    @Captor
    private ArgumentCaptor<NoteNotelink> notelinkCaptor;

    /** 模拟 NoteRecordMapper.insertNoteRecord 的 useGeneratedKeys 自增 id 回填 */
    private final AtomicLong recordIdSequence = new AtomicLong(1000L);

    /** 模拟 insertNoteDwtableItem(s) 的 useGeneratedKeys 自增 id 回填（零缓冲不变量依赖） */
    private final AtomicLong itemIdSequence = new AtomicLong(5000L);

    /** 模拟 insertNoteColumn 的 useGeneratedKeys 自增 id 回填（pending 列 items 组装依赖） */
    private final AtomicLong columnIdSequence = new AtomicLong(8000L);

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
        // 替换式导入：pending 新建列落库回填自增 id（useGeneratedKeys 语义，否则 pending items 的 columnId 为 null）
        when(noteColumnMapper.insertNoteColumn(any(NoteColumn.class))).thenAnswer(inv -> {
            NoteColumn column = inv.getArgument(0);
            column.setId(columnIdSequence.getAndIncrement());
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

    /**
     * 补充目标表现有记录桩（替换式影响面 deletedRowCount 用；在 mockRelatedRecords 之后调用，
     * argThat 桩按注册序优先生效，不影响被关联表记录路由）。
     */
    private void mockTargetTableRecords(List<NoteRecord> records)
    {
        when(noteRecordMapper.selectNoteRecordList(argThat((NoteRecordVo query) ->
                query != null && DWTABLE_ID.equals(query.getDwtableId()))))
                .thenReturn(new ArrayList<>(records));
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

    // ===== AE1：预检无缺参，完整影响面（替换式），零写库 =====

    @Test
    void precheck_noMissingParams_returnsFullImpactAndZeroWrites()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(102L, "数量", 2L, "{\"default\":\"0\"}", 0L),
                column(103L, "状态", 3L, "{\"select\":\"进行中,已完成\"}", 0L),
                column(104L, "关联", 21L, "{\"table_id\":80,\"back_field_id\":502}", 0L),
                column(105L, "隐藏列", 1L, null, 1L),
                column(106L, "公式", 20L, null, 0L)));
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Arrays.asList(record(301L, "张三", 1L), record(302L, "李四", 2L)));
        mockTargetTableRecords(Arrays.asList(record(1L, "旧行1", 1L), record(2L, "旧行2", 2L)));
        when(noteColumnMapper.selectNoteColumnById(502L)).thenReturn(relatedColumn(502L, "关联的双向链接"));

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

        // 替换式语义：未映射列不再产生默认值填充（数量列将删除）
        assertTrue(result.getDefaultValueFills().isEmpty());

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

        // 忽略 sheet（R2）；跳过表头（R5/R6：record_id 源列——未匹配表头为新建列候选不再进跳过清单）
        assertEquals(Collections.singletonList("其他表"), result.getIgnoredSheets());
        assertEquals(1, result.getSkippedHeaders().size());
        assertEquals("record_id", result.getSkippedHeaders().get(0).getHeaderName());
        assertNotNull(result.getSkippedHeaders().get(0).getReason());

        // 选择器候选（KTD7：sort 升序前 100 条，已过 R23 归属校验）
        assertEquals(1, result.getRelationCandidates().size());
        RelationCandidates candidates = result.getRelationCandidates().get(0);
        assertEquals(Long.valueOf(104L), candidates.getColumnId());
        assertEquals(2, candidates.getCandidates().size());
        assertEquals(Long.valueOf(301L), candidates.getCandidates().get(0).getRecordId());
        assertEquals("张三", candidates.getCandidates().get(0).getName());
        assertEquals(Long.valueOf(302L), candidates.getCandidates().get(1).getRecordId());

        // 替换影响面：目标表现有 2 行将全删；数量/隐藏列/公式列不被任何表头引用 → 删除；
        // 未知列无同名列 → 新建；跨表入链影响按重指向口径（U6 语义迁移）——本场景无他表入链
        // 数据（selectDWTableList 空表清单），重指向列与清空条目均为空
        ExcelImportPrecheckResult.ReplaceImpact replace = result.getReplaceImpact();
        assertNotNull(replace);
        assertEquals(2, replace.getDeletedRowCount());
        assertEquals(3, replace.getDeletedColumns().size());
        DeletedColumnInfo deleted = replace.getDeletedColumns().get(0);
        assertEquals(Long.valueOf(102L), deleted.getColumnId());
        assertEquals("数量", deleted.getColumnName());
        assertFalse(deleted.isCascadeDeletesPairedColumn());
        assertEquals(Long.valueOf(105L), replace.getDeletedColumns().get(1).getColumnId());
        assertEquals(Long.valueOf(106L), replace.getDeletedColumns().get(2).getColumnId());
        assertEquals(Collections.singletonList("未知列"), replace.getNewColumnNames());
        assertTrue(replace.getRepointedLinkColumns().isEmpty());
        assertTrue(replace.getWillClearLinkEntries().isEmpty());
        assertTrue(replace.getUnhiddenColumnNames().isEmpty());

        // 文件指纹（KTD5）
        assertEquals(bytes.length, result.getFileFingerprint().getSize());
        assertEquals(md5Hex(bytes), result.getFileFingerprint().getMd5());

        // R23：被关联表归属校验执行
        verify(agentOwnershipChecker, times(1)).checkDwtableOwnership(RELATED_TABLE_ID, USER_ID);
        // 预检零写路径（R9：不写库；select 类读调用不算写）
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
        verify(noteRecordMapper, never()).updateNoteRecord(any(NoteRecord.class));
        verify(noteColumnMapper, never()).insertNoteColumn(any(NoteColumn.class));
        verify(noteColumnMapper, never()).updateNoteColumn(any(NoteColumn.class));
        verify(noteDwtableMapper, never()).insertNoteDwtable(any(NoteDwtable.class));
        verify(noteDwtableMapper, never()).updateNoteDwtable(any(NoteDwtable.class));
    }

    // ===== AE2（替换式改写）：表头未映射的有默认值列 → 进删除清单，不再默认值填充 =====

    @Test
    void precheck_unmappedColumnWithDefault_goesToDeletedColumnsNotDefaultFill()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(102L, "数量", 2L, "{\"default\":\"0\"}", 0L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称"},
                Collections.singletonList(new String[] {"a"}));

        ExcelImportPrecheckResult result =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);

        // 不进缺参清单、不产生默认值填充（列将删除，替换式语义）
        assertTrue(result.getMissingParams().isEmpty());
        assertFalse(result.isHasBlockingIssues());
        assertTrue(result.getDefaultValueFills().isEmpty());
        // 进替换影响面删除清单
        assertEquals(1, result.getReplaceImpact().getDeletedColumns().size());
        DeletedColumnInfo deleted = result.getReplaceImpact().getDeletedColumns().get(0);
        assertEquals(Long.valueOf(102L), deleted.getColumnId());
        assertEquals("数量", deleted.getColumnName());
        assertEquals(Long.valueOf(2L), deleted.getColumnType());
        assertFalse(deleted.isCascadeDeletesPairedColumn());
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

    // ===== AE4：歧义 → 预选 sort 靠前（18 列回归红线：同名取 sort 最小不变；
    // 21 列歧义已改为链接失败强失败，见 precheck_doubleLink_ambiguousName_linkFailure） =====

    @Test
    void precheck_ambiguousName_preselectsLowerSortRecord()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(104L, "关联", 18L, "{\"select\":80}", 0L)));
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
        // 18 列无对称写入影响（21 专属）
        assertTrue(result.getSymmetricWriteImpact().isEmpty());
        assertTrue(result.isHasBlockingIssues());
    }

    // ===== AE5：未命中 → 未命中名清单（18 列回归红线：缺参记录选择器不变）；
    // 关联空单元格不进任何缺参 =====

    @Test
    void precheck_unmatchedName_missNamesWithRowNumbers()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(104L, "关联", 18L, "{\"select\":80}", 0L)));
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
                column(104L, "关联", 21L, "{\"table_id\":80,\"back_field_id\":502}", 0L)));
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
                column(104L, "关联", 21L, "{\"table_id\":80,\"back_field_id\":502}", 0L)));
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

    // ===== 关联列不在表头（替换式改写）：不产生缺参 → 进删除清单且标记配对列级联删除 =====

    @Test
    void precheck_unmappedLinkColumn_goesToDeletedColumnsWithCascadeFlag()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(104L, "关联", 21L, "{\"table_id\":80,\"back_field_id\":502}", 0L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称"},
                Collections.singletonList(new String[] {"a"}));

        ExcelImportPrecheckResult result =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);

        // 未映射列不再产生缺参（列将删除），无阻断
        assertTrue(result.getMissingParams().isEmpty());
        assertFalse(result.isHasBlockingIssues());
        // 21 列进删除清单且标记配对列级联删除
        assertEquals(1, result.getReplaceImpact().getDeletedColumns().size());
        DeletedColumnInfo deleted = result.getReplaceImpact().getDeletedColumns().get(0);
        assertEquals(Long.valueOf(104L), deleted.getColumnId());
        assertEquals("关联", deleted.getColumnName());
        assertEquals(Long.valueOf(21L), deleted.getColumnType());
        assertTrue(deleted.isCascadeDeletesPairedColumn());
        // 未映射的关联列不触发 R23 校验，也不附候选
        verify(noteDwtableMapper, never()).selectNoteDwtableById(RELATED_TABLE_ID);
        assertTrue(result.getRelationCandidates().isEmpty());
    }

    // ===== R23：被关联表不同 noteId → 阻断（含列名） =====

    @Test
    void precheck_relatedTableInDifferentNote_blockedWithColumnName()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(104L, "关联", 21L, "{\"table_id\":80,\"back_field_id\":502}", 0L)));
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
                column(104L, "关联", 21L, "{\"table_id\":80,\"back_field_id\":502}", 0L)));
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
        return paramsJson(fingerprintBytes, missingColumns, missNames, ambiguity,
                columnValues, relationSelections, null);
    }

    /**
     * 构造 params JSON（含替换基线）：replaceDeletedColumns 为预检时确认将删除的列名清单
     * （替换式语义漂移比对基线——本次将删列不在其中即整体拒绝；null → 空清单）。
     */
    private String paramsJson(byte[] fingerprintBytes, String missingColumns, String missNames,
            String ambiguity, String columnValues, String relationSelections, String replaceDeletedColumns)
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
        baseline.put("replaceDeletedColumns",
                JSONArray.parseArray(replaceDeletedColumns == null ? "[]" : replaceDeletedColumns));
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

    /** 他表入链条目（U5 重指向捕获形态；21 配对列另需 setLinkColumnId(源列 id) 参与互指判定） */
    private NoteDwtableItem inboundItem(Long id, Long dwtId, Long recordId, Long columnId,
            String linkRecordId, String linkItemId, String value)
    {
        NoteDwtableItem item = new NoteDwtableItem();
        item.setId(id);
        item.setDwtId(dwtId);
        item.setRecordId(recordId);
        item.setColumnId(columnId);
        item.setLinkRecordId(linkRecordId);
        item.setLinkItemId(linkItemId);
        item.setValue(value);
        return item;
    }

    /** 本表旧 item（U5 linkItemId 坐标链：(recordId, columnId) → item id） */
    private NoteDwtableItem legacyItem(Long id, Long recordId, Long columnId)
    {
        NoteDwtableItem item = new NoteDwtableItem();
        item.setId(id);
        item.setDwtId(DWTABLE_ID);
        item.setRecordId(recordId);
        item.setColumnId(columnId);
        return item;
    }

    /**
     * U5 入链重指向公共桩：他表清单（selectDWTableList）+ 本表旧记录快照（capture 用）
     * + 本表旧 items 坐标快照 + 全量反查捕获（selectLinkItemsByDwtIds）。
     */
    private void mockInboundRepointContext(List<NoteDwtable> otherTables, List<NoteRecord> legacyRecords,
            List<NoteDwtableItem> legacyItems, List<NoteDwtableItem> inboundItems)
    {
        when(noteDwtableMapper.selectDWTableList(NOTE_ID)).thenReturn(new ArrayList<>(otherTables));
        mockTargetTableRecords(legacyRecords);
        when(noteDwtableItemMapper.selectNoteDwtableItemList(any(NoteDwtableItem.class)))
                .thenAnswer(inv -> {
                    NoteDwtableItem query = inv.getArgument(0);
                    if (DWTABLE_ID.equals(query.getDwtId()))
                    {
                        return new ArrayList<>(legacyItems);
                    }
                    return new ArrayList<NoteDwtableItem>();
                });
        when(noteDwtableItemMapper.selectLinkItemsByDwtIds(anyList()))
                .thenReturn(new ArrayList<>(inboundItems));
    }

    // ===== 三源合并（AE3 导入部分）+ 复选框映射 + sort/name 派生（替换式：未映射"完成"列删除） =====

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

        // 基线含替换删除列"完成"（预检时已确认的删除）
        int count = runImport(bytes, paramsJson(bytes,
                "[\"备注\",\"勾选\"]", null, null, "{\"备注\":\"待补充\",\"勾选\":\"false\"}", null,
                "[\"完成\"]"));

        assertEquals(3, count);

        // sort=maxSort+1 递增（6/7/8），name 按首个 type=1 列（名称）最终值派生
        verify(noteRecordMapper, times(3)).insertNoteRecord(recordCaptor.capture());
        for (int i = 0; i < 3; i++)
        {
            assertEquals(DWTABLE_ID, recordCaptor.getAllValues().get(i).getDwtableId());
            assertEquals(Long.valueOf(6L + i), recordCaptor.getAllValues().get(i).getSort());
            assertEquals(Arrays.asList("a", "b", "c").get(i), recordCaptor.getAllValues().get(i).getName());
        }

        // 替换式：未映射的"完成"列被删除（无默认值填充 item）→ 3 行 × 4 参与列 = 12 个 item
        verify(noteColumnMapper).deleteNoteColumnById(110L);
        verify(noteColumnMapper, never()).insertNoteColumn(any(NoteColumn.class));
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        List<NoteDwtableItem> items = itemsCaptor.getValue();
        assertEquals(12, items.size());
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
        // 行2：数量空 → 默认值 "0"；备注空 → 用户补参 "待补充"；复选框 false→'1'
        assertEquals("b", itemByColumnRow1.get(101L).getValue());
        assertEquals("0", itemByColumnRow1.get(102L).getValue());
        assertEquals("待补充", itemByColumnRow1.get(107L).getValue());
        assertEquals("1", itemByColumnRow1.get(109L).getValue());
        // 行3：勾选 "xx" 类型违规 → 不用 Excel 值，用补参 "false"→'1'
        assertEquals("c", itemByColumnRow2.get(101L).getValue());
        assertEquals("3", itemByColumnRow2.get(102L).getValue());
        assertEquals("y", itemByColumnRow2.get(107L).getValue());
        assertEquals("1", itemByColumnRow2.get(109L).getValue());

        // 全量最终 flush 单批（12 < 500）；无对称写入与选项追加
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

    // ===== 反向漂移：歧义预选变化拒绝（18 列——歧义预选漂移检测机制不变） =====

    @Test
    void import_drift_ambiguityPreselectChanged_rejected()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(104L, "关联", 18L, "{\"select\":80}", 0L)));
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

    // ===== 补参校验（R26，18 列）：伪造 recordId（不属于被关联表/不存在）拒绝 =====

    @Test
    void import_supplement_forgedRecordId_rejected()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(104L, "关联", 18L, "{\"select\":80}", 0L)));
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

    // ===== 补参优先语义（KTD5，18 列）：显式留空后重新匹配命中也不建立关联 =====

    @Test
    void import_supplementPriority_explicitNullBeatsStage2Match()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(104L, "关联", 18L, "{\"select\":80}", 0L)));
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

    // ===== 补参优先语义（KTD5，18 列）：歧义改选后以补参为准 =====

    @Test
    void import_supplementPriority_reselectionWinsOverPreselect()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(104L, "关联", 18L, "{\"select\":80}", 0L)));
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        // 同名两记录：sort=2 的 202 为预选；补参改选 201
        mockRelatedRecords(Arrays.asList(record(201L, "张三", 5L), record(202L, "张三", 2L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本"},
                Collections.singletonList(new String[] {"a", "张三"}));

        runImport(bytes, paramsJson(bytes, null, null,
                "[{\"columnName\":\"关联\",\"name\":\"张三\",\"preselectedRecordId\":202}]", null,
                "[{\"columnName\":\"关联\",\"name\":\"张三\",\"recordId\":201}]"));

        // 改选生效：源 item linkRecordId=201（非预选 202）；18 列无对称写入（零 FOR UPDATE/零 update）
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        NoteDwtableItem linkItem = null;
        for (NoteDwtableItem item : itemsCaptor.getValue())
        {
            if (item.getColumnId().equals(104L))
            {
                linkItem = item;
            }
        }
        assertNotNull(linkItem);
        assertEquals("201", linkItem.getLinkRecordId());
        assertEquals("张三", linkItem.getValue());
        verify(noteDwtableItemMapper, never())
                .selectNoteDwtableItemByRecordAndColumnForUpdate(any(NoteDwtableItem.class));
        verify(noteDwtableItemMapper, never()).updateNoteDwtableItem(any(NoteDwtableItem.class));
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

        // 替换式语义：26/24 列须被同名表头引用（skip-referenced）才保留——否则将被删除且不重算
        byte[] bytes = singleSheetBytes(new String[] {"名称", "关联_文本", "引用", "运算"},
                Collections.singletonList(new String[] {"a", "B记录", "x", "y"}));
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

    // ===== #6（替换式改写）：隐藏最左 type=1 列出现在 Excel → 取消隐藏且可作 name 派生源 =====

    @Test
    void import_hiddenFirstNameColumn_nameDerivedFromHiddenColumnWhenMatched()
    {
        NoteColumn hidden = column(150L, "隐藏名", 1L, null, 1L);
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                hidden,
                column(101L, "名称", 1L, null, 0L)));
        when(noteColumnMapper.selectNoteColumnById(150L)).thenReturn(hidden);

        byte[] bytes = singleSheetBytes(new String[] {"隐藏名", "名称"},
                Collections.singletonList(new String[] {"h", "n"}));

        runImport(bytes, paramsJson(bytes, null, null, null, null, null));

        // 隐藏列按列表序为首个保留 type=1 列 → name 从其本行值派生（含将被取消隐藏的隐藏列）
        verify(noteRecordMapper, times(1)).insertNoteRecord(recordCaptor.capture());
        assertEquals("h", recordCaptor.getValue().getName());

        // 取消隐藏：isShow 1→0 且数据正常导入
        verify(noteColumnMapper).updateNoteColumn(columnCaptor.capture());
        assertEquals(Long.valueOf(150L), columnCaptor.getValue().getId());
        assertEquals(Long.valueOf(0L), columnCaptor.getValue().getIsShow());
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        assertEquals(2, itemsCaptor.getValue().size());
        assertEquals("h", itemsCaptor.getValue().get(0).getValue());
        assertEquals("n", itemsCaptor.getValue().get(1).getValue());
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

    // ===== #16：checkDrift 新增同名记录分支（18 列——基线歧义为空、阶段二两条同名 → 拒绝） =====

    @Test
    void import_drift_newSameNameRecord_rejected()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(104L, "关联", 18L, "{\"select\":80}", 0L)));
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

    // ===== #16：relationSelections 列未映射（替换式语义下将删除的关联列）→ "未参与本次导入"拒绝 =====

    @Test
    void import_supplement_relationColumnNotImported_rejected()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(104L, "关联", 21L, "{\"table_id\":80,\"back_field_id\":502}", 0L)));
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Collections.singletonList(record(100L, "张三", 1L)));

        // 关联列不在 Excel 表头中（替换式语义下将删除）→ 不在 headerIndexByColumn；
        // 基线含已确认的删除列"关联"（替换删除漂移通过后走到补参校验）
        byte[] bytes = singleSheetBytes(new String[] {"名称"},
                Collections.singletonList(new String[] {"a"}));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, paramsJson(bytes, "[\"关联\"]", null, null, null,
                        "[{\"columnName\":\"关联\",\"name\":\"张三\",\"recordId\":100}]", "[\"关联\"]")));
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

    // ==================================================================
    // 替换式导入（replace_）：全链路 / 配置保留 / 级联删除 / 跨表清空 / 取消隐藏 /
    // 空表头 guard / 自引用 guard / pending 冲突 / name 派生 / 替换删除漂移
    // ==================================================================

    // ===== 全链路：2 列 3 行存量，表头 [A, C] → A 保留、B 删除、C 新建 =====

    @Test
    void replace_fullPath()
    {
        NoteColumn columnA = column(101L, "A", 1L, null, 0L);
        columnA.setSort(3L);
        NoteColumn columnB = column(102L, "B", 1L, null, 0L);
        columnB.setSort(5L);
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class)))
                .thenReturn(Arrays.asList(columnA, columnB));
        mockTargetTableRecords(Arrays.asList(
                record(1L, "旧1", 1L), record(2L, "旧2", 2L), record(3L, "旧3", 3L)));

        byte[] bytes = singleSheetBytes(new String[] {"A", "C"}, Arrays.asList(
                new String[] {"a1", "c1"},
                new String[] {"a2", "c2"},
                new String[] {"a3", "c3"}));

        // 预检：删 3 行、删 B 列、新建 C 列
        ExcelImportPrecheckResult precheck =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);
        assertEquals(3, precheck.getReplaceImpact().getDeletedRowCount());
        assertEquals(1, precheck.getReplaceImpact().getDeletedColumns().size());
        assertEquals("B", precheck.getReplaceImpact().getDeletedColumns().get(0).getColumnName());
        assertEquals(Collections.singletonList("C"), precheck.getReplaceImpact().getNewColumnNames());

        int count = runImport(bytes, paramsJson(bytes, null, null, null, null, null, "[\"B\"]"));
        assertEquals(3, count);

        // 旧数据全删（替换删除段）
        verify(noteDwtableItemMapper).deleteNoteDwtableItemByDwtId(DWTABLE_ID);
        verify(noteRecordMapper).deleteNoteRecordByDwtableId(DWTABLE_ID);
        // B 列删除 + 孤儿 notelink 清理
        verify(noteNotelinkMapper).deleteNoteNotelinkByColumnId(102L);
        verify(noteColumnMapper).deleteNoteColumnById(102L);
        // C 列新建：纯文本列（type=1 / isShow=0 / property null），sort 从现存最大值 5 递增为 6
        verify(noteColumnMapper).insertNoteColumn(columnCaptor.capture());
        NoteColumn created = columnCaptor.getValue();
        assertEquals("C", created.getName());
        assertEquals(Long.valueOf(1L), created.getType());
        assertEquals(Long.valueOf(0L), created.getIsShow());
        assertNull(created.getProperty());
        assertEquals(DWTABLE_ID, created.getDwtableId());
        assertEquals(Long.valueOf(6L), created.getSort());
        // 新行 items 只含 A 与新建 C（B 已删，无任何 B 的 items）
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        List<NoteDwtableItem> items = itemsCaptor.getValue();
        assertEquals(6, items.size());
        Set<Long> itemColumnIds = new HashSet<>();
        for (NoteDwtableItem item : items)
        {
            itemColumnIds.add(item.getColumnId());
        }
        assertEquals(new HashSet<>(Arrays.asList(101L, created.getId())), itemColumnIds);
        // name 派生自保留的 A 列
        verify(noteRecordMapper, times(3)).insertNoteRecord(recordCaptor.capture());
        for (int i = 0; i < 3; i++)
        {
            assertEquals("a" + (i + 1), recordCaptor.getAllValues().get(i).getName());
        }
    }

    // ===== 导入记录携带归属视图：网格 dataList 按视图 viewId 过滤记录（无 viewId 不显示） =====

    @Test
    void replace_recordsCarryDefaultViewId()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Collections.singletonList(
                column(101L, "名称", 1L, null, 0L)));
        NoteView view = new NoteView();
        view.setId(901L);
        view.setDwtableId(DWTABLE_ID);
        view.setName("表格视图");
        when(noteViewMapper.selectNoteViewListByDwtableId(DWTABLE_ID))
                .thenReturn(Collections.singletonList(view));

        byte[] bytes = singleSheetBytes(new String[] {"名称"}, Arrays.asList(
                new String[] {"r1"},
                new String[] {"r2"}));

        int count = runImport(bytes, paramsJson(bytes, null, null, null, null, null));
        assertEquals(2, count);

        // 每条插入记录携带目标表首视图 id（UI 建行同语义；dataList 按视图过滤，无 viewId 的记录不显示）
        verify(noteRecordMapper, times(2)).insertNoteRecord(recordCaptor.capture());
        for (NoteRecord inserted : recordCaptor.getAllValues())
        {
            assertEquals(Long.valueOf(901L), inserted.getViewId());
        }
    }

    // ===== 同名单选列保留配置：property 原样，仅数据替换 =====

    @Test
    void replace_sameNameColumn_configPreserved()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Collections.singletonList(
                column(103L, "状态", 3L, "{\"select\":\"进行中,已完成\"}", 0L)));
        mockTargetTableRecords(Arrays.asList(record(1L, "旧1", 1L), record(2L, "旧2", 2L)));

        byte[] bytes = singleSheetBytes(new String[] {"状态"}, Arrays.asList(
                new String[] {"进行中"},
                new String[] {"已完成"}));

        ExcelImportPrecheckResult precheck =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);
        assertTrue(precheck.getReplaceImpact().getDeletedColumns().isEmpty());

        int count = runImport(bytes, paramsJson(bytes, null, null, null, null, null));
        assertEquals(2, count);

        // 配置保留：该列的 updateNoteColumn 不被调用（无取消隐藏、无选项追加），也不删除/新建列
        verify(noteColumnMapper, never()).updateNoteColumn(any(NoteColumn.class));
        verify(noteColumnMapper, never()).deleteNoteColumnById(any(Long.class));
        verify(noteColumnMapper, never()).insertNoteColumn(any(NoteColumn.class));
        // 数据替换：旧行全删 + 新行 items 值为单元格文本
        verify(noteRecordMapper).deleteNoteRecordByDwtableId(DWTABLE_ID);
        verify(noteDwtableItemMapper).insertNoteDwtableItems(itemsCaptor.capture());
        List<NoteDwtableItem> items = itemsCaptor.getValue();
        assertEquals(2, items.size());
        assertEquals("进行中", items.get(0).getValue());
        assertEquals("已完成", items.get(1).getValue());
    }

    // ===== 本表 21 列被删 → 被关联表配对列级联删除（items + notelink + 列） =====

    @Test
    void replace_deletedDoubleLink_cascadePairedColumn()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(104L, "关联", 21L, "{\"table_id\":80,\"back_field_id\":502}", 0L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称"},
                Collections.singletonList(new String[] {"a"}));

        // 预检：21 列删除且标记配对列级联删除
        ExcelImportPrecheckResult precheck =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);
        assertEquals(1, precheck.getReplaceImpact().getDeletedColumns().size());
        assertTrue(precheck.getReplaceImpact().getDeletedColumns().get(0).isCascadeDeletesPairedColumn());

        runImport(bytes, paramsJson(bytes, null, null, null, null, null, "[\"关联\"]"));

        // 被关联表配对列 502 级联删除（items + notelink + 列）
        verify(noteDwtableItemMapper).deleteNoteDwtableItemByColumnId(502L);
        verify(noteNotelinkMapper).deleteNoteNotelinkByColumnId(502L);
        verify(noteColumnMapper).deleteNoteColumnById(502L);
        // 源 21 列自身也删除
        verify(noteNotelinkMapper).deleteNoteNotelinkByColumnId(104L);
        verify(noteColumnMapper).deleteNoteColumnById(104L);
    }

    // ===== kept 21 列：入链重指向取代整列清空——他表配对条目三字段精准重写（Covers AE3 前半） =====

    @Test
    void replace_keptDoubleLink_inboundRepointed_insteadOfColumnClear()
    {
        mockDoubleLinkColumns();
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Collections.singletonList(record(100L, "B记录", 1L)));
        when(noteColumnMapper.selectNoteColumnById(502L))
                .thenReturn(relatedColumn(502L, "关联的双向链接"));
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumnForUpdate(any(NoteDwtableItem.class)))
                .thenReturn(pairedItem(900L, 502L, null, null, null));
        // 本表旧记录 1（旧名"a"）+ 旧 item (1, 104) + 他表（被关联表）配对条目指向旧记录/旧 item
        NoteDwtableItem inbound = inboundItem(900L, RELATED_TABLE_ID, 100L, 502L, "1", "4000", "a");
        inbound.setLinkColumnId(104L);
        mockInboundRepointContext(Collections.singletonList(relatedTable("被关联表", NOTE_ID)),
                Collections.singletonList(record(1L, "a", 1L)),
                Collections.singletonList(legacyItem(4000L, 1L, 104L)),
                Collections.singletonList(inbound));

        // 导出产物不改一字往返：record_id 锚点 + 名称 + 双列
        byte[] bytes = singleSheetBytes(
                new String[] {"record_id", "名称", "关联_ID", "关联_文本"},
                Collections.singletonList(new String[] {"1", "a", "100", "B记录"}));

        // 预检（U6 新口径）：kept 21 列的配对列按"将重指向"呈现——1 列/1 单元格/1 条目，
        // 无单元格级清空条目、无链接失败
        ExcelImportPrecheckResult precheck =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);
        List<RepointedLinkColumn> repointed = precheck.getReplaceImpact().getRepointedLinkColumns();
        assertEquals(1, repointed.size());
        assertEquals(Long.valueOf(502L), repointed.get(0).getColumnId());
        assertEquals("关联的双向链接", repointed.get(0).getColumnName());
        assertEquals("被关联表", repointed.get(0).getTableName());
        assertEquals(1, repointed.get(0).getAffectedCellCount());
        assertEquals(1, repointed.get(0).getRepointedEntryCount());
        assertTrue(precheck.getReplaceImpact().getWillClearLinkEntries().isEmpty());
        assertTrue(precheck.getLinkFailures().isEmpty());

        int count = runImport(bytes, paramsJson(bytes, null, null, null, null, null));
        assertEquals(1, count);

        // R13：kept 配对列不再整列清空（旧"陪葬"用例改判为保留 + 精准重指向）
        verify(noteDwtableItemMapper, never()).clearLinkFieldsByColumnId(any(Long.class));
        // Covers AE3：三字段精准重写——linkRecordId=新记录 id（record_id 精确映射）、
        // linkItemId=新 item id（坐标链：旧 (1,104) → 新 (新记录,104)）、value=新记录名
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
        verify(noteDwtableItemMapper).updateLinkFieldsById(eq(900L),
                eq(String.valueOf(sourceInserted.getRecordId())),
                eq(String.valueOf(sourceInserted.getId())), eq("a"));
        // 时序：新数据落库（回填新 id）→ 重指向三字段重写（P1 裁决：21 对称写回之前）；
        // 对称写回照常执行（配对 item update + 源 item 回写）
        InOrder inOrder = inOrder(noteDwtableItemMapper);
        inOrder.verify(noteDwtableItemMapper).insertNoteDwtableItems(anyList());
        inOrder.verify(noteDwtableItemMapper).updateLinkFieldsById(anyLong(), anyString(), anyString(), anyString());
        verify(noteDwtableItemMapper, times(2)).updateNoteDwtableItem(any(NoteDwtableItem.class));
    }

    // ===== 新建列空单元格：不产生缺参，item 值为空串 =====

    @Test
    void replace_newColumn_emptyCells_noMissingParams()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Collections.singletonList(
                column(101L, "名称", 1L, null, 0L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称", "新列"}, Arrays.asList(
                new String[] {"a", ""},
                new String[] {"b", ""}));

        ExcelImportPrecheckResult precheck =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);
        assertTrue(precheck.getMissingParams().isEmpty());
        assertFalse(precheck.isHasBlockingIssues());
        assertEquals(Collections.singletonList("新列"), precheck.getReplaceImpact().getNewColumnNames());

        int count = runImport(bytes, paramsJson(bytes, null, null, null, null, null));
        assertEquals(2, count);

        verify(noteColumnMapper).insertNoteColumn(columnCaptor.capture());
        Long newColumnId = columnCaptor.getValue().getId();
        assertNotNull(newColumnId);
        // 2 行 × (名称 + 新列) = 4 items，新列 item 值为空串
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        List<NoteDwtableItem> items = itemsCaptor.getValue();
        assertEquals(4, items.size());
        int newColumnItemCount = 0;
        for (NoteDwtableItem item : items)
        {
            if (newColumnId.equals(item.getColumnId()))
            {
                newColumnItemCount++;
                assertEquals("", item.getValue());
            }
        }
        assertEquals(2, newColumnItemCount);
    }

    // ===== isShow=1 列被同名表头映射 → 取消隐藏（setIsShow(0)）并导入数据 =====

    @Test
    void replace_hiddenColumnMatched_unhiddenAndImported()
    {
        NoteColumn hidden = column(150L, "隐藏列", 1L, null, 1L);
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class)))
                .thenReturn(Collections.singletonList(hidden));
        when(noteColumnMapper.selectNoteColumnById(150L)).thenReturn(hidden);

        byte[] bytes = singleSheetBytes(new String[] {"隐藏列"},
                Collections.singletonList(new String[] {"v1"}));

        ExcelImportPrecheckResult precheck =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);
        assertEquals(Collections.singletonList("隐藏列"), precheck.getReplaceImpact().getUnhiddenColumnNames());

        runImport(bytes, paramsJson(bytes, null, null, null, null, null));

        verify(noteColumnMapper).updateNoteColumn(columnCaptor.capture());
        assertEquals(Long.valueOf(150L), columnCaptor.getValue().getId());
        assertEquals(Long.valueOf(0L), columnCaptor.getValue().getIsShow());
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        assertEquals(1, itemsCaptor.getValue().size());
        assertEquals("v1", itemsCaptor.getValue().get(0).getValue());
    }

    // ===== 他表 18 列指向本表：无条件重指向（取代清空），value 刷新为新记录名 =====

    @Test
    void replace_otherTableSingleLink_inboundRepointed_valueRefreshed()
    {
        Long otherTableId = 90L;
        NoteColumn otherLink = column(201L, "他表关联", 18L, "{\"table_id\":60}", 0L);
        otherLink.setDwtableId(otherTableId);
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenAnswer(inv -> {
            NoteColumn query = inv.getArgument(0);
            if (DWTABLE_ID.equals(query.getDwtableId()))
            {
                return Collections.singletonList(column(101L, "名称", 1L, null, 0L));
            }
            if (otherTableId.equals(query.getDwtableId()))
            {
                return Collections.singletonList(otherLink);
            }
            return new ArrayList<NoteColumn>();
        });
        NoteDwtable otherTable = new NoteDwtable();
        otherTable.setId(otherTableId);
        otherTable.setNoteId(NOTE_ID);
        otherTable.setName("他表");
        when(noteDwtableMapper.selectNoteDwtableById(otherTableId)).thenReturn(otherTable);
        // 本表旧记录 2（旧名"旧名"）+ 他表 18 列条目指向它（18 无互指约束，无条件重指向）
        mockInboundRepointContext(Collections.singletonList(otherTable),
                Collections.singletonList(record(2L, "旧名", 1L)),
                Collections.emptyList(),
                Collections.singletonList(inboundItem(700L, otherTableId, 55L, 201L, "2", null, "旧名")));

        // record_id 锚点场景：旧记录 2 重建且名称被改写为"新名"（18 入链 value 同步刷新）
        byte[] bytes = singleSheetBytes(new String[] {"record_id", "名称"},
                Collections.singletonList(new String[] {"2", "新名"}));

        // 预检（U6 新口径）：他表指向本表的 18 列按"将重指向"呈现——1 列/1 单元格/1 条目
        ExcelImportPrecheckResult precheck =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);
        List<RepointedLinkColumn> repointed = precheck.getReplaceImpact().getRepointedLinkColumns();
        assertEquals(1, repointed.size());
        assertEquals(Long.valueOf(201L), repointed.get(0).getColumnId());
        assertEquals("他表关联", repointed.get(0).getColumnName());
        assertEquals("他表", repointed.get(0).getTableName());
        assertEquals(1, repointed.get(0).getAffectedCellCount());
        assertEquals(1, repointed.get(0).getRepointedEntryCount());
        assertTrue(precheck.getReplaceImpact().getWillClearLinkEntries().isEmpty());

        runImport(bytes, paramsJson(bytes, null, null, null, null, null));

        // R13：他表 18 列不再整列清空；重指向三字段——linkRecordId=新记录 id、value=新记录名
        verify(noteDwtableItemMapper, never()).clearLinkFieldsByColumnId(any(Long.class));
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        Long newRecordId = itemsCaptor.getValue().get(0).getRecordId();
        verify(noteDwtableItemMapper).updateLinkFieldsById(eq(700L),
                eq(String.valueOf(newRecordId)), eq(""), eq("新名"));
    }

    // ===== 只有表头无数据行：删旧行列、0 记录、executeRecompute 仍执行 =====

    @Test
    void replace_headerOnlyExcel()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(130L, "引用", 26L,
                        "{\"double_link_column_id\":\"104\",\"source_column_id\":\"101\"}", 0L)));

        // "引用"同名表头使 26 列被引用保留（skip-referenced），无数据行
        byte[] bytes = singleSheetBytes(new String[] {"名称", "引用"}, Collections.emptyList());

        ExcelImportPrecheckResult precheck =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);
        assertTrue(precheck.getMissingParams().isEmpty());
        assertTrue(precheck.getReplaceImpact().getDeletedColumns().isEmpty());

        int count = runImport(bytes, paramsJson(bytes, null, null, null, null, null));
        assertEquals(0, count);

        // 旧行列清理照常执行（rows 为空也执行删除段）
        verify(noteDwtableItemMapper).deleteNoteDwtableItemByDwtId(DWTABLE_ID);
        verify(noteRecordMapper).deleteNoteRecordByDwtableId(DWTABLE_ID);
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
        // executeRecompute 无条件执行：保留的 26 列仍被重算（不依赖新行）
        verify(noteRecordService).recomputeLookupColumnValues(any(NoteColumn.class));
        verify(noteRecordService).recomputeSetOperationsForLookup(any(NoteColumn.class));
    }

    // ===== 全部空表头 → 防误删 guard 拒绝（预检与导入两阶段） =====

    @Test
    void replace_blankSheet_rejected()
    {
        // 表头行存在但无任何单元格（无任何非空表头）
        byte[] bytes = workbookBytes(workbook -> {
            XSSFSheet sheet = workbook.createSheet("目标表");
            sheet.createRow(0);
            Row dataRow = sheet.createRow(1);
            dataRow.createCell(0).setCellValue("a");
        });

        ServiceException precheckEx = assertThrows(ServiceException.class,
                () -> precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID));
        assertTrue(precheckEx.getMessage().contains("没有任何非空表头"));

        ServiceException importEx = assertThrows(ServiceException.class,
                () -> runImport(bytes, paramsJson(bytes, null, null, null, null, null)));
        assertTrue(importEx.getMessage().contains("没有任何非空表头"));

        // 零写入（含替换删除段不执行）
        verify(noteRecordMapper, never()).deleteNoteRecordByDwtableId(any(Long.class));
        verify(noteDwtableItemMapper, never()).deleteNoteDwtableItemByDwtId(any(Long.class));
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
    }

    // ===== 自引用 21 列被删但配对列被表头保留 → 不级联删配对列 =====

    @Test
    void replace_selfReferencingDoubleLink_keptPairNotCascaded()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(104L, "关联", 21L, "{\"table_id\":60,\"back_field_id\":502}", 0L),
                column(502L, "配对列", 21L, "{\"table_id\":60,\"back_field_id\":104}", 0L)));

        // "关联"不在 Excel（将删除）；"配对列"被同名表头保留（空单元格不建关联）
        byte[] bytes = singleSheetBytes(new String[] {"名称", "配对列"},
                Collections.singletonList(new String[] {"a", ""}));

        ExcelImportPrecheckResult precheck =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);
        assertEquals(1, precheck.getReplaceImpact().getDeletedColumns().size());
        assertEquals("关联", precheck.getReplaceImpact().getDeletedColumns().get(0).getColumnName());

        runImport(bytes, paramsJson(bytes, null, null, null, null, null, "[\"关联\"]"));

        // 源 21 列删除，配对列被 Excel 保留 → guard 跳过级联（pairId ∈ 存活列集）
        verify(noteColumnMapper).deleteNoteColumnById(104L);
        verify(noteColumnMapper, never()).deleteNoteColumnById(502L);
        verify(noteDwtableItemMapper, never()).deleteNoteDwtableItemByColumnId(502L);
        // 配对列数据正常导入（空单元格 → 空串 item）
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        List<NoteDwtableItem> items = itemsCaptor.getValue();
        assertEquals(2, items.size());
        for (NoteDwtableItem item : items)
        {
            if (Long.valueOf(502L).equals(item.getColumnId()))
            {
                assertEquals("", item.getValue());
                assertNull(item.getLinkRecordId());
            }
        }
    }

    // ===== 事务段 pending 新列名与现存列冲突（TOCTOU 窗口内新建）→ drift 拒绝 =====

    @Test
    void replace_pendingNameConflict_rejected()
    {
        // 前置段首次列查询无"新列"；事务段重查含"新列"（模拟预检后用户新建同名列）
        AtomicInteger targetColumnQueries = new AtomicInteger();
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenAnswer(inv -> {
            NoteColumn query = inv.getArgument(0);
            if (DWTABLE_ID.equals(query.getDwtableId()) && targetColumnQueries.incrementAndGet() > 1)
            {
                return Arrays.asList(
                        column(101L, "名称", 1L, null, 0L),
                        column(102L, "新列", 1L, null, 0L));
            }
            return Collections.singletonList(column(101L, "名称", 1L, null, 0L));
        });

        byte[] bytes = singleSheetBytes(new String[] {"名称", "新列"},
                Collections.singletonList(new String[] {"a", "x"}));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, paramsJson(bytes, null, null, null, null, null)));
        assertTrue(ex.getMessage().contains("数据已变化，请重新预检"));
        assertTrue(ex.getMessage().contains("新列"));
        assertTrue(ex.getMessage().contains("无法新建同名列"));

        // fail-fast 于任何写入之前（含替换删除段）
        verify(noteRecordMapper, never()).deleteNoteRecordByDwtableId(any(Long.class));
        verify(noteDwtableItemMapper, never()).deleteNoteDwtableItemByDwtId(any(Long.class));
        verify(noteColumnMapper, never()).insertNoteColumn(any(NoteColumn.class));
    }

    // ===== 无 kept type=1 列 → name 从首个新建文本列的预解析值派生 =====

    @Test
    void replace_nameDerivedFromNewTextColumn()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Collections.singletonList(
                column(103L, "状态", 3L, "{\"select\":\"进行中,已完成\"}", 0L)));

        byte[] bytes = singleSheetBytes(new String[] {"状态", "名字"}, Arrays.asList(
                new String[] {"进行中", "张三"},
                new String[] {"已完成", "李四"}));

        runImport(bytes, paramsJson(bytes, null, null, null, null, null));

        verify(noteRecordMapper, times(2)).insertNoteRecord(recordCaptor.capture());
        assertEquals("张三", recordCaptor.getAllValues().get(0).getName());
        assertEquals("李四", recordCaptor.getAllValues().get(1).getName());
    }

    // ===== 替换删除漂移：baseline.replaceDeletedColumns 不含某将删列 → 拒绝 =====

    @Test
    void replace_drift_extraDeletion_rejected()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(102L, "旧列", 1L, null, 0L)));

        byte[] bytes = singleSheetBytes(new String[] {"名称"},
                Collections.singletonList(new String[] {"a"}));

        // 基线 replaceDeletedColumns 为空（"旧列"为预检后出现）→ 本次将删它 → 漂移拒绝
        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, paramsJson(bytes, null, null, null, null, null)));
        assertTrue(ex.getMessage().contains("数据已变化，请重新预检"));
        assertTrue(ex.getMessage().contains("旧列"));
        assertTrue(ex.getMessage().contains("本次导入将删除它"));

        // 前置段拒绝：零写入（含替换删除段不执行）
        verify(noteRecordMapper, never()).deleteNoteRecordByDwtableId(any(Long.class));
        verify(noteColumnMapper, never()).deleteNoteColumnById(any(Long.class));
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
    }

    // ==================================================================
    // U3：21 列 ID 优先重建（双轨制：`_ID` 锚点 + LinkNameResolver 名称回退）
    // ==================================================================

    /** 21 列 round-trip 场景公共桩：被关联表 B记录(100) + 已存在配对 item (100, 502) */
    private void mockDoubleLinkRoundTrip()
    {
        mockDoubleLinkColumns();
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Collections.singletonList(record(100L, "B记录", 1L)));
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumnForUpdate(any(NoteDwtableItem.class)))
                .thenReturn(pairedItem(900L, 502L, null, null, null));
    }

    // ===== Covers AE1. 导出文件不改再导入：`_ID` + `_文本` 双列回读，链接恢复 + 配对列对称 =====

    @Test
    void precheckAndImport_doubleLink_idHit_roundTripRestoresLinkAndSymmetry()
    {
        mockDoubleLinkRoundTrip();

        // 导出产物布局（renderer：record_id 首列 + 双列 `_ID` 前 `_文本` 后）
        byte[] bytes = singleSheetBytes(
                new String[] {"record_id", "名称", "关联_ID", "关联_文本"},
                Collections.singletonList(new String[] {"1", "a", "100", "B记录"}));

        // 预检：ID 全命中 → 通过（无缺参/歧义/链接失败），对称写入影响 1 条
        ExcelImportPrecheckResult precheck =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);
        assertTrue(precheck.getLinkFailures().isEmpty());
        assertTrue(precheck.getMissingParams().isEmpty());
        assertTrue(precheck.getAmbiguityItems().isEmpty());
        assertFalse(precheck.isHasBlockingIssues());
        assertEquals(1, precheck.getSymmetricWriteImpact().size());
        assertEquals(1, precheck.getSymmetricWriteImpact().get(0).getAffectedRecordCount());
        // `_ID` 锚点列进跳过清单（不作值导入）
        assertEquals(2, precheck.getSkippedHeaders().size());

        int count = runImport(bytes, paramsJson(bytes, null, null, null, null, null));
        assertEquals(1, count);

        // 源 item：linkRecordId=100（ID 精确重建）、value=记录当前名、linkColumnId=配对列
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
        Long sourceItemId = sourceInserted.getId();
        Long newRecordId = sourceInserted.getRecordId();
        assertNotNull(sourceItemId);

        // 配对 item 对称写入：追加新记录 id / 源 item id / 新记录名（linkColumnId=源列 id）
        verify(noteDwtableItemMapper, times(2)).updateNoteDwtableItem(itemCaptor.capture());
        NoteDwtableItem pairedUpdate = itemCaptor.getAllValues().get(0);
        NoteDwtableItem sourceUpdate = itemCaptor.getAllValues().get(1);
        assertEquals(Long.valueOf(100L), pairedUpdate.getRecordId());
        assertEquals(Long.valueOf(502L), pairedUpdate.getColumnId());
        assertEquals(String.valueOf(newRecordId), pairedUpdate.getLinkRecordId());
        assertEquals(String.valueOf(sourceItemId), pairedUpdate.getLinkItemId());
        assertEquals("a", pairedUpdate.getValue());
        assertEquals(Long.valueOf(104L), pairedUpdate.getLinkColumnId());
        // 源 item 回写 linkItemId=配对 item id（配对列对称闭合）
        assertEquals("900", sourceUpdate.getLinkItemId());
        assertEquals("100", sourceUpdate.getLinkRecordId());
    }

    // ===== `_ID` 命中但名称被外部改写：以 ID 为准重建，名称不产生失败 =====

    @Test
    void import_doubleLink_idHitWithRewrittenName_idWins()
    {
        mockDoubleLinkRoundTrip();

        // 名称列被改写为不存在的记录名——ID 命中即整格按 ID 重建，名称不参与解析
        byte[] bytes = singleSheetBytes(
                new String[] {"record_id", "名称", "关联_ID", "关联_文本"},
                Collections.singletonList(new String[] {"1", "a", "100", "被外部改写的名称"}));

        ExcelImportPrecheckResult precheck =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);
        assertTrue(precheck.getLinkFailures().isEmpty());
        assertFalse(precheck.isHasBlockingIssues());

        runImport(bytes, paramsJson(bytes, null, null, null, null, null));

        // 重建值取记录当前名（非单元格文本）；链接指向 id=100
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
    }

    // ===== `_ID` 未命中（目标已删）且名称回退唯一命中：按名称重建 =====

    @Test
    void import_doubleLink_idMiss_nameFallbackUniqueHit()
    {
        mockDoubleLinkColumns();
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Collections.singletonList(record(301L, "张三", 1L)));
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumnForUpdate(any(NoteDwtableItem.class)))
                .thenReturn(pairedItem(900L, 502L, null, null, null));

        byte[] bytes = singleSheetBytes(
                new String[] {"record_id", "名称", "关联_ID", "关联_文本"},
                Collections.singletonList(new String[] {"1", "a", "999", "张三"}));

        ExcelImportPrecheckResult precheck =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);
        assertTrue(precheck.getLinkFailures().isEmpty());

        runImport(bytes, paramsJson(bytes, null, null, null, null, null));

        // 名称回退命中唯一记录 301
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
        assertEquals("301", sourceInserted.getLinkRecordId());
        assertEquals("张三", sourceInserted.getValue());
    }

    // ===== 名称回退歧义：预检失败清单含定位与原因（不再预选），整表零写入 =====

    @Test
    void precheck_doubleLink_ambiguousName_linkFailure()
    {
        mockDoubleLinkColumns();
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Arrays.asList(record(201L, "张三", 5L), record(202L, "张三", 2L)));

        // 空 `_ID`（ID 缺失）→ 纯名称解析 → 歧义
        byte[] bytes = singleSheetBytes(
                new String[] {"record_id", "名称", "关联_ID", "关联_文本"},
                Collections.singletonList(new String[] {"1", "a", "", "张三"}));

        ExcelImportPrecheckResult result =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);

        // 链接失败清单：定位（列+行号）+ 原因 + 容错命中说明；与缺参清单并列阻断
        assertEquals(1, result.getLinkFailures().size());
        LinkFailure failure = result.getLinkFailures().get(0);
        assertEquals(Long.valueOf(104L), failure.getColumnId());
        assertEquals("关联", failure.getColumnName());
        assertEquals(2, failure.getRowNumber());
        assertEquals("张三", failure.getEntryText());
        assertTrue(failure.getReason().contains("歧义"));
        assertNotNull(failure.getExplanation());
        // 21 列不再产生歧义预选项与未命中缺参（选择器为 18 专属）
        assertTrue(result.getAmbiguityItems().isEmpty());
        assertTrue(result.getMissingParams().isEmpty());
        assertTrue(result.isHasBlockingIssues());

        // 导入（阶段二防御）：漂移式拒绝，零写入
        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, paramsJson(bytes, null, null, null, null, null)));
        assertTrue(ex.getMessage().contains("数据已变化，请重新预检"));
        assertTrue(ex.getMessage().contains("解析失败"));
        assertTrue(ex.getMessage().contains("张三"));
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
        verify(noteDwtableItemMapper, never()).insertNoteDwtableItems(anyList());
        verify(noteDwtableItemMapper, never()).updateNoteDwtableItem(any(NoteDwtableItem.class));
    }

    // ===== 多值部分命中（R9）：任一条目失败 → 整表零写入 =====

    @Test
    void precheck_doubleLink_multiValuePartialHit_linkFailureZeroWrites()
    {
        mockDoubleLinkColumns();
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Collections.singletonList(record(301L, "张三", 1L)));

        // `_ID`=301,999：301 命中、999 死链；同位名称"张三,错名"：错名未命中 → 失败
        byte[] bytes = singleSheetBytes(
                new String[] {"record_id", "名称", "关联_ID", "关联_文本"},
                Collections.singletonList(new String[] {"1", "a", "301,999", "张三,错名"}));

        ExcelImportPrecheckResult result =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);

        assertEquals(1, result.getLinkFailures().size());
        LinkFailure failure = result.getLinkFailures().get(0);
        assertEquals("错名", failure.getEntryText());
        assertEquals(2, failure.getRowNumber());
        assertTrue(failure.getReason().contains("ID 未命中"));
        assertTrue(result.isHasBlockingIssues());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, paramsJson(bytes, null, null, null, null, null)));
        assertTrue(ex.getMessage().contains("解析失败"));
        assertTrue(ex.getMessage().contains("错名"));
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
        verify(noteDwtableItemMapper, never()).insertNoteDwtableItems(anyList());
    }

    // ===== `_ID` 指向已删目标且名称空（U1 悬空导出回读）：按“无链接”跳过不失败；
    //       空 `_ID` + 空名称：无链接（R15） =====

    @Test
    void import_doubleLink_deadIdWithEmptyName_skipped_noFailure()
    {
        mockDoubleLinkColumns();
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Collections.singletonList(record(301L, "张三", 1L)));

        // 行2：`_ID`=999（目标已删）+ 名称空 → 死链回读跳过；行3：`_ID` 空 + 名称空 → 无链接
        byte[] bytes = singleSheetBytes(
                new String[] {"record_id", "名称", "关联_ID", "关联_文本"},
                Arrays.asList(
                        new String[] {"1", "a", "999", ""},
                        new String[] {"2", "b", "", ""}));

        ExcelImportPrecheckResult result =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);
        assertTrue(result.getLinkFailures().isEmpty());
        assertTrue(result.getMissingParams().isEmpty());
        assertTrue(result.getSymmetricWriteImpact().isEmpty());
        assertFalse(result.isHasBlockingIssues());

        int count = runImport(bytes, paramsJson(bytes, null, null, null, null, null));
        assertEquals(2, count);

        // 两行均不建立关联：空串 value、无 link 字段、零对称写入
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        int linkItemCount = 0;
        for (NoteDwtableItem item : itemsCaptor.getValue())
        {
            if (item.getColumnId().equals(104L))
            {
                linkItemCount++;
                assertEquals("", item.getValue());
                assertNull(item.getLinkRecordId());
            }
        }
        assertEquals(2, linkItemCount);
        verify(noteDwtableItemMapper, never())
                .selectNoteDwtableItemByRecordAndColumnForUpdate(any(NoteDwtableItem.class));
        verify(noteDwtableItemMapper, never()).updateNoteDwtableItem(any(NoteDwtableItem.class));
    }

    // ===== 18 列回归（红线）：`列名_文本` 机制不变、`_ID` 仍忽略不消费 =====

    @Test
    void precheckAndImport_singleLink_textMechanismAndIdIgnored_unchanged()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(105L, "单向", 18L, "{\"select\":80}", 0L)));
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Collections.singletonList(record(301L, "张三", 1L)));

        // `_ID` 单元格放了错误 id（888）：18 列 `_ID` 仍被忽略（不消费），链接仅由 `_文本` 名称建立
        byte[] bytes = singleSheetBytes(
                new String[] {"record_id", "名称", "单向_ID", "单向_文本"},
                Collections.singletonList(new String[] {"1", "a", "888", "张三"}));

        ExcelImportPrecheckResult result =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);
        assertTrue(result.getLinkFailures().isEmpty());
        assertTrue(result.getMissingParams().isEmpty());
        assertFalse(result.isHasBlockingIssues());
        // record_id + 单向_ID 两个跳过表头（后者为源 id 列不导入值）
        assertEquals(2, result.getSkippedHeaders().size());

        runImport(bytes, paramsJson(bytes, null, null, null, null, null));

        // 链接由名称建立（301），`_ID` 的 888 未参与；18 列无对称写入
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        NoteDwtableItem linkItem = null;
        for (NoteDwtableItem item : itemsCaptor.getValue())
        {
            if (item.getColumnId().equals(105L))
            {
                linkItem = item;
            }
        }
        assertNotNull(linkItem);
        assertEquals("301", linkItem.getLinkRecordId());
        assertEquals("张三", linkItem.getValue());
        assertNull(linkItem.getLinkColumnId());
        verify(noteDwtableItemMapper, never())
                .selectNoteDwtableItemByRecordAndColumnForUpdate(any(NoteDwtableItem.class));
        verify(noteDwtableItemMapper, never()).updateNoteDwtableItem(any(NoteDwtableItem.class));
    }

    // ==================================================================
    // U4：25 列（语义关联）从零建立（双轨制：`_ID` notelink 行 id 优先 + 笔记名回退）
    // ==================================================================

    /** 25 列场景公共桩：名称(1) + 语义(25)；笔记候选（U4/R6：候选=库内全部笔记标题） */
    private void mockSemanticColumns(NoteNote... notes)
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(104L, "语义", 25L, null, 0L)));
        when(noteNoteMapper.selectAllNoteList()).thenReturn(new ArrayList<>(Arrays.asList(notes)));
    }

    private NoteNote note(Long id, String title)
    {
        NoteNote note = new NoteNote();
        note.setId(id);
        note.setTitle(title);
        return note;
    }

    /** 既有 NoteNotelink 行（`_ID` 命中时按行 id 恢复的核心字段载体） */
    private NoteNotelink notelink(Long id, Long noteId, Long blockId, String contextText,
            String itemValue, Long linkNoteId, Long linkDwTableId, Long linkRecordId,
            Long linkColumnId, Long linkItemId)
    {
        NoteNotelink link = new NoteNotelink();
        link.setId(id);
        link.setNoteId(noteId);
        link.setBlockId(blockId);
        link.setContextText(contextText);
        link.setItemValue(itemValue);
        link.setLinkNoteId(linkNoteId);
        link.setLinkDwTableId(linkDwTableId);
        link.setLinkRecordId(linkRecordId);
        link.setLinkColumnId(linkColumnId);
        link.setLinkItemId(linkItemId);
        return link;
    }

    /** 取批量 insert 落库的 25 列（columnId=104）item */
    private NoteDwtableItem semanticItemOf(List<NoteDwtableItem> items)
    {
        for (NoteDwtableItem item : items)
        {
            if (item.getColumnId().equals(104L))
            {
                return item;
            }
        }
        return null;
    }

    // ===== Covers AE2. 导出再导入：notelink 行重建、坐标指向本表新记录、跳转参数完整；
    //       行 id 不变（data-link-id 不断链）、blockId/核心字段恢复；26 列排除类型回归 =====

    @Test
    void precheckAndImport_semanticLink_idHit_roundTripRestoresRowAndCoordinates()
    {
        // 26 列（排除类型回归）：同名表头 skip-referenced，不参与导入、不进删除清单
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(
                column(101L, "名称", 1L, null, 0L),
                column(104L, "语义", 25L, null, 0L),
                column(106L, "引用", 26L, null, 0L)));
        when(noteNoteMapper.selectAllNoteList()).thenReturn(new ArrayList<>(
                Collections.singletonList(note(40L, "引用笔记"))));
        when(noteNotelinkMapper.selectNoteNotelinkById(901L)).thenReturn(
                notelink(901L, 40L, 701L, "锚词", "锚词", 50L, 60L, 999L, 104L, 888L));

        // 导出产物布局（renderer：record_id 首列 + 双列 `_ID` 前 `_文本` 后）
        byte[] bytes = singleSheetBytes(
                new String[] {"record_id", "名称", "语义_ID", "语义_文本", "引用"},
                Collections.singletonList(new String[] {"1", "a", "901", "引用笔记（锚词）", "x"}));

        // 预检：行 id 命中即通过（无链接失败/缺参/歧义）
        ExcelImportPrecheckResult precheck =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);
        assertTrue(precheck.getLinkFailures().isEmpty());
        assertTrue(precheck.getMissingParams().isEmpty());
        assertTrue(precheck.getAmbiguityItems().isEmpty());
        assertFalse(precheck.isHasBlockingIssues());
        // 跳过表头：record_id + 语义_ID（行 id 锚点）+ 引用（26 排除类型，行为不变）
        assertEquals(3, precheck.getSkippedHeaders().size());
        assertTrue(precheck.getReplaceImpact().getDeletedColumns().isEmpty());

        int count = runImport(bytes, paramsJson(bytes, null, null, null, null, null));
        assertEquals(1, count);

        // 25 列 item 落库：value=名称条目串原样（显示值），自增 id 为 notelink 行内坐标
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        NoteDwtableItem semanticItem = semanticItemOf(itemsCaptor.getValue());
        assertNotNull(semanticItem);
        assertEquals("引用笔记（锚词）", semanticItem.getValue());
        Long newItemId = semanticItem.getId();
        Long newRecordId = semanticItem.getRecordId();
        assertNotNull(newItemId);
        assertNotNull(newRecordId);

        // 重建顺序：先按列清旧行（悬空清理），再按行 id upsert 恢复原行
        InOrder inOrder = inOrder(noteNotelinkMapper);
        inOrder.verify(noteNotelinkMapper).deleteNoteNotelinkByColumnId(104L);
        inOrder.verify(noteNotelinkMapper).updateNoteNotelinkCoordinates(notelinkCaptor.capture());
        NoteNotelink restored = notelinkCaptor.getValue();
        // 行 id 保留（源笔记正文锚点 data-link-id 不断链）+ blockId/核心字段按原行恢复
        assertEquals(Long.valueOf(901L), restored.getId());
        assertEquals(Long.valueOf(40L), restored.getNoteId());
        assertEquals(Long.valueOf(701L), restored.getBlockId());
        assertEquals("锚词", restored.getContextText());
        assertEquals("锚词", restored.getItemValue());
        // 跳转参数完整（五字段）：linkNoteId=本表归属笔记规范值、坐标指向本表新记录/新单元格
        assertEquals(NOTE_ID, restored.getLinkNoteId());
        assertEquals(DWTABLE_ID, restored.getLinkDwTableId());
        assertEquals(Long.valueOf(104L), restored.getLinkColumnId());
        assertEquals(newItemId, restored.getLinkItemId());
        assertEquals(newRecordId, restored.getLinkRecordId());
        // ID 命中不产生名称回退新建
        verify(noteNotelinkMapper, never()).insertNoteNotelink(any(NoteNotelink.class));
    }

    // ===== 笔记名回退命中唯一笔记：新建行、linkNoteId 规范值、contextText=锚词、blockId 降级；
    //       列原名表头（外部手写形态）同样识别为 25 列名称主列 =====

    @Test
    void import_semanticLink_nameFallbackUniqueNote_insertsRowWithCanonicalFields()
    {
        mockSemanticColumns(note(40L, "引用笔记"));

        // 空 `_ID` → 纯名称解析；名称主列用列原名"语义"（与 `语义_文本` 同为合法来源）
        byte[] bytes = singleSheetBytes(
                new String[] {"record_id", "名称", "语义_ID", "语义"},
                Collections.singletonList(new String[] {"1", "a", "", "引用笔记（锚词）"}));

        ExcelImportPrecheckResult precheck =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);
        assertTrue(precheck.getLinkFailures().isEmpty());
        assertFalse(precheck.isHasBlockingIssues());

        runImport(bytes, paramsJson(bytes, null, null, null, null, null));

        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        NoteDwtableItem semanticItem = semanticItemOf(itemsCaptor.getValue());
        assertNotNull(semanticItem);
        Long newItemId = semanticItem.getId();
        Long newRecordId = semanticItem.getRecordId();

        // 新建行：noteId=命中笔记、blockId=null 锚点降级、contextText/itemValue=锚词、
        // linkNoteId=本表归属笔记规范值、坐标指向本表新记录
        verify(noteNotelinkMapper, times(1)).deleteNoteNotelinkByColumnId(104L);
        verify(noteNotelinkMapper, times(1)).insertNoteNotelink(notelinkCaptor.capture());
        verify(noteNotelinkMapper, never()).updateNoteNotelinkCoordinates(any(NoteNotelink.class));
        NoteNotelink inserted = notelinkCaptor.getValue();
        assertEquals(Long.valueOf(40L), inserted.getNoteId());
        assertNull(inserted.getBlockId());
        assertEquals("锚词", inserted.getContextText());
        assertEquals("锚词", inserted.getItemValue());
        assertEquals(NOTE_ID, inserted.getLinkNoteId());
        assertEquals(DWTABLE_ID, inserted.getLinkDwTableId());
        assertEquals(Long.valueOf(104L), inserted.getLinkColumnId());
        assertEquals(newItemId, inserted.getLinkItemId());
        assertEquals(newRecordId, inserted.getLinkRecordId());
        assertNull(inserted.getId());
    }

    // ===== 笔记重名歧义：失败清单零写入（含阶段二漂移防御，notelink 重建段不执行） =====

    @Test
    void precheck_semanticLink_ambiguousNoteTitle_linkFailureZeroWrites()
    {
        mockSemanticColumns(note(40L, "引用笔记"), note(41L, "引用笔记"));

        byte[] bytes = singleSheetBytes(
                new String[] {"record_id", "名称", "语义_ID", "语义_文本"},
                Collections.singletonList(new String[] {"1", "a", "", "引用笔记（锚词）"}));

        ExcelImportPrecheckResult result =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);

        // 链接失败清单：定位（列+行号）+ 原因 + 容错命中说明；与缺参清单并列阻断
        assertEquals(1, result.getLinkFailures().size());
        LinkFailure failure = result.getLinkFailures().get(0);
        assertEquals(Long.valueOf(104L), failure.getColumnId());
        assertEquals("语义", failure.getColumnName());
        assertEquals(2, failure.getRowNumber());
        assertEquals("引用笔记（锚词）", failure.getEntryText());
        assertTrue(failure.getReason().contains("歧义"));
        assertNotNull(failure.getExplanation());
        assertTrue(result.getMissingParams().isEmpty());
        assertTrue(result.isHasBlockingIssues());

        // 导入（阶段二防御）：漂移式拒绝，零写入
        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, paramsJson(bytes, null, null, null, null, null)));
        assertTrue(ex.getMessage().contains("数据已变化，请重新预检"));
        assertTrue(ex.getMessage().contains("解析失败"));
        assertTrue(ex.getMessage().contains("引用笔记（锚词）"));
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
        verify(noteDwtableItemMapper, never()).insertNoteDwtableItems(anyList());
        verify(noteNotelinkMapper, never()).deleteNoteNotelinkByColumnId(any(Long.class));
        verify(noteNotelinkMapper, never()).insertNoteNotelink(any(NoteNotelink.class));
        verify(noteNotelinkMapper, never()).updateNoteNotelinkCoordinates(any(NoteNotelink.class));
    }

    // ===== 多锚点单元格（多行 notelink）：逐条目全命中才通过——两行 id 均恢复 =====

    @Test
    void import_semanticLink_multiAnchorAllHit_bothRestored()
    {
        mockSemanticColumns(note(40L, "引用笔记"), note(41L, "另一笔记"));
        when(noteNotelinkMapper.selectNoteNotelinkById(901L)).thenReturn(
                notelink(901L, 40L, 701L, "锚词", "锚词", 50L, 60L, 998L, 104L, 887L));
        when(noteNotelinkMapper.selectNoteNotelinkById(902L)).thenReturn(
                notelink(902L, 41L, 702L, "词二", "词二", 50L, 60L, 999L, 104L, 888L));

        byte[] bytes = singleSheetBytes(
                new String[] {"record_id", "名称", "语义_ID", "语义_文本"},
                Collections.singletonList(new String[] {"1", "a", "901,902", "引用笔记（锚词）,另一笔记（词二）"}));

        ExcelImportPrecheckResult precheck =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);
        assertTrue(precheck.getLinkFailures().isEmpty());

        int count = runImport(bytes, paramsJson(bytes, null, null, null, null, null));
        assertEquals(1, count);

        verify(noteNotelinkMapper, times(1)).deleteNoteNotelinkByColumnId(104L);
        verify(noteNotelinkMapper, times(2)).updateNoteNotelinkCoordinates(notelinkCaptor.capture());
        // 两个行 id 均保留、核心字段各自恢复
        Set<Long> restoredIds = new HashSet<>();
        for (NoteNotelink restored : notelinkCaptor.getAllValues())
        {
            restoredIds.add(restored.getId());
        }
        assertEquals(new HashSet<>(Arrays.asList(901L, 902L)), restoredIds);
        verify(noteNotelinkMapper, never()).insertNoteNotelink(any(NoteNotelink.class));
    }

    // ===== 多锚点部分未命中（R9）：`_ID` 死链且同位名称未命中 → 任一失败整表零写入 =====

    @Test
    void precheck_semanticLink_multiAnchorPartialMiss_linkFailureZeroWrites()
    {
        mockSemanticColumns(note(40L, "引用笔记"));
        when(noteNotelinkMapper.selectNoteNotelinkById(901L)).thenReturn(
                notelink(901L, 40L, 701L, "锚词", "锚词", 50L, 60L, 999L, 104L, 888L));

        // 999 为已删 notelink 行；同位名称"错名"未命中 → 失败
        byte[] bytes = singleSheetBytes(
                new String[] {"record_id", "名称", "语义_ID", "语义_文本"},
                Collections.singletonList(new String[] {"1", "a", "901,999", "引用笔记（锚词）,错名"}));

        ExcelImportPrecheckResult result =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);

        assertEquals(1, result.getLinkFailures().size());
        LinkFailure failure = result.getLinkFailures().get(0);
        assertEquals("错名", failure.getEntryText());
        assertEquals(2, failure.getRowNumber());
        assertTrue(failure.getReason().contains("行 id 未命中"));
        assertTrue(result.isHasBlockingIssues());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, paramsJson(bytes, null, null, null, null, null)));
        assertTrue(ex.getMessage().contains("解析失败"));
        assertTrue(ex.getMessage().contains("错名"));
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
        verify(noteNotelinkMapper, never()).deleteNoteNotelinkByColumnId(any(Long.class));
        verify(noteNotelinkMapper, never()).updateNoteNotelinkCoordinates(any(NoteNotelink.class));
    }

    // ===== 悬空导出回读（空名称+空 `_ID`）再导入：不建行、不失败（U1/R4 边界） =====

    @Test
    void import_semanticLink_deadIdWithEmptyName_skipped_noFailure()
    {
        mockSemanticColumns(note(40L, "引用笔记"));

        // 行2：`_ID`=999（行已删）+ 名称空 → 死链回读跳过；行3：双空 → 无链接
        byte[] bytes = singleSheetBytes(
                new String[] {"record_id", "名称", "语义_ID", "语义_文本"},
                Arrays.asList(
                        new String[] {"1", "a", "999", ""},
                        new String[] {"2", "b", "", ""}));

        ExcelImportPrecheckResult result =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);
        assertTrue(result.getLinkFailures().isEmpty());
        assertTrue(result.getMissingParams().isEmpty());
        assertFalse(result.isHasBlockingIssues());

        int count = runImport(bytes, paramsJson(bytes, null, null, null, null, null));
        assertEquals(2, count);

        // 两行均不重建 notelink 行；清旧行仍执行（本列旧 notelink 悬空清理）
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        int semanticItemCount = 0;
        for (NoteDwtableItem item : itemsCaptor.getValue())
        {
            if (item.getColumnId().equals(104L))
            {
                semanticItemCount++;
                assertEquals("", item.getValue());
            }
        }
        assertEquals(2, semanticItemCount);
        verify(noteNotelinkMapper, times(1)).deleteNoteNotelinkByColumnId(104L);
        verify(noteNotelinkMapper, never()).insertNoteNotelink(any(NoteNotelink.class));
        verify(noteNotelinkMapper, never()).updateNoteNotelinkCoordinates(any(NoteNotelink.class));
    }

    // ===== 仅表头 Excel（清空场景）：25 列旧行同样清除，无重建 =====

    @Test
    void import_semanticLink_headerOnlyExcel_clearsOldRows()
    {
        mockSemanticColumns();

        byte[] bytes = singleSheetBytes(
                new String[] {"record_id", "名称", "语义_ID", "语义_文本"},
                Collections.emptyList());

        int count = runImport(bytes, paramsJson(bytes, null, null, null, null, null));
        assertEquals(0, count);

        // rows 为空也执行清旧行（指向已删 items 的悬空 notelink 不残留），无重建
        verify(noteNotelinkMapper, times(1)).deleteNoteNotelinkByColumnId(104L);
        verify(noteNotelinkMapper, never()).insertNoteNotelink(any(NoteNotelink.class));
        verify(noteNotelinkMapper, never()).updateNoteNotelinkCoordinates(any(NoteNotelink.class));
    }

    // ==================================================================
    // U5：入链重指向（R11-R13，取代整列清空；2026-10-10 裁决的三类失败处置）
    // ==================================================================

    /** U5 入链重指向场景公共桩：双链列 + 被关联表 B记录(100) + 配对列 502 + 入链捕获 */
    private void mockInboundRepointDoubleLink(NoteDwtableItem... inboundItems)
    {
        mockDoubleLinkColumns();
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Collections.singletonList(record(100L, "B记录", 1L)));
        List<NoteDwtableItem> inboundList = new ArrayList<>(Arrays.asList(inboundItems));
        mockInboundRepointContext(Collections.singletonList(relatedTable("被关联表", NOTE_ID)),
                Arrays.asList(record(1L, "a", 1L), record(2L, "b", 2L)),
                Arrays.asList(legacyItem(4000L, 1L, 104L), legacyItem(4001L, 2L, 104L)),
                inboundList);
    }

    // ===== Excel 删除被引用行：该入链条目单元格级全清/部分清，结果报告（日志）携带 =====

    @Test
    void replace_inboundRepoint_deletedRow_entryClearedAndReported()
    {
        // 捕获两条：900 多值（"1,2"——记录 2 被删，位级部分清）；901 仅指向被删记录 2（全清）
        NoteDwtableItem multi = inboundItem(900L, RELATED_TABLE_ID, 100L, 502L, "1,2", "4000,4001", "a,b");
        multi.setLinkColumnId(104L);
        NoteDwtableItem sole = inboundItem(901L, RELATED_TABLE_ID, 101L, 502L, "2", "4001", "b");
        sole.setLinkColumnId(104L);
        mockInboundRepointDoubleLink(multi, sole);
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumnForUpdate(any(NoteDwtableItem.class)))
                .thenReturn(pairedItem(900L, 502L, null, null, null));

        // Excel 只保留 record_id=1 的行（记录 2 被用户从 Excel 删除），正向链接仍指向 B记录
        byte[] bytes = singleSheetBytes(
                new String[] {"record_id", "名称", "关联_ID", "关联_文本"},
                Collections.singletonList(new String[] {"1", "a", "100", "B记录"}));

        int count = runImport(bytes, paramsJson(bytes, null, null, null, null, null));
        assertEquals(1, count);

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
        // 900：位 0（记录 1）重指向保留，位 1（记录 2，未出现在 Excel）条目级清空——
        // 复刻 clearLinkFieldsByColumnId 的字段语义（三字段按位清除，保留 linkColumnId）
        verify(noteDwtableItemMapper).updateLinkFieldsById(eq(900L),
                eq(String.valueOf(sourceInserted.getRecordId())),
                eq(String.valueOf(sourceInserted.getId())), eq("a"));
        // 901：唯一条目指向被删记录 → 单元格级全清（三字段空串；报告随导入结果日志输出）
        verify(noteDwtableItemMapper).updateLinkFieldsById(eq(901L), eq(""), eq(""), eq(""));
        // 整列清空不再发生（R13）
        verify(noteDwtableItemMapper, never()).clearLinkFieldsByColumnId(any(Long.class));
    }

    // ===== Excel 删正向链接（目标行存活）：配对列背链条目级清空+报告（互指一致性） =====

    @Test
    void replace_inboundRepoint_noMutualDeclaration_backEntryCleared()
    {
        // 背链条目指向本表记录 1；Excel 中记录 1 的行存活但正向链接被删（关联_文本 空）
        NoteDwtableItem back = inboundItem(900L, RELATED_TABLE_ID, 100L, 502L, "1", "4000", "a");
        back.setLinkColumnId(104L);
        mockInboundRepointDoubleLink(back);

        byte[] bytes = singleSheetBytes(
                new String[] {"record_id", "名称", "关联_文本"},
                Collections.singletonList(new String[] {"1", "a", ""}));

        int count = runImport(bytes, paramsJson(bytes, null, null, null, null, null));
        assertEquals(1, count);

        // 行存活的名称列正常落库，但 21 列声明集为空 → 背链条目按"无对侧互指声明"清空+报告
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(anyList());
        verify(noteDwtableItemMapper).updateLinkFieldsById(eq(900L), eq(""), eq(""), eq(""));
        // 正向链接已删 → 无对称写回
        verify(noteDwtableItemMapper, never()).updateNoteDwtableItem(any(NoteDwtableItem.class));
        verify(noteDwtableItemMapper, never()).clearLinkFieldsByColumnId(any(Long.class));
    }

    // ===== U6：clearedLinkColumns 新口径（R11-R13）——record_id 精确映射下重指向与清空条目并存 =====

    @Test
    void precheck_inboundRepoint_repointedAndClearedEntry_newImpactSemantics()
    {
        // 捕获 900 多值（"1,2"——记录 1 保留（record_id 映射 + 互指声明覆盖）→ 重指向；
        // 记录 2 被用户从 Excel 删除 → 单元格级清空并报告）
        NoteDwtableItem multi = inboundItem(900L, RELATED_TABLE_ID, 100L, 502L, "1,2", "4000,4001", "a,b");
        multi.setLinkColumnId(104L);
        mockInboundRepointDoubleLink(multi);

        byte[] bytes = singleSheetBytes(
                new String[] {"record_id", "名称", "关联_ID", "关联_文本"},
                Collections.singletonList(new String[] {"1", "a", "100", "B记录"}));

        ExcelImportPrecheckResult precheck =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);

        // 将重指向的列：被关联表 502——1 单元格 / 1 条目（仅记录 1 的条目）
        List<RepointedLinkColumn> repointed = precheck.getReplaceImpact().getRepointedLinkColumns();
        assertEquals(1, repointed.size());
        assertEquals(Long.valueOf(502L), repointed.get(0).getColumnId());
        assertEquals("关联的双向链接", repointed.get(0).getColumnName());
        assertEquals("被关联表", repointed.get(0).getTableName());
        assertEquals(1, repointed.get(0).getAffectedCellCount());
        assertEquals(1, repointed.get(0).getRepointedEntryCount());
        // 将单元格级清空并报告的条目：记录 2 未出现在导入文件中（定位 + 旧记录名 + 原因）
        List<WillClearLinkEntry> willClear = precheck.getReplaceImpact().getWillClearLinkEntries();
        assertEquals(1, willClear.size());
        assertEquals("被关联表", willClear.get(0).getTableName());
        assertEquals("关联的双向链接", willClear.get(0).getColumnName());
        assertEquals("b", willClear.get(0).getRecordName());
        assertTrue(willClear.get(0).getReason().contains("未出现在导入文件"));
        // 清空报告不阻断（R12 裁决：仅歧义进失败清单阻断）
        assertFalse(precheck.isHasBlockingIssues());
        assertTrue(precheck.getLinkFailures().isEmpty());
    }

    // ===== U6：互指声明消失的预检呈现——行存活但正向链接被删 → 清空条目（无重指向） =====

    @Test
    void precheck_inboundRepoint_noMutualDeclaration_willClearEntry()
    {
        // 背链条目指向本表记录 1；Excel 中记录 1 的行存活但正向链接被删（关联_文本 空）
        NoteDwtableItem back = inboundItem(900L, RELATED_TABLE_ID, 100L, 502L, "1", "4000", "a");
        back.setLinkColumnId(104L);
        mockInboundRepointDoubleLink(back);

        byte[] bytes = singleSheetBytes(
                new String[] {"record_id", "名称", "关联_文本"},
                Collections.singletonList(new String[] {"1", "a", ""}));

        ExcelImportPrecheckResult precheck =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);

        // 行存活的 21 列声明集为空 → 背链条目按"无对侧互指声明"清空并报告；无重指向条目
        List<WillClearLinkEntry> willClear = precheck.getReplaceImpact().getWillClearLinkEntries();
        assertEquals(1, willClear.size());
        assertEquals("a", willClear.get(0).getRecordName());
        assertTrue(willClear.get(0).getReason().contains("互指"));
        assertTrue(precheck.getReplaceImpact().getRepointedLinkColumns().isEmpty());
        assertFalse(precheck.isHasBlockingIssues());
    }

    // ===== U6（R8/R10）：链接失败与缺参并存的混合预检——两类分组同现、确认前零写入 =====

    @Test
    void precheck_mixedLinkFailureAndMissingParams_bothGroupsPresent_zeroWrites()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenAnswer(inv -> {
            NoteColumn query = inv.getArgument(0);
            if (RELATED_TABLE_ID.equals(query.getDwtableId()))
            {
                return Collections.singletonList(relatedColumn(502L, "关联的双向链接"));
            }
            return Arrays.asList(
                    column(101L, "名称", 1L, null, 0L),
                    column(102L, "数量", 2L, null, 0L),
                    column(104L, "关联", 21L, "{\"table_id\":80,\"back_field_id\":502}", 0L));
        });
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Collections.singletonList(record(100L, "B记录", 1L)));

        // 数量空单元格（缺值缺参）+ 21 列名称未命中（链接失败）并存
        byte[] bytes = singleSheetBytes(
                new String[] {"record_id", "名称", "数量", "关联_文本"},
                Collections.singletonList(new String[] {"1", "a", "", "错名"}));

        ExcelImportPrecheckResult result =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);

        // 两类分组并存：链接失败清单（定位/原因/容错说明）+ 缺参清单，hasBlockingIssues 置位
        assertEquals(1, result.getLinkFailures().size());
        assertEquals("错名", result.getLinkFailures().get(0).getEntryText());
        assertEquals(2, result.getLinkFailures().get(0).getRowNumber());
        assertEquals(1, result.getMissingParams().size());
        assertEquals("数量", result.getMissingParams().get(0).getColumnName());
        assertTrue(result.isHasBlockingIssues());

        // 导入（阶段二防御）：21 列解析失败先于任何写入 fail-fast，整表零写入
        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, paramsJson(bytes, null, null, null, null, null)));
        assertTrue(ex.getMessage().contains("解析失败"));
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
        verify(noteDwtableItemMapper, never()).insertNoteDwtableItems(anyList());
        verify(noteRecordMapper, never()).deleteNoteRecordByDwtableId(any(Long.class));
    }

    // ===== 名称回退歧义（外部手写无 record_id 列）：预检失败清单 + 整表零写入 =====

    @Test
    void precheck_inboundRepoint_nameFallbackAmbiguous_linkFailureZeroWrites()
    {
        // 他表 18 列条目指向本表旧记录 1（名"x"）；手写 Excel 无 record_id 首列且两行同名"x"
        NoteDwtable otherTable = relatedTable("他表", NOTE_ID);
        otherTable.setId(90L);
        mockInboundRepointContext(Collections.singletonList(otherTable),
                Collections.singletonList(record(1L, "x", 1L)),
                Collections.emptyList(),
                Collections.singletonList(inboundItem(700L, 90L, 55L, 201L, "1", null, "x")));
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenAnswer(inv -> {
            NoteColumn query = inv.getArgument(0);
            if (DWTABLE_ID.equals(query.getDwtableId()))
            {
                return Collections.singletonList(column(101L, "名称", 1L, null, 0L));
            }
            return new ArrayList<NoteColumn>();
        });

        byte[] bytes = singleSheetBytes(new String[] {"名称"},
                Arrays.asList(new String[] {"x"}, new String[] {"x"}));

        // 预检：名称回退"x"命中两条同名新记录 → 歧义进链接失败清单（与 U3/U4 同机制并列阻断）
        ExcelImportPrecheckResult precheck =
                precheckService.precheck(NOTE_ID, DWTABLE_ID, xlsxFile(bytes), USER_ID);
        assertEquals(1, precheck.getLinkFailures().size());
        LinkFailure failure = precheck.getLinkFailures().get(0);
        assertEquals("x", failure.getEntryText());
        assertTrue(failure.getReason().contains("入链重指向名称歧义"));
        assertTrue(failure.getReason().contains("record_id"));
        assertTrue(precheck.isHasBlockingIssues());
        // 歧义条目不进重指向/清空影响面（导入将被整体拒绝，不呈现假想影响）
        assertTrue(precheck.getReplaceImpact().getRepointedLinkColumns().isEmpty());
        assertTrue(precheck.getReplaceImpact().getWillClearLinkEntries().isEmpty());

        // 导入：事务段兜底 fail-fast（预检-导入间隙数据变化防御），整表零写入
        ServiceException ex = assertThrows(ServiceException.class,
                () -> runImport(bytes, paramsJson(bytes, null, null, null, null, null)));
        assertTrue(ex.getMessage().contains("零写入"));
        assertTrue(ex.getMessage().contains("歧义"));
        verify(noteRecordMapper, never()).deleteNoteRecordByDwtableId(any(Long.class));
        verify(noteRecordMapper, never()).insertNoteRecord(any(NoteRecord.class));
        verify(noteDwtableItemMapper, never()).updateLinkFieldsById(
                anyLong(), anyString(), anyString(), anyString());
    }

    // ===== 名称回退唯一命中（外部手写无 record_id 列）：按名称重指向 =====

    @Test
    void replace_inboundRepoint_nameFallbackUniqueHit_repainted()
    {
        NoteDwtable otherTable = relatedTable("他表", NOTE_ID);
        otherTable.setId(90L);
        mockInboundRepointContext(Collections.singletonList(otherTable),
                Collections.singletonList(record(1L, "x", 1L)),
                Collections.emptyList(),
                Collections.singletonList(inboundItem(700L, 90L, 55L, 201L, "1", null, "x")));
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenAnswer(inv -> {
            NoteColumn query = inv.getArgument(0);
            if (DWTABLE_ID.equals(query.getDwtableId()))
            {
                return Collections.singletonList(column(101L, "名称", 1L, null, 0L));
            }
            return new ArrayList<NoteColumn>();
        });

        byte[] bytes = singleSheetBytes(new String[] {"名称"},
                Collections.singletonList(new String[] {"x"}));

        int count = runImport(bytes, paramsJson(bytes, null, null, null, null, null));
        assertEquals(1, count);

        // 旧记录名"x"唯一命中新行 → 重指向（linkRecordId=新记录 id、value=新记录名）
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(itemsCaptor.capture());
        Long newRecordId = itemsCaptor.getValue().get(0).getRecordId();
        verify(noteDwtableItemMapper).updateLinkFieldsById(eq(700L),
                eq(String.valueOf(newRecordId)), eq(""), eq("x"));
    }

    // ===== 时序红线：重指向先于 21 对称写回与跨表重算（P1 裁决） =====

    @Test
    void replace_inboundRepoint_precedesSymmetricWriteAndRecompute()
    {
        // 双链场景 + 本表保留 lookup(26) 列（同名表头 skip-referenced → 重算编排覆盖）
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenAnswer(inv -> {
            NoteColumn query = inv.getArgument(0);
            if (RELATED_TABLE_ID.equals(query.getDwtableId()))
            {
                return Collections.singletonList(relatedColumn(502L, "关联的双向链接"));
            }
            return Arrays.asList(
                    column(101L, "名称", 1L, null, 0L),
                    column(104L, "关联", 21L, "{\"table_id\":80,\"back_field_id\":502}", 0L),
                    column(130L, "引用", 26L,
                            "{\"double_link_column_id\":\"104\",\"source_column_id\":\"101\"}", 0L));
        });
        NoteDwtableItem inbound = inboundItem(900L, RELATED_TABLE_ID, 100L, 502L, "1", "4000", "a");
        inbound.setLinkColumnId(104L);
        // 注意桩注册序：mockRelatedRecords（any 路由）先于 mockTargetTableRecords（argThen
        // 本表路由，后注册优先），否则 capture 的本表旧记录快照会被 any 路由吞掉
        mockRelatedTable(relatedTable("被关联表", NOTE_ID));
        mockRelatedRecords(Collections.singletonList(record(100L, "B记录", 1L)));
        mockInboundRepointContext(Collections.singletonList(relatedTable("被关联表", NOTE_ID)),
                Collections.singletonList(record(1L, "a", 1L)),
                Collections.singletonList(legacyItem(4000L, 1L, 104L)),
                Collections.singletonList(inbound));
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumnForUpdate(any(NoteDwtableItem.class)))
                .thenReturn(pairedItem(900L, 502L, null, null, null));

        byte[] bytes = singleSheetBytes(
                new String[] {"record_id", "名称", "关联_ID", "关联_文本", "引用"},
                Collections.singletonList(new String[] {"1", "a", "100", "B记录", ""}));

        int count = runImport(bytes, paramsJson(bytes, null, null, null, null, null));
        assertEquals(1, count);

        // 落库（回填新 id）→ 入链重指向 → 21 对称写回（FOR UPDATE 锁定读）→ 跨表重算
        InOrder inOrder = inOrder(noteDwtableItemMapper, noteRecordService);
        inOrder.verify(noteDwtableItemMapper).insertNoteDwtableItems(anyList());
        inOrder.verify(noteDwtableItemMapper).updateLinkFieldsById(anyLong(), anyString(), anyString(), anyString());
        inOrder.verify(noteDwtableItemMapper).selectNoteDwtableItemByRecordAndColumnForUpdate(any(NoteDwtableItem.class));
        inOrder.verify(noteRecordService).recomputeLookupColumnValues(any(NoteColumn.class));
    }

    // ===== 不改一字往返：重指向先行 + 对称写回 CSV 去重 → 配对列无重复条目 =====

    @Test
    void replace_inboundRepoint_roundTrip_noDuplicateEntries()
    {
        NoteDwtableItem pairedExisting = inboundItem(900L, RELATED_TABLE_ID, 100L, 502L, "1", "4000", "a");
        pairedExisting.setLinkColumnId(104L);
        mockInboundRepointDoubleLink(pairedExisting);
        // 对称写回 FOR UPDATE 读到"重指向后"的行状态：updateLinkFieldsById 联动更新内存实例
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumnForUpdate(any(NoteDwtableItem.class)))
                .thenReturn(pairedExisting);
        when(noteDwtableItemMapper.updateLinkFieldsById(anyLong(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    pairedExisting.setLinkRecordId(inv.getArgument(1));
                    pairedExisting.setLinkItemId(inv.getArgument(2));
                    pairedExisting.setValue(inv.getArgument(3));
                    return 1;
                });

        byte[] bytes = singleSheetBytes(
                new String[] {"record_id", "名称", "关联_ID", "关联_文本"},
                Collections.singletonList(new String[] {"1", "a", "100", "B记录"}));

        int count = runImport(bytes, paramsJson(bytes, null, null, null, null, null));
        assertEquals(1, count);

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
        // 重指向：旧 id→新 id（value/linkItemId 同步刷新）
        verify(noteDwtableItemMapper).updateLinkFieldsById(eq(900L),
                eq(String.valueOf(sourceInserted.getRecordId())),
                eq(String.valueOf(sourceInserted.getId())), eq("a"));
        // 对称写回合并同一新 id：Set 去重后配对 item 三串各只一条（无重复条目，P1 裁决）
        verify(noteDwtableItemMapper, times(2)).updateNoteDwtableItem(itemCaptor.capture());
        NoteDwtableItem pairedUpdate = itemCaptor.getAllValues().get(0);
        assertEquals(String.valueOf(sourceInserted.getRecordId()), pairedUpdate.getLinkRecordId());
        assertEquals(String.valueOf(sourceInserted.getId()), pairedUpdate.getLinkItemId());
        assertEquals("a", pairedUpdate.getValue());
        assertFalse(pairedUpdate.getLinkRecordId().contains(","));
    }

    // ===== 无入链场景回归：默认桩（无他表/无捕获）下输出与现状一致 =====

    @Test
    void replace_noInboundLinks_outputUnchanged()
    {
        mockDoubleLinkRoundTrip();

        byte[] bytes = singleSheetBytes(
                new String[] {"record_id", "名称", "关联_ID", "关联_文本"},
                Collections.singletonList(new String[] {"1", "a", "100", "B记录"}));

        int count = runImport(bytes, paramsJson(bytes, null, null, null, null, null));
        assertEquals(1, count);

        // 无入链（selectDWTableList 默认空表清单 → 无捕获）：重指向与整列清空均不发生，
        // 落库/对称写回/源回写与现状逐项一致（既有断言锚点：insert 1 次 + update 2 次）
        verify(noteDwtableItemMapper, never()).updateLinkFieldsById(
                anyLong(), anyString(), anyString(), anyString());
        verify(noteDwtableItemMapper, never()).clearLinkFieldsByColumnId(any(Long.class));
        verify(noteDwtableItemMapper, times(1)).insertNoteDwtableItems(anyList());
        verify(noteDwtableItemMapper, times(2)).updateNoteDwtableItem(any(NoteDwtableItem.class));
    }
}
