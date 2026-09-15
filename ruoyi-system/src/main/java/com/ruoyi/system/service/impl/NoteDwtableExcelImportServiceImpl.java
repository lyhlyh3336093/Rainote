package com.ruoyi.system.service.impl;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.agent.security.AgentOwnershipChecker;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.NoteDwtable;
import com.ruoyi.system.domain.NoteDwtableItem;
import com.ruoyi.system.domain.NoteRecord;
import com.ruoyi.system.domain.dto.ExcelImportParams;
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
import com.ruoyi.system.mapper.NoteDwtableItemMapper;
import com.ruoyi.system.mapper.NoteDwtableMapper;
import com.ruoyi.system.mapper.NoteRecordMapper;
import com.ruoyi.system.service.INoteDwtableExcelImportService;
import com.ruoyi.system.service.INoteRecordService;
import com.ruoyi.system.service.impl.ExcelColumnMatcher.ColumnMapping;
import com.ruoyi.system.service.impl.ExcelColumnMatcher.HeaderEntry;
import com.ruoyi.system.service.impl.ExcelColumnMatcher.SheetSelection;
import com.ruoyi.system.service.impl.ExcelWorkbookReader.ParsedSheet;
import com.ruoyi.system.service.impl.ExcelWorkbookReader.RowData;

/**
 * 多维表格 Excel 导入服务（U4 阶段一预检 + U5 阶段二导入写入）。
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
 * <p>
 * 导入流程（U5，R16-R26，KTD3/KTD5/KTD8）：非事务前置段（指纹校验 → 同一私有方法链
 * 重新解析/映射/匹配 → params 解析 → 补参校验 R26 → 反向漂移 fail-fast）→
 * {@code @Transactional} 写入段（逐行 NoteRecord + items 批量 ≥500 flush + 选项追加 +
 * 全量最终 flush + 双链对称写入 + 重算编排）→ 返回 recordCount；失败整体回滚（R22）。
 *
 * @author ruoyi
 */
@Service
public class NoteDwtableExcelImportServiceImpl implements INoteDwtableExcelImportService
{
    private static final Logger log = LoggerFactory.getLogger(NoteDwtableExcelImportServiceImpl.class);

    /** 六类基础列（R7 默认值支持范围，亦为缺参分析范围） */
    private static final Set<Long> BASIC_TYPES = new HashSet<>(Arrays.asList(1L, 2L, 3L, 4L, 5L, 7L));

    /** 单选 */
    private static final long TYPE_SINGLE_SELECT = 3L;

    /** 多选 */
    private static final long TYPE_MULTI_SELECT = 4L;

    /** 复选框 */
    private static final long TYPE_CHECKBOX = 7L;

    /** lookup 列 */
    private static final long TYPE_LOOKUP = 26L;

    /** 集合运算列 */
    private static final long TYPE_SET_OPERATION = 24L;

    /** 关联列选择器候选首批上限（按 sort 升序前 N 条，KTD7） */
    private static final int CANDIDATE_LIMIT = 100;

    /** NoteDwtableItem 批量 insert 单批上限（单元格数，对齐 SQL 导入 BATCH_LIMIT） */
    private static final int BATCH_LIMIT = 500;

    /** 每列每次导入新选项上限（R19，超限中止回滚） */
    private static final int NEW_OPTION_LIMIT = 100;

    /** 导入参数单段条目数上限（columnValues / relationSelections 各自适用，防超大 params 放大） */
    private static final int PARAM_ENTRIES_LIMIT = 10000;

    @Autowired
    private NoteDwtableMapper noteDwtableMapper;

    @Autowired
    private NoteColumnMapper noteColumnMapper;

    @Autowired
    private NoteRecordMapper noteRecordMapper;

    @Autowired
    private NoteDwtableItemMapper noteDwtableItemMapper;

    @Autowired
    private AgentOwnershipChecker agentOwnershipChecker;

    /** 重算编排入口（KTD8：经 Spring 代理调用加入导入事务） */
    @Autowired
    private INoteRecordService noteRecordService;

    /**
     * 自注入代理引用：导入入口（非事务前置段）完成后经代理进入 {@code @Transactional}
     * 写入段（同类自调用不经代理，事务由本字段承载；注入具体类依赖 CGLIB 代理，
     * 与 {@code NoteDwtableServiceImpl} 注入 {@code NoteViewServiceImpl} 同一先例）。
     */
    @Autowired
    private NoteDwtableExcelImportServiceImpl self;

    /** 供单元测试注入未代理实例（生产环境由 Spring 注入代理） */
    void setSelf(NoteDwtableExcelImportServiceImpl self)
    {
        this.self = self;
    }

