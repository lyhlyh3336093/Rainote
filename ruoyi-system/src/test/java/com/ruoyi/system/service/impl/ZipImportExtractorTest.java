package com.ruoyi.system.service.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import com.ruoyi.common.exception.ServiceException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ZipImportExtractor} 单元测试。
 * <p>
 * 覆盖 AE7 多 .sql 升序、单 .sql、非 .sql 跳过、空包拒绝、
 * zip-bomb 字节上限与压缩比上限、zip-slip 入口名净化（经排序行为断言）、损坏 zip。
 *
 * @author ruoyi
 */
class ZipImportExtractorTest
{
    /** 构造 zip 字节：names[i] 与 contents[i] 一一对应 */
    private static byte[] makeZip(String[] names, byte[][] contents) throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos))
        {
            for (int i = 0; i < names.length; i++)
            {
                zos.putNextEntry(new ZipEntry(names[i]));
                zos.write(contents[i]);
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    private static byte[] bytesOf(String s)
    {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void extract_multipleSqlFiles_sortedAscendingByName() throws IOException
    {
        // 写入顺序故意乱序（p3/p1/p2），验证输出按入口名升序（AE7）
        byte[] zip = makeZip(
                new String[] {"T_p3.sql", "T_p1.sql", "T_p2.sql"},
                new byte[][] {bytesOf("INSERT 3"), bytesOf("INSERT 1"), bytesOf("INSERT 2")});
        List<byte[]> result = ZipImportExtractor.extract(zip);
        assertEquals(3, result.size());
        assertEquals("INSERT 1", new String(result.get(0), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("INSERT 2", new String(result.get(1), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("INSERT 3", new String(result.get(2), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void extract_singleSqlFile_returnsContent() throws IOException
    {
        byte[] zip = makeZip(new String[] {"T_p1.sql"}, new byte[][] {bytesOf("INSERT INTO T VALUES (1)")});
        List<byte[]> result = ZipImportExtractor.extract(zip);
        assertEquals(1, result.size());
        assertEquals("INSERT INTO T VALUES (1)", new String(result.get(0), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void extract_nonSqlEntries_skipped() throws IOException
    {
        byte[] zip = makeZip(
                new String[] {"readme.txt", "notes.md", "T_p1.sql"},
                new byte[][] {bytesOf("text"), bytesOf("markdown"), bytesOf("INSERT 1")});
        List<byte[]> result = ZipImportExtractor.extract(zip);
        assertEquals(1, result.size());
        assertEquals("INSERT 1", new String(result.get(0), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void extract_noSqlFiles_throwsServiceException() throws IOException
    {
        byte[] zip = makeZip(
                new String[] {"readme.txt", "notes.md"},
                new byte[][] {bytesOf("text"), bytesOf("markdown")});
        ServiceException ex = assertThrows(ServiceException.class, () -> ZipImportExtractor.extract(zip));
        assertTrue(ex.getMessage().contains("未找到 .sql"));
    }

    @Test
    void extract_totalUncompressedOver100mb_throwsServiceException() throws IOException
    {
        // 101MB 高度可压缩数据：解压累计超 100MB 字节上限（先于压缩比检查触发）
        byte[] data = new byte[101 * 1024 * 1024];
        Arrays.fill(data, (byte) 'A');
        byte[] zip = makeZip(new String[] {"bomb.sql"}, new byte[][] {data});
        ServiceException ex = assertThrows(ServiceException.class, () -> ZipImportExtractor.extract(zip));
        assertTrue(ex.getMessage().contains("100MB"));
    }

    @Test
    void extract_compressionRatioOver100_throwsServiceException() throws IOException
    {
        // ~300KB 可压缩数据，zip 输入远小于 3KB：压缩比超 100:1（未触发 100MB 字节上限）
        byte[] data = new byte[300 * 1024];
        Arrays.fill(data, (byte) 'A');
        byte[] zip = makeZip(new String[] {"bomb.sql"}, new byte[][] {data});
        assertTrue(zip.length * 100 < 300L * 1024, "测试前提：压缩比确实超过 100:1");
        ServiceException ex = assertThrows(ServiceException.class, () -> ZipImportExtractor.extract(zip));
        assertTrue(ex.getMessage().contains("压缩比"));
    }

    @Test
    void extract_zipSlipRelativePath_sanitizedNameUsedForSort() throws IOException
    {
        // 原名 "../etc/passwd.sql"（首字符 '.'）本应排在 "Zzz.sql" 前；
        // 净化后 "_etc_passwd.sql"（首字符 '_' > 'Z'）应排在后——输出顺序证明净化名参与排序
        byte[] zip = makeZip(
                new String[] {"../etc/passwd.sql", "Zzz.sql"},
                new byte[][] {bytesOf("INSERT P"), bytesOf("INSERT Z")});
        List<byte[]> result = ZipImportExtractor.extract(zip);
        assertEquals(2, result.size());
        assertEquals("INSERT Z", new String(result.get(0), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("INSERT P", new String(result.get(1), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void extract_zipSlipAbsolutePath_sanitizedNameUsedForSort() throws IOException
    {
        // 原名 "/etc/passwd.sql"（首字符 '/'）本应排在 "Bbb.sql" 前；
        // 净化后 "etc_passwd.sql"（首字符 'e' > 'B'）应排在后
        byte[] zip = makeZip(
                new String[] {"/etc/passwd.sql", "Bbb.sql"},
                new byte[][] {bytesOf("INSERT P"), bytesOf("INSERT B")});
        List<byte[]> result = ZipImportExtractor.extract(zip);
        assertEquals(2, result.size());
        assertEquals("INSERT B", new String(result.get(0), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("INSERT P", new String(result.get(1), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void extract_shardNamesWithDoubleDigits_naturalOrder() throws IOException
    {
        // 导出分片 _p1.._p10：纯字典序会把 T_p10 排在 T_p2 前，自然序按数值比较（R8）
        byte[] zip = makeZip(
                new String[] {"T_p10.sql", "T_p2.sql", "T_p1.sql"},
                new byte[][] {bytesOf("n10"), bytesOf("n2"), bytesOf("n1")});
        List<byte[]> result = ZipImportExtractor.extract(zip);
        assertEquals(3, result.size());
        assertEquals("n1", new String(result.get(0), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("n2", new String(result.get(1), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("n10", new String(result.get(2), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void extract_uppercaseSqlExtension_included() throws IOException
    {
        // .sql 后缀判断大小写不敏感，与控制器外层文件分流行为一致
        byte[] zip = makeZip(new String[] {"T_P1.SQL"}, new byte[][] {bytesOf("INSERT 1")});
        List<byte[]> result = ZipImportExtractor.extract(zip);
        assertEquals(1, result.size());
        assertEquals("INSERT 1", new String(result.get(0), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void extract_corruptedZip_throwsServiceException()
    {
        byte[] notZip = bytesOf("this is definitely not a zip file");
        ServiceException ex = assertThrows(ServiceException.class, () -> ZipImportExtractor.extract(notZip));
        assertTrue(ex.getMessage().contains("损坏") || ex.getMessage().contains("未找到 .sql"));
    }
}
