package com.ruoyi.system.domain.dto;

/**
 * 渲染器产出的单个导出文件（文件名 + 字节内容）。
 * <p>
 * 由 Excel/SQL 渲染器产出，交由打包器（U4）写入 zip。
 * 文件名未经 zip entry 净化——打包器负责 ZipEntryNameSanitizer 净化。
 *
 * @author ruoyi
 */
public class ExportFile
{
    /** 文件名（含扩展名，如 T1.sql、T_p1.sql、multitable-export.xlsx） */
    private String fileName;

    /** 文件字节内容 */
    private byte[] content;

    public ExportFile()
    {
    }

    public ExportFile(String fileName, byte[] content)
    {
        this.fileName = fileName;
        this.content = content;
    }

    public String getFileName()
    {
        return fileName;
    }

    public void setFileName(String fileName)
    {
        this.fileName = fileName;
    }

    public byte[] getContent()
    {
        return content;
    }

    public void setContent(byte[] content)
    {
        this.content = content;
    }
}
