package com.ruoyi.system.service.impl;

import java.util.*;
import java.util.stream.Collectors;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.domain.*;
import com.ruoyi.system.domain.dto.ExportColumn;
import com.ruoyi.system.domain.dto.ExportMatrix;
import com.ruoyi.system.domain.vo.NoteRecordVo;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.INoteDwtableExportPivotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 多维表格导出 pivot 服务实现。
 * <p>
 * 把 noteId 下每张数据表的 EAV 单元格 pivot 成行×列矩阵（{@link ExportMatrix}）。
 * 按列类型渲染规则产出每行的列值：
 * <ul>
 *   <li>记录 id 列（R6）：每表首位，值=NoteRecord.id，SQL 中唯一可 JOIN 的标量锚点</li>
 *   <li>基础类型列（1/2/3/4/5/7/11/13/15/17/22）：直接取 item.value（R11）</li>
 *   <li>系统列（1001-1005）：按语义输出可读值（R12）</li>
 *   <li>双向关联(21)/单向关联(18)：双列——ID=linkRecordId，文本=关联记录 name（21 列名称含逗号/引号时 CSV 风格转义，R1；18 列维持现状）</li>
 *   <li>lookup(26)：双列——ID 经 double_link_column_id 回溯，文本=source_column_id 值</li>
 *   <li>集合运算(24)：双列——ID=item.linkRecordId（best-effort），文本=记录 name</li>
 *   <li>语义关联(25)：双列——ID=NoteNotelink 行 id，文本=每条链接"引用笔记名（锚词）"条目串（R2，引用来源为 noteId 侧笔记）</li>
 * </ul>
 * 隐藏列（isShow=1）不导出（R5）。空表仍产出列定义（R20）。
 * <p>
 * 性能：构建 recordId→(columnId→item) 内存索引避免 N+1；关联列文本批量预取；
 * 25 列引用笔记标题与表级归属笔记解析走导出全程共享缓存（miss 才落库，对齐 recordNameCache 先例）。
 *
 * @author ruoyi
 */
@Service
public class NoteDwtableExportPivotServiceImpl implements INoteDwtableExportPivotService
{
    private static final Logger log = LoggerFactory.getLogger(NoteDwtableExportPivotServiceImpl.class);

    /** 列类型常量 */
    private static final long TYPE_TEXT = 1L;
    private static final long TYPE_NUMBER = 2L;
    private static final long TYPE_SINGLE_SELECT = 3L;
    private static final long TYPE_MULTI_SELECT = 4L;
    private static final long TYPE_DATE = 5L;
    private static final long TYPE_CHECKBOX = 7L;
    private static final long TYPE_PERSON = 11L;
    private static final long TYPE_PHONE = 13L;
    private static final long TYPE_HYPERLINK = 15L;
    private static final long TYPE_ATTACHMENT = 17L;
    private static final long TYPE_SINGLE_LINK = 18L;
    private static final long TYPE_DOUBLE_LINK = 21L;
    private static final long TYPE_LOCATION = 22L;
    private static final long TYPE_SET_OPERATION = 24L;
    private static final long TYPE_SEMANTIC_LINK = 25L;
    private static final long TYPE_LOOKUP = 26L;

    private static final long TYPE_SYS_CREATE_TIME = 1001L;
    private static final long TYPE_SYS_UPDATE_TIME = 1002L;
    private static final long TYPE_SYS_CREATE_BY = 1003L;
    private static final long TYPE_SYS_UPDATE_BY = 1004L;
    private static final long TYPE_SYS_AUTO_NUMBER = 1005L;

    /** 隐藏列标记：isShow=1 表示隐藏（baseTable/related/index.vue filter(item => !item.isShow)） */
    private static final long IS_SHOW_HIDDEN = 1L;

    /** 基础类型集合——直接输出 value，单列 */
    private static final Set<Long> BASIC_TYPES = new HashSet<>(Arrays.asList(
            TYPE_TEXT, TYPE_NUMBER, TYPE_SINGLE_SELECT, TYPE_MULTI_SELECT, TYPE_DATE,
            TYPE_CHECKBOX, TYPE_PERSON, TYPE_PHONE, TYPE_HYPERLINK, TYPE_ATTACHMENT, TYPE_LOCATION));

