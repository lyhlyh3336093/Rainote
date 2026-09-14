package com.ruoyi.system.service.impl;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.agent.security.AgentOwnershipChecker;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.NoteDwtable;
import com.ruoyi.system.domain.NoteRecord;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.AmbiguityItem;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.DefaultValueFill;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.FileFingerprint;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.MissingParam;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.MissName;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.NewOption;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.RecordCandidate;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.RelationCandidates;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.SkippedHeader;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult.SymmetricWriteImpact;
import com.ruoyi.system.domain.vo.NoteRecordVo;
import com.ruoyi.system.mapper.NoteColumnMapper;
import com.ruoyi.system.mapper.NoteDwtableMapper;
import com.ruoyi.system.mapper.NoteRecordMapper;
import com.ruoyi.system.service.INoteDwtableExcelImportService;
import com.ruoyi.system.service.impl.ExcelColumnMatcher.ColumnMapping;
import com.ruoyi.system.service.impl.ExcelColumnMatcher.HeaderEntry;
import com.ruoyi.system.service.impl.ExcelColumnMatcher.SheetSelection;
import com.ruoyi.system.service.impl.ExcelWorkbookReader.ParsedSheet;

/**
 * 多维表格 Excel 导入服务（U4：阶段一预检）。
 * <p>
 * 预检流程（R9/R10/R11/R14/R15/R23/R24/R25，KTD4/KTD5）：
 * <ol>
 *   <li>查目标表（防御性复查 noteId 匹配，调用端已做过归属校验）；</li>
 *   <li>{@link ExcelWorkbookReader#parse} 安全解析全部 sheet；</li>
 *   <li>{@link ExcelColumnMatcher#selectSheet} 选定逻辑表（目标表名）；</li>
 *   <li>查目标表全部列定义 → {@link ExcelColumnMatcher#mapColumns} 映射
 *       （传全部列，隐藏/排除类型由 matcher 过滤）；</li>
 *   <li>对每个映射到的 18/21 关联列：解析 property 取 table_id → R23 归属校验
 *       （被关联表须存在、与目标表同 noteId 且过 {@link AgentOwnershipChecker}，
 *       失败抛 {@link ServiceException} 含列名与原因，阻断整次预检）→
 *       KTD4 批量预解析（一次查被关联表全部 NoteRecord，按 sort 升序构建
 *       name → 有序记录列表，同名多条保留全部：首条=预选，size&gt;1=歧义）；</li>
 *   <li>逐行汇总：缺参项（缺列/缺值/类型违规/关联缺列/关联未命中）、歧义项、
 *       默认值填充项、新选项创建项、对称写入影响、忽略提示；</li>
 *   <li>文件指纹（KTD5：size + md5）随结果返回，供阶段二比对。</li>
 * </ol>
 * 文本匹配（KTD4 整串优先）：单元格文本（trim）先整串匹配记录名，命中即用（不拆逗号）；
 * 未命中再按英文逗号拆分逐个（trim）匹配。每个未命中的名字（distinct）收集行号集。
 * 空单元格不建立关联、不算缺参不算未命中（R15）。
 * <p>
 * 预检不触碰任何写路径（零 insert/update mapper 调用）；
 * 日志只记录列名/行号/跳过原因等元数据，不含单元格值（R25）。
 *
 * @author ruoyi
 */
@Service
public class NoteDwtableExcelImportServiceImpl implements INoteDwtableExcelImportService
{
    private static final Logger log = LoggerFactory.getLogger(NoteDwtableExcelImportServiceImpl.class);

    /** 六类基础列（R7 默认值支持范围，亦为缺参分析范围） */
    private static final Set<Long> BASIC_TYPES = new HashSet<>(Arrays.asList(1L, 2L, 3L, 4L, 5L, 7L));

    /** 单向关联 */
    private static final long TYPE_SINGLE_LINK = 18L;

    /** 双向关联 */
    private static final long TYPE_DOUBLE_LINK = 21L;

    /** 单选 */
    private static final long TYPE_SINGLE_SELECT = 3L;

    /** 多选 */
    private static final long TYPE_MULTI_SELECT = 4L;

    /** 隐藏列标记：isShow=1 表示隐藏 */
    private static final long IS_SHOW_HIDDEN = 1L;

    /** 关联列选择器候选首批上限（按 sort 升序前 N 条，KTD7） */
    private static final int CANDIDATE_LIMIT = 100;

