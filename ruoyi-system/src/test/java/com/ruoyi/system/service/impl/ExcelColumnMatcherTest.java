package com.ruoyi.system.service.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.service.impl.ExcelColumnMatcher.ColumnMapping;
import com.ruoyi.system.service.impl.ExcelColumnMatcher.HeaderEntry;
import com.ruoyi.system.service.impl.ExcelColumnMatcher.SheetSelection;
import com.ruoyi.system.service.impl.ExcelWorkbookReader.ParsedSheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExcelColumnMatcher} 单元测试。
 * <p>
 * 覆盖 U3 场景（R2/R5/R6/R11）：sheet 选定（单 sheet 直用 / 净化名匹配含特殊字符表名 /
 * 分片家族按序合并含不连续序号 / 两者皆无报错含长表名提示）、列映射（精确匹配区分大小写 /
 * round-trip 双列后缀与 record_id 源列识别 / record_id 同名列冲突 / 隐藏列与排除类型列跳过 /
 * 未匹配表头记录）、单元格显示值类型校验（数字/日期/复选框，空缺值不算违规）。
 * <p>
 * ParsedSheet 直接构造（包级构造器）为主，另含一条 POI 真实 xlsx →
 * {@link ExcelWorkbookReader#parse} → selectSheet 的端到端链路验证。
 *
 * @author ruoyi
 */
class ExcelColumnMatcherTest
{
    private static final String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    // ==================== 构造辅助 ====================

    private static ParsedSheet sheet(String name, List<String> headers, List<List<String>> rows)
    {
        return new ParsedSheet(name, headers, rows);
    }

    private static List<List<String>> rows(String[]... rowArrays)
    {
        List<List<String>> result = new ArrayList<>();
        for (String[] row : rowArrays)
        {
            result.add(new ArrayList<>(Arrays.asList(row)));
        }
        return result;
    }

    private static NoteColumn column(Long id, String name, long type)
    {
        return column(id, name, type, 0L);
    }

    private static NoteColumn column(Long id, String name, long type, Long isShow)
    {
        NoteColumn column = new NoteColumn();
        column.setId(id);
        column.setName(name);
        column.setType(type);
        column.setIsShow(isShow);
        return column;
    }

    /** 按表头名查映射条目 */
    private static HeaderEntry entryOf(ColumnMapping mapping, String headerName)
    {
        for (HeaderEntry entry : mapping.getEntries())
        {
            if (entry.getHeaderName().equals(headerName))
            {
                return entry;
            }
        }
        return null;
    }

    // ==================== sheet 选定（R2） ====================

    @Test
    void selectShouldUseSingleSheetRegardlessOfName()
    {
        List<ParsedSheet> sheets = Collections.singletonList(
                sheet("随便什么名字", Arrays.asList("名称"), rows(new String[] { "a" })));

        SheetSelection selection = ExcelColumnMatcher.selectSheet(sheets, "目标表");

        assertEquals(Arrays.asList("名称"), selection.getHeaders());
        assertEquals(1, selection.getRows().size());
        assertEquals(Collections.singletonList("随便什么名字"), selection.getSelectedSheetNames());
        assertTrue(selection.getIgnoredSheetNames().isEmpty());
    }

    @Test
    void selectShouldRejectEmptySheetList()
    {
        ServiceException exception = assertThrows(ServiceException.class,
                () -> ExcelColumnMatcher.selectSheet(Collections.<ParsedSheet>emptyList(), "目标表"));

        assertTrue(exception.getMessage().contains("没有任何工作表"));
    }

    @Test
    void selectShouldMatchSanitizedTableNameAmongMultipleSheets()
    {
        // 特殊字符表名：[] 净化为 _ 后命中（R2——按导出端同名变换规则比较，兼容含特殊字符表名）
        List<ParsedSheet> sheets = Arrays.asList(
                sheet("其他表", Arrays.asList("x"), rows(new String[] { "1" })),
                sheet("项目_核心_", Arrays.asList("名称", "状态"), rows(new String[] { "a", "进行中" })),
                sheet("日志", Arrays.asList("y"), rows(new String[] { "2" })));

        SheetSelection selection = ExcelColumnMatcher.selectSheet(sheets, "项目[核心]");

        assertEquals(Arrays.asList("名称", "状态"), selection.getHeaders());
        assertEquals(1, selection.getRows().size());
        assertEquals(Collections.singletonList("项目_核心_"), selection.getSelectedSheetNames());
        assertEquals(Arrays.asList("其他表", "日志"), selection.getIgnoredSheetNames());
    }

    @Test
    void selectShouldMergeShardFamilyInAscendingOrder()
    {
        List<ParsedSheet> sheets = Arrays.asList(
                sheet("无关表", Arrays.asList("z"), rows(new String[] { "9" })),
                sheet("T_p2", Arrays.asList("record_id", "名称"), rows(new String[] { "3", "c" })),
                sheet("T_p1", Arrays.asList("record_id", "名称"),
                        rows(new String[] { "1", "a" }, new String[] { "2", "b" })));

        SheetSelection selection = ExcelColumnMatcher.selectSheet(sheets, "T");

        // 表头取第一个分片（p1），行数据按序号升序拼接（p1 两行 + p2 一行）
        assertEquals(Arrays.asList("record_id", "名称"), selection.getHeaders());
        assertEquals(3, selection.getRows().size());
        assertEquals(Arrays.asList("1", "a"), selection.getRows().get(0));
        assertEquals(Arrays.asList("2", "b"), selection.getRows().get(1));
        assertEquals(Arrays.asList("3", "c"), selection.getRows().get(2));
        assertEquals(Arrays.asList("T_p1", "T_p2"), selection.getSelectedSheetNames());
        assertEquals(Collections.singletonList("无关表"), selection.getIgnoredSheetNames());
    }

    @Test
    void selectShouldMergeShardsWithNonConsecutiveIndices()
    {
        // 序号从 2 开始且不连续（p2、p4）：按存在的序号升序合并，表头取序号最小者
        List<ParsedSheet> sheets = Arrays.asList(
                sheet("T_p4", Arrays.asList("record_id", "名称"), rows(new String[] { "4", "d" })),
                sheet("T_p2", Arrays.asList("record_id", "名称"), rows(new String[] { "2", "b" })));

        SheetSelection selection = ExcelColumnMatcher.selectSheet(sheets, "T");

        assertEquals(Arrays.asList("record_id", "名称"), selection.getHeaders());
        assertEquals(2, selection.getRows().size());
        assertEquals(Arrays.asList("2", "b"), selection.getRows().get(0));
        assertEquals(Arrays.asList("4", "d"), selection.getRows().get(1));
        assertEquals(Arrays.asList("T_p2", "T_p4"), selection.getSelectedSheetNames());
    }

    @Test
    void selectShouldRejectWhenNoSheetMatches()
    {
        List<ParsedSheet> sheets = Arrays.asList(
                sheet("表A", Arrays.asList("x"), rows(new String[] { "1" })),
                sheet("表B", Arrays.asList("y"), rows(new String[] { "2" })));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> ExcelColumnMatcher.selectSheet(sheets, "T"));

        // 消息含目标表名与已有 sheet 名清单
        assertTrue(exception.getMessage().contains("T"));
        assertTrue(exception.getMessage().contains("表A"));
        assertTrue(exception.getMessage().contains("表B"));
    }

    @Test
    void selectShouldHintShorteningWhenSanitizedNameTooLong()
    {
        // 净化后 30 字符（≥29）：分片后缀必被 31 字符截断破坏 → 报错并提示缩短表名
        //（多 sheet 才走匹配→分片→报错路径；单 sheet 直用不校验名字）
        String longTableName = repeat("长", 30);
        List<ParsedSheet> sheets = Arrays.asList(
                sheet("别的表", Arrays.asList("x"), rows(new String[] { "1" })),
                sheet("另一表", Arrays.asList("y"), rows(new String[] { "2" })));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> ExcelColumnMatcher.selectSheet(sheets, longTableName));

        assertTrue(exception.getMessage().contains("缩短表名"));
    }

    @Test
    void selectShouldNotLetTruncatedIndicesBreakShardRecognition()
    {
        // 28 字符表名：n=1..9 的分片名恰 31 字符（无截断）可识别；n≥10 截断与 n=1 同名——
        // 截断的序号不得注册为期望名，否则无关 sheet 拉高 n 上限会把合法 p1 误标歧义
        String tableName = repeat("长", 28);
        List<ParsedSheet> sheets = new ArrayList<>();
        sheets.add(sheet("无关A", Arrays.asList("x"), rows(new String[] { "0" })));
        for (int n = 1; n <= 9; n++)
        {
            sheets.add(sheet(tableName + "_p" + n, Arrays.asList("record_id"),
                    rows(new String[] { String.valueOf(n) })));
        }
        sheets.add(sheet("无关B", Arrays.asList("y"), rows(new String[] { "99" })));

        SheetSelection selection = ExcelColumnMatcher.selectSheet(sheets, tableName);

        assertEquals(9, selection.getRows().size());
        assertEquals(Arrays.asList("无关A", "无关B"), selection.getIgnoredSheetNames());
    }

    @Test
    void selectShouldMergeShardsFromRealWorkbook()
    {
        // 端到端链路：POI 生成真实分片 xlsx → ExcelWorkbookReader.parse → selectSheet
        byte[] bytes = workbookBytes(workbook -> {
            XSSFSheet p1 = workbook.createSheet("项目表_p1");
            p1.createRow(0).createCell(0).setCellValue("record_id");
            p1.getRow(0).createCell(1).setCellValue("名称");
            p1.createRow(1).createCell(0).setCellValue(1L);
            p1.getRow(1).createCell(1).setCellValue("a");
            XSSFSheet p2 = workbook.createSheet("项目表_p2");
            p2.createRow(0).createCell(0).setCellValue("record_id");
            p2.getRow(0).createCell(1).setCellValue("名称");
            p2.createRow(1).createCell(0).setCellValue(2L);
            p2.getRow(1).createCell(1).setCellValue("b");
        });
        List<ParsedSheet> sheets = ExcelWorkbookReader.parse(multipartFileOf("分片.xlsx", bytes));

        SheetSelection selection = ExcelColumnMatcher.selectSheet(sheets, "项目表");

        assertEquals(Arrays.asList("record_id", "名称"), selection.getHeaders());
        assertEquals(2, selection.getRows().size());
        assertEquals(Arrays.asList("1", "a"), selection.getRows().get(0));
        assertEquals(Arrays.asList("2", "b"), selection.getRows().get(1));
    }

    // ==================== 列映射：精确匹配（R5） ====================

    @Test
    void mapShouldMatchExactColumnNamesCaseSensitively()
    {
        NoteColumn nameColumn = column(1L, "姓名", 1L);
        ColumnMapping mapping = ExcelColumnMatcher.mapColumns(
                Arrays.asList("姓名", "NAME", ""),
                Collections.singletonList(nameColumn));

        // 同名精确命中
        HeaderEntry matched = entryOf(mapping, "姓名");
        assertNotNull(matched);
        assertEquals(nameColumn, matched.getColumn());
        // 大小写不同不命中（区分大小写），记为未匹配跳过
        HeaderEntry caseMismatch = entryOf(mapping, "NAME");
        assertNotNull(caseMismatch);
        assertNull(caseMismatch.getColumn());
        assertNotNull(caseMismatch.getReason());
        // 空表头记录明确原因
        HeaderEntry empty = entryOf(mapping, "");
        assertNotNull(empty);
        assertNull(empty.getColumn());
        assertEquals("空表头", empty.getReason());
        // 跳过清单含未匹配表头名
        assertTrue(mapping.getSkippedEntries().size() == 2);
    }

    @Test
    void mapShouldMapLinkColumnByExactHeader()
    {
        // 外部文件单列形式：表头恰为 18/21 列名 → 直接精确映射（值按文本匹配，U4 处理）
        NoteColumn linkColumn = column(5L, "关联", 21L);
        ColumnMapping mapping = ExcelColumnMatcher.mapColumns(
                Collections.singletonList("关联"), Collections.singletonList(linkColumn));

        assertEquals(linkColumn, mapping.mappedColumn(0));
    }

    // ==================== 列映射：round-trip 双列 / record_id（R5） ====================

    @Test
    void mapShouldStripTextSuffixOntoLinkColumnsAndIgnoreIdColumns()
    {
        NoteColumn singleLink = column(5L, "单向", 18L);
        NoteColumn doubleLink = column(6L, "双向", 21L);
        ColumnMapping mapping = ExcelColumnMatcher.mapColumns(
                Arrays.asList("record_id", "名称", "单向_ID", "单向_文本", "双向_ID", "双向_文本"),
                Arrays.asList(singleLink, doubleLink));

        // record_id 源列（首列）忽略
        HeaderEntry recordId = entryOf(mapping, "record_id");
        assertNull(recordId.getColumn());
        assertTrue(recordId.getReason().contains("记录 id"));

        // _ID 后缀表头忽略（源 id 不映射）——18 与 21 列一致
        assertNull(entryOf(mapping, "单向_ID").getColumn());
        assertTrue(entryOf(mapping, "单向_ID").getReason().contains("源 id"));
        assertNull(entryOf(mapping, "双向_ID").getColumn());

        // _文本 后缀剥离命中 18/21 关联列
        assertEquals(singleLink, mapping.mappedColumn(3));
        assertEquals(doubleLink, mapping.mappedColumn(5));

        // 双列错位警戒：按 headerIndex 取数（不按列序号）——首列 record_id 之后 index 与列序错位
        List<String> row = Arrays.asList("1", "张三", "42", "甲记录", "43,44", "乙记录,丙记录");
        assertEquals("甲记录", mapping.cellText(row, 3));
        assertEquals("乙记录,丙记录", mapping.cellText(row, 5));
    }

    @Test
    void mapShouldNotStripTextSuffixOntoNonLinkColumn()
    {
        // 剥离 _文本 后命中的是普通文本列（非 18/21）→ 该表头无效，记未匹配
        NoteColumn textColumn = column(1L, "备注", 1L);
        ColumnMapping mapping = ExcelColumnMatcher.mapColumns(
                Collections.singletonList("备注_文本"), Collections.singletonList(textColumn));

        HeaderEntry entry = mapping.getEntries().get(0);
        assertNull(entry.getColumn());
        assertNotNull(entry.getReason());
    }

    @Test
    void mapShouldRejectRecordIdHeaderConflictWithRealColumn()
    {
        // 冲突：首列 record_id 表头（导出源 id 列，应忽略）+ 目标表存在同名真实参与导入列 → 报冲突，不静默丢弃
        NoteColumn realRecordId = column(9L, "record_id", 1L);
        ServiceException exception = assertThrows(ServiceException.class,
                () -> ExcelColumnMatcher.mapColumns(
                        Arrays.asList("record_id", "名称"),
                        Arrays.asList(realRecordId, column(2L, "名称", 1L))));

        assertTrue(exception.getMessage().contains("record_id"));
        assertTrue(exception.getMessage().contains("冲突"));
    }

    @Test
    void mapShouldMapNonFirstRecordIdHeaderToRealColumn()
    {
        // 非首列 record_id 表头：不是导出源 id 列（导出固定首列），精确命中真实列是合法显式意图
        NoteColumn realRecordId = column(9L, "record_id", 1L);
        ColumnMapping mapping = ExcelColumnMatcher.mapColumns(
                Arrays.asList("名称", "record_id"),
                Arrays.asList(column(2L, "名称", 1L), realRecordId));

        assertEquals(realRecordId, mapping.mappedColumn(1));
    }

    // ==================== 列映射：隐藏列与排除类型（R6/R17） ====================

    @Test
    void mapShouldSkipHiddenColumnHeaders()
    {
        NoteColumn hiddenText = column(3L, "机密", 1L, 1L);
        NoteColumn hiddenLink = column(4L, "隐藏关联", 21L, 1L);
        ColumnMapping mapping = ExcelColumnMatcher.mapColumns(
                Arrays.asList("机密", "隐藏关联_文本", "隐藏关联_ID"),
                Arrays.asList(hiddenText, hiddenLink));

        // isShow=1 列即使表头同名（含后缀形式）也不映射，记录原因
        HeaderEntry hidden = entryOf(mapping, "机密");
        assertNull(hidden.getColumn());
        assertTrue(hidden.getReason().contains("隐藏"));
        assertNull(entryOf(mapping, "隐藏关联_文本").getColumn());
        assertNull(entryOf(mapping, "隐藏关联_ID").getColumn());
    }

    @Test
    void mapShouldSkipExcludedAndSystemTypeColumns()
    {
        NoteColumn formula = column(10L, "公式列", 20L);
        NoteColumn lookup = column(11L, "引用列", 26L);
        NoteColumn sysTime = column(12L, "创建时间", 1001L);
        ColumnMapping mapping = ExcelColumnMatcher.mapColumns(
                Arrays.asList("公式列", "引用列_文本", "引用列_ID", "创建时间"),
                Arrays.asList(formula, lookup, sysTime));

        // 公式/lookup/系统列同名表头（含后缀形式）忽略并记录原因
        assertNull(entryOf(mapping, "公式列").getColumn());
        assertTrue(entryOf(mapping, "公式列").getReason().contains("公式"));
        assertNull(entryOf(mapping, "引用列_文本").getColumn());
        assertNull(entryOf(mapping, "引用列_ID").getColumn());
        assertNull(entryOf(mapping, "创建时间").getColumn());
        assertTrue(entryOf(mapping, "创建时间").getReason().contains("系统列"));
        assertTrue(mapping.getSkippedEntries().size() == 4);
    }

    @Test
    void mapShouldRecordUnmatchedHeaders()
    {
        NoteColumn nameColumn = column(1L, "姓名", 1L);
        ColumnMapping mapping = ExcelColumnMatcher.mapColumns(
                Arrays.asList("姓名", "不存在的列"),
                Collections.singletonList(nameColumn));

        assertEquals(1, mapping.getMappedEntries().size());
        List<HeaderEntry> skipped = mapping.getSkippedEntries();
        assertEquals(1, skipped.size());
        assertEquals("不存在的列", skipped.get(0).getHeaderName());
        assertNotNull(skipped.get(0).getReason());
    }

    // ==================== 单元格显示值类型校验（R11/KTD9） ====================

    @Test
    void validateShouldCheckNumericCellText()
    {
        NoteColumn numberColumn = column(1L, "数量", 2L);

        assertNull(ExcelColumnMatcher.validateCellText(numberColumn, "123.5"));
        assertNull(ExcelColumnMatcher.validateCellText(numberColumn, "-42"));
        assertNull(ExcelColumnMatcher.validateCellText(numberColumn, " 12 "));
        // 空串/null 视为缺值，不算违规
        assertNull(ExcelColumnMatcher.validateCellText(numberColumn, ""));
        assertNull(ExcelColumnMatcher.validateCellText(numberColumn, null));
        assertNull(ExcelColumnMatcher.validateCellText(numberColumn, "  "));

        assertNotNull(ExcelColumnMatcher.validateCellText(numberColumn, "abc"));
    }

    @Test
    void validateShouldCheckDateCellText()
    {
        NoteColumn dateColumn = column(2L, "日期", 5L);

        // 严格两种格式合法
        assertNull(ExcelColumnMatcher.validateCellText(dateColumn, "2026-01-02 03:04:05"));
        assertNull(ExcelColumnMatcher.validateCellText(dateColumn, "2026-01-02"));
        // 其他格式/非法日期违规
        assertNotNull(ExcelColumnMatcher.validateCellText(dateColumn, "2026/1/1"));
        assertNotNull(ExcelColumnMatcher.validateCellText(dateColumn, "2026-13-01"));
        assertNotNull(ExcelColumnMatcher.validateCellText(dateColumn, "2026-1-2"));
    }

    @Test
    void validateShouldCheckCheckboxCellText()
    {
        NoteColumn checkboxColumn = column(3L, "勾选", 7L);

        assertNull(ExcelColumnMatcher.validateCellText(checkboxColumn, "true"));
        assertNull(ExcelColumnMatcher.validateCellText(checkboxColumn, "false"));

        assertNotNull(ExcelColumnMatcher.validateCellText(checkboxColumn, "是"));
        assertNotNull(ExcelColumnMatcher.validateCellText(checkboxColumn, "TRUE"));
    }

    @Test
    void validateShouldPassThroughNonValidatedTypes()
    {
        // 文本/单选/关联列不在本单元做显示值类型校验（选项匹配归 U4 预检）
        assertNull(ExcelColumnMatcher.validateCellText(column(1L, "文本", 1L), "任意内容"));
        assertNull(ExcelColumnMatcher.validateCellText(column(2L, "单选", 3L), "不存在也没关系"));
        assertNull(ExcelColumnMatcher.validateCellText(column(3L, "关联", 21L), "任意记录名"));
        assertNull(ExcelColumnMatcher.validateCellText(null, "任意"));
    }

    // ==================== 测试辅助 ====================

    private static String repeat(String s, int count)
    {
        StringBuilder builder = new StringBuilder(count * s.length());
        for (int i = 0; i < count; i++)
        {
            builder.append(s);
        }
        return builder.toString();
    }

    /** 构造小工作簿字节（POI 真实 xlsx 链路验证用） */
    private byte[] workbookBytes(java.util.function.Consumer<XSSFWorkbook> customizer)
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

    private MockMultipartFile multipartFileOf(String originalFilename, byte[] bytes)
    {
        return new MockMultipartFile("file", originalFilename, XLSX_CONTENT_TYPE, bytes);
    }
}