    /** 双列类型集合——关联/派生类列 */
    private static final Set<Long> DUAL_TYPES = new HashSet<>(Arrays.asList(
            TYPE_SINGLE_LINK, TYPE_DOUBLE_LINK, TYPE_SET_OPERATION, TYPE_SEMANTIC_LINK, TYPE_LOOKUP));

    /** PII 列类型集合——人员(11)、创建人(1003)、修改人(1004) */
    public static final Set<Long> PII_TYPES = new HashSet<>(Arrays.asList(TYPE_PERSON, TYPE_SYS_CREATE_BY, TYPE_SYS_UPDATE_BY));

    @Autowired
    private NoteDwtableMapper noteDwtableMapper;

    @Autowired
    private NoteColumnMapper noteColumnMapper;

    @Autowired
    private NoteRecordMapper noteRecordMapper;

    @Autowired
    private NoteDwtableItemMapper noteDwtableItemMapper;

    @Autowired
    private NoteNotelinkMapper noteNotelinkMapper;

    @Autowired
    private NoteNoteMapper noteNoteMapper;

    @Override
    public List<ExportMatrix> pivot(Long noteId)
    {
        List<ExportMatrix> matrices = new ArrayList<>();
        // 查询 noteId 下全部数据表
        List<NoteDwtable> tables = noteDwtableMapper.selectDWTableList(noteId);
        if (tables == null || tables.isEmpty())
        {
            return matrices;
        }
        // 笔记标题缓存（对齐 recordNameCache 先例）：25 列逐行解析引用笔记标题改为缓存
        // miss 才落库，导出全程（跨表）共享——同一来源笔记只查一次，悬空笔记以空串负缓存
        Map<Long, String> noteTitleCache = new HashMap<>();
        // 表级归属笔记缓存：resolveSemanticSourceNoteId 的 linkDwTableId→NoteDwtable.noteId
        // 兜底查询同样导出全程共享（含 null 负缓存），多行链接指向同一目标表时只查一次
        Map<Long, Long> tableOwnerNoteIdCache = new HashMap<>();
        for (NoteDwtable table : tables)
        {
            ExportMatrix matrix = pivotTable(table, noteTitleCache, tableOwnerNoteIdCache);
            matrices.add(matrix);
        }
        return matrices;
    }

    /**
     * 把单张数据表 pivot 成导出矩阵。
     *
     * @param noteTitleCache 笔记标题缓存（导出全程共享，跨表复用）
     * @param tableOwnerNoteIdCache 表级归属笔记缓存（导出全程共享，跨表复用）
     */
    private ExportMatrix pivotTable(NoteDwtable table, Map<Long, String> noteTitleCache,
            Map<Long, Long> tableOwnerNoteIdCache)
    {
        ExportMatrix matrix = new ExportMatrix();
        matrix.setDwtableId(table.getId());
        matrix.setTableName(table.getName());

        // 查列定义，过滤隐藏列（R5），按 sort 排序
        NoteColumn colQuery = new NoteColumn();
        colQuery.setDwtableId(table.getId());
        List<NoteColumn> allColumns = noteColumnMapper.selectNoteColumnList(colQuery);
        List<NoteColumn> visibleColumns = filterVisibleColumns(allColumns);

        // 查记录，按 sort 排序（mapper XML order by sort）
        NoteRecordVo recordQuery = new NoteRecordVo();
        recordQuery.setDwtableId(table.getId());
        List<NoteRecord> records = noteRecordMapper.selectNoteRecordList(recordQuery);

        // 查全部单元格，构建 recordId→(columnId→item) 索引
        NoteDwtableItem itemQuery = new NoteDwtableItem();
        itemQuery.setDwtId(table.getId());
        List<NoteDwtableItem> items = noteDwtableItemMapper.selectNoteDwtableItemList(itemQuery);
        Map<Long, Map<Long, NoteDwtableItem>> itemIndex = buildItemIndex(items);

        // 构建列定义：记录 id 列首位 + 可见列
        List<ExportColumn> exportColumns = buildExportColumns(visibleColumns);
        matrix.setColumns(exportColumns);

        // 批量预取关联记录名称（避免逐行 N+1）
        Map<Long, String> recordNameCache = batchPrefetchRecordNames(visibleColumns, itemIndex, records);

        // 构建行数据
        List<List<String>> rows = new ArrayList<>();
        if (records != null)
        {
            for (NoteRecord record : records)
            {
                List<String> row = buildRow(record, visibleColumns, itemIndex, recordNameCache, table,
                        noteTitleCache, tableOwnerNoteIdCache);
                rows.add(row);
            }
        }
        matrix.setRows(rows);

        log.debug("[EXPORT-PIVOT] table={}, columns={}, rows={}", table.getName(), exportColumns.size(), rows.size());
        return matrix;
    }

