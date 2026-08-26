package com.ruoyi.system.service.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ruoyi.common.exception.ServiceException;

/**
 * 导入 zip 内存解压器（KTD3）。
 * <p>
 * 接收 .zip 字节，在内存中解压（{@link ZipInputStream} + {@link ByteArrayOutputStream}，
 * 不写磁盘临时文件），输出内部全部 {@code .sql} 文件字节列表，按净化后入口名升序（R8）。
 * <p>
 * 防护：
 * <ul>
 *   <li>zip-bomb：累计解压字节超 100MB 或压缩比超 100:1 即拒绝</li>
 *   <li>zip-slip：入口名经 {@link ZipEntryNameSanitizer} 净化后再参与排序与日志，
 *       防恶意名注入（内存解压无落盘，此处净化保护下游排序与日志输出）</li>
 * </ul>
 * zip 内无 .sql 文件或 zip 损坏抛 {@link ServiceException}（R25）。
 *
 * @author ruoyi
 */
public final class ZipImportExtractor
{
    private static final Logger log = LoggerFactory.getLogger(ZipImportExtractor.class);

    /** 解压后累计字节上限：100MB（zip-bomb 防护，KTD3） */
    private static final long MAX_TOTAL_UNCOMPRESSED = 100L * 1024 * 1024;

    /** 最大压缩比：100:1（zip-bomb 防护，KTD3） */
    private static final long MAX_COMPRESSION_RATIO = 100L;

    /** 流式读取 buffer 大小 */
    private static final int BUFFER_SIZE = 8192;

    private ZipImportExtractor()
    {
    }

    /**
     * 解压 zip 字节，返回内部全部 .sql 文件字节列表（按净化后入口名升序）。
     *
     * @param zipBytes zip 文件字节
     * @return .sql 文件字节列表，按入口名升序
     * @throws ServiceException zip 为空 / 损坏 / 无 .sql / 超 zip-bomb 防护上限
     */
    public static List<byte[]> extract(byte[] zipBytes)
    {
        if (zipBytes == null || zipBytes.length == 0)
        {
            throw new ServiceException("导入失败：zip 文件为空");
        }
        List<ExtractedFile> files = new ArrayList<>();
        long totalUncompressed = 0L;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes)))
        {
            int idx = 0;
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null)
            {
                try
                {
                    if (entry.isDirectory())
                    {
                        continue;
                    }
                    // zip-slip 防护：净化名仅用于排序与日志（无落盘，KTD3）
                    String safeName = ZipEntryNameSanitizer.sanitize(entry.getName(), idx);
                    idx++;
                    if (!entry.getName().endsWith(".sql"))
                    {
                        log.info("zip 导入：跳过非 .sql 入口 {}", safeName);
                        continue;
                    }
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int n;
                    while ((n = zis.read(buffer)) != -1)
                    {
                        totalUncompressed += n;
                        if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED)
                        {
                            throw new ServiceException("导入失败：zip 解压后总大小超过 100MB 上限");
                        }
                        out.write(buffer, 0, n);
                    }
                    // zip-bomb 压缩比检查（每入口完成后，分母为整个 zip 压缩输入）
                    if (totalUncompressed > zipBytes.length * MAX_COMPRESSION_RATIO)
                    {
                        throw new ServiceException("导入失败：zip 压缩比超过 100:1 上限");
                    }
                    files.add(new ExtractedFile(safeName, out.toByteArray()));
                }
                finally
                {
                    zis.closeEntry();
                }
            }
        }
        catch (ZipException e)
        {
            throw new ServiceException("导入失败：zip 文件损坏或格式无效");
        }
        catch (IOException e)
        {
            throw new ServiceException("导入失败：zip 读取失败：" + e.getMessage());
        }
        if (files.isEmpty())
        {
            throw new ServiceException("导入失败：zip 内未找到 .sql 文件");
        }
        files.sort(Comparator.comparing(f -> f.name));
        // F10：.zip 路径下 multipart 限制的是压缩字节，实际事务规模由 100MB 解压上限决定
        log.warn("zip 导入路径：解压 {} 个 SQL 文件共 {} 字节（上限 100MB），导入事务规模由此解压上限决定",
                files.size(), totalUncompressed);
        List<byte[]> result = new ArrayList<>(files.size());
        for (ExtractedFile f : files)
        {
            result.add(f.bytes);
        }
        return result;
    }

    /** 解压产物：净化名 + 内容字节 */
    private static final class ExtractedFile
    {
        final String name;
        final byte[] bytes;

        ExtractedFile(String name, byte[] bytes)
        {
            this.name = name;
            this.bytes = bytes;
        }
    }
}
