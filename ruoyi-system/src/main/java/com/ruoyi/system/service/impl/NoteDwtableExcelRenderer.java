package com.ruoyi.system.service.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import com.ruoyi.system.domain.dto.ExportColumn;
import com.ruoyi.system.domain.dto.ExportFile;
import com.ruoyi.system.domain.dto.ExportMatrix;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Excel 渲染器：把多张 {@link ExportMatrix} 渲染为单个 Excel 工作簿字节。
 * <p>
 * 每表一 sheet；超 2000 行触发按行分片为多 sheet（R24）。
 * 用 SXSSFWorkbook（流式）控制超限表大 sheet 内存占用（KTD2）。
 * <p>
 * 大文本超 32767 字符截断并追加 [TRUNCATED] 标记（KTD4）。
 * sheet 名含非法字符（[]、: 等）按 Excel 通用规则替换。
 * <p>
 * 整个导出物为单个工作簿字节（R13），交由 U4 打包。
 *
 * @author ruoyi
 */
@Component
public class NoteDwtableExcelRenderer
{
    private static final Logger log = LoggerFactory.getLogger(NoteDwtableExcelRenderer.class);

    /** 分片行数阈值（R21/R24） */
    public static final int SHARD_SIZE = 2000;

    /** Excel 单元格字符上限（POI/Office Open XML 规范） */
    public static final int CELL_CHAR_LIMIT = 32767;

    /** 截断标记 */
    public static final String TRUNCATED_MARKER = "[TRUNCATED]";

    /** 整个导出物的工作簿文件名 */
    public static final String WORKBOOK_FILENAME = "multitable-export.xlsx";

    /**
     * 把多张数据表的矩阵渲染为单个 Excel 工作簿文件。
     *
     * @param matrices 多表矩阵列表
     * @return 单个 ExportFile（含整个工作簿字节）
     */
    public ExportFile render(List<ExportMatrix> matrices)
    {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100);
             ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            if (matrices != null)
            {
                for (ExportMatrix matrix : matrices)
                {
                    renderTable(workbook, matrix);
                }
            }
            workbook.write(baos);
            byte[] content = baos.toByteArray();
            // 清理临时文件
            workbook.dispose();
            log.debug("[EXCEL-RENDER] matrices={}, bytes={}", matrices != null ? matrices.size() : 0, content.length);
            return new ExportFile(WORKBOOK_FILENAME, content);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Excel 渲染失败", e);
        }
    }

    /**
     * 把单张数据表渲染为 sheet（超限表多 sheet 分片）。
     */
    private void renderTable(SXSSFWorkbook workbook, ExportMatrix matrix)
    {
        if (matrix == null)
        {
            return;
        }
        String tableName = matrix.getTableName() != null ? matrix.getTableName() : "table_" + matrix.getDwtableId();
        List<List<String>> rows = matrix.getRows();
        int totalRows = rows != null ? rows.size() : 0;

        if (totalRows <= SHARD_SIZE)
        {
            // 合格表：单 sheet
            String sheetName = sanitizeSheetName(tableName);
            Sheet sheet = workbook.createSheet(sheetName);
            renderSheet(sheet, matrix, 0, totalRows);
        }
        else
        {
            // 超限表：按行分片为多 sheet
            int shardCount = (totalRows + SHARD_SIZE - 1) / SHARD_SIZE;
            for (int s = 0; s < shardCount; s++)
            {
                int from = s * SHARD_SIZE;
                int to = Math.min(from + SHARD_SIZE, totalRows);
                String sheetName = sanitizeSheetName(tableName + "_p" + (s + 1));
                Sheet sheet = workbook.createSheet(sheetName);
                renderSheet(sheet, matrix, from, to);
            }
        }
    }

    /**
     * 渲染单个 sheet：列名行 + 数据行（rows[from..to) 范围）。
     */
    private void renderSheet(Sheet sheet, ExportMatrix matrix, int from, int to)
    {
        List<ExportColumn> columns = matrix.getColumns();
        List<List<String>> rows = matrix.getRows();

        // 列名行
        Row headerRow = sheet.createRow(0);
        int colIdx = 0;
        for (ExportColumn col : columns)
        {
            if (col.isDualColumn())
            {
                // 双列：列名_ID + 列名_文本
                headerRow.createCell(colIdx++).setCellValue(col.getName() + "_ID");
                headerRow.createCell(colIdx++).setCellValue(col.getName() + "_文本");
            }
            else
            {
                headerRow.createCell(colIdx++).setCellValue(col.getName());
            }
        }

        // 数据行
        if (rows == null)
        {
            return;
        }
        int rowIdx = 1;
        for (int r = from; r < to; r++)
        {
            List<String> rowData = rows.get(r);
            Row row = sheet.createRow(rowIdx++);
            int dataColIdx = 0;
            // 数据索引独立推进：普通列消费 1 个单元格，双列消费 2 个（ID + 文本）——
            // 列索引与数据索引在首个双列后即错位，复用列索引会跳过双列后的普通列
            int dataIdx = 0;
            for (int c = 0; c < columns.size(); c++)
            {
                ExportColumn col = columns.get(c);
                if (col.isDualColumn())
                {
                    // 双列在行数据中占两个相邻位置
                    String idValue = dataIdx < rowData.size() ? rowData.get(dataIdx) : "";
                    String textValue = dataIdx + 1 < rowData.size() ? rowData.get(dataIdx + 1) : "";
                    dataIdx += 2;
                    row.createCell(dataColIdx++).setCellValue(truncateIfNeeded(idValue));
                    row.createCell(dataColIdx++).setCellValue(truncateIfNeeded(textValue));
                }
                else
                {
                    String value = dataIdx < rowData.size() ? rowData.get(dataIdx) : "";
                    dataIdx++;
                    row.createCell(dataColIdx++).setCellValue(truncateIfNeeded(value));
                }
            }
        }
    }

    /**
     * 大文本超 32767 字符截断并追加 [TRUNCATED] 标记（KTD4）。
     */
    private String truncateIfNeeded(String value)
    {
        if (value == null)
        {
            return "";
        }
        if (value.length() <= CELL_CHAR_LIMIT)
        {
            return value;
        }
        // 截断到 32767 - [TRUNCATED].length，再追加标记
        int markerLen = TRUNCATED_MARKER.length();
        return value.substring(0, CELL_CHAR_LIMIT - markerLen) + TRUNCATED_MARKER;
    }

    /**
     * 净化 sheet 名：Excel sheet 名禁用字符 []:*?/\ 替换为 _，长度限制 31。
     */
    private String sanitizeSheetName(String name)
    {
        if (name == null || name.isEmpty())
        {
            return "Sheet";
        }
        String sanitized = name.replaceAll("[\\[\\]:*?/\\\\]", "_");
        if (sanitized.length() > 31)
        {
            sanitized = sanitized.substring(0, 31);
        }
        return sanitized.isEmpty() ? "Sheet" : sanitized;
    }
}