    /**
     * 过滤隐藏列（isShow=1），保留可见列（isShow=0 或 null），按 sort 排序。
     */
    private List<NoteColumn> filterVisibleColumns(List<NoteColumn> allColumns)
    {
        if (allColumns == null || allColumns.isEmpty())
        {
            return new ArrayList<>();
        }
        return allColumns.stream()
                .filter(c -> c.getIsShow() == null || c.getIsShow() != IS_SHOW_HIDDEN)
                .sorted(Comparator.comparing(c -> c.getSort() == null ? Long.MAX_VALUE : c.getSort()))
                .collect(Collectors.toList());
    }

    /**
     * 构建记录 id→(columnId→item) 内存索引，避免逐单元格查 DB。
     */
    private Map<Long, Map<Long, NoteDwtableItem>> buildItemIndex(List<NoteDwtableItem> items)
    {
        Map<Long, Map<Long, NoteDwtableItem>> index = new HashMap<>();
        if (items == null)
        {
            return index;
        }
        for (NoteDwtableItem item : items)
        {
            index.computeIfAbsent(item.getRecordId(), k -> new HashMap<>())
                    .put(item.getColumnId(), item);
        }
        return index;
    }

    /**
     * 构建导出列定义：首位记录 id 列 + 可见列。
     * 关联/派生类列标记为双列。
     */
    private List<ExportColumn> buildExportColumns(List<NoteColumn> visibleColumns)
    {
        List<ExportColumn> exportColumns = new ArrayList<>();
        // 记录 id 列（R6）——合成列，columnId=null，type=null
        exportColumns.add(new ExportColumn(null, "record_id", null, false, true));
        for (NoteColumn col : visibleColumns)
        {
            boolean dual = DUAL_TYPES.contains(col.getType());
            exportColumns.add(new ExportColumn(col.getId(), col.getName(), col.getType(), dual, false));
        }
        return exportColumns;
    }

    /**
     * 批量预取关联记录的 name，构建 recordId→name 缓存。
     * 收集所有关联列（21/18/24/26）引用的全部 recordId，一次 IN 查询取回 name。
     */
    private Map<Long, String> batchPrefetchRecordNames(List<NoteColumn> columns,
            Map<Long, Map<Long, NoteDwtableItem>> itemIndex, List<NoteRecord> records)
    {
        Map<Long, String> cache = new HashMap<>();
        if (records == null || records.isEmpty())
        {
            return cache;
        }
        // 收集所有关联列引用的 recordId
        Set<String> refIdStrs = new HashSet<>();
        for (NoteRecord record : records)
        {
            Map<Long, NoteDwtableItem> rowItems = itemIndex.get(record.getId());
            if (rowItems == null)
            {
                continue;
            }
            for (NoteColumn col : columns)
            {
                if (!DUAL_TYPES.contains(col.getType()))
                {
                    continue;
                }
                NoteDwtableItem item = rowItems.get(col.getId());
                if (item == null || item.getLinkRecordId() == null || item.getLinkRecordId().isEmpty())
                {
                    continue;
                }
                for (String id : item.getLinkRecordId().split(","))
                {
                    if (!id.isEmpty())
                    {
                        refIdStrs.add(id);
                    }
                }
            }
        }
        if (refIdStrs.isEmpty())
        {
            return cache;
        }
        // 批量查询记录名称
        String[] idArray = refIdStrs.toArray(new String[0]);
        List<NoteRecord> refRecords = noteRecordMapper.selectNoteRecordByIds(idArray);
        if (refRecords != null)
        {
            for (NoteRecord r : refRecords)
            {
                cache.put(r.getId(), r.getName());
            }
        }
        return cache;
    }