    @Override
    public ExcelImportPrecheckResult precheck(Long noteId, Long dwtableId, MultipartFile file, Long userId)
    {
        NoteDwtable dwtable = noteDwtableMapper.selectNoteDwtableById(dwtableId);
        if (dwtable == null || !noteId.equals(dwtable.getNoteId()))
        {
            throw new ServiceException("预检失败：多维表与笔记不匹配");
        }
        // 先算指纹（getBytes），再解析（getInputStream）——Tomcat multipart 对
        // getInputStream 后再 getBytes 在某些配置下失败（真实 HTTP 才触发，单测 mock 无法覆盖）
        ExcelImportPrecheckResult.FileFingerprint fp = fingerprint(file);
        List<ParsedSheet> sheets = ExcelWorkbookReader.parse(file);
        SheetSelection selection = ExcelColumnMatcher.selectSheet(sheets, dwtable.getName());

        NoteColumn query = new NoteColumn();
        query.setDwtableId(dwtableId);
        List<NoteColumn> columns = noteColumnMapper.selectNoteColumnList(query);
        ColumnMapping mapping = ExcelColumnMatcher.mapColumns(selection.getHeaders(), columns);

        ExcelImportPrecheckResult result = new ExcelImportPrecheckResult();
        result.setFileFingerprint(fp);
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
            List<RowData> rows, List<NoteColumn> columns, ColumnMapping mapping)
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
            if (column == null || column.getType() == null || ExcelColumnMatcher.isHidden(column))
            {
                continue;
            }
            Long type = column.getType();
            boolean link = type == ExcelColumnMatcher.TYPE_SINGLE_LINK || type == ExcelColumnMatcher.TYPE_DOUBLE_LINK;
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
                fill.setColumnName(dtoColumnName(column));
                fill.setDefaultValue(defaultValue);
                fill.setRowCount(totalRows);
                result.getDefaultValueFills().add(fill);
                return;
            }
        }
        MissingParam param = new MissingParam();
        param.setColumnId(column.getId());
        param.setColumnName(dtoColumnName(column));
        param.setColumnType(type);
        param.setKind(link ? ExcelImportPrecheckResult.KIND_LINK_MISSING_COLUMN
                : ExcelImportPrecheckResult.KIND_MISSING_COLUMN);
        param.setTotalRows(Integer.valueOf(totalRows));
        result.getMissingParams().add(param);
    }

    /**
     * 基础列（1/2/3/4/5/7）逐行分析：缺值（默认值填充或缺参）、类型违规（缺参，附原因）、
     * 单选/多选未命中选项（新选项创建项）。
     * <p>
     * 同列缺值与类型违规合并为一条缺参（rowNumbers 取并集、kind 有违规时"类型违规"否则"缺值"、
     * reason 保留违规描述），避免同列两条缺参导致前端双行输入与 columnValues 同键覆盖。
     */
    private static void analyzeBasicColumn(ExcelImportPrecheckResult result, NoteColumn column, Long type,
            int headerIndex, ColumnMapping mapping, List<RowData> rows)
    {
        String defaultValue = ColumnDefaultValueSupport.read(column);
        Set<String> options = (type == TYPE_SINGLE_SELECT || type == TYPE_MULTI_SELECT)
                ? ColumnDefaultValueSupport.parseSelectOptions(column) : null;

        // 空单元格行号集（缺值分析，物理行号与 Excel UI 一致）
        TreeSet<Integer> emptyRows = new TreeSet<>();
        // 类型违规行号集（R11：违规按缺参走补值链路）
        TreeSet<Integer> violationRows = new TreeSet<>();
        String violationReason = null;
        // 未命中选项文本 → 出现次数（R19 新选项创建）
        Map<String, Integer> newOptionCounts = new LinkedHashMap<>();

        for (int r = 0; r < rows.size(); r++)
        {
            RowData row = rows.get(r);
            String text = mapping.cellText(row, headerIndex).trim();
            if (text.isEmpty())
            {
                emptyRows.add(Integer.valueOf(row.getRowNumber()));
                continue;
            }
            String violation = ExcelColumnMatcher.validateCellText(column, text);
            if (violation != null)
            {
                violationRows.add(Integer.valueOf(row.getRowNumber()));
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
                // 多选拆分逐个匹配（R19）：先全角逗号归一为半角（与选项集解析口径对称）
                for (String part : text.replace("，", ",").split(","))
                {
                    String candidate = part.trim();
                    if (!candidate.isEmpty() && options != null && !options.contains(candidate))
                    {
                        newOptionCounts.merge(candidate, Integer.valueOf(1), Integer::sum);
                    }
                }
            }
        }

        boolean missingEmptyRows = !emptyRows.isEmpty() && defaultValue == null;
        if (!emptyRows.isEmpty() && defaultValue != null)
        {
            // 有默认值的列不进弹框：默认值填充项（行数=空单元格行数）
            DefaultValueFill fill = new DefaultValueFill();
            fill.setColumnId(column.getId());
            fill.setColumnName(dtoColumnName(column));
            fill.setDefaultValue(defaultValue);
            fill.setRowCount(emptyRows.size());
            result.getDefaultValueFills().add(fill);
        }
        if (missingEmptyRows || !violationRows.isEmpty())
        {
            // 同列缺值+类型违规合并为一条（kind 有违规时"类型违规"，rowNumbers 取并集）
            MissingParam param = new MissingParam();
            param.setColumnId(column.getId());
            param.setColumnName(dtoColumnName(column));
            param.setColumnType(type);
            if (violationRows.isEmpty())
            {
                param.setKind(ExcelImportPrecheckResult.KIND_MISSING_VALUE);
                param.setRowNumbers(new ArrayList<>(emptyRows));
            }
            else
            {
                param.setKind(ExcelImportPrecheckResult.KIND_TYPE_VIOLATION);
                TreeSet<Integer> unionRows = new TreeSet<>(violationRows);
                if (missingEmptyRows)
                {
                    unionRows.addAll(emptyRows);
                }
                param.setRowNumbers(new ArrayList<>(unionRows));
                param.setReason(missingEmptyRows
                        ? violationReason + "；另有 " + emptyRows.size() + " 行缺值"
                        : violationReason);
            }
            result.getMissingParams().add(param);
        }
        for (Map.Entry<String, Integer> entry : newOptionCounts.entrySet())
        {
            NewOption option = new NewOption();
            option.setColumnId(column.getId());
            option.setColumnName(dtoColumnName(column));
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
            Long type, int headerIndex, ColumnMapping mapping, List<RowData> rows,
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
            RowData row = rows.get(r);
            String text = mapping.cellText(row, headerIndex).trim();
            if (text.isEmpty())
            {
                // R15：空单元格不建立关联，不算缺参不算未命中
                continue;
            }
            // KTD4 整串优先（candidateNames）：整串命中即返回整串（不拆逗号，处理记录名含逗号），
            // 未命中再按英文逗号拆分逐个（trim）匹配
            for (String name : candidateNames(text, recordsByName))
            {
                List<NoteRecord> matched = recordsByName.get(name);
                if (matched != null)
                {
                    collectMatch(name, matched, matchedRecordIds, ambiguityByName);
                }
                else
                {
                    missRowsByName.computeIfAbsent(name, k -> new TreeSet<>())
                            .add(Integer.valueOf(row.getRowNumber()));
                }
            }
        }

        for (Map.Entry<String, List<NoteRecord>> entry : ambiguityByName.entrySet())
        {
            AmbiguityItem item = new AmbiguityItem();
            item.setColumnId(column.getId());
            item.setColumnName(dtoColumnName(column));
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
            param.setColumnName(dtoColumnName(column));
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
        if (type == ExcelColumnMatcher.TYPE_DOUBLE_LINK && !matchedRecordIds.isEmpty())
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
        Map<Long, NoteRecord> recordsById = new HashMap<>();
        for (NoteRecord record : records)
        {
            String name = record.getName() == null ? "" : record.getName();
            recordsByName.computeIfAbsent(name, k -> new ArrayList<>()).add(record);
            recordsById.put(record.getId(), record);
        }
        LinkTableContext context = new LinkTableContext(related, records, recordsByName, recordsById);
        cache.put(tableId, context);
        return context;
    }

    /**
     * 解析关联列 property JSON 的 table_id（被关联表 id）；缺失或非法抛 {@link ServiceException} 含列名。
     * <p>
     * U5 兼容：18 单向关联列由前端建列时写入 {@code {select: 被关联表id}}（无 table_id 键），
     * table_id 缺失时对 18 列回退读取 select 的数字值；非数字 select 与缺失同路径报错。
     */
    private static Long parseTableId(NoteColumn column)
    {
        String columnName = column.getName() == null ? "" : column.getName();
        Long tableId = null;
        JSONObject prop = parsePropertySafe(column);
        if (!prop.isEmpty())
        {
            tableId = prop.getLong("table_id");
            if (tableId == null && column.getType() != null
                    && column.getType() == ExcelColumnMatcher.TYPE_SINGLE_LINK)
            {
                tableId = prop.getLong("select");
            }
        }
        if (tableId == null)
        {
            throw new ServiceException("关联列“" + columnName + "”的配置缺少被关联表（table_id），无法导入");
        }
        return tableId;
    }

    /**
     * 关联文本匹配的名字序列（KTD4 整串优先，预检与导入两阶段共用）：
     * 单元格文本（trim 后非空）先整串匹配记录名，命中返回整串单元素列表（不拆逗号）；
     * 未命中按英文逗号拆分逐个 trim 去空。
     */
    static List<String> candidateNames(String text, Map<String, List<NoteRecord>> recordsByName)
    {
        if (text == null || text.isEmpty())
        {
            return Collections.emptyList();
        }
        if (recordsByName.containsKey(text))
        {
            return Collections.singletonList(text);
        }
        List<String> names = new ArrayList<>();
        for (String part : text.split(","))
        {
            String name = part.trim();
            if (!name.isEmpty())
            {
                names.add(name);
            }
        }
        return names;
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
     * 预检 DTO 输出列名统一 trim（与阶段二 buildImportPlan 的漂移比对键、补参 columnValues
     * 取值键全链路一致——空格列名下基线回传不漂移、补参键不错位）。
     */
    private static String dtoColumnName(NoteColumn column)
    {
        return column.getName() == null ? "" : column.getName().trim();
    }

    /**
     * 宽松解析 property JSON：null/空/非法时返回空 JSONObject（不抛异常）；
     * 委托 {@link ColumnDefaultValueSupport#parseSafe}（property 解析唯一实现）。
     */
    private static JSONObject parsePropertySafe(NoteColumn column)
    {
        return column == null ? new JSONObject() : ColumnDefaultValueSupport.parseSafe(column.getProperty());
    }

    /**
     * 解析关联列 property 的 back_field_id（配对列）：21 双向关联必填（对称写入目标列），
     * 18 单向关联可选（前端建列时 18 的 property 无 back_field_id → null，对齐 UI 落库形态）。
     */
    private static Long parseBackFieldId(NoteColumn column)
    {
        Long backFieldId = parsePropertySafe(column).getLong("back_field_id");
        if (backFieldId == null && column.getType() != null
                && column.getType() == ExcelColumnMatcher.TYPE_DOUBLE_LINK)
        {
            throw new ServiceException("关联列“" + (column.getName() == null ? "" : column.getName())
                    + "”的配置缺少配对列（back_field_id），无法导入");
        }
        return backFieldId;
    }

    /**
     * 被关联表上下文（KTD4 预解析产物，同表多列共享）：
     * 表信息 + sort 升序全量记录 + name → 有序记录列表（同名多条保留全部）
     * + id → 记录（recordId 存在性校验 O(1)）。
     */
    private static final class LinkTableContext
    {
        private final NoteDwtable table;

        private final List<NoteRecord> sortedRecords;

        private final Map<String, List<NoteRecord>> recordsByName;

        private final Map<Long, NoteRecord> recordsById;

        private LinkTableContext(NoteDwtable table, List<NoteRecord> sortedRecords,
                Map<String, List<NoteRecord>> recordsByName, Map<Long, NoteRecord> recordsById)
        {
            this.table = table;
            this.sortedRecords = sortedRecords;
            this.recordsByName = recordsByName;
            this.recordsById = recordsById;
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

        private Map<Long, NoteRecord> getRecordsById()
        {
            return recordsById;
        }
    }

    // ==================== 阶段二：导入写入（U5） ====================

    @Override
    public int importExcelData(Long noteId, Long dwtableId, MultipartFile file, String paramsJson, Long userId)
    {
        // 防御性复查（Controller 已做归属校验 + noteId↔dwtableId 断言，照 importData 模式）
        NoteDwtable dwtable = noteDwtableMapper.selectNoteDwtableById(dwtableId);
        if (dwtable == null || !noteId.equals(dwtable.getNoteId()))
        {
            throw new ServiceException("导入失败：多维表与笔记不匹配");
        }
        if (file == null || file.isEmpty())
        {
            throw new ServiceException("导入失败：上传文件为空");
        }
        ExcelImportParams params = parseParams(paramsJson);
        verifyFingerprint(file, params);
        // 非事务前置段：解析/匹配/漂移/补参校验不占事务（KTD5）
        ImportPlan plan = buildImportPlan(noteId, dwtableId, userId, dwtable, file, params);
        // 经自注入代理进入 @Transactional 写入段（同类直接调用不经代理）
        return self.executeImport(plan);
    }

    /**
     * 解析 params JSON（fastjson2 → {@link ExcelImportParams}），空/非法给明确拒绝文案。
     */
    private static ExcelImportParams parseParams(String paramsJson)
    {
        if (paramsJson == null || paramsJson.trim().isEmpty())
        {
            throw new ServiceException("导入参数（params）不能为空，请通过预检后导入");
        }
        ExcelImportParams params;
        try
        {
            params = JSONObject.parseObject(paramsJson, ExcelImportParams.class);
        }
        catch (Exception e)
        {
            throw new ServiceException("导入参数（params）格式错误，无法解析，请通过预检后重新导入");
        }
        if (params == null)
        {
            throw new ServiceException("导入参数（params）格式错误，无法解析，请通过预检后重新导入");
        }
        // 显式 null 段归一为空集合（后续遍历不做 null 防御）
        if (params.getPrecheckBaseline() == null)
        {
            params.setPrecheckBaseline(new ExcelImportParams.PrecheckBaseline());
        }
        if (params.getColumnValues() == null)
        {
            params.setColumnValues(new LinkedHashMap<String, String>());
        }
        if (params.getRelationSelections() == null)
        {
            params.setRelationSelections(new ArrayList<ExcelImportParams.RelationSelection>());
        }
        // 单段条目数上限：params 可达 10MB，防超大 columnValues/relationSelections 造成下游 CPU 放大
        if (params.getColumnValues().size() > PARAM_ENTRIES_LIMIT)
        {
            throw new ServiceException("导入参数条目数超限（columnValues 超过 " + PARAM_ENTRIES_LIMIT
                    + " 条），请重新预检");
        }
        if (params.getRelationSelections().size() > PARAM_ENTRIES_LIMIT)
        {
            throw new ServiceException("导入参数条目数超限（relationSelections 超过 " + PARAM_ENTRIES_LIMIT
                    + " 条），请重新预检");
        }
        return params;
    }

    /**
     * 文件指纹校验（KTD5）：size 优先（廉价）再 md5（重算后比对），
     * 不一致抛"文件与预检时不一致"（防预检 A 文件、导入 B 文件的补参错位命中）。
     */
    private static void verifyFingerprint(MultipartFile file, ExcelImportParams params)
    {
        FileFingerprint expected = params.getFileFingerprint();
        if (expected == null || expected.getMd5() == null || expected.getMd5().trim().isEmpty())
        {
            throw new ServiceException("导入参数缺少文件指纹，请重新预检");
        }
        if (file.getSize() != expected.getSize() || !md5Hex(readBytes(file)).equals(expected.getMd5()))
        {
            throw new ServiceException("文件与预检时不一致，请重新预检");
        }
    }

    /**
     * 构建导入计划（非事务前置段主体）：与预检同一私有方法链重新解析/映射/匹配 →
     * 阶段二原始状态汇总 → 反向漂移 fail-fast → 补参校验（R26）→
     * 三源合并 + 补参应用构建逐行写入计划 → 重算编排清单（KTD8）。
     */
    private ImportPlan buildImportPlan(Long noteId, Long dwtableId, Long userId, NoteDwtable dwtable,
            MultipartFile file, ExcelImportParams params)
    {
        List<ParsedSheet> sheets = ExcelWorkbookReader.parse(file);
        SheetSelection selection = ExcelColumnMatcher.selectSheet(sheets, dwtable.getName());

        NoteColumn query = new NoteColumn();
        query.setDwtableId(dwtableId);
        List<NoteColumn> columns = noteColumnMapper.selectNoteColumnList(query);
        ColumnMapping mapping = ExcelColumnMatcher.mapColumns(selection.getHeaders(), columns);
        List<RowData> rows = selection.getRows();
        int rowCount = rows.size();

        // 参与列分类（与预检 analyzeColumns 同口径：非隐藏 且 ∈ 六类基础 + 18/21）
        List<NoteColumn> participants = new ArrayList<>();
        Map<String, NoteColumn> columnByName = new HashMap<>();
        Long nameColumnId = null;
        for (NoteColumn column : columns)
        {
            if (column == null || column.getType() == null)
            {
                continue;
            }
            if (column.getName() != null && !column.getName().trim().isEmpty())
            {
                columnByName.putIfAbsent(column.getName().trim(), column);
            }
            if (ExcelColumnMatcher.isHidden(column))
            {
                continue;
            }
            // name 派生源列：首个非隐藏 type=1 列（隐藏列不生成 item，选它派生恒空）
            if (nameColumnId == null && column.getType() == 1L)
            {
                nameColumnId = column.getId();
            }
            Long type = column.getType();
            if (BASIC_TYPES.contains(type) || type == ExcelColumnMatcher.TYPE_SINGLE_LINK
                    || type == ExcelColumnMatcher.TYPE_DOUBLE_LINK)
            {
                participants.add(column);
            }
        }

        Map<NoteColumn, Integer> headerIndexByColumn = new IdentityHashMap<>();
        for (HeaderEntry entry : mapping.getMappedEntries())
        {
            headerIndexByColumn.put(entry.getColumn(), Integer.valueOf(entry.getHeaderIndex()));
        }

        // ===== 阶段二逐列逐行分析（重新匹配的原始状态，未应用补参——供漂移比对） =====
        Map<Long, LinkTableContext> contextByTableId = new HashMap<>();
        Set<String> stage2MissingColumns = new LinkedHashSet<>();
        Map<String, Set<String>> missNamesByColumn = new LinkedHashMap<>();
        Map<String, Map<String, Long>> ambiguityByColumn = new LinkedHashMap<>();
        Map<Long, LinkedHashSet<String>> newOptionsByColumnId = new LinkedHashMap<>();
        IdentityHashMap<NoteColumn, String[]> excelValuesByColumn = new IdentityHashMap<>();
        IdentityHashMap<NoteColumn, boolean[]> violationsByColumn = new IdentityHashMap<>();
        IdentityHashMap<NoteColumn, LinkColumnAnalysis> linkAnalysisByColumn = new IdentityHashMap<>();

        for (NoteColumn column : participants)
        {
            String columnName = column.getName() == null ? "" : column.getName().trim();
            Long type = column.getType();
            boolean link = type == ExcelColumnMatcher.TYPE_SINGLE_LINK || type == ExcelColumnMatcher.TYPE_DOUBLE_LINK;
            Integer headerIndex = headerIndexByColumn.get(column);
            if (headerIndex == null)
            {
                // 缺列：基础列有默认值 → 默认填充不算缺参；否则缺参列（R10）
                if (link || ColumnDefaultValueSupport.read(column) == null)
                {
                    stage2MissingColumns.add(columnName);
                }
                continue;
            }
            if (link)
            {
                LinkTableContext context = linkTableContext(column, noteId, userId, contextByTableId);
                LinkColumnAnalysis analysis = new LinkColumnAnalysis();
                analysis.context = context;
                analysis.backFieldId = parseBackFieldId(column);
                Set<String> missNames = new LinkedHashSet<>();
                Map<String, Long> ambiguity = new LinkedHashMap<>();
                for (int r = 0; r < rowCount; r++)
                {
                    String text = mapping.cellText(rows.get(r), headerIndex.intValue()).trim();
                    List<String> names = candidateNames(text, context.getRecordsByName());
                    analysis.namesPerRow.add(names);
                    for (String name : names)
                    {
                        List<NoteRecord> matched = context.getRecordsByName().get(name);
                        if (matched != null && !matched.isEmpty())
                        {
                            // 同名多条 → 歧义，预选 sort 靠前首条（R14）
                            if (matched.size() > 1)
                            {
                                ambiguity.putIfAbsent(name, Long.valueOf(matched.get(0).getId()));
                            }
                        }
                        else
                        {
                            missNames.add(name);
                        }
                    }
                }
                linkAnalysisByColumn.put(column, analysis);
                if (!missNames.isEmpty())
                {
                    missNamesByColumn.put(columnName, missNames);
                    stage2MissingColumns.add(columnName);
                }
                if (!ambiguity.isEmpty())
                {
                    ambiguityByColumn.put(columnName, ambiguity);
                }
            }
            else
            {
                String defaultValue = ColumnDefaultValueSupport.read(column);
                Set<String> options = (type == TYPE_SINGLE_SELECT || type == TYPE_MULTI_SELECT)
                        ? ColumnDefaultValueSupport.parseSelectOptions(column) : null;
                String[] excelValues = new String[rowCount];
                boolean[] violations = new boolean[rowCount];
                boolean hasMissing = false;
                LinkedHashSet<String> newOptions = new LinkedHashSet<>();
                for (int r = 0; r < rowCount; r++)
                {
                    String text = mapping.cellText(rows.get(r), headerIndex.intValue()).trim();
                    if (!text.isEmpty() && ExcelColumnMatcher.validateCellText(column, text) == null)
                    {
                        excelValues[r] = text;
                        // R19 新选项：单选整格、多选拆分逐个匹配（全角逗号先归一，与预检/选项集口径对称）
                        if (type == TYPE_SINGLE_SELECT && options != null && !options.contains(text))
                        {
                            newOptions.add(text);
                        }
                        else if (type == TYPE_MULTI_SELECT && options != null)
                        {
                            for (String part : text.replace("，", ",").split(","))
                            {
                                String candidate = part.trim();
                                if (!candidate.isEmpty() && !options.contains(candidate))
                                {
                                    newOptions.add(candidate);
                                }
                            }
                        }
                    }
                    else
                    {
                        // 空单元格（无默认值才算缺参）或类型违规（一律缺参，走补参链路，R11）
                        violations[r] = !text.isEmpty();
                        if (defaultValue == null || !text.isEmpty())
                        {
                            hasMissing = true;
                        }
                    }
                }
                excelValuesByColumn.put(column, excelValues);
                violationsByColumn.put(column, violations);
                if (hasMissing)
                {
                    stage2MissingColumns.add(columnName);
                }
                if (!newOptions.isEmpty())
                {
                    newOptionsByColumnId.put(column.getId(), newOptions);
                }
            }
        }

        // ===== 反向漂移 fail-fast（KTD5 全触发集） =====
        checkDrift(stage2MissingColumns, missNamesByColumn, ambiguityByColumn, params);

        // ===== 补参服务端校验（R26）与预解析 =====
        validateColumnValues(params, columnByName);
        RelationResolution relations = resolveRelationSelections(params, columnByName,
                headerIndexByColumn, contextByTableId, noteId, userId);

        // ===== 终解析：三源合并 + 补参应用，构建逐行写入计划 =====
        // 参与列默认值预解析（循环外一次解析 property JSON，避免逐空单元格重复解析）
        Map<Long, String> defaultValuesByColumnId = new HashMap<>();
        for (NoteColumn column : participants)
        {
            defaultValuesByColumnId.put(column.getId(), ColumnDefaultValueSupport.read(column));
        }
        ImportPlan plan = new ImportPlan();
        plan.noteId = noteId;
        plan.dwtableId = dwtableId;
        plan.userId = userId;
        plan.participants.addAll(participants);
        plan.newOptionsByColumnId = newOptionsByColumnId;
        for (int r = 0; r < rowCount; r++)
        {
            RowPlan rowPlan = new RowPlan();
            for (NoteColumn column : participants)
            {
                Long type = column.getType();
                boolean link = type == ExcelColumnMatcher.TYPE_SINGLE_LINK || type == ExcelColumnMatcher.TYPE_DOUBLE_LINK;
                NoteDwtableItem item = new NoteDwtableItem();
                item.setDwtId(dwtableId);
                item.setColumnId(column.getId());
                if (link)
                {
                    LinkColumnAnalysis analysis = linkAnalysisByColumn.get(column);
                    // analysis == null：表头未映射的关联列（关联缺列，漂移检查已保证 ∈ baseline）
                    List<NoteRecord> resolved = analysis == null ? Collections.<NoteRecord>emptyList()
                            : resolveLinkRecords(column, analysis.namesPerRow.get(r),
                                    relations, analysis.context.getRecordsByName());
                    if (!resolved.isEmpty())
                    {
                        // 21/18 列 link 字段经扩展批量 insert 落库（P0：不得被批量 insert 丢弃）
                        item.setLinkRecordId(joinRecordIds(resolved));
                        item.setValue(joinRecordNames(resolved));
                        item.setLinkColumnId(analysis.backFieldId);
                        if (type == ExcelColumnMatcher.TYPE_DOUBLE_LINK)
                        {
                            SymmetricLink symmetric = new SymmetricLink();
                            symmetric.sourceItem = item;
                            symmetric.backFieldId = analysis.backFieldId;
                            symmetric.relatedTableId = analysis.context.getTable().getId();
                            symmetric.records = resolved;
                            rowPlan.symmetricLinks.add(symmetric);
                        }
                    }
                    else
                    {
                        // R15：空单元格/显式留空/关联缺列 → 空串 value，不建立关联
                        item.setValue("");
                    }
                }
                else
                {
                    item.setValue(resolveBasicValue(column, r,
                            excelValuesByColumn.get(column), violationsByColumn.get(column),
                            params.getColumnValues(), defaultValuesByColumnId));
                }
                rowPlan.items.add(item);
            }
            // name 派生：首个 type=1 列的本行最终值（三源合并后，对齐 SQL 导入 deriveName）
            rowPlan.name = deriveRowName(nameColumnId, rowPlan.items);
            plan.rows.add(rowPlan);
        }

        // ===== 重算编排清单（KTD8） =====
        buildRecomputeTargets(plan, columns, headerIndexByColumn);
        return plan;
    }

    /**
     * 反向漂移 fail-fast（KTD5 全触发集，任一命中抛"数据已变化，请重新预检"整体拒绝）：
     * <ol>
     *   <li>阶段二未命中名 ∉ baseline.missNames（被关联记录被删等）；</li>
     *   <li>歧义集合与 baseline 不一致（新增同名记录 / 预选变化）；</li>
     *   <li>缺参列 ∉ baseline.missingColumns（列配置/默认值变化等）；</li>
     * </ol>
     * 补参 recordId 已不存在的校验在 {@link #resolveRelationSelections}（查库，KTD4 预解析记录集）。
     */
    private static void checkDrift(Set<String> stage2MissingColumns, Map<String, Set<String>> missNamesByColumn,
            Map<String, Map<String, Long>> ambiguityByColumn, ExcelImportParams params)
    {
        ExcelImportParams.PrecheckBaseline baseline = params.getPrecheckBaseline() == null
                ? new ExcelImportParams.PrecheckBaseline() : params.getPrecheckBaseline();

        // 1. 未命中名漂移
        Map<String, Set<String>> baselineMissByColumn = new HashMap<>();
        for (ExcelImportParams.BaselineName miss : baseline.getMissNames())
        {
            if (miss == null || miss.getColumnName() == null || miss.getName() == null)
            {
                continue;
            }
            baselineMissByColumn.computeIfAbsent(miss.getColumnName(), k -> new HashSet<>()).add(miss.getName());
        }
        for (Map.Entry<String, Set<String>> entry : missNamesByColumn.entrySet())
        {
            Set<String> baselineNames = baselineMissByColumn.get(entry.getKey());
            for (String name : entry.getValue())
            {
                if (baselineNames == null || !baselineNames.contains(name))
                {
                    throw driftException("关联列“" + entry.getKey() + "”出现预检之外的未命中名“" + name + "”");
                }
            }
        }

        // 2. 歧义集合漂移（新增同名记录 / 预选变化）
        Map<String, Map<String, Long>> baselineAmbiguityByColumn = new HashMap<>();
        for (ExcelImportParams.BaselineAmbiguity ambiguity : baseline.getAmbiguity())
        {
            if (ambiguity == null || ambiguity.getColumnName() == null || ambiguity.getName() == null)
            {
                continue;
            }
            baselineAmbiguityByColumn.computeIfAbsent(ambiguity.getColumnName(), k -> new HashMap<>())
                    .put(ambiguity.getName(), ambiguity.getPreselectedRecordId());
        }
        for (Map.Entry<String, Map<String, Long>> entry : ambiguityByColumn.entrySet())
        {
            Map<String, Long> baselineByColumn = baselineAmbiguityByColumn.get(entry.getKey());
            if (baselineByColumn == null)
            {
                baselineByColumn = Collections.emptyMap();
            }
            for (Map.Entry<String, Long> ambiguity : entry.getValue().entrySet())
            {
                Long baselinePreselect = baselineByColumn.get(ambiguity.getKey());
                if (baselinePreselect == null)
                {
                    throw driftException("关联列“" + entry.getKey() + "”的“" + ambiguity.getKey()
                            + "”在预检后出现新的同名记录");
                }
                if (!baselinePreselect.equals(ambiguity.getValue()))
                {
                    throw driftException("关联列“" + entry.getKey() + "”的“" + ambiguity.getKey()
                            + "”预选记录已变化");
                }
            }
        }

        // 3. 缺参列漂移
        Set<String> baselineMissingColumns = new HashSet<>();
        for (String columnName : baseline.getMissingColumns())
        {
            if (columnName != null)
            {
                baselineMissingColumns.add(columnName);
            }
        }
        for (String columnName : stage2MissingColumns)
        {
            if (!baselineMissingColumns.contains(columnName))
            {
                throw driftException("列“" + columnName + "”在预检后出现新的缺参");
            }
        }
    }

    private static ServiceException driftException(String detail)
    {
        return new ServiceException("数据已变化，请重新预检（" + detail + "）");
    }

    /**
     * 补参 columnValues 服务端校验（R26）：列须存在且为六类基础列，
     * 非空补值过 {@link ExcelColumnMatcher#validateCellText} 同语义类型校验（空串=显式补空）。
     */
    private static void validateColumnValues(ExcelImportParams params, Map<String, NoteColumn> columnByName)
    {
        for (Map.Entry<String, String> entry : params.getColumnValues().entrySet())
        {
            String columnName = entry.getKey() == null ? "" : entry.getKey().trim();
            String value = entry.getValue();
            if (value == null || value.trim().isEmpty())
            {
                continue;
            }
            NoteColumn column = columnByName.get(columnName);
            if (column == null)
            {
                throw new ServiceException("补参列“" + columnName + "”在目标数据表中不存在，请重新预检");
            }
            if (column.getType() == null || !BASIC_TYPES.contains(column.getType()))
            {
                throw new ServiceException("补参列“" + columnName + "”不是基础列，不支持文本补值，请重新预检");
            }
            String violation = ExcelColumnMatcher.validateCellText(column, value);
            if (violation != null)
            {
                throw new ServiceException("列“" + columnName + "”的补参值类型非法：" + violation);
            }
        }
    }

    /**
     * 补参 relationSelections 服务端校验（R26）与预解析：键=列名+名；
     * 列须为参与导入（表头映射）的 18/21 关联列；recordId 非空时须属于该列被关联表且存在
     * （查 KTD4 预解析记录集）——不存在即漂移拒绝。
     *
     * @return 列名 → {名 → 选择 / 名 → 选中记录} 双映射（补参优先语义的解析基础）
     */
    private RelationResolution resolveRelationSelections(ExcelImportParams params,
            Map<String, NoteColumn> columnByName, Map<NoteColumn, Integer> headerIndexByColumn,
            Map<Long, LinkTableContext> contextByTableId, Long noteId, Long userId)
    {
        RelationResolution resolution = new RelationResolution();
        for (ExcelImportParams.RelationSelection selection : params.getRelationSelections())
        {
            if (selection == null || selection.getColumnName() == null || selection.getName() == null)
            {
                throw new ServiceException("导入参数中的关联补参缺少列名或记录名，请重新预检");
            }
            String columnName = selection.getColumnName().trim();
            NoteColumn column = columnByName.get(columnName);
            if (column == null || column.getType() == null
                    || (column.getType() != ExcelColumnMatcher.TYPE_SINGLE_LINK
                            && column.getType() != ExcelColumnMatcher.TYPE_DOUBLE_LINK))
            {
                throw new ServiceException("补参选择的关联列“" + columnName + "”不存在或不是关联列，请重新预检");
            }
            if (!headerIndexByColumn.containsKey(column))
            {
                throw new ServiceException("补参选择的关联列“" + columnName + "”未参与本次导入，请重新预检");
            }
            resolution.selectionsByName
                    .computeIfAbsent(columnName, k -> new LinkedHashMap<String, ExcelImportParams.RelationSelection>())
                    .put(selection.getName(), selection);
            if (selection.getRecordId() != null)
            {
                // R26：recordId 须属于该列被关联表且存在（查库——KTD4 预解析记录集，id 索引 O(1)）
                LinkTableContext context = linkTableContext(column, noteId, userId, contextByTableId);
                NoteRecord record = context.getRecordsById().get(selection.getRecordId());
                if (record == null)
                {
                    throw driftException("关联列“" + columnName + "”补参选择的记录（id="
                            + selection.getRecordId() + "）不存在或已被删除");
                }
                resolution.selectedRecordsByName
                        .computeIfAbsent(columnName, k -> new LinkedHashMap<String, NoteRecord>())
                        .put(selection.getName(), record);
            }
        }
        return resolution;
    }

    /**
     * 解析一个关联单元格的最终记录序列（补参优先，KTD5）：
     * <ul>
     *   <li>补参覆盖（列名+名）以补参为准——recordId=null 显式留空不建立关联（即便重新匹配命中），
     *       recordId 非空用补选记录；</li>
     *   <li>未覆盖的键用当下匹配结果（无歧义命中或歧义预选 sort 靠前首条）；</li>
     *   <li>阶段二未命中且无补参选择：漂移检查已保证 ∈ baseline missNames，
     *       前端契约要求显式发射 null 选择——缺失即补参不完整，拒绝（禁止静默降级丢关联）。</li>
     * </ul>
     * 同单元格按记录 id 去重保序（"张三,张三" 或补选与命中共指同一记录）。
     */
    private static List<NoteRecord> resolveLinkRecords(NoteColumn column, List<String> names,
            RelationResolution relations, Map<String, List<NoteRecord>> recordsByName)
    {
        if (names == null || names.isEmpty())
        {
            return Collections.emptyList();
        }
        String columnName = column.getName() == null ? "" : column.getName().trim();
        Map<String, ExcelImportParams.RelationSelection> selectionsByName =
                relations.selectionsByName.getOrDefault(columnName, Collections.emptyMap());
        Map<String, NoteRecord> selectedRecordsByName =
                relations.selectedRecordsByName.getOrDefault(columnName, Collections.emptyMap());
        Map<Long, NoteRecord> resolved = new LinkedHashMap<>();
        for (String name : names)
        {
            ExcelImportParams.RelationSelection selection = selectionsByName.get(name);
            if (selection != null)
            {
                // 补参优先（KTD5）：覆盖的键以补参为准
                if (selection.getRecordId() != null)
                {
                    NoteRecord selected = selectedRecordsByName.get(name);
                    if (selected != null)
                    {
                        resolved.putIfAbsent(selected.getId(), selected);
                    }
                }
                continue;
            }
            List<NoteRecord> matched = recordsByName.get(name);
            if (matched != null && !matched.isEmpty())
            {
                resolved.putIfAbsent(matched.get(0).getId(), matched.get(0));
            }
            else
            {
                throw new ServiceException("关联列“" + columnName + "”的未命中名“" + name
                        + "”缺少补参选择，请重新预检并完成补参");
            }
        }
        return new ArrayList<>(resolved.values());
    }

    /**
     * 基础列单元格三源合并（R21）：Excel 值 &gt; 列默认值 &gt; 用户补参；NULL/空 → 空串。
     * 类型违规单元格不用 Excel 值也不用默认值（预检将其送入补参弹框，导入以补参为准）；
     * 复选框显示值映射 true→'0'（勾选）/false→'1'（未勾选）（U2 偏离决定，三源统一映射）。
     * 默认值经 {@code defaultValuesByColumnId} 预解析传入（避免逐单元格重复解析 property JSON）。
     */
    private static String resolveBasicValue(NoteColumn column, int rowIndex, String[] excelValues,
            boolean[] violations, Map<String, String> columnValues, Map<Long, String> defaultValuesByColumnId)
    {
        String columnName = column.getName() == null ? "" : column.getName().trim();
        if (excelValues != null && rowIndex < excelValues.length && excelValues[rowIndex] != null)
        {
            return mapCheckboxValue(column, excelValues[rowIndex]);
        }
        boolean violation = violations != null && rowIndex < violations.length && violations[rowIndex];
        if (!violation)
        {
            String defaultValue = defaultValuesByColumnId.get(column.getId());
            if (defaultValue != null)
            {
                return mapCheckboxValue(column, defaultValue);
            }
        }
        return mapCheckboxValue(column, columnValues.get(columnName));
    }

    /**
     * 复选框(7)显示值映射为 item.value 消费值：true→'0'（勾选）、false→'1'（未勾选）；
     * 其余类型原样返回；null → 空串（R21）。
     */
    private static String mapCheckboxValue(NoteColumn column, String value)
    {
        if (value == null)
        {
            return "";
        }
        if (column.getType() != null && column.getType() == TYPE_CHECKBOX)
        {
            String trimmed = value.trim();
            if ("true".equals(trimmed))
            {
                return "0";
            }
            if ("false".equals(trimmed))
            {
                return "1";
            }
        }
        return value;
    }

    /**
     * 派生行 name：首个 type=1 列在本行 items 中的最终值（无匹配/无该列返回空串），
     * 与 SQL 导入 deriveName / deriveRecordName 语义一致。
     */
    private static String deriveRowName(Long nameColumnId, List<NoteDwtableItem> items)
    {
        if (nameColumnId == null)
        {
            return "";
        }
        for (NoteDwtableItem item : items)
        {
            if (nameColumnId.equals(item.getColumnId()))
            {
                return item.getValue() == null ? "" : item.getValue();
            }
        }
        return "";
    }

    private static String joinRecordIds(List<NoteRecord> records)
    {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < records.size(); i++)
        {
            if (i > 0)
            {
                builder.append(",");
            }
            builder.append(records.get(i).getId());
        }
        return builder.toString();
    }

    private static String joinRecordNames(List<NoteRecord> records)
    {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < records.size(); i++)
        {
            if (i > 0)
            {
                builder.append(",");
            }
            builder.append(records.get(i).getName() == null ? "" : records.get(i).getName());
        }
        return builder.toString();
    }

    /**
     * 构建重算编排清单（KTD8）：
     * <ol>
     *   <li>目标表全部 lookup(26) 列（两步级联，含隐藏列——隐藏只影响显示不影响存储值）；</li>
     *   <li>目标表中 columnA/B 直接引用本次导入（已映射）18/21 列的集合运算列；</li>
     *   <li>每个对称写入涉及的（源 21 列 C，被关联表 B，配对列 P）：
     *       B 表中锚定列（double_link_column_id）指向 C 的 lookup 列（读取被修改的配对 P items）
     *       与 columnA/B 直接引用 P 的集合运算列。</li>
     * </ol>
     */
    private void buildRecomputeTargets(ImportPlan plan, List<NoteColumn> columns,
            Map<NoteColumn, Integer> headerIndexByColumn)
    {
        Set<Long> importedLinkColumnIds = new HashSet<>();
        for (NoteColumn column : columns)
        {
            if (column == null || column.getType() == null || ExcelColumnMatcher.isHidden(column))
            {
                continue;
            }
            Long type = column.getType();
            if ((type == ExcelColumnMatcher.TYPE_SINGLE_LINK || type == ExcelColumnMatcher.TYPE_DOUBLE_LINK)
                    && headerIndexByColumn.containsKey(column))
            {
                importedLinkColumnIds.add(column.getId());
            }
        }
        for (NoteColumn column : columns)
        {
            if (column == null || column.getType() == null)
            {
                continue;
            }
            if (column.getType() == TYPE_LOOKUP)
            {
                plan.targetLookupColumns.add(column);
            }
            else if (column.getType() == TYPE_SET_OPERATION
                    && (referencesColumnId(column, importedLinkColumnIds)))
            {
                plan.targetSetColumns.add(column);
            }
        }

        Map<Long, List<NoteColumn>> relatedColumnsByTableId = new HashMap<>();
        Set<Long> processedSourceColumns = new HashSet<>();
        for (RowPlan row : plan.rows)
        {
            for (SymmetricLink link : row.symmetricLinks)
            {
                Long sourceColumnId = link.sourceItem.getColumnId();
                if (!processedSourceColumns.add(sourceColumnId))
                {
                    continue;
                }
                List<NoteColumn> relatedColumns = relatedColumnsByTableId
                        .computeIfAbsent(link.relatedTableId, tableId -> {
                            NoteColumn query = new NoteColumn();
                            query.setDwtableId(tableId);
                            return noteColumnMapper.selectNoteColumnList(query);
                        });
                SymmetricTarget target = new SymmetricTarget();
                String sourceColumnIdStr = String.valueOf(sourceColumnId);
                for (NoteColumn related : relatedColumns)
                {
                    if (related == null || related.getType() == null)
                    {
                        continue;
                    }
                    if (related.getType() == TYPE_LOOKUP)
                    {
                        // B 表锚定列指向源列 C：该 lookup 读取被对称写入修改的配对 P items
                        String anchor = parsePropertySafe(related).getString("double_link_column_id");
                        if (anchor != null && sourceColumnIdStr.equals(anchor.trim()))
                        {
                            target.relatedLookups.add(related);
                        }
                    }
                    else if (related.getType() == TYPE_SET_OPERATION)
                    {
                        // B 表 columnA/B 直接引用配对列 P（读取配对 P items）
                        if (link.backFieldId != null && referencesColumnId(related,
                                Collections.singleton(link.backFieldId)))
                        {
                            target.relatedSetColumns.add(related);
                        }
                    }
                }
                plan.symmetricTargets.add(target);
            }
        }
    }

    /**
     * 集合运算列的 columnAId/columnBId 是否引用指定列 id 集合中的任一列。
     */
    private static boolean referencesColumnId(NoteColumn setColumn, Set<Long> columnIds)
    {
        JSONObject prop = parsePropertySafe(setColumn);
        return containsColumnId(prop.getString("columnAId"), columnIds)
                || containsColumnId(prop.getString("columnBId"), columnIds);
    }

    private static boolean containsColumnId(String value, Set<Long> columnIds)
    {
        if (value == null || value.trim().isEmpty())
        {
            return false;
        }
        try
        {
            return columnIds.contains(Long.valueOf(value.trim()));
        }
        catch (NumberFormatException e)
        {
            return false;
        }
    }

    /**
     * 事务写入段（R22，经自注入代理进入）：#13 漂移触发集补"列被删"方向——入口按事务段
     * 时点重查目标表参与列与被关联表配对列存在性（fail-fast 于任何写入之前）→
     * 逐行 NoteRecord insert（sort=maxSort+1 递增、name 已派生、自增 id 回填）→
     * items 累积 ≥500 flush → 选项追加（FOR UPDATE）→
     * items 全量最终 flush（零缓冲不变量）→ 双链对称写入 → 重算编排 → 返回 recordCount。
     * 任一步骤抛 {@link ServiceException} 整体回滚（含被关联表存量 item 修改与选项追加）。
     */
    @Transactional(rollbackFor = Exception.class)
    public int executeImport(ImportPlan plan)
    {
        int recordCount = 0;
        if (!plan.rows.isEmpty())
        {
            verifyParticipantsExist(plan);
            verifyBackFieldsExist(plan);
            Long maxSort = noteRecordMapper.selectMaxSortByDwtableId(plan.dwtableId);
            long sort = (maxSort == null ? 0L : maxSort.longValue()) + 1L;
            List<NoteDwtableItem> batch = new ArrayList<>(BATCH_LIMIT);
            for (RowPlan row : plan.rows)
            {
                NoteRecord record = new NoteRecord();
                record.setDwtableId(plan.dwtableId);
                record.setSort(Long.valueOf(sort++));
                record.setName(row.name);
                // viewId/property/linkRecordId/linkName 保持 null（对齐 SQL 导入）
                noteRecordMapper.insertNoteRecord(record);
                row.record = record;
                for (NoteDwtableItem item : row.items)
                {
                    item.setRecordId(record.getId());
                }
                batch.addAll(row.items);
                recordCount++;
                if (batch.size() >= BATCH_LIMIT)
                {
                    noteDwtableItemMapper.insertNoteDwtableItems(batch);
                    // 重新分配而非 clear：已传递给 mapper 的列表不再原地变更
                    batch = new ArrayList<>(BATCH_LIMIT);
                }
            }
            // 单选/多选未知选项追加（R19，FOR UPDATE 锁定读改写，每列上限 100）
            appendNewOptions(plan);
            // 零缓冲不变量：全部本表 item 落库有自增 id（对称写入的 linkItemId 须引用之）
            if (!batch.isEmpty())
            {
                noteDwtableItemMapper.insertNoteDwtableItems(batch);
            }
            executeSymmetricWrite(plan);
            executeRecompute(plan);
        }
        log.info("[EXCEL-IMPORT] 导入完成 noteId={}, dwtableId={}, userId={}, recordCount={}, "
                        + "newOptionColumns={}, symmetricTargets={}",
                plan.noteId, plan.dwtableId, plan.userId, recordCount,
                plan.newOptionsByColumnId.size(), plan.symmetricTargets.size());
        return recordCount;
    }

    /**
     * #13 漂移校验（目标表参与列方向）：事务段入口重查目标表列清单，校验前置段快照的
     * 全部参与列仍存在——防前置段列快照与事务段之间参与列被并发删除（TOCTOU 窄窗口）
     * 导致批量 insert 写入指向已删列的孤儿 item。缺失抛 driftException（含列名）。
     */
    private void verifyParticipantsExist(ImportPlan plan)
    {
        NoteColumn query = new NoteColumn();
        query.setDwtableId(plan.dwtableId);
        Set<Long> currentColumnIds = new HashSet<>();
        for (NoteColumn current : noteColumnMapper.selectNoteColumnList(query))
        {
            if (current != null && current.getId() != null)
            {
                currentColumnIds.add(current.getId());
            }
        }
        for (NoteColumn participant : plan.participants)
        {
            if (!currentColumnIds.contains(participant.getId()))
            {
                throw driftException("列“" + (participant.getName() == null ? "" : participant.getName())
                        + "”已被删除");
            }
        }
    }

    /**
     * #13 漂移校验（被关联表配对列方向）：双链对称写入前按 (relatedTableId, backFieldId)
     * 全集（直接遍历 plan.rows 的 symmetricLinks，不随 buildRecomputeTargets 的
     * processedSourceColumns 去重漏配对）校验每个配对列仍存在于被关联表——
     * 列删除不清理源列 property 的 back_field_id 残留引用（parseBackFieldId 读到旧值不报错），
     * 必须显式校验配对列存在性，否则 upsert 会写入指向已删列的孤儿配对 item。
     */
    private void verifyBackFieldsExist(ImportPlan plan)
    {
        Map<Long, String> sourceNamesByColumnId = new HashMap<>();
        for (NoteColumn participant : plan.participants)
        {
            sourceNamesByColumnId.put(participant.getId(),
                    participant.getName() == null ? "" : participant.getName());
        }
        Map<Long, Set<Long>> backFieldIdsByTableId = new LinkedHashMap<>();
        Map<Long, String> sourceNameByBackFieldId = new HashMap<>();
        for (RowPlan row : plan.rows)
        {
            for (SymmetricLink link : row.symmetricLinks)
            {
                if (link.backFieldId != null)
                {
                    backFieldIdsByTableId
                            .computeIfAbsent(link.relatedTableId, k -> new LinkedHashSet<>())
                            .add(link.backFieldId);
                    sourceNameByBackFieldId.putIfAbsent(link.backFieldId,
                            sourceNamesByColumnId.get(link.sourceItem.getColumnId()));
                }
            }
        }
        for (Map.Entry<Long, Set<Long>> entry : backFieldIdsByTableId.entrySet())
        {
            NoteColumn query = new NoteColumn();
            query.setDwtableId(entry.getKey());
            Set<Long> relatedColumnIds = new HashSet<>();
            for (NoteColumn related : noteColumnMapper.selectNoteColumnList(query))
            {
                if (related != null && related.getId() != null)
                {
                    relatedColumnIds.add(related.getId());
                }
            }
            for (Long backFieldId : entry.getValue())
            {
                if (!relatedColumnIds.contains(backFieldId))
                {
                    throw driftException("关联列“" + sourceNameByBackFieldId.get(backFieldId)
                            + "”的配对列（id=" + backFieldId + "）已被删除");
                }
            }
        }
    }

    /**
     * 单选/多选未知选项追加（R19）：读列 property（FOR UPDATE 锁定）→ select 逗号字符串
     * 去重追加新选项 → 直更 property（保留 select 之外的其他键，KTD6 保留不变量）；
     * 每列每次导入新选项上限 {@value #NEW_OPTION_LIMIT}，超限抛 {@link ServiceException} 中止回滚。
     */
    private void appendNewOptions(ImportPlan plan)
    {
        for (Map.Entry<Long, LinkedHashSet<String>> entry : plan.newOptionsByColumnId.entrySet())
        {
            LinkedHashSet<String> candidates = entry.getValue();
            if (candidates.isEmpty())
            {
                continue;
            }
            NoteColumn locked = noteColumnMapper.selectNoteColumnByIdForUpdate(entry.getKey());
            if (locked == null)
            {
                throw driftException("选项列（id=" + entry.getKey() + "）已被删除");
            }
            JSONObject prop = parsePropertySafe(locked);
            String select = prop.getString(ColumnDefaultValueSupport.SELECT_KEY);
            Set<String> existing = ColumnDefaultValueSupport.parseSelectOptions(locked);
            List<String> toAppend = new ArrayList<>();
            for (String candidate : candidates)
            {
                if (!existing.contains(candidate) && !toAppend.contains(candidate))
                {
                    toAppend.add(candidate);
                }
            }
            if (toAppend.isEmpty())
            {
                continue;
            }
            if (toAppend.size() > NEW_OPTION_LIMIT)
            {
                throw new ServiceException("列“" + (locked.getName() == null ? "" : locked.getName())
                        + "”本次导入将新增选项 " + toAppend.size() + " 个，超过上限 " + NEW_OPTION_LIMIT
                        + "，请先整理选项后重试");
            }
            String merged = (select == null || select.trim().isEmpty())
                    ? String.join(",", toAppend) : select + "," + String.join(",", toAppend);
            prop.put(ColumnDefaultValueSupport.SELECT_KEY, merged);
            locked.setProperty(prop.toJSONString());
            noteColumnMapper.updateNoteColumn(locked);
        }
    }

    /**
     * 双链(21)对称写入（KTD3，独立实现不复用 updateNoteRecord）：
     * <ul>
     *   <li>按配对 item（recordId+columnId）聚合——同配对 item 的全部追加在内存合并后单次落库；</li>
     *   <li>落库前 SELECT ... FOR UPDATE 锁定涉及行；锁内复核行存在性，不存在则 upsert 创建
     *       （KTD3 修正②：UI 路径 null-NPE 缺陷不复刻）；</li>
     *   <li>配对 item 四字段：linkRecordId 追加新记录 id、linkItemId 追加本表新 item id、
     *       value 追加新记录 name（去重键=recordId，KTD3 修正①：同名新记录都追加各自 name，
     *       保持 value 与 linkRecordId 等长）、linkColumnId=源列 id；</li>
     *   <li>本表源 item 回写 linkRecordId（命中记录 id 列表，批量 insert 已带）/
     *       linkItemId（配对 item id 列表，与 linkRecordId 顺序对齐）/linkColumnId（back_field_id）/
     *       value（命中记录 name 列表，批量 insert 已带）；</li>
     *   <li>序列化一律 String.join——绝不用 List.toString().replace。</li>
     * </ul>
     */
    private void executeSymmetricWrite(ImportPlan plan)
    {
        if (plan.rows.isEmpty())
        {
            return;
        }
        // 聚合：配对 item 键（recordId + columnId）→ 追加明细（保持行/列/记录顺序，确定性加锁顺序）
        Map<Long, Map<Long, PairedGroup>> groupsByRecordId = new LinkedHashMap<>();
        for (RowPlan row : plan.rows)
        {
            for (SymmetricLink link : row.symmetricLinks)
            {
                for (NoteRecord target : link.records)
                {
                    groupsByRecordId
                            .computeIfAbsent(target.getId(), k -> new LinkedHashMap<Long, PairedGroup>())
                            .computeIfAbsent(link.backFieldId, k -> new PairedGroup(link.relatedTableId))
                            .appends.add(new SymmetricAppend(row.record, link.sourceItem));
                }
            }
        }
        // 配对 item 结果：(recordId, columnId) → 落库后的配对 item（含自增 id），供源 item 回写 linkItemId
        Map<Long, Map<Long, NoteDwtableItem>> pairedByRecordAndColumn = new HashMap<>();
        for (Map.Entry<Long, Map<Long, PairedGroup>> byRecord : groupsByRecordId.entrySet())
        {
            for (Map.Entry<Long, PairedGroup> byColumn : byRecord.getValue().entrySet())
            {
                Long targetRecordId = byRecord.getKey();
                Long pairedColumnId = byColumn.getKey();
                PairedGroup group = byColumn.getValue();
                for (SymmetricAppend append : group.appends)
                {
                    if (append.sourceItem.getId() == null)
                    {
                        // 零缓冲不变量防御：批量 insert 未回填自增 id 时宁可中止不可写坏数据
                        throw new ServiceException("导入失败：单元格数据落库后未取得自增 id，无法完成关联写入");
                    }
                }
                // KTD3：update 前 SELECT ... FOR UPDATE 锁定；锁内复核存在性，不存在则 upsert 创建
                NoteDwtableItem query = new NoteDwtableItem();
                query.setRecordId(targetRecordId);
                query.setColumnId(pairedColumnId);
                NoteDwtableItem paired = noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumnForUpdate(query);
                boolean created = paired == null;
                if (created)
                {
                    paired = new NoteDwtableItem();
                    paired.setDwtId(group.relatedTableId);
                    paired.setRecordId(targetRecordId);
                    paired.setColumnId(pairedColumnId);
                }
                // 内存合并全部追加（同配对 item 单次落库，无覆盖丢失）
                Set<String> linkRecordIds = splitCsvToSet(paired.getLinkRecordId());
                List<String> linkItemIds = splitCsvToList(paired.getLinkItemId());
                List<String> values = splitCsvToList(paired.getValue());
                for (SymmetricAppend append : group.appends)
                {
                    String sourceRecordId = String.valueOf(append.sourceRecord.getId());
                    // value 追加去重键=recordId（KTD3 修正①）：r 追加时才追加其 name
                    if (linkRecordIds.add(sourceRecordId))
                    {
                        values.add(append.sourceRecord.getName() == null ? "" : append.sourceRecord.getName());
                    }
                    String sourceItemId = String.valueOf(append.sourceItem.getId());
                    if (!linkItemIds.contains(sourceItemId))
                    {
                        linkItemIds.add(sourceItemId);
                    }
                    paired.setLinkColumnId(append.sourceItem.getColumnId());
                }
                paired.setLinkRecordId(String.join(",", linkRecordIds));
                paired.setLinkItemId(String.join(",", linkItemIds));
                paired.setValue(String.join(",", values));
                if (created)
                {
                    noteDwtableItemMapper.insertNoteDwtableItem(paired);
                }
                else
                {
                    noteDwtableItemMapper.updateNoteDwtableItem(paired);
                }
                pairedByRecordAndColumn
                        .computeIfAbsent(targetRecordId, k -> new HashMap<Long, NoteDwtableItem>())
                        .put(pairedColumnId, paired);
            }
        }
        // 本表源 item 回写 linkItemId（与 linkRecordId 顺序对齐，单次 update）
        for (RowPlan row : plan.rows)
        {
            for (SymmetricLink link : row.symmetricLinks)
            {
                List<String> pairedItemIds = new ArrayList<>(link.records.size());
                for (NoteRecord target : link.records)
                {
                    NoteDwtableItem paired = pairedByRecordAndColumn.get(target.getId()).get(link.backFieldId);
                    pairedItemIds.add(String.valueOf(paired.getId()));
                }
                link.sourceItem.setLinkItemId(String.join(",", pairedItemIds));
                noteDwtableItemMapper.updateNoteDwtableItem(link.sourceItem);
            }
        }
    }

    /**
     * 重算编排（KTD8，先 flush 后调用，经 {@link INoteRecordService} 接口——Spring 代理
     * 加入导入事务）：目标表全部 lookup 列两步级联 → 目标表直接引用导入 18/21 列的集合运算列 →
     * 对称写入涉及的被关联表以配对列为源的 lookup 与集合运算列；按列 id 去重避免重复重算。
     */
    private void executeRecompute(ImportPlan plan)
    {
        Set<Long> recomputedLookups = new HashSet<>();
        Set<Long> recomputedSetColumns = new HashSet<>();
        for (NoteColumn lookup : plan.targetLookupColumns)
        {
            if (!recomputedLookups.add(lookup.getId()))
            {
                continue;
            }
            noteRecordService.recomputeLookupColumnValues(lookup);
            noteRecordService.recomputeSetOperationsForLookup(lookup);
        }
        for (NoteColumn setColumn : plan.targetSetColumns)
        {
            if (!recomputedSetColumns.add(setColumn.getId()))
            {
                continue;
            }
            noteRecordService.recomputeSetOperationColumn(setColumn);
        }
        for (SymmetricTarget target : plan.symmetricTargets)
        {
            for (NoteColumn lookup : target.relatedLookups)
            {
                if (!recomputedLookups.add(lookup.getId()))
                {
                    continue;
                }
                noteRecordService.recomputeLookupColumnValues(lookup);
                noteRecordService.recomputeSetOperationsForLookup(lookup);
            }
            for (NoteColumn setColumn : target.relatedSetColumns)
            {
                if (!recomputedSetColumns.add(setColumn.getId()))
                {
                    continue;
                }
                noteRecordService.recomputeSetOperationColumn(setColumn);
            }
        }
    }

    // ==================== U5 内部结构 ====================

    /** 导入计划（非事务前置段产物，写入段消费） */
    static final class ImportPlan
    {
        Long noteId;

        Long dwtableId;

        Long userId;

        /** 逐行写入计划（与数据行等长同序） */
        final List<RowPlan> rows = new ArrayList<>();

        /** 全部参与列（#13 事务段入口列存在性校验用，含错误消息所需列名） */
        final List<NoteColumn> participants = new ArrayList<>();

        /** 列 id → 有序去重新选项（R19，事务段 FOR UPDATE 追加） */
        Map<Long, LinkedHashSet<String>> newOptionsByColumnId = Collections.emptyMap();

        /** 目标表全部 lookup(26) 列（KTD8 两步级联） */
        final List<NoteColumn> targetLookupColumns = new ArrayList<>();

        /** 目标表中 columnA/B 直接引用导入 18/21 列的集合运算列（KTD8） */
        final List<NoteColumn> targetSetColumns = new ArrayList<>();

        /** 对称写入涉及的（源 21 列 → 被关联表派生列）编排清单（KTD8） */
        final List<SymmetricTarget> symmetricTargets = new ArrayList<>();
    }

    /** 单行写入计划 */
    private static final class RowPlan
    {
        /** 本行 NoteRecord（写入段 insert 后回填，含自增 id 与 name） */
        NoteRecord record;

        /** 派生 name（首个 type=1 列最终值） */
        String name;

        /** 全部参与列的 item（批量 insert 用，写入段回填 recordId） */
        final List<NoteDwtableItem> items = new ArrayList<>();

        /** 21 列的对称写入明细 */
        final List<SymmetricLink> symmetricLinks = new ArrayList<>();
    }

    /** 单个 21 列在一行上的对称写入明细 */
    private static final class SymmetricLink
    {
        /** 源 item（(本行记录, 源 21 列)，批量 insert 后有自增 id；columnId=源列 C） */
        NoteDwtableItem sourceItem;

        /** 配对列 P（源列 back_field_id，被关联表 B 上） */
        Long backFieldId;

        /** 被关联表 B id（配对 item 不存在时 upsert 的 dwtId） */
        Long relatedTableId;

        /** 命中/补选的被关联记录（有序去重） */
        List<NoteRecord> records;
    }

    /** 一次对配对 item 的追加（源记录 + 源 item） */
    private static final class SymmetricAppend
    {
        final NoteRecord sourceRecord;

        final NoteDwtableItem sourceItem;

        SymmetricAppend(NoteRecord sourceRecord, NoteDwtableItem sourceItem)
        {
            this.sourceRecord = sourceRecord;
            this.sourceItem = sourceItem;
        }
    }

    /** 配对 item 聚合组（同一 (recordId, columnId) 的全部追加） */
    private static final class PairedGroup
    {
        final Long relatedTableId;

        final List<SymmetricAppend> appends = new ArrayList<>();

        PairedGroup(Long relatedTableId)
        {
            this.relatedTableId = relatedTableId;
        }
    }

    /** 对称写入涉及的被关联表派生列清单（KTD8 编排条目） */
    private static final class SymmetricTarget
    {
        /** B 表中锚定列（double_link_column_id）指向源 21 列 C 的 lookup 列 */
        final List<NoteColumn> relatedLookups = new ArrayList<>();

        /** B 表中 columnA/B 直接引用配对列 P 的集合运算列 */
        final List<NoteColumn> relatedSetColumns = new ArrayList<>();
    }

    /** 关联列的阶段二分析产物 */
    private static final class LinkColumnAnalysis
    {
        /** 被关联表上下文（R23 已校验 + KTD4 预解析） */
        LinkTableContext context;

        /** 配对列（21 必填 / 18 可空） */
        Long backFieldId;

        /** 逐行名字序列（KTD4 整串优先拆分） */
        final List<List<String>> namesPerRow = new ArrayList<>();
    }

    /** 补参 relationSelections 的解析产物（键=列名 trim） */
    private static final class RelationResolution
    {
        /** 列名 → 名 → 用户选择（含显式 recordId=null） */
        final Map<String, Map<String, ExcelImportParams.RelationSelection>> selectionsByName = new LinkedHashMap<>();

        /** 列名 → 名 → 选中记录（仅 recordId 非空且查库存在） */
        final Map<String, Map<String, NoteRecord>> selectedRecordsByName = new LinkedHashMap<>();
    }

    /**
     * 逗号串拆为去重保序集合（linkRecordId 追加语义：contains 去重）。
     */
    private static Set<String> splitCsvToSet(String value)
    {
        Set<String> result = new LinkedHashSet<>();
        if (value != null && !value.isEmpty())
        {
            for (String part : value.split(","))
            {
                result.add(part);
            }
        }
        return result;
    }

    /**
     * 逗号串拆为有序列表（value/linkItemId 保序追加语义；空串 → 空列表）。
     */
    private static List<String> splitCsvToList(String value)
    {
        List<String> result = new ArrayList<>();
        if (value != null && !value.isEmpty())
        {
            result.addAll(Arrays.asList(value.split(",")));
        }
        return result;
    }
}
