package com.ruoyi.system.service.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.common.exception.ServiceException;

/**
 * 不可信 Excel 工作簿安全读取工具（U1，R3/R4）。
 * <p>
 * 职责限定为纯解析与资源边界，不承载 sheet 选定、列映射等导入域策略（归后续单元）：
 * <ul>
 * <li>入口显式 10MB 文件大小快速失败（R3，不依赖容器 multipart 配置隐式兜底）；</li>
 * <li>zip 解压资源边界三参数显式声明（R4）：minInflateRatio 0.01、maxEntrySize 64MB、maxTextSize 10MB；</li>
 * <li>MultipartFile 落盘为系统临时目录下不可预测名的临时文件（originalFilename 绝不参与路径拼接，
 * 防目录穿越），{@code WorkbookFactory.create} 只读模式打开，try-finally 删除临时文件（含异常路径）；</li>
 * <li>行/列数上限检查（R3/R4）：单 sheet 行数超 {@link #MAX_ROWS}、单行列数超 {@link #MAX_COLS}、
 *     单 sheet 单元格总数超 {@link #MAX_TOTAL_CELLS}（DOM 全量加载的内存上界，含表头行）拒绝；</li>
 * <li>全空数据行（有行无值：样式空行 / Del 清除内容行）跳过，保留行的行号为 Excel 1-based
 *     物理行号（与 Excel UI 一致，供预检缺参行号提示）；</li>
 * <li>单元格值统一经 {@link DataFormatter} 取显示文本（KTD9，与导出显示值对称）。</li>
 * </ul>
 * <p>
 * {@code WorkbookFactory.create} 为 DOM 全量加载（创建时全量物化，"惰性"仅指返回结构的遍历）。
 * 上限触发与解析失败统一抛 {@link ServiceException} 中文消息，原始异常经 cause 保留。
 *
 * @author ruoyi
 */
public final class ExcelWorkbookReader
{
    private static final Logger log = LoggerFactory.getLogger(ExcelWorkbookReader.class);

    /** 单个导入文件大小上限：10MB（R3） */
    public static final long MAX_FILE_SIZE = 10L * 1024 * 1024;

    /** 单工作表行数上限（含表头行，R3/R4） */
    public static final int MAX_ROWS = 50000;

    /** 单行单元格列数上限（含表头行，R4） */
    public static final int MAX_COLS = 256;

    /** 单工作表单元格总数上限（含表头行）：DOM 全量加载的内存上界 */
    public static final long MAX_TOTAL_CELLS = 1_000_000L;

    static
    {
        // zip 解压资源边界（R4）：显式声明（minInflateRatio 0.01 为 POI 默认值），防 zip bomb 与解压放大。
        // 注意：ZipSecureFile 的阈值为 JVM 全局静态配置，本类一经加载即对同 JVM 全部 POI 使用方生效
        //（含 ruoyi-common 的 ExcelUtil 等既有路径），不仅是本导入链路
        ZipSecureFile.setMinInflateRatio(0.01);
        ZipSecureFile.setMaxEntrySize(64L * 1024 * 1024);
        ZipSecureFile.setMaxTextSize(10_000_000L);
    }

    private ExcelWorkbookReader()
    {
    }

    /**
     * 解析 MultipartFile 为全部 sheet 的表头与数据行。
     *
     * @param file 上传的 Excel 文件
     * @return 每个 sheet 一个 {@link ParsedSheet}（sheet 名 + 首行表头 + 数据行），保持工作簿内顺序
     * @throws ServiceException 文件为空/超 10MB/超行列上限/解析失败（中文消息，cause 保留原始异常）
     */
    public static List<ParsedSheet> parse(MultipartFile file)
    {
        return parse(file, MAX_ROWS, MAX_COLS);
    }

