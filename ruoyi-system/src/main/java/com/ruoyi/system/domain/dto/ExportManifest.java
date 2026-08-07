package com.ruoyi.system.domain.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 导出物索引文件（manifest.json）的数据结构（KTD5）。
 * <p>
 * 承载文件名无法表达的信息：导出来源（noteId）、格式、数据表清单与各自记录数/分片数、
 * 生成时间戳、schema 版本。分片关系与执行顺序由文件名编码，manifest 为人工/编排参考。
 *
 * @author ruoyi
 */
public class ExportManifest
{
    /** 导出来源 noteId */
    private Long noteId;

    /** 导出格式：excel / sql */
    private String format;

    /** 数据表清单 */
    private List<ManifestTable> tables = new ArrayList<>();

    /** 生成时间戳（ISO-8601） */
    private String generatedAt;

    /** manifest schema 版本 */
    private String schemaVersion = "1.0";

    /** 整个导出物 zip 字节大小 */
    private long zipBytes;

    /**
     * manifest 中单张数据表的元数据。
     */
    public static class ManifestTable
    {
        private String name;
        private int records;
        private int shardCount;

        public ManifestTable()
        {
        }

        public ManifestTable(String name, int records, int shardCount)
        {
            this.name = name;
            this.records = records;
            this.shardCount = shardCount;
        }

        public String getName()
        {
            return name;
        }

        public void setName(String name)
        {
            this.name = name;
        }

        public int getRecords()
        {
            return records;
        }

        public void setRecords(int records)
        {
            this.records = records;
        }

        public int getShardCount()
        {
            return shardCount;
        }

        public void setShardCount(int shardCount)
        {
            this.shardCount = shardCount;
        }
    }

    public Long getNoteId()
    {
        return noteId;
    }

    public void setNoteId(Long noteId)
    {
        this.noteId = noteId;
    }

    public String getFormat()
    {
        return format;
    }

    public void setFormat(String format)
    {
        this.format = format;
    }

    public List<ManifestTable> getTables()
    {
        return tables;
    }

    public void setTables(List<ManifestTable> tables)
    {
        this.tables = tables;
    }

    public String getGeneratedAt()
    {
        return generatedAt;
    }

    public void setGeneratedAt(String generatedAt)
    {
        this.generatedAt = generatedAt;
    }

    public String getSchemaVersion()
    {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion)
    {
        this.schemaVersion = schemaVersion;
    }

    public long getZipBytes()
    {
        return zipBytes;
    }

    public void setZipBytes(long zipBytes)
    {
        this.zipBytes = zipBytes;
    }
}