    /**
     * 构建单行数据：按列定义顺序产出单元格值列表。
     * 双列在行数据中占两个相邻位置（ID 在前，文本在后）。
     */
    private List<String> buildRow(NoteRecord record, List<NoteColumn> visibleColumns,
            Map<Long, Map<Long, NoteDwtableItem>> itemIndex, Map<Long, String> recordNameCache,
            NoteDwtable table, Map<Long, String> noteTitleCache, Map<Long, Long> tableOwnerNoteIdCache)
    {
        List<String> row = new ArrayList<>();
        // 记录 id 列（R6）
        row.add(String.valueOf(record.getId()));

        Map<Long, NoteDwtableItem> rowItems = itemIndex.getOrDefault(record.getId(), Collections.emptyMap());

        for (NoteColumn col : visibleColumns)
        {
            Long type = col.getType();
            if (BASIC_TYPES.contains(type))
            {
                // 基础类型：直接取 value（R11）
                NoteDwtableItem item = rowItems.get(col.getId());
                row.add(item != null && item.getValue() != null ? item.getValue() : "");
            }
            else if (isSystemColumn(type))
            {
                // 系统列：按语义输出可读值（R12）
                row.add(resolveSystemColumnValue(type, record));
            }
            else if (DUAL_TYPES.contains(type))
            {
                // 关联/派生类列：双列（ID + 文本）
                resolveDualColumn(col, record, rowItems, row, recordNameCache, table,
                        noteTitleCache, tableOwnerNoteIdCache);
            }
            else
            {
                // 未知类型：输出空值
                row.add("");
                log.warn("[EXPORT-PIVOT] 未知列类型 columnId={}, type={}, 输出空值", col.getId(), type);
            }
        }
        return row;
    }

    /**
     * 解析关联/派生类列的双列值（ID + 文本），追加到行数据。
     */
    private void resolveDualColumn(NoteColumn col, NoteRecord record,
            Map<Long, NoteDwtableItem> rowItems, List<String> row,
            Map<Long, String> recordNameCache, NoteDwtable table,
            Map<Long, String> noteTitleCache, Map<Long, Long> tableOwnerNoteIdCache)
    {
        Long type = col.getType();
        if (type == TYPE_DOUBLE_LINK)
        {
            // 双向关联(21)：ID=linkRecordId 原样；文本=记录 name，名称含逗号/引号时 CSV 风格转义（R1）
            NoteDwtableItem item = rowItems.get(col.getId());
            String idValue = (item != null && item.getLinkRecordId() != null) ? item.getLinkRecordId() : "";
            String textValue = resolveRecordNames(idValue, recordNameCache, true);
            row.add(idValue);
            row.add(textValue);
        }
        else if (type == TYPE_SINGLE_LINK)
        {
            // 单向关联(18)：ID=linkRecordId，文本=记录 name（维持现状不转义，18 列行为回归红线）
            NoteDwtableItem item = rowItems.get(col.getId());
            String idValue = (item != null && item.getLinkRecordId() != null) ? item.getLinkRecordId() : "";
            String textValue = resolveRecordNames(idValue, recordNameCache);
            row.add(idValue);
            row.add(textValue);
        }
        else if (type == TYPE_LOOKUP)
        {
            // lookup(26)：经 double_link_column_id 回溯到底层双向关联列的 linkRecordId
            resolveLookupColumn(col, record, rowItems, row, recordNameCache);
        }
        else if (type == TYPE_SET_OPERATION)
        {
            // 集合运算(24)：best-effort 从 item.linkRecordId 取结果列表
            NoteDwtableItem item = rowItems.get(col.getId());
            String idValue = (item != null && item.getLinkRecordId() != null) ? item.getLinkRecordId() : "";
            String textValue = resolveRecordNames(idValue, recordNameCache);
            row.add(idValue);
            row.add(textValue);
        }
        else if (type == TYPE_SEMANTIC_LINK)
        {
            // 语义关联(25)：ID=NoteNotelink 行 id，文本="引用笔记名（锚词）"条目串（R2/R4）
            resolveSemanticLinkColumn(col, record, rowItems, row, table,
                    noteTitleCache, tableOwnerNoteIdCache);
        }
    }