    @Autowired
    private NoteDwtableMapper noteDwtableMapper;

    @Autowired
    private NoteColumnMapper noteColumnMapper;

    @Autowired
    private NoteRecordMapper noteRecordMapper;

    @Autowired
    private AgentOwnershipChecker agentOwnershipChecker;

    @Override
    public ExcelImportPrecheckResult precheck(Long noteId, Long dwtableId, MultipartFile file, Long userId)
    {
        NoteDwtable dwtable = noteDwtableMapper.selectNoteDwtableById(dwtableId);
        if (dwtable == null || !noteId.equals(dwtable.getNoteId()))
        {
            throw new ServiceException("预检失败：多维表与笔记不匹配");
        }
        List<ParsedSheet> sheets = ExcelWorkbookReader.parse(file);
        SheetSelection selection = ExcelColumnMatcher.selectSheet(sheets, dwtable.getName());

        NoteColumn query = new NoteColumn();
        query.setDwtableId(dwtableId);
        List<NoteColumn> columns = noteColumnMapper.selectNoteColumnList(query);
        ColumnMapping mapping = ExcelColumnMatcher.mapColumns(selection.getHeaders(), columns);

        ExcelImportPrecheckResult result = new ExcelImportPrecheckResult();
        result.setFileFingerprint(fingerprint(file));
        result.setIgnoredSheets(new ArrayList<>(selection.getIgnoredSheetNames()));
        appendSkippedHeaders(result, mapping, noteId, dwtableId);
        analyzeColumns(result, noteId, dwtableId, userId, selection.getRows(), columns, mapping);
        result.setHasBlockingIssues(!result.getMissingParams().isEmpty() || !result.getAmbiguityItems().isEmpty());

        log.info("[EXCEL-PRECHECK] 预检完成 noteId={}, dwtableId={}, userId={}, totalRows={}, missingParams={}, "
                        + "ambiguityItems={}, defaultValueFills={}, newOptions={}, symmetricWriteImpact={}, "
                        + "ignoredSheets={}, skippedHeaders={}",
                noteId, dwtableId, userId, selection.getRows().size(),
                result.getMissingParams().size(), result.getAmbiguityItems().size(),
                result.getDefaultValueFills().size(), result.getNewOptions().size(),
                result.getSymmetricWriteImpact().size(), result.getIgnoredSheets().size(),
                result.getSkippedHeaders().size());
        return result;
    }

    // ==================== 列分析 ====================

    /**
     * 遍历参与导入的列（非隐藏 且 类型 ∈ 六类基础 1/2/3/4/5/7 + 关联 18/21），逐列汇总预检清单。
     */
    private void analyzeColumns(ExcelImportPrecheckResult result, Long noteId, Long dwtableId, Long userId,
            List<List<String>> rows, List<NoteColumn> columns, ColumnMapping mapping)
    {
        int totalRows = rows.size();

        // 已映射列实例 → headerIndex（matcher 保证一个列至多被一个表头映射）
        Map<NoteColumn, Integer> headerIndexByColumn = new IdentityHashMap<>();
        for (HeaderEntry entry : mapping.getMappedEntries())
        {
            headerIndexByColumn.put(entry.getColumn(), Integer.valueOf(entry.getHeaderIndex()));
        }

        // 被关联表上下文缓存（同表多列共享一次 KTD4 批量预解析）
        Map<Long, LinkTableContext> contextByTableId = new HashMap<>();

        for (NoteColumn column : columns)
        {
            if (column == null || column.getType() == null || isHidden(column))
            {
                continue;
            }
            Long type = column.getType();
            boolean link = type == TYPE_SINGLE_LINK || type == TYPE_DOUBLE_LINK;
            if (!link && !BASIC_TYPES.contains(type))
            {
                continue;
            }
            Integer headerIndex = headerIndexByColumn.get(column);
            if (headerIndex == null)
            {
                // 缺列：有默认值 → 默认值填充项（影响行数=全部行数）；否则缺参项（R10）
                appendMissingColumn(result, column, type, link, totalRows);
                continue;
            }
            if (link)
            {
                analyzeLinkColumn(result, noteId, userId, column, type, headerIndex.intValue(),
                        mapping, rows, contextByTableId);
            }
            else
            {
                analyzeBasicColumn(result, column, type, headerIndex.intValue(), mapping, rows);
            }
        }
    }

