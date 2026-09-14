package com.ruoyi.system.domain.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 多维表格 Excel 导入预检结果（U4，阶段一，R9/R11）。
 * <p>
 * 预检不写库，本 DTO 随端点响应回传前端，供补参弹框（U6）与阶段二文件同一性比对（KTD5）消费：
 * <ul>
 *   <li>缺参项（{@link MissingParam}）：缺列/缺值/类型违规/关联缺列/关联未命中；</li>
 *   <li>歧义项（{@link AmbiguityItem}）：同名多记录，预选 sort 靠前；</li>
 *   <li>默认值填充项（{@link DefaultValueFill}）：有默认值的列不进缺参弹框（R10 填充优先级）；</li>
 *   <li>新选项创建项（{@link NewOption}）：单选/多选未命中选项自动追加（R19）；</li>
 *   <li>对称写入跨表影响（{@link SymmetricWriteImpact}）：21 双向关联列将修改的被关联表记录数；</li>
 *   <li>忽略 sheet/跳过表头提示（R2/R5/R6）；</li>
 *   <li>关联列选择器候选（{@link RelationCandidates}）：已过 R23 归属校验的内嵌数据源（KTD7）；</li>
 *   <li>文件指纹（{@link FileFingerprint}）：size + md5，阶段二重新上传文件时比对（KTD5）。</li>
 * </ul>
 * 行号口径：Excel 行号（表头为第 1 行，数据行从第 2 行起）。
 *
 * @author ruoyi
 */
public class ExcelImportPrecheckResult
{
    /** 缺参类型：基础列缺列（表头未映射到且无默认值，全部行受影响） */
    public static final String KIND_MISSING_COLUMN = "缺列";

    /** 缺参类型：基础列缺值（表头命中但单元格空且无默认值，携带行号集） */
    public static final String KIND_MISSING_VALUE = "缺值";

    /** 缺参类型：单元格类型校验违规（数字/日期/复选框，走补值链路，附违规原因） */
    public static final String KIND_TYPE_VIOLATION = "类型违规";

    /** 缺参类型：关联列缺列（表头未映射到；关联列不支持默认值） */
    public static final String KIND_LINK_MISSING_COLUMN = "关联缺列";

    /** 缺参类型：关联列未命中（单元格文本未匹配到被关联表记录名，携带未命中名清单） */
    public static final String KIND_LINK_MISS = "关联未命中";

    /** 文件指纹（size + md5），供阶段二导入前比对文件同一性（KTD5） */
    private FileFingerprint fileFingerprint = new FileFingerprint();

    /** 缺参项清单（R10/R11 三情形 + 类型违规） */
    private List<MissingParam> missingParams = new ArrayList<>();

    /** 歧义项清单：匹配中名字对应多条同名记录（列 + 名 + 候选数 + 预选记录 id，R14） */
    private List<AmbiguityItem> ambiguityItems = new ArrayList<>();

    /** 默认值填充项清单：列名 + 默认值 + 影响行数（有默认值的列不进缺参弹框，R10） */
    private List<DefaultValueFill> defaultValueFills = new ArrayList<>();

    /** 新选项创建项清单：单选/多选未命中选项自动追加（列 + 选项文本 + 出现次数，R19） */
    private List<NewOption> newOptions = new ArrayList<>();

    /** 对称写入跨表影响清单：每个 21 双向关联列将修改的被关联表记录数（表名 + 记录数，R11） */
    private List<SymmetricWriteImpact> symmetricWriteImpact = new ArrayList<>();

    /** 忽略的 sheet 名清单（多 sheet 选定时未使用的 sheet，R2） */
    private List<String> ignoredSheets = new ArrayList<>();

    /** 跳过的表头清单（表头名 + 跳过原因：未匹配/隐藏/排除类型/双列 ID 忽略/record_id 忽略等，R5/R6） */
    private List<SkippedHeader> skippedHeaders = new ArrayList<>();

    /** 关联列选择器候选清单：每个映射到的 18/21 关联列附被关联表记录候选首批（KTD7，已过 R23 归属校验） */
    private List<RelationCandidates> relationCandidates = new ArrayList<>();

    /** 是否存在阻断性问题（缺参或歧义非空 → 前端打开补参弹框，R13 分流） */
    private boolean hasBlockingIssues;