    /**
     * lookup(26) 列解析：
     * 经 property.double_link_column_id 回溯到底层双向关联列 item 的 linkRecordId（R8）；
     * 文本列 = source_column_id 在那些记录上的值。
     */
    private void resolveLookupColumn(NoteColumn col, NoteRecord record,
            Map<Long, NoteDwtableItem> rowItems, List<String> row,
            Map<Long, String> recordNameCache)
    {
        String idValue = "";
        String textValue = "";
        try
        {
            JSONObject prop = JSONObject.parseObject(col.getProperty());
            if (prop == null)
            {
                row.add("");
                row.add("");
                return;
            }
            String doubleLinkColIdStr = prop.get("double_link_column_id") != null
                    ? prop.get("double_link_column_id").toString() : null;
            String sourceColIdStr = prop.get("source_column_id") != null
                    ? prop.get("source_column_id").toString() : null;

            if (doubleLinkColIdStr != null)
            {
                // 回溯到底层双向关联列的 item 取 linkRecordId
                Long doubleLinkColId = Long.valueOf(doubleLinkColIdStr);
                NoteDwtableItem doubleLinkItem = rowItems.get(doubleLinkColId);
                if (doubleLinkItem != null && doubleLinkItem.getLinkRecordId() != null
                        && !doubleLinkItem.getLinkRecordId().isEmpty())
                {
                    idValue = doubleLinkItem.getLinkRecordId();
                }
            }

            // 文本列：source_column_id 在关联记录上的值
            if (sourceColIdStr != null && !idValue.isEmpty())
            {
                textValue = resolveSourceColumnValues(idValue, sourceColIdStr, recordNameCache);
            }
        }
        catch (Exception e)
        {
            log.warn("[EXPORT-PIVOT] lookup 列解析失败 columnId={}, recordId={}, error={}",
                    col.getId(), record.getId(), e.getMessage());
        }
        row.add(idValue);
        row.add(textValue);
    }

    /**
     * 解析 lookup 列的 source_column_id 在关联记录上的值。
     * 简化实现：逐记录查 item.value；后续可优化为批量 IN 查询。
     */
    private String resolveSourceColumnValues(String linkRecordIds, String sourceColIdStr,
            Map<Long, String> recordNameCache)
    {
        List<String> values = new ArrayList<>();
        Long sourceColId = Long.valueOf(sourceColIdStr);
        for (String rid : linkRecordIds.split(","))
        {
            if (rid.isEmpty())
            {
                continue;
            }
            try
            {
                Long recordId = Long.valueOf(rid.trim());
                // 查 source_column_id 在该记录上的 item
                NoteDwtableItem query = new NoteDwtableItem();
                query.setRecordId(recordId);
                query.setColumnId(sourceColId);
                NoteDwtableItem srcItem = noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(query);
                if (srcItem != null && srcItem.getValue() != null)
                {
                    values.add(srcItem.getValue());
                }
                else
                {
                    values.add(recordNameCache.getOrDefault(recordId, ""));
                }
            }
            catch (NumberFormatException e)
            {
                log.warn("[EXPORT-PIVOT] 解析 source_column_id 值失败 recordId={}", rid);
            }
        }
        return String.join(",", values);
    }

