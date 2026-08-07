package com.ruoyi.system.service.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.system.domain.dto.ExportFile;
import com.ruoyi.system.domain.dto.ExportManifest;
import com.ruoyi.system.domain.dto.ExportMatrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 压缩包打包器：把渲染器产出的文件字节打包为 zip + manifest.json 索引文件（KTD6）。
 * <p>
 * 所有 ZipEntry name 经 {@link ZipEntryNameSanitizer} 净化，防止 zip slip（KTD10）。
 * manifest.json 承载文件名无法表达的元数据（KTD5）。
 * 返回 byte[] 供控制器下载。
 *
 * @author ruoyi
 */
@Component
public class NoteDwtableExportPackager
{
    private static final Logger log = LoggerFactory.getLogger(NoteDwtableExportPackager.class);

    /** manifest.json 文件名（固定，不经净化） */
    public static final String MANIFEST_FILENAME = "manifest.json";

    /**
     * manifest 中 zipBytes 字段的循环依赖最大迭代次数。
     * manifest.zipBytes = 整个 zip 字节数（含 manifest 自身），而 zip 又包含 manifest，
     * 字段值变化导致 manifest 字节数变化，进而影响 zip 总大小。
     * 实测 2-3 次即可收敛（数字位数稳定后字节数不再变化），设 5 次防御性上限。
     */
    private static final int ZIP_BYTES_CONVERGE_MAX_ITER = 5;

    /**
     * 把导出文件列表打包为 zip byte[]，含 manifest.json。
     * <p>
     * manifest.json 中的 zipBytes 字段记录整个 zip 的字节数（含 manifest 自身）。
     * 由于存在循环依赖（zip 大小取决于 manifest 内容，manifest 内容又包含 zip 大小），
     * 采用迭代打包直到字节数收敛：先用占位值 0 打包得到大小，再用真实大小重新打包。
     *
     * @param files    渲染器产出的导出文件列表
     * @param noteId   导出来源 noteId
     * @param format   导出格式（excel/sql）
     * @param matrices 原始矩阵列表（用于生成 manifest 表清单）
     * @return zip 字节
     */
    public byte[] packageExport(List<ExportFile> files, Long noteId, String format, List<ExportMatrix> matrices)
    {
        long prevZipBytes = 0;
        byte[] result = null;
        for (int iter = 0; iter < ZIP_BYTES_CONVERGE_MAX_ITER; iter++)
        {
            result = doPackage(files, noteId, format, matrices, prevZipBytes);
            if (result.length == prevZipBytes)
            {
                // 收敛：manifest 中 zipBytes 数字位数稳定，zip 总字节数不再变化
                break;
            }
            prevZipBytes = result.length;
        }
        log.debug("[PACKAGE] noteId={}, format={}, files={}, zipBytes={}, converged={}",
                noteId, format, files.size() + 1, result.length, result.length == prevZipBytes);
        return result;
    }

    /**
     * 执行一次实际打包（写入导出文件 + manifest.json）。
     *
     * @param files       渲染器产出的导出文件列表
     * @param noteId      导出来源 noteId
     * @param format      导出格式
     * @param matrices    原始矩阵列表
     * @param manifestZipBytes 写入 manifest 的 zipBytes 占位值（迭代用）
     * @return 本次打包产出的 zip 字节
     */
    private byte[] doPackage(List<ExportFile> files, Long noteId, String format,
            List<ExportMatrix> matrices, long manifestZipBytes)
    {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos))
        {
            // 写入导出文件（entry name 经 ZipEntryNameSanitizer 净化）
            for (int i = 0; i < files.size(); i++)
            {
                ExportFile file = files.get(i);
                String safeName = ZipEntryNameSanitizer.sanitize(file.getFileName(), i);
                ZipEntry entry = new ZipEntry(safeName);
                zos.putNextEntry(entry);
                if (file.getContent() != null)
                {
                    zos.write(file.getContent());
                }
                zos.closeEntry();
            }

            // 生成 manifest.json（zipBytes 用传入的占位值，迭代收敛后即为真实值）
            ExportManifest manifest = buildManifest(noteId, format, matrices, manifestZipBytes);
            String manifestJson = JSON.toJSONString(manifest);
            ZipEntry manifestEntry = new ZipEntry(MANIFEST_FILENAME);
            zos.putNextEntry(manifestEntry);
            zos.write(manifestJson.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.finish();
            return baos.toByteArray();
        }
        catch (IOException e)
        {
            throw new RuntimeException("zip 打包失败", e);
        }
    }

    /**
     * 构建 manifest 元数据。
     *
     * @param noteId      导出来源 noteId
     * @param format      导出格式
     * @param matrices    原始矩阵列表
     * @param zipBytes    整个 zip 字节数（迭代收敛后为真实值，首次为 0 占位）
     */
    private ExportManifest buildManifest(Long noteId, String format, List<ExportMatrix> matrices, long zipBytes)
    {
        ExportManifest manifest = new ExportManifest();
        manifest.setNoteId(noteId);
        manifest.setFormat(format);
        manifest.setGeneratedAt(Instant.now().toString());
        manifest.setZipBytes(zipBytes);

        if (matrices != null)
        {
            for (ExportMatrix matrix : matrices)
            {
                int rows = matrix.getRows() != null ? matrix.getRows().size() : 0;
                // 计算分片数：超过 SHARD_SIZE 则分片
                int shardCount = rows <= NoteDwtableSqlRenderer.SHARD_SIZE ? 1
                        : (rows + NoteDwtableSqlRenderer.SHARD_SIZE - 1) / NoteDwtableSqlRenderer.SHARD_SIZE;
                manifest.getTables().add(new ExportManifest.ManifestTable(
                        matrix.getTableName(), rows, shardCount));
            }
        }
        return manifest;
    }
}
