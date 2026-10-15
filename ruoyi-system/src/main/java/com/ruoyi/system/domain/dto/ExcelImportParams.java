package com.ruoyi.system.domain.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多维表格 Excel 导入补参（U5，阶段二 params JSON 契约，KTD5）。
 * <p>
 * 前端在预检（{@code precheckExcelImport}）响应分流后，把用户在补参弹框中的输入
 * 连同预检基线与文件指纹组装为 JSON 字符串，经 {@code importExcelData} 的
 * {@code params} 参数回传，服务端 fastjson2 解析为本 DTO：
 * <ul>
 *   <li>{@link #fileFingerprint}——预检响应返回的文件指纹（size + md5），
 *       阶段二重新上传文件后比对，不一致整体拒绝（防预检 A 文件、导入 B 文件）；</li>
 *   <li>{@link #precheckBaseline}——预检时的缺参列/未命中名/歧义快照，
 *       阶段二重新匹配后做反向漂移 fail-fast 比对基线；</li>
 *   <li>{@link #columnValues}——基础列/违规列统一补值（键=列名，值可为空串）；</li>
 *   <li>{@link #relationSelections}——关联列逐值选择（键=列名+名），
 *       {@code recordId=null} 表示显式留空不建立关联（KTD5 补参优先语义）。</li>
 * </ul>
 * 服务端独立校验（R26）：补参不作可信输入——记录 ID 须属于该列被关联表且存在（查库），
 * 文本补值须过与单元格同语义的类型校验；解析失败给明确拒绝文案。
 *
 * @author ruoyi
 */
public class ExcelImportParams
{
    /** 预检响应返回的文件指纹（size + md5），阶段二比对文件同一性（KTD5） */
    private ExcelImportPrecheckResult.FileFingerprint fileFingerprint;

    /** 预检基线：缺参列名/未命中名/歧义快照，反向漂移 fail-fast 的比对基线（KTD5） */
    private PrecheckBaseline precheckBaseline = new PrecheckBaseline();

    /** 基础列/违规列统一补值：键=列名，值=统一值（可为空串=显式补空） */
    private Map<String, String> columnValues = new LinkedHashMap<>();

    /** 关联列逐值选择：每个（列名+未命中名/歧义名）一条，recordId=null 表示显式留空 */
    private List<RelationSelection> relationSelections = new ArrayList<>();

    /**
     * 待建双向链接列赋值（U1/R2/R3）：每个 pendingLinkColumn 一条（headerName=裸名 X）。
     * link=按原生 21 创建语义建列（本表 X 列 + 目标表配对列 + property 互写 + 两侧既有
     * 记录补空 item），建列后 `X_文本`/`X_ID` 值走现有 21 列路径；skip=按现状两个文本列。
     * 缺省兼容：不带本参数时 pending 非空即后端阻断（行为等同缺参，零写入）。
     */
    private List<LinkColumnAssignment> linkColumnAssignments = new ArrayList<>();

    /** 待建双向链接列赋值动作：建列（事务内按原生 21 语义，R3） */
    public static final String LINK_ACTION_LINK = "link";

    /** 待建双向链接列赋值动作：跳过（按现状两个文本列处理，R3） */
    public static final String LINK_ACTION_SKIP = "skip";

    public ExcelImportPrecheckResult.FileFingerprint getFileFingerprint()
    {
        return fileFingerprint;
    }

    public void setFileFingerprint(ExcelImportPrecheckResult.FileFingerprint fileFingerprint)
    {
        this.fileFingerprint = fileFingerprint;
    }

    public PrecheckBaseline getPrecheckBaseline()
    {
        return precheckBaseline;
    }

    public void setPrecheckBaseline(PrecheckBaseline precheckBaseline)
    {
        this.precheckBaseline = precheckBaseline;
    }

    public Map<String, String> getColumnValues()
    {
        return columnValues;
    }

    public void setColumnValues(Map<String, String> columnValues)
    {
        this.columnValues = columnValues;
    }

    public List<RelationSelection> getRelationSelections()
    {
        return relationSelections;
    }

    public void setRelationSelections(List<RelationSelection> relationSelections)
    {
        this.relationSelections = relationSelections;
    }

    public List<LinkColumnAssignment> getLinkColumnAssignments()
    {
        return linkColumnAssignments;
    }

    public void setLinkColumnAssignments(List<LinkColumnAssignment> linkColumnAssignments)
    {
        this.linkColumnAssignments = linkColumnAssignments;
    }

    /**
     * 预检基线（KTD5 反向漂移比对基线）：阶段二重新匹配后，
     * 出现基线之外的未命中名/歧义变化/缺参列/替换删除列即判定数据已变化，整体拒绝。
     */
    public static class PrecheckBaseline
    {
        /** 预检时缺参列名（缺值/类型违规/关联未命中涉及的列） */
        private List<String> missingColumns = new ArrayList<>();

        /** 预检时全部未命中名（键=列名+名，KTD7 选择器分组口径） */
        private List<BaselineName> missNames = new ArrayList<>();

        /** 预检时歧义项（键=列名+名，含预选记录 id，供阶段二比对预选变化） */
        private List<BaselineAmbiguity> ambiguity = new ArrayList<>();

        /** 预检时将被删除的列名（替换式语义：阶段二出现基线之外的删除列即漂移拒绝） */
        private List<String> replaceDeletedColumns = new ArrayList<>();

        /** 预检时将新建的列名（信息性基线；阶段二超出不拒绝） */
        private List<String> replaceNewColumns = new ArrayList<>();

        public List<String> getMissingColumns()
        {
            return missingColumns;
        }

        public void setMissingColumns(List<String> missingColumns)
        {
            this.missingColumns = missingColumns;
        }

        public List<BaselineName> getMissNames()
        {
            return missNames;
        }

        public void setMissNames(List<BaselineName> missNames)
        {
            this.missNames = missNames;
        }

        public List<BaselineAmbiguity> getAmbiguity()
        {
            return ambiguity;
        }

        public void setAmbiguity(List<BaselineAmbiguity> ambiguity)
        {
            this.ambiguity = ambiguity;
        }

        public List<String> getReplaceDeletedColumns()
        {
            return replaceDeletedColumns;
        }

        public void setReplaceDeletedColumns(List<String> replaceDeletedColumns)
        {
            this.replaceDeletedColumns = replaceDeletedColumns;
        }

        public List<String> getReplaceNewColumns()
        {
            return replaceNewColumns;
        }

        public void setReplaceNewColumns(List<String> replaceNewColumns)
        {
            this.replaceNewColumns = replaceNewColumns;
        }
    }

    /**
     * 基线名键：列名 + 名（未命中名或歧义名的统一键口径）。
     */
    public static class BaselineName
    {
        /** 关联列名 */
        private String columnName;

        /** 未命中/歧义记录名 */
        private String name;

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
    }

    /**
     * 基线歧义项：列名 + 名 + 预选记录 id（sort 最靠前的同名记录）。
     */
    public static class BaselineAmbiguity
    {
        /** 关联列名 */
        private String columnName;

        /** 歧义记录名 */
        private String name;

        /** 预检时预选记录 id（sort 最靠前） */
        private Long preselectedRecordId;

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
     * 关联列逐值选择（KTD7）：列名 + 名 → 记录 id。
     * {@code recordId=null} 表示显式留空不建立关联（即便阶段二重新匹配命中也不建立，KTD5 补参优先）。
     */
    public static class RelationSelection
    {
        /** 关联列名 */
        private String columnName;

        /** 未命中名或歧义名 */
        private String name;

        /** 选择的被关联表记录 id；null=显式留空不建立关联 */
        private Long recordId;

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

        public Long getRecordId()
        {
            return recordId;
        }

        public void setRecordId(Long recordId)
        {
            this.recordId = recordId;
        }
    }

    /**
     * 待建双向链接列赋值（U1/R3）：headerName 为裸名 X（预检 pendingLinkColumns 的
     * headerName 匹配键），action 取 {@link #LINK_ACTION_LINK}（建列，targetTableId 必填）
     * 或 {@link #LINK_ACTION_SKIP}（跳过，按现状两个文本列处理）。
     * 服务端独立校验：目标表须存在、与本表同 noteId 且过归属校验（对齐既有 21 列 R23）；
     * 目标表已有名为 X 或 "X的双向链接" 的列 → 拒绝整表零写入（R4 防重，提示改裸名）。
     */
    public static class LinkColumnAssignment
    {
        /** 裸名 X（待建链接列的赋值键） */
        private String headerName;

        /** 目标表 id（action=link 必填；action=skip 可空） */
        private Long targetTableId;

        /** 赋值动作：link=建列 / skip=按现状两个文本列 */
        private String action;

        public String getHeaderName()
        {
            return headerName;
        }

        public void setHeaderName(String headerName)
        {
            this.headerName = headerName;
        }

        public Long getTargetTableId()
        {
            return targetTableId;
        }

        public void setTargetTableId(Long targetTableId)
        {
            this.targetTableId = targetTableId;
        }

        public String getAction()
        {
            return action;
        }

        public void setAction(String action)
        {
            this.action = action;
        }
    }
}