    /**
     * 带行列上限参数的解析入口（默认入口的测试友好重载）。
     *
     * @param file 上传的 Excel 文件
     * @param maxRows 单 sheet 行数上限（含表头行）
     * @param maxCols 单行列数上限（含表头行）
     * @return 同 {@link #parse(MultipartFile)}
     * @throws ServiceException 同 {@link #parse(MultipartFile)}
     */
    static List<ParsedSheet> parse(MultipartFile file, int maxRows, int maxCols)
    {
        return parse(file, maxRows, maxCols, MAX_TOTAL_CELLS);
    }

    /**
     * 带行列与单元格总数上限参数的解析入口（测试友好重载，注入小阈值验证上限逻辑）。
     *
     * @param file 上传的 Excel 文件
     * @param maxRows 单 sheet 行数上限（含表头行）
     * @param maxCols 单行列数上限（含表头行）
     * @param maxTotalCells 单 sheet 单元格总数上限（含表头行）
     * @return 同 {@link #parse(MultipartFile)}
     * @throws ServiceException 同 {@link #parse(MultipartFile)}
     */
    static List<ParsedSheet> parse(MultipartFile file, int maxRows, int maxCols, long maxTotalCells)
    {
        if (file == null)
        {
            throw new ServiceException("导入文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE)
        {
            throw new ServiceException("导入文件大小超过上限 10MB");
        }

        Path tempFile = null;
        try
        {
            // 系统临时目录 + 不可预测文件名，originalFilename 不参与路径拼接
            tempFile = Files.createTempFile("excel-import-", ".xlsx");
            file.transferTo(tempFile.toFile());

            List<ParsedSheet> sheets;
            try (Workbook workbook = WorkbookFactory.create(tempFile.toFile(), null, true))
            {
                DataFormatter formatter = new DataFormatter(Locale.CHINA);
                sheets = new ArrayList<>(workbook.getNumberOfSheets());
                for (int i = 0; i < workbook.getNumberOfSheets(); i++)
                {
                    sheets.add(parseSheet(workbook.getSheetAt(i), formatter, maxRows, maxCols, maxTotalCells));
                }
            }
            return sheets;
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.warn("Excel 工作簿解析失败: {}", e.getMessage());
            ServiceException exception = new ServiceException("Excel 文件解析失败，文件可能已损坏或格式不受支持");
            exception.setDetailMessage(e.toString());
            exception.initCause(e);
            throw exception;
        }
        finally
        {
            if (tempFile != null)
            {
                try
                {
                    Files.deleteIfExists(tempFile);
                }
                catch (IOException e)
                {
                    log.warn("Excel 导入临时文件删除失败: {}", tempFile, e);
                }
            }
        }
    }

    /**
     * 解析单个 sheet：首行为表头，其余行为数据行；全空数据行（row==null 或有行无值）
     * 跳过；保留行携带 Excel 1-based 物理行号；逐行累计单元格总数（含表头行），超限拒绝。
     */
    private static ParsedSheet parseSheet(Sheet sheet, DataFormatter formatter, int maxRows, int maxCols,
            long maxTotalCells)
    {
        String sheetName = sheet.getSheetName();
        if (sheet.getPhysicalNumberOfRows() == 0)
        {
            return new ParsedSheet(sheetName, Collections.<String>emptyList(), Collections.<RowData>emptyList());
        }

        int totalRows = sheet.getLastRowNum() + 1;
        if (totalRows > maxRows)
        {
            throw new ServiceException("工作表[" + sheetName + "]共 " + totalRows + " 行，超过单表行数上限 " + maxRows + " 行");
        }

        List<String> headers = formatRow(sheet.getRow(0), formatter, sheetName, 1, maxCols);
        long totalCells = headers.size();
        if (totalCells > maxTotalCells)
        {
            throw new ServiceException("工作表[" + sheetName + "]单元格总数已达 " + totalCells
                    + "，超过单元格总数上限 " + maxTotalCells);
        }
        List<RowData> rows = new ArrayList<>(Math.max(0, totalRows - 1));
        for (int r = 1; r <= sheet.getLastRowNum(); r++)
        {
            Row row = sheet.getRow(r);
            if (row == null)
            {
                // 物理空行（该行从未写入）：无单元格可解析，跳过
                continue;
            }
            List<String> cells = formatRow(row, formatter, sheetName, r + 1, maxCols);
            totalCells += cells.size();
            if (totalCells > maxTotalCells)
            {
                throw new ServiceException("工作表[" + sheetName + "]单元格总数已达 " + totalCells
                        + "，超过单元格总数上限 " + maxTotalCells);
            }
            if (isAllEmpty(cells))
            {
                // 有行无值（样式空行 / Del 清除内容行）：跳过，不产出全空记录
                continue;
            }
            rows.add(new RowData(r + 1, cells));
        }
        return new ParsedSheet(sheetName, headers, rows);
    }

    /**
     * 判定一行单元格显示文本是否全空（null/空白 trim 后均算空）。
     */
    private static boolean isAllEmpty(List<String> cells)
    {
        for (String cell : cells)
        {
            if (cell != null && !cell.trim().isEmpty())
            {
                return false;
            }
        }
        return true;
    }

    /**
     * 把一行格式化为显示文本列表：单元格为 null（未写过）时取空串，其余经 {@link DataFormatter}。
     *
     * @param row 目标行（可为 null，返回空列表）
     * @param displayRowNumber 用户可读行号（1-based，Excel UI 行号），用于上限报错消息
     */
    private static List<String> formatRow(Row row, DataFormatter formatter, String sheetName, int displayRowNumber, int maxCols)
    {
        if (row == null)
        {
            return Collections.emptyList();
        }
        int colCount = row.getLastCellNum();
        if (colCount < 0)
        {
            colCount = 0;
        }
        if (colCount > maxCols)
        {
            throw new ServiceException("工作表[" + sheetName + "]第 " + displayRowNumber + " 行共 " + colCount
                    + " 列，超过单行列数上限 " + maxCols + " 列");
        }
        List<String> values = new ArrayList<>(colCount);
        for (int c = 0; c < colCount; c++)
        {
            Cell cell = row.getCell(c);
            values.add(cell == null ? "" : formatter.formatCellValue(cell));
        }
        return values;
    }

    /**
     * 单个 sheet 的纯数据解析结果（不持有任何 POI/文件资源，workbook 已在解析时关闭）。
     */
    public static final class ParsedSheet
    {
        /** sheet 名 */
        private final String sheetName;

        /** 首行表头（显示文本），空 sheet 为空列表 */
        private final List<String> headers;

        /** 数据行（第 2 行起，全空行已跳过），每行含 Excel 1-based 物理行号与显示文本列表 */
        private final List<RowData> rows;

        ParsedSheet(String sheetName, List<String> headers, List<RowData> rows)
        {
            this.sheetName = sheetName;
            this.headers = Collections.unmodifiableList(headers);
            this.rows = Collections.unmodifiableList(rows);
        }

        public String getSheetName()
        {
            return sheetName;
        }

        public List<String> getHeaders()
        {
            return headers;
        }

        public List<RowData> getRows()
        {
            return rows;
        }
    }

    /**
     * 单个数据行：Excel 1-based 物理行号（与 Excel UI 行号一致）+ 单元格显示文本列表。
     * <p>
     * 分片合并时各行保留其所属 sheet 自身的物理行号（不重排）。
     */
    public static final class RowData
    {
        /** Excel 1-based 物理行号（表头为第 1 行，首个数据行为第 2 行） */
        private final int rowNumber;

        /** 单元格显示文本列表（下标与表头列对齐，行短于表头数时为截断列表） */
        private final List<String> cells;

        RowData(int rowNumber, List<String> cells)
        {
            this.rowNumber = rowNumber;
            this.cells = Collections.unmodifiableList(new ArrayList<>(cells));
        }

        public int getRowNumber()
        {
            return rowNumber;
        }

        public List<String> getCells()
        {
            return cells;
        }
    }
}
