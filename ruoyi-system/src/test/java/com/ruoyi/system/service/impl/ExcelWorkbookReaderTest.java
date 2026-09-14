package com.ruoyi.system.service.impl;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.service.impl.ExcelWorkbookReader.ParsedSheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ExcelWorkbookReader} 单元测试。
 * <p>
 * 覆盖 U1 场景：多 sheet 表头与数据行读取（含 originalFilename 不参与路径拼接）、
 * 表头 DataFormatter 显示值、10MB 文件大小快速失败、行数/列数上限拒绝、
 * zip bomb 压缩比拒绝、临时文件成功/失败路径清理。
 *
 * @author ruoyi
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExcelWorkbookReaderTest
{
    private static final String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Mock
    private MultipartFile multipartFile;

    /** 构造小工作簿（含注入阈值用例），返回工作簿字节 */
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

    private MockMultipartFile multipartFileOf(String originalFilename, byte[] bytes)
    {
        return new MockMultipartFile("file", originalFilename, XLSX_CONTENT_TYPE, bytes);
    }

    /**
     * spy 真实 MockMultipartFile，捕获 transferTo 落盘的临时文件路径（供清理断言），其余行为不变。
     */
    private MultipartFile spyCapturingTempPath(byte[] bytes, AtomicReference<Path> tempPath) throws IOException
    {
        MockMultipartFile spyFile = spy(multipartFileOf("test.xlsx", bytes));
        doAnswer(invocation -> {
            tempPath.set(((File) invocation.getArgument(0)).toPath());
            return invocation.callRealMethod();
        }).when(spyFile).transferTo(any(File.class));
        return spyFile;
    }

    /** 构造 zip bomb：单条目 1MB 零字节（deflate 后约 1KB，压缩比约 0.1% < 1% 阈值） */
    private byte[] zipBombBytes()
    {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out))
        {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write(new byte[1024 * 1024]);
            zip.closeEntry();
            return out.toByteArray();
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void parseShouldReturnAllSheetsWithHeadersAndRows()
    {
        byte[] bytes = workbookBytes(workbook -> {
            XSSFSheet first = workbook.createSheet("学生表");
            first.createRow(0).createCell(0).setCellValue("姓名");
            first.getRow(0).createCell(1).setCellValue("年龄");
            first.createRow(1).createCell(0).setCellValue("张三");
            first.getRow(1).createCell(1).setCellValue(20);
            XSSFSheet second = workbook.createSheet("成绩表");
            second.createRow(0).createCell(0).setCellValue("科目");
            second.getRow(0).createCell(1).setCellValue("分数");
            second.createRow(1).createCell(0).setCellValue("语文");
            second.getRow(1).createCell(1).setCellValue(99.5);
        });
        // originalFilename 含路径分隔符：不应参与临时文件路径拼接（防目录穿越回归）
        MultipartFile file = multipartFileOf("..\\..\\evil\\学生表.xlsx", bytes);

        List<ParsedSheet> sheets = ExcelWorkbookReader.parse(file);

        assertEquals(2, sheets.size());
        ParsedSheet first = sheets.get(0);
        assertEquals("学生表", first.getSheetName());
        assertEquals(Arrays.asList("姓名", "年龄"), first.getHeaders());
        assertEquals(1, first.getRows().size());
        assertEquals(Arrays.asList("张三", "20"), first.getRows().get(0));
        ParsedSheet second = sheets.get(1);
        assertEquals("成绩表", second.getSheetName());
        assertEquals(Arrays.asList("科目", "分数"), second.getHeaders());
        assertEquals(Arrays.asList("语文", "99.5"), second.getRows().get(0));
    }

    @Test
    void parseShouldFormatNumericHeaderAsDisplayedText()
    {
        byte[] bytes = workbookBytes(workbook -> {
            XSSFSheet sheet = workbook.createSheet("Sheet1");
            sheet.createRow(0).createCell(0).setCellValue("列一");
            sheet.getRow(0).createCell(1).setCellValue(123);
            sheet.createRow(1).createCell(0).setCellValue("值");
        });

        List<ParsedSheet> sheets = ExcelWorkbookReader.parse(multipartFileOf("headers.xlsx", bytes));

        assertEquals(Arrays.asList("列一", "123"), sheets.get(0).getHeaders());
        assertEquals(Arrays.asList("值"), sheets.get(0).getRows().get(0));
    }

    @Test
    void parseShouldRejectFileExceedingSizeLimit() throws IOException
    {
        when(multipartFile.getSize()).thenReturn(ExcelWorkbookReader.MAX_FILE_SIZE + 1);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> ExcelWorkbookReader.parse(multipartFile));

        assertTrue(exception.getMessage().contains("10MB"));
        // 快速失败：不应落盘临时文件
        verify(multipartFile, never()).transferTo(any(File.class));
    }

    @Test
    void parseShouldRejectRowLimitExceeded()
    {
        byte[] bytes = workbookBytes(workbook -> {
            XSSFSheet sheet = workbook.createSheet("小表");
            sheet.createRow(0).createCell(0).setCellValue("表头");
            sheet.createRow(1).createCell(0).setCellValue("行1");
            sheet.createRow(2).createCell(0).setCellValue("行2");
        });

        // 小文件 + 注入小阈值（3 行 > 2）验证行数上限逻辑
        ServiceException exception = assertThrows(ServiceException.class,
                () -> ExcelWorkbookReader.parse(multipartFileOf("rows.xlsx", bytes), 2, ExcelWorkbookReader.MAX_COLS));

        assertTrue(exception.getMessage().contains("行数上限"));
        assertTrue(exception.getMessage().contains("小表"));
    }

    @Test
    void parseShouldRejectColumnLimitExceeded()
    {
        byte[] bytes = workbookBytes(workbook -> {
            XSSFSheet sheet = workbook.createSheet("宽表");
            org.apache.poi.ss.usermodel.Row row = sheet.createRow(0);
            for (int c = 0; c <= ExcelWorkbookReader.MAX_COLS; c++)
            {
                row.createCell(c).setCellValue("列" + c);
            }
        });

        ServiceException exception = assertThrows(ServiceException.class,
                () -> ExcelWorkbookReader.parse(multipartFileOf("cols.xlsx", bytes)));

        assertTrue(exception.getMessage().contains("列"));
        assertTrue(exception.getMessage().contains(String.valueOf(ExcelWorkbookReader.MAX_COLS + 1)));
    }

    @Test
    void parseShouldRejectZipBombContent()
    {
        MultipartFile file = multipartFileOf("bomb.xlsx", zipBombBytes());

        ServiceException exception = assertThrows(ServiceException.class, () -> ExcelWorkbookReader.parse(file));

        // 原始异常（zip bomb / 解压边界）经 cause 保留，且压缩比检测真实触发
        assertNotNull(exception.getCause());
        assertTrue(causeChainContains(exception, "Zip bomb"),
                "压缩比超限 zip 应被 ZipSecureFile 检测拒绝");
    }

    /** 遍历异常 cause 链（限深防循环），判断是否某层消息含关键字 */
    private static boolean causeChainContains(Throwable throwable, String keyword)
    {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 20; depth++)
        {
            if (current.getMessage() != null && current.getMessage().contains(keyword))
            {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @Test
    void parseShouldDeleteTempFileAfterSuccess() throws IOException
    {
        byte[] bytes = workbookBytes(workbook -> {
            XSSFSheet sheet = workbook.createSheet("Sheet1");
            sheet.createRow(0).createCell(0).setCellValue("表头");
            sheet.createRow(1).createCell(0).setCellValue("数据");
        });
        AtomicReference<Path> tempPath = new AtomicReference<>();
        MultipartFile file = spyCapturingTempPath(bytes, tempPath);

        ExcelWorkbookReader.parse(file);

        assertNotNull(tempPath.get());
        assertFalse(Files.exists(tempPath.get()));
    }

    @Test
    void parseShouldDeleteTempFileOnParseFailure() throws IOException
    {
        AtomicReference<Path> tempPath = new AtomicReference<>();
        MultipartFile file = spyCapturingTempPath("not an excel".getBytes(StandardCharsets.UTF_8), tempPath);

        assertThrows(ServiceException.class, () -> ExcelWorkbookReader.parse(file));

        assertNotNull(tempPath.get());
        assertFalse(Files.exists(tempPath.get()));
    }
}