    /**
     * 缺列情形：表头未映射到。基础列有默认值 → 默认值填充项（rowCount=全部行数，不进缺参弹框）；
     * 否则缺参项（基础列 kind=缺列；关联列 kind=关联缺列，关联列不支持默认值）。
     */
    private static void appendMissingColumn(ExcelImportPrecheckResult result, NoteColumn column,
            Long type, boolean link, int totalRows)
    {
        if (!link)
        {
            String defaultValue = ColumnDefaultValueSupport.read(column);
            if (defaultValue != null)
            {
                DefaultValueFill fill = new DefaultValueFill();
                fill.setColumnId(column.getId());
                fill.setColumnName(column.getName());
                fill.setDefaultValue(defaultValue);
                fill.setRowCount(totalRows);
                result.getDefaultValueFills().add(fill);
                return;
            }
        }
        MissingParam param = new MissingParam();
        param.setColumnId(column.getId());
        param.setColumnName(column.getName());
        param.setColumnType(type);
        param.setKind(link ? ExcelImportPrecheckResult.KIND_LINK_MISSING_COLUMN
                : ExcelImportPrecheckResult.KIND_MISSING_COLUMN);
        param.setTotalRows(Integer.valueOf(totalRows));
        result.getMissingParams().add(param);
    }

    /**
     * 基础列（1/2/3/4/5/7）逐行分析：缺值（默认值填充或缺参）、类型违规（缺参，附原因）、
     * 单选/多选未命中选项（新选项创建项）。
     */
    private static void analyzeBasicColumn(ExcelImportPrecheckResult result, NoteColumn column, Long type,
            int headerIndex, ColumnMapping mapping, List<List<String>> rows)
    {
        String defaultValue = ColumnDefaultValueSupport.read(column);
        Set<String> options = (type == TYPE_SINGLE_SELECT || type == TYPE_MULTI_SELECT)
                ? parseSelectOptions(column) : null;

        // 空单元格行号集（缺值分析）
        TreeSet<Integer> emptyRows = new TreeSet<>();
        // 类型违规行号集（R11：违规按缺参走补值链路）
        TreeSet<Integer> violationRows = new TreeSet<>();
        String violationReason = null;
        // 未命中选项文本 → 出现次数（R19 新选项创建）
        Map<String, Integer> newOptionCounts = new LinkedHashMap<>();

        for (int r = 0; r < rows.size(); r++)
        {
            int rowNumber = r + 2;
            String text = mapping.cellText(rows.get(r), headerIndex).trim();
            if (text.isEmpty())
            {
                emptyRows.add(Integer.valueOf(rowNumber));
                continue;
            }
            String violation = ExcelColumnMatcher.validateCellText(column, text);
            if (violation != null)
            {
                violationRows.add(Integer.valueOf(rowNumber));
                if (violationReason == null)
                {
                    violationReason = violation;
                }
                continue;
            }
            if (type == TYPE_SINGLE_SELECT)
            {
                // 单选整格匹配（R19）
                if (options != null && !options.contains(text))
                {
                    newOptionCounts.merge(text, Integer.valueOf(1), Integer::sum);
                }
            }
            else if (type == TYPE_MULTI_SELECT)
            {
                // 多选按英文逗号拆分逐个匹配（R19）
                for (String part : text.split(","))
                {
                    String candidate = part.trim();
                    if (!candidate.isEmpty() && options != null && !options.contains(candidate))
                    {
                        newOptionCounts.merge(candidate, Integer.valueOf(1), Integer::sum);
                    }
                }
            }
        }

        if (!emptyRows.isEmpty())
        {
            if (defaultValue != null)
            {
                // 有默认值的列不进弹框：默认值填充项（行数=空单元格行数）
                DefaultValueFill fill = new DefaultValueFill();
                fill.setColumnId(column.getId());
                fill.setColumnName(column.getName());
                fill.setDefaultValue(defaultValue);
                fill.setRowCount(emptyRows.size());
                result.getDefaultValueFills().add(fill);
            }
            else
            {
                MissingParam param = new MissingParam();
                param.setColumnId(column.getId());
                param.setColumnName(column.getName());
                param.setColumnType(type);
                param.setKind(ExcelImportPrecheckResult.KIND_MISSING_VALUE);
                param.setRowNumbers(new ArrayList<>(emptyRows));
                result.getMissingParams().add(param);
            }
        }
        if (!violationRows.isEmpty())
        {
            MissingParam param = new MissingParam();
            param.setColumnId(column.getId());
            param.setColumnName(column.getName());
            param.setColumnType(type);
            param.setKind(ExcelImportPrecheckResult.KIND_TYPE_VIOLATION);
            param.setRowNumbers(new ArrayList<>(violationRows));
            param.setReason(violationReason);
            result.getMissingParams().add(param);
        }
        for (Map.Entry<String, Integer> entry : newOptionCounts.entrySet())
        {
            NewOption option = new NewOption();
            option.setColumnId(column.getId());
            option.setColumnName(column.getName());
            option.setOptionText(entry.getKey());
            option.setOccurrences(entry.getValue().intValue());
            result.getNewOptions().add(option);
        }
    }

