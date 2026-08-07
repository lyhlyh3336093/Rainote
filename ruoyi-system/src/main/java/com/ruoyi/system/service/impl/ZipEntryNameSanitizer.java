package com.ruoyi.system.service.impl;

/**
 * zip entry name 净化工具，防止 zip slip 路径穿越（KTD10）。
 * <p>
 * 导出物文件名由 NoteDwtable.name / NoteColumn.name 派生，用户可控。
 * 若含 {@code ../} 或绝对路径前缀，解压端可能写到 zip 根目录之外。
 * <p>
 * 净化规则：
 * <ul>
 *   <li>剥离前导 {@code /} 和 {@code \}（防绝对路径）</li>
 *   <li>按路径分隔符拆分后，替换 {@code ..} 段为 {@code _}（防 {@code ../} 穿越）</li>
 *   <li>替换路径分隔符 {@code /} 和 {@code \} 为 {@code _}（本导出物为扁平结构）</li>
 *   <li>整体长度限制 255 字符</li>
 *   <li>净化失败回退到 {@code entry_{idx}}</li>
 * </ul>
 *
 * @author ruoyi
 */
public final class ZipEntryNameSanitizer
{
    private ZipEntryNameSanitizer()
    {
    }

    /** zip entry name 最大长度 */
    private static final int MAX_LENGTH = 255;

    /**
     * 净化 zip entry name，防止路径穿越。
     *
     * @param raw 原始文件名（可能含路径穿越向量）
     * @param idx 文件序号（净化失败时用于回退名）
     * @return 安全的 entry name
     */
    public static String sanitize(String raw, int idx)
    {
        if (raw == null || raw.isEmpty())
        {
            return "entry_" + idx;
        }
        try
        {
            String name = raw;
            // 剥离前导 / 和 \（防绝对路径）
            while (name.startsWith("/") || name.startsWith("\\"))
            {
                name = name.substring(1);
            }
            // 按 / 和 \ 拆分，替换 .. 段为 _，再用 _ 连接
            String[] parts = name.split("[/\\\\]");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++)
            {
                if (i > 0)
                {
                    sb.append("_");
                }
                String part = parts[i];
                if ("..".equals(part) || ".".equals(part))
                {
                    sb.append("_");
                }
                else
                {
                    sb.append(part);
                }
            }
            String result = sb.toString();
            // 替换 Windows 盘符冒号等危险字符
            result = result.replace(":", "_");
            // 整体长度限制
            if (result.length() > MAX_LENGTH)
            {
                // 保留扩展名
                int dotIdx = result.lastIndexOf('.');
                if (dotIdx > 0 && result.length() - dotIdx <= 10)
                {
                    String ext = result.substring(dotIdx);
                    result = result.substring(0, MAX_LENGTH - ext.length()) + ext;
                }
                else
                {
                    result = result.substring(0, MAX_LENGTH);
                }
            }
            // 确保非空
            if (result.isEmpty() || result.equals("_"))
            {
                result = "entry_" + idx;
            }
            return result;
        }
        catch (Exception e)
        {
            return "entry_" + idx;
        }
    }
}