    /**
     * 语义关联(25) 列解析：
     * 从 NoteNotelink 按 (linkColumnId, linkItemId) 查询（mapper 按行 id 升序返回）；
     * 文本列 = 每条链接的"引用笔记名（锚词）"条目串——引用来源为 NoteNotelink 的 noteId 侧笔记
     * （noteId 为 null 的历史行经 COALESCE 式兜底解析），锚词=contextText（空则回退 itemValue），
     * 锚词为空省略全角括号；多值逗号连接、CSV 风格转义（R2）；
     * ID 列 = NoteNotelink 行 id 串，与名称条目顺序一一对应（R2）。
     * 标题解析走导出全程共享 noteTitleCache（miss 才落库）：同一来源笔记被多行链接
     * 引用时只查一次库，表级归属兜底解析同样共享缓存。
     * 悬空引用（来源笔记已删/无法解析）整体跳过该条目（名称与行 id 均不写），不报错不阻断（R4）。
     */
    private void resolveSemanticLinkColumn(NoteColumn col, NoteRecord record,
            Map<Long, NoteDwtableItem> rowItems, List<String> row, NoteDwtable table,
            Map<Long, String> noteTitleCache, Map<Long, Long> tableOwnerNoteIdCache)
    {
        String idValue = "";
        String textValue = "";
        try
        {
            NoteDwtableItem item = rowItems.get(col.getId());
            if (item == null || item.getId() == null)
            {
                row.add("");
                row.add("");
                return;
            }
            // 按 (linkColumnId, linkItemId) 查 NoteNotelink
            List<NoteNotelink> links = noteNotelinkMapper.selectNoteNotelinkByCell(col.getId(), item.getId());
            if (links == null || links.isEmpty())
            {
                row.add("");
                row.add("");
                return;
            }
            // 逐行产出"引用笔记名（锚词）"条目与行 id，悬空行整体跳过（保持名称与 id 条目一一对应）
            List<String> entries = new ArrayList<>();
            List<String> linkIds = new ArrayList<>();
            for (NoteNotelink link : links)
            {
                // 引用来源笔记：noteId 侧；历史 null 行经 COALESCE 式兜底解析
                Long sourceNoteId = resolveSemanticSourceNoteId(link, tableOwnerNoteIdCache);
                if (sourceNoteId == null)
                {
                    continue;
                }
                // 标题走导出全程共享缓存（miss 才落库，对齐 recordNameCache 先例）：
                // 同一笔记被多行链接引用只查一次，悬空笔记以空串负缓存
                String sourceTitle = resolveNoteTitle(sourceNoteId, noteTitleCache);
                if (sourceTitle == null || sourceTitle.isEmpty())
                {
                    // 悬空引用：来源笔记已删，跳过条目不阻断（R4）
                    continue;
                }
                // 锚词：contextText 优先，空则回退 itemValue；都为空省略括号
                String anchor = link.getContextText();
                if (anchor == null || anchor.isEmpty())
                {
                    anchor = link.getItemValue();
                }
                String entry = (anchor != null && !anchor.isEmpty())
                        ? sourceTitle + "（" + anchor + "）"
                        : sourceTitle;
                entries.add(escapeCsvValue(entry));
                linkIds.add(String.valueOf(link.getId()));
            }
            if (!entries.isEmpty())
            {
                textValue = String.join(",", entries);
                idValue = String.join(",", linkIds);
            }
        }
        catch (Exception e)
        {
            log.warn("[EXPORT-PIVOT] 语义关联列解析失败 columnId={}, recordId={}, error={}",
                    col.getId(), record.getId(), e.getMessage());
        }
        row.add(idValue);
        row.add(textValue);
    }