    public FileFingerprint getFileFingerprint()
    {
        return fileFingerprint;
    }

    public void setFileFingerprint(FileFingerprint fileFingerprint)
    {
        this.fileFingerprint = fileFingerprint;
    }

    public List<MissingParam> getMissingParams()
    {
        return missingParams;
    }

    public void setMissingParams(List<MissingParam> missingParams)
    {
        this.missingParams = missingParams;
    }

    public List<AmbiguityItem> getAmbiguityItems()
    {
        return ambiguityItems;
    }

    public void setAmbiguityItems(List<AmbiguityItem> ambiguityItems)
    {
        this.ambiguityItems = ambiguityItems;
    }

    public List<DefaultValueFill> getDefaultValueFills()
    {
        return defaultValueFills;
    }

    public void setDefaultValueFills(List<DefaultValueFill> defaultValueFills)
    {
        this.defaultValueFills = defaultValueFills;
    }

    public List<NewOption> getNewOptions()
    {
        return newOptions;
    }

    public void setNewOptions(List<NewOption> newOptions)
    {
        this.newOptions = newOptions;
    }

    public List<SymmetricWriteImpact> getSymmetricWriteImpact()
    {
        return symmetricWriteImpact;
    }

    public void setSymmetricWriteImpact(List<SymmetricWriteImpact> symmetricWriteImpact)
    {
        this.symmetricWriteImpact = symmetricWriteImpact;
    }

    public List<String> getIgnoredSheets()
    {
        return ignoredSheets;
    }

    public void setIgnoredSheets(List<String> ignoredSheets)
    {
        this.ignoredSheets = ignoredSheets;
    }

    public List<SkippedHeader> getSkippedHeaders()
    {
        return skippedHeaders;
    }

    public void setSkippedHeaders(List<SkippedHeader> skippedHeaders)
    {
        this.skippedHeaders = skippedHeaders;
    }

    public List<RelationCandidates> getRelationCandidates()
    {
        return relationCandidates;
    }

    public void setRelationCandidates(List<RelationCandidates> relationCandidates)
    {
        this.relationCandidates = relationCandidates;
    }

    public boolean isHasBlockingIssues()
    {
        return hasBlockingIssues;
    }

    public void setHasBlockingIssues(boolean hasBlockingIssues)
    {
        this.hasBlockingIssues = hasBlockingIssues;
    }

    /**
     * 文件指纹（KTD5）：预检文件的 size 与内容 md5，阶段二携带补参重新上传时
     * 与预检响应中的指纹比对，不一致即拒绝（防预检 A 文件、导入 B 文件）。
     */
    public static class FileFingerprint
    {
        /** 文件字节数 */
        private long size;

        /** 文件内容 MD5（32 位小写十六进制） */
        private String md5;

        public long getSize()
        {
            return size;
        }

        public void setSize(long size)
        {
            this.size = size;
        }

        public String getMd5()
        {
            return md5;
        }

        public void setMd5(String md5)
        {
            this.md5 = md5;
        }
    }

    /**
     * 缺参项：一个列一条（类型违规与缺值分列同类，按 kind 区分）。
     * <ul>
     *   <li>缺列/关联缺列：填 totalRows（全部行），rowNumbers/missNames 为 null；</li>
     *   <li>缺值/类型违规：填 rowNumbers（受影响行号集），totalRows 为 null；类型违规附 reason；</li>
     *   <li>关联未命中：填 missNames（未命中名清单，每名带行号集，供 U6 逐值选择器）。</li>
     * </ul>
     */
    public static class MissingParam
    {
        /** 列 id */
        private Long columnId;

        /** 列名 */
        private String columnName;

        /** 列类型（1 文本/2 数字/3 单选/4 多选/5 日期/7 复选框/18 单向关联/21 双向关联） */
        private Long columnType;

        /** 缺参类型：缺列/缺值/类型违规/关联缺列/关联未命中 */
        private String kind;

        /** 受影响行号集（Excel 行号，缺值/类型违规时填写） */
        private List<Integer> rowNumbers;

        /** 受影响总行数（缺列/关联缺列时填写，= 全部数据行数） */
        private Integer totalRows;

        /** 违规原因（类型违规时填写，中文描述） */
        private String reason;

