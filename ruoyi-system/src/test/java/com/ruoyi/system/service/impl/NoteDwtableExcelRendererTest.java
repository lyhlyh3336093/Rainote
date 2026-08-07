package com.ruoyi.system.service.impl;

import java.util.ArrayList;
import java.util.List;

import com.ruoyi.system.domain.dto.ExportColumn;
import com.ruoyi.system.domain.dto.ExportFile;
import com.ruoyi.system.domain.dto.ExportMatrix;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;

/**
 * {@link NoteDwtableExcelRenderer} 单元测试。
 * <p>
 * 覆盖 AE7 超限表分片（多 sheet）、空表 sheet、双列占位、大文本截断、多表同簿。
 *
 * @author ruoyi
 */
class NoteDwtableExcelRendererTest
{
    private final NoteDwtableExcelRenderer renderer = new NoteDwtableExcelRenderer();

    private ExportMatrix buildMatrix(String tableName, int rowCount, boolean withDualColumn)
    {
        ExportMatrix matrix = new ExportMatrix();
        matrix.setDwtableId(1L);
        matrix.setTableName(tableName);
        List<ExportColumn> columns = new ArrayList<>();
        columns.add(new ExportColumn(null, "record_id", null, false, true));
        columns.add(new ExportColumn(10L, "name", 1L, false, false));
        if (withDualColumn)
        {
            columns.add(new ExportColumn(11L, "关联", 21L, true, false));
        }
        matrix.setColumns(columns);
        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i <= rowCount; i++)
        {
            List<String> row = new ArrayList<>();
            row.add(String.valueOf(i));
            row.add("value" + i);
            if (withDualColumn)
            {
                row.add("100,101");     // ID 值
                row.add("张三,李四");     // 文本值
            }
            rows.add(row);
        }
        matrix.setRows(rows);
        return matrix;
    }

    @Test
    void render_normalTable_singleSheet() throws Exception
    {
        List<ExportMatrix> matrices = new ArrayList<>();
        matrices.add(buildMatrix("T1", 100, false));
        ExportFile file = renderer.render(matrices);
        assertEquals("multitable-export.xlsx", file.getFileName());
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(file.getContent())))
        {
            assertEquals(1, wb.getNumberOfSheets());
            Sheet sheet = wb.getSheetAt(0);
            // 列名行 + 100 数据行
            assertEquals(101, sheet.getLastRowNum() + 1);
        }
    }

    @Test
    void render_overLimitTable_multipleSheets() throws Exception
    {
        List<ExportMatrix> matrices = new ArrayList<>();
        matrices.add(buildMatrix("T", 5000, false));
        ExportFile file = renderer.render(matrices);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(file.getContent())))
        {
            // 5000 行 / 2000 = 3 个 sheet
            assertEquals(3, wb.getNumberOfSheets());
            // sheet 名含分片号
            assertNotNull(wb.getSheet("T_p1"));
            assertNotNull(wb.getSheet("T_p2"));
            assertNotNull(wb.getSheet("T_p3"));
            // 各 sheet 列名行一致
            assertEquals("record_id", wb.getSheet("T_p1").getRow(0).getCell(0).getStringCellValue());
            assertEquals("record_id", wb.getSheet("T_p2").getRow(0).getCell(0).getStringCellValue());
            // p1 应有 2001 行（含表头）
            assertEquals(2001, wb.getSheet("T_p1").getLastRowNum() + 1);
            // p3 应有 1001 行（含表头）
            assertEquals(1001, wb.getSheet("T_p3").getLastRowNum() + 1);
        }
    }

    @Test
    void render_emptyTable_onlyHeaderRow() throws Exception
    {
        List<ExportMatrix> matrices = new ArrayList<>();
        matrices.add(buildMatrix("empty", 0, false));
        ExportFile file = renderer.render(matrices);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(file.getContent())))
        {
            Sheet sheet = wb.getSheetAt(0);
            // 只有列名行
            assertEquals(1, sheet.getLastRowNum() + 1);
            assertEquals("record_id", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("name", sheet.getRow(0).getCell(1).getStringCellValue());
        }
    }

    @Test
    void render_dualColumn_twoColumnsInSheet() throws Exception
    {
        List<ExportMatrix> matrices = new ArrayList<>();
        matrices.add(buildMatrix("T1", 1, true));
        ExportFile file = renderer.render(matrices);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(file.getContent())))
        {
            Sheet sheet = wb.getSheetAt(0);
            // 列名行：record_id, name, 关联_ID, 关联_文本
            assertEquals("record_id", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("name", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("关联_ID", sheet.getRow(0).getCell(2).getStringCellValue());
            assertEquals("关联_文本", sheet.getRow(0).getCell(3).getStringCellValue());
            // 数据行
            assertEquals("100,101", sheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals("张三,李四", sheet.getRow(1).getCell(3).getStringCellValue());
        }
    }

    @Test
    void render_largeText_truncated() throws Exception
    {
        ExportMatrix matrix = new ExportMatrix();
        matrix.setDwtableId(1L);
        matrix.setTableName("T1");
        List<ExportColumn> columns = new ArrayList<>();
        columns.add(new ExportColumn(null, "record_id", null, false, true));
        columns.add(new ExportColumn(10L, "big_text", 1L, false, false));
        matrix.setColumns(columns);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        row.add("1");
        row.add(new String(new char[40000]).replace('\0', 'a')); // 超 32767
        rows.add(row);
        matrix.setRows(rows);

        List<ExportMatrix> matrices = new ArrayList<>();
        matrices.add(matrix);
        ExportFile file = renderer.render(matrices);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(file.getContent())))
        {
            Sheet sheet = wb.getSheetAt(0);
            String cellValue = sheet.getRow(1).getCell(1).getStringCellValue();
            // 应截断到 32767 字符
            assertEquals(NoteDwtableExcelRenderer.CELL_CHAR_LIMIT, cellValue.length());
            assertTrue(cellValue.endsWith(NoteDwtableExcelRenderer.TRUNCATED_MARKER));
        }
    }

    @Test
    void render_multipleTables_sameWorkbook() throws Exception
    {
        List<ExportMatrix> matrices = new ArrayList<>();
        matrices.add(buildMatrix("T1", 10, false));
        matrices.add(buildMatrix("T2", 20, false));
        ExportFile file = renderer.render(matrices);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(file.getContent())))
        {
            assertEquals(2, wb.getNumberOfSheets());
            assertNotNull(wb.getSheet("T1"));
            assertNotNull(wb.getSheet("T2"));
        }
    }
}