    /**
     * 关联列（18/21）逐行分析（KTD4 整串优先）：命中收集歧义与对称写入影响，
     * 未命中名（distinct）收集行号集供 U6 逐值选择器；附选择器候选首批。
     */
    private void analyzeLinkColumn(ExcelImportPrecheckResult result, Long noteId, Long userId, NoteColumn column,
            Long type, int headerIndex, ColumnMapping mapping, List<List<String>> rows,
            Map<Long, LinkTableContext> contextByTableId)
    {
        LinkTableContext context = linkTableContext(column, noteId, userId, contextByTableId);
        Map<String, List<NoteRecord>> recordsByName = context.getRecordsByName();

        // 未命中名 → 行号集（distinct 名，LinkedHashMap 保持首次出现顺序）
        Map<String, TreeSet<Integer>> missRowsByName = new LinkedHashMap<>();
        // 歧义名 → 同名记录列表（匹配中才登记）
        Map<String, List<NoteRecord>> ambiguityByName = new LinkedHashMap<>();
        // 命中的 distinct 记录 id（含歧义预选与整串/拆分命中的并集，R11 影响面）
        Set<Long> matchedRecordIds = new LinkedHashSet<>();

        for (int r = 0; r < rows.size(); r++)
        {
            int rowNumber = r + 2;
            String text = mapping.cellText(rows.get(r), headerIndex).trim();
            if (text.isEmpty())
            {
                // R15：空单元格不建立关联，不算缺参不算未命中
                continue;
            }
            // KTD4 整串优先：整串命中即用，不拆逗号（处理记录名含逗号）
            List<NoteRecord> full = recordsByName.get(text);
            if (full != null)
            {
                collectMatch(text, full, matchedRecordIds, ambiguityByName);
                continue;
            }
            // 未命中 → 按英文逗号拆分逐个匹配
            for (String part : text.split(","))
            {
                String name = part.trim();
                if (name.isEmpty())
                {
                    continue;
                }
                List<NoteRecord> matched = recordsByName.get(name);
                if (matched != null)
                {
                    collectMatch(name, matched, matchedRecordIds, ambiguityByName);
                }
                else
                {
                    missRowsByName.computeIfAbsent(name, k -> new TreeSet<>()).add(Integer.valueOf(rowNumber));
                }
            }
        }

        for (Map.Entry<String, List<NoteRecord>> entry : ambiguityByName.entrySet())
        {
            AmbiguityItem item = new AmbiguityItem();
            item.setColumnId(column.getId());
            item.setColumnName(column.getName());
            item.setName(entry.getKey());
            item.setCandidateCount(entry.getValue().size());
            // 预选 sort 最靠前的同名记录（列表已按 sort 升序）
            item.setPreselectedRecordId(entry.getValue().get(0).getId());
            result.getAmbiguityItems().add(item);
        }
        if (!missRowsByName.isEmpty())
        {
            MissingParam param = new MissingParam();
            param.setColumnId(column.getId());
            param.setColumnName(column.getName());
            param.setColumnType(type);
            param.setKind(ExcelImportPrecheckResult.KIND_LINK_MISS);
            List<MissName> missNames = new ArrayList<>(missRowsByName.size());
            for (Map.Entry<String, TreeSet<Integer>> entry : missRowsByName.entrySet())
            {
                MissName miss = new MissName();
                miss.setName(entry.getKey());
                miss.setRowNumbers(new ArrayList<>(entry.getValue()));
                missNames.add(miss);
            }
            param.setMissNames(missNames);
            result.getMissingParams().add(param);
        }
        // 对称写入影响：仅 21 双向关联列（16/R11）
        if (type == TYPE_DOUBLE_LINK && !matchedRecordIds.isEmpty())
        {
            SymmetricWriteImpact impact = new SymmetricWriteImpact();
            impact.setTableName(context.getTable().getName());
            impact.setAffectedRecordCount(matchedRecordIds.size());
            result.getSymmetricWriteImpact().add(impact);
        }
        // 选择器候选（U6 内嵌数据源）：按 sort 升序前 CANDIDATE_LIMIT 条
        RelationCandidates relationCandidates = new RelationCandidates();
        relationCandidates.setColumnId(column.getId());
        List<RecordCandidate> candidates = new ArrayList<>(
                Math.min(CANDIDATE_LIMIT, context.getSortedRecords().size()));
        for (int i = 0; i < context.getSortedRecords().size() && i < CANDIDATE_LIMIT; i++)
        {
            NoteRecord record = context.getSortedRecords().get(i);
            RecordCandidate candidate = new RecordCandidate();
            candidate.setRecordId(record.getId());
            candidate.setName(record.getName() == null ? "" : record.getName());
            candidates.add(candidate);
        }
        relationCandidates.setCandidates(candidates);
        result.getRelationCandidates().add(relationCandidates);
    }