        /** 未命中名清单（关联未命中时填写，每名带行号集） */
        private List<MissName> missNames;

        public Long getColumnId()
        {
            return columnId;
        }

        public void setColumnId(Long columnId)
        {
            this.columnId = columnId;
        }

        public String getColumnName()
        {
            return columnName;
        }

        public void setColumnName(String columnName)
        {
            this.columnName = columnName;
        }

        public Long getColumnType()
        {
            return columnType;
        }

        public void setColumnType(Long columnType)
        {
            this.columnType = columnType;
        }

        public String getKind()
        {
            return kind;
        }

        public void setKind(String kind)
        {
            this.kind = kind;
        }

        public List<Integer> getRowNumbers()
        {
            return rowNumbers;
        }

        public void setRowNumbers(List<Integer> rowNumbers)
        {
            this.rowNumbers = rowNumbers;
        }

        public Integer getTotalRows()
        {
            return totalRows;
        }

        public void setTotalRows(Integer totalRows)
        {
            this.totalRows = totalRows;
        }

        public String getReason()
        {
            return reason;
        }

        public void setReason(String reason)
        {
            this.reason = reason;
        }

        public List<MissName> getMissNames()
        {
            return missNames;
        }

        public void setMissNames(List<MissName> missNames)
        {
            this.missNames = missNames;
        }
    }

    /**
     * 关联列未命中名：名字 + 出现行号集（KTD5/KTD7 的补参键口径：列名 + 未命中名）。
     */
    public static class MissName
    {
        /** 未命中的记录名（单元格文本整串或逗号拆分后的名字） */
        private String name;

        /** 该名字出现的行号集（Excel 行号） */
        private List<Integer> rowNumbers;

        public String getName()
        {
            return name;
        }

        public void setName(String name)
        {
            this.name = name;
        }

        public List<Integer> getRowNumbers()
        {
            return rowNumbers;
        }

        public void setRowNumbers(List<Integer> rowNumbers)
        {
            this.rowNumbers = rowNumbers;
        }
    }

    /**
     * 歧义项：匹配中名字在被关联表对应多条同名记录（预选 sort 靠前，弹框可改选，R14/KTD7）。
     */
    public static class AmbiguityItem
    {
        /** 关联列 id */
        private Long columnId;

        /** 关联列名 */
        private String columnName;

        /** 歧义记录名 */
        private String name;

        /** 候选记录数（同名记录条数） */
        private int candidateCount;

        /** 预选记录 id（sort 最靠前的同名记录） */
        private Long preselectedRecordId;

        public Long getColumnId()
        {
            return columnId;
        }

        public void setColumnId(Long columnId)
        {
            this.columnId = columnId;
        }

        public String getColumnName()
        {
            return columnName;
        }

        public void setColumnName(String columnName)
        {
            this.columnName = columnName;
        }

        public String getName()
        {
            return name;
        }

        public void setName(String name)
        {
            this.name = name;
        }

        public int getCandidateCount()
        {
            return candidateCount;
        }

        public void setCandidateCount(int candidateCount)
        {
            this.candidateCount = candidateCount;
        }

        public Long getPreselectedRecordId()
        {
            return preselectedRecordId;
        }

        public void setPreselectedRecordId(Long preselectedRecordId)
        {
            this.preselectedRecordId = preselectedRecordId;
        }
    }

    /**
     * 默认值填充项：缺列或缺值处将以列默认值填充（R10：填充优先级默认值 > 弹框）。
     */
    public static class DefaultValueFill
    {
        /** 列 id */
        private Long columnId;

        /** 列名 */
        private String columnName;

        /** 默认值（显示值：单选/多选存选项文本，日期存 yyyy-MM-dd HH:mm:ss，复选框存 true/false） */
        private String defaultValue;

        /** 将填充的行数（缺列=全部行数；缺值=空单元格行数） */
        private int rowCount;

        public Long getColumnId()
        {
            return columnId;
        }

        public void setColumnId(Long columnId)
        {
            this.columnId = columnId;
        }

        public String getColumnName()
        {
            return columnName;
        }

        public void setColumnName(String columnName)
        {
            this.columnName = columnName;
        }

        public String getDefaultValue()
        {
            return defaultValue;
        }