    /**
     * 解析语义链接行的引用来源笔记 id：
     * 优先 NoteNotelink.noteId（引用来源）；为 null 的历史行经 COALESCE 式兜底——
     * 先取 linkNoteId（目标表归属笔记），仍为 null 时从 linkDwTableId→NoteDwtable.noteId 取
     * （对齐 selectNoteNotelinkByNoteId 的 COALESCE 兜底模式）。
     * 表级归属笔记查询走导出全程共享缓存（miss 才落库，含 null 负缓存）——
     * 多行链接指向同一目标表时只查一次。
     */
    private Long resolveSemanticSourceNoteId(NoteNotelink link, Map<Long, Long> tableOwnerNoteIdCache)
    {
        if (link.getNoteId() != null)
        {
            return link.getNoteId();
        }
        if (link.getLinkNoteId() != null)
        {
            return link.getLinkNoteId();
        }
        if (link.getLinkDwTableId() != null)
        {
            Long dwTableId = link.getLinkDwTableId();
            if (!tableOwnerNoteIdCache.containsKey(dwTableId))
            {
                NoteDwtable linkedTable = noteDwtableMapper.selectNoteDwtableById(dwTableId);
                tableOwnerNoteIdCache.put(dwTableId,
                        linkedTable != null ? linkedTable.getNoteId() : null);
            }
            return tableOwnerNoteIdCache.get(dwTableId);
        }
        return null;
    }

    /**
     * 解析来源笔记标题（25 列文本列用）：导出全程共享缓存 miss 才落库
     * （对齐 recordNameCache 先例）；悬空笔记（已删/无标题）以空串负缓存，
     * 避免同一悬空引用被多行链接重复查询。
     */
    private String resolveNoteTitle(Long noteId, Map<Long, String> noteTitleCache)
    {
        if (!noteTitleCache.containsKey(noteId))
        {
            NoteNote note = noteNoteMapper.selectNoteNoteById(noteId);
            noteTitleCache.put(noteId,
                    note != null && note.getTitle() != null ? note.getTitle() : "");
        }
        return noteTitleCache.get(noteId);
    }

    /**
     * 把逗号分隔的 recordId 列表解析为对应的记录 name 列表（不转义，18/24 列现状行为）。
     */
    private String resolveRecordNames(String linkRecordIds, Map<Long, String> recordNameCache)
    {
        return resolveRecordNames(linkRecordIds, recordNameCache, false);
    }

    /**
     * 把逗号分隔的 recordId 列表解析为对应的记录 name 列表。
     *
     * @param csvEscape true 时按 CSV 风格转义每个名称——名称含逗号/引号时整名双引号包裹、
     *                  内层双引号翻倍（R1）；悬空引用的空占位不参与转义
     */
    private String resolveRecordNames(String linkRecordIds, Map<Long, String> recordNameCache, boolean csvEscape)
    {
        if (linkRecordIds == null || linkRecordIds.isEmpty())
        {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (String id : linkRecordIds.split(","))
        {
            if (id.isEmpty())
            {
                continue;
            }
            try
            {
                Long rid = Long.valueOf(id.trim());
                String name = recordNameCache.getOrDefault(rid, "");
                names.add(csvEscape ? escapeCsvValue(name) : name);
            }
            catch (NumberFormatException e)
            {
                names.add("");
            }
        }
        return String.join(",", names);
    }

    /**
     * CSV 风格转义（R1）：名称含逗号或双引号时，整名用双引号包裹、内层双引号翻倍；
     * 空串原样返回（悬空引用的空占位不包裹）。
     */
    private String escapeCsvValue(String value)
    {
        if (value == null || value.isEmpty())
        {
            return "";
        }
        if (value.contains(",") || value.contains("\""))
        {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * 系统列值解析（R12）：
     * 1001 创建时间 → createTime；1002 最后更新时间 → updateTime；
     * 1003 创建人 → createBy；1004 修改人 → updateBy；1005 自动编号 → record.id。
     */
    private String resolveSystemColumnValue(Long type, NoteRecord record)
    {
        switch (type.intValue())
        {
            case 1001:
                return record.getCreateTime() != null ? record.getCreateTime().toString() : "";
            case 1002:
                return record.getUpdateTime() != null ? record.getUpdateTime().toString() : "";
            case 1003:
                return record.getCreateBy() != null ? record.getCreateBy() : "";
            case 1004:
                return record.getUpdateBy() != null ? record.getUpdateBy() : "";
            case 1005:
                return String.valueOf(record.getId());
            default:
                return "";
        }
    }

    private boolean isSystemColumn(Long type)
    {
        return type != null && type >= 1001L && type <= 1005L;
    }
}