    /**
     * 收集一次命中：预选记录（sort 靠前首条）计入影响面；同名多条登记歧义（R14）。
     */
    private static void collectMatch(String name, List<NoteRecord> matched, Set<Long> matchedRecordIds,
            Map<String, List<NoteRecord>> ambiguityByName)
    {
        matchedRecordIds.add(matched.get(0).getId());
        if (matched.size() > 1)
        {
            ambiguityByName.putIfAbsent(name, matched);
        }
    }

    // ==================== 被关联表上下文（R23 + KTD4） ====================

    /**
     * 取（或构建）被关联表上下文：R23 归属校验（存在 + 同 noteId + AgentOwnershipChecker，
     * 失败抛 {@link ServiceException} 含列名与原因，阻断整次预检）→ 一次查全部 NoteRecord
     * 按 sort 升序构建 name → 有序记录列表（同表多列共享缓存）。
     */
    private LinkTableContext linkTableContext(NoteColumn column, Long noteId, Long userId,
            Map<Long, LinkTableContext> cache)
    {
        Long tableId = parseTableId(column);
        LinkTableContext cached = cache.get(tableId);
        if (cached != null)
        {
            return cached;
        }
        String columnName = column.getName() == null ? "" : column.getName();

        NoteDwtable related = noteDwtableMapper.selectNoteDwtableById(tableId);
        if (related == null)
        {
            throw new ServiceException("关联列“" + columnName + "”的被关联数据表不存在，无法导入");
        }
        if (!noteId.equals(related.getNoteId()))
        {
            throw new ServiceException("关联列“" + columnName + "”的被关联数据表不在当前笔记内，无法导入");
        }
        try
        {
            agentOwnershipChecker.checkDwtableOwnership(tableId, userId);
        }
        catch (ServiceException e)
        {
            throw new ServiceException("关联列“" + columnName + "”的被关联数据表归属校验失败：" + e.getMessage());
        }

        // KTD4 批量预解析：一次查被关联表全部记录，避免逐单元格 N+1
        NoteRecordVo query = new NoteRecordVo();
        query.setDwtableId(tableId);
        List<NoteRecord> records = new ArrayList<>(noteRecordMapper.selectNoteRecordList(query));
        records.sort(Comparator
                .comparing((NoteRecord record) -> record.getSort() == null ? Long.MAX_VALUE : record.getSort())
                .thenComparing(record -> record.getId() == null ? Long.MAX_VALUE : record.getId()));
        Map<String, List<NoteRecord>> recordsByName = new LinkedHashMap<>();
        for (NoteRecord record : records)
        {
            String name = record.getName() == null ? "" : record.getName();
            recordsByName.computeIfAbsent(name, k -> new ArrayList<>()).add(record);
        }
        LinkTableContext context = new LinkTableContext(related, records, recordsByName);
        cache.put(tableId, context);
        return context;
    }