        public void setDefaultValue(String defaultValue)
        {
            this.defaultValue = defaultValue;
        }

        public int getRowCount()
        {
            return rowCount;
        }

        public void setRowCount(int rowCount)
        {
            this.rowCount = rowCount;
        }
    }

    /**
     * 新选项创建项：单选/多选列的未命中选项文本，导入时自动追加到选项集（R19）。
     */
    public static class NewOption
    {
        /** 列 id */
        private Long columnId;

        /** 列名 */
        private String columnName;

        /** 未命中的选项文本（单选为整格文本，多选为逗号拆分后的单项） */
        private String optionText;

        /** 出现次数（该选项文本在本列各行的出现次数） */
        private int occurrences;

        public Long getColumnId()
        {
            return columnId;
        }

        public void setColumnId(Long columnId)
        {
            this.columnId = columnId;
        }

        public String getColumnName()
        {
            return columnName;
        }

        public void setColumnName(String columnName)
        {
            this.columnName = columnName;
        }

        public String getOptionText()
        {
            return optionText;
        }

        public void setOptionText(String optionText)
        {
            this.optionText = optionText;
        }

        public int getOccurrences()
        {
            return occurrences;
        }

        public void setOccurrences(int occurrences)
        {
            this.occurrences = occurrences;
        }
    }

    /**
     * 对称写入跨表影响项：一个 21 双向关联列一条，导入时将对称写回的被关联表与记录数（R11/R16）。
     */
    public static class SymmetricWriteImpact
    {
        /** 被关联表名 */
        private String tableName;

        /** 将修改的被关联表记录数（该列命中的 distinct 记录数，含歧义预选） */
        private int affectedRecordCount;

        public String getTableName()
        {
            return tableName;
        }

        public void setTableName(String tableName)
        {
            this.tableName = tableName;
        }

        public int getAffectedRecordCount()
        {
            return affectedRecordCount;
        }

        public void setAffectedRecordCount(int affectedRecordCount)
        {
            this.affectedRecordCount = affectedRecordCount;
        }
    }

    /**
     * 跳过的表头：表头名 + 跳过原因（未匹配/隐藏列/排除类型/双列源 id 忽略/record_id 源列忽略等）。
     */
    public static class SkippedHeader
    {
        /** 表头名（规整后显示文本） */
        private String headerName;

        /** 跳过原因（中文） */
        private String reason;

        public SkippedHeader()
        {
        }

        public SkippedHeader(String headerName, String reason)
        {
            this.headerName = headerName;
            this.reason = reason;
        }

        public String getHeaderName()
        {
            return headerName;
        }

        public void setHeaderName(String headerName)
        {
            this.headerName = headerName;
        }

        public String getReason()
        {
            return reason;
        }

        public void setReason(String reason)
        {
            this.reason = reason;
        }
    }

    /**
     * 关联列选择器候选：一个映射到的 18/21 关联列一条，内嵌被关联表记录候选首批
     * （按 sort 升序前 100 条，U6 逐值选择器的数据源，已过 R23 归属校验）。
     */
    public static class RelationCandidates
    {
        /** 关联列 id */
        private Long columnId;

        /** 候选记录清单（按 sort 升序前 100 条） */
        private List<RecordCandidate> candidates;

        public Long getColumnId()
        {
            return columnId;
        }

        public void setColumnId(Long columnId)
        {
            this.columnId = columnId;
        }

        public List<RecordCandidate> getCandidates()
        {
            return candidates;
        }

        public void setCandidates(List<RecordCandidate> candidates)
        {
            this.candidates = candidates;
        }
    }

    /**
     * 选择器候选记录：记录 id + 记录名。
     */
    public static class RecordCandidate
    {
        /** 被关联表记录 id */
        private Long recordId;

        /** 被关联表记录名 */
        private String name;

        public RecordCandidate()
        {
        }

        public RecordCandidate(Long recordId, String name)
        {
            this.recordId = recordId;
            this.name = name;
        }

        public Long getRecordId()
        {
            return recordId;
        }

        public void setRecordId(Long recordId)
        {
            this.recordId = recordId;
        }

        public String getName()
        {
            return name;
        }

        public void setName(String name)
        {
            this.name = name;
        }
    }
}