    /**
     * 解析关联列 property JSON 的 table_id（被关联表 id）；缺失或非法抛 {@link ServiceException} 含列名。
     */
    private static Long parseTableId(NoteColumn column)
    {
        String columnName = column.getName() == null ? "" : column.getName();
        Long tableId = null;
        if (column.getProperty() != null && !column.getProperty().trim().isEmpty())
        {
            try
            {
                JSONObject prop = JSONObject.parseObject(column.getProperty());
                if (prop != null)
                {
                    tableId = prop.getLong("table_id");
                }
            }
            catch (Exception ignored)
            {
                // 非法 property 与缺失 table_id 同路径处理
            }
        }
        if (tableId == null)
        {
            throw new ServiceException("关联列“" + columnName + "”的配置缺少被关联表（table_id），无法导入");
        }
        return tableId;
    }

    /**
     * 解析单选/多选列的选项集（property select 逗号字符串，全角逗号先归一为半角）。
     */
    private static Set<String> parseSelectOptions(NoteColumn column)
    {
        Set<String> options = new HashSet<>();
        if (column.getProperty() == null || column.getProperty().trim().isEmpty())
        {
            return options;
        }
        try
        {
            JSONObject prop = JSONObject.parseObject(column.getProperty());
            Object select = prop == null ? null : prop.get("select");
            if (select == null)
            {
                return options;
            }
            for (String part : String.valueOf(select).replace("，", ",").split(","))
            {
                String option = part.trim();
                if (!option.isEmpty())
                {
                    options.add(option);
                }
            }
        }
        catch (Exception ignored)
        {
            // 非法 property 视为无选项集（全部单元格文本都进新选项创建项）
        }
        return options;
    }

    // ==================== 辅助 ====================

    /**
     * 忽略提示 + 跳过表头清单；后端日志记录跳过列名与原因（不含单元格值，R25/R7）。
     */
    private static void appendSkippedHeaders(ExcelImportPrecheckResult result, ColumnMapping mapping,
            Long noteId, Long dwtableId)
    {
        for (HeaderEntry entry : mapping.getSkippedEntries())
        {
            result.getSkippedHeaders().add(new SkippedHeader(entry.getHeaderName(), entry.getReason()));
            log.info("[EXCEL-PRECHECK] 跳过表头 noteId={}, dwtableId={}, headerName={}, reason={}",
                    noteId, dwtableId, entry.getHeaderName(), entry.getReason());
        }
    }

    /**
     * 计算文件指纹（KTD5）：size + 内容 MD5（JDK MessageDigest，32 位小写十六进制）。
     */
    private static FileFingerprint fingerprint(MultipartFile file)
    {
        FileFingerprint fingerprint = new FileFingerprint();
        fingerprint.setSize(file.getSize());
        fingerprint.setMd5(md5Hex(readBytes(file)));
        return fingerprint;
    }

    private static byte[] readBytes(MultipartFile file)
    {
        try
        {
            return file.getBytes();
        }
        catch (IOException e)
        {
            throw new ServiceException("读取上传文件失败，请重试");
        }
    }

    private static String md5Hex(byte[] bytes)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("MD5").digest(bytes);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte b : digest)
            {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("MD5 算法不可用", e);
        }
    }

    /**
     * 隐藏列判定：isShow=1 表示隐藏。
     */
    private static boolean isHidden(NoteColumn column)
    {
        return column.getIsShow() != null && column.getIsShow() == IS_SHOW_HIDDEN;
    }

    /**
     * 被关联表上下文（KTD4 预解析产物，同表多列共享）：
     * 表信息 + sort 升序全量记录 + name → 有序记录列表（同名多条保留全部）。
     */
    private static final class LinkTableContext
    {
        private final NoteDwtable table;

        private final List<NoteRecord> sortedRecords;

        private final Map<String, List<NoteRecord>> recordsByName;

        private LinkTableContext(NoteDwtable table, List<NoteRecord> sortedRecords,
                Map<String, List<NoteRecord>> recordsByName)
        {
            this.table = table;
            this.sortedRecords = sortedRecords;
            this.recordsByName = recordsByName;
        }

        private NoteDwtable getTable()
        {
            return table;
        }

        private List<NoteRecord> getSortedRecords()
        {
            return sortedRecords;
        }

        private Map<String, List<NoteRecord>> getRecordsByName()
        {
            return recordsByName;
        }
    }
}
