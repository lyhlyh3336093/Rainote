package com.ruoyi.system.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.NoteDwtableItem;
import com.ruoyi.system.domain.NoteRecord;
import com.ruoyi.system.domain.dto.ParsedInsert;
import com.ruoyi.system.mapper.NoteColumnMapper;
import com.ruoyi.system.mapper.NoteDwtableItemMapper;
import com.ruoyi.system.mapper.NoteRecordMapper;
import com.ruoyi.system.service.INoteRecordService;
import com.ruoyi.system.service.INoteDwtableImportService;

/**
 * 多维表格导入核心服务（R9-R19，KTD2/KTD5）。
 * <p>
 * 单事务（{@code @Transactional(rollbackFor = Exception.class)}）批量写入：
 * <ul>
 *   <li>每行 {@code NoteRecord} 单条 insert 取自增 id（KTD5）</li>
 *   <li>同行 {@code NoteDwtableItem} 累积后通过 MyBatis {@code foreach} 批量 insert
 *       （单批上限 500 单元格，Deferred），全程 {@code #{}} 参数化禁 {@code ${}}</li>
 *   <li>默认值策略重新实现（KTD2）：dwtableId=目标表 id；viewId/property/linkRecordId/linkName=null；
 *       sort=当前最大 sort+1 递增（F2 追加语义，区别 UI 新路径 sort=null）；
 *       name 由 {@code deriveRecordName(dwtableId, null, items)} 派生（不调用整个 insertNoteRecord）</li>
 *   <li>列名区分大小写 {@code String.equals} 匹配（KTD4，无全角/半角归一化）</li>
 *   <li>排除类型列（18/20/21/23/24/25/26）不进映射 → SQL 侧与目标表侧统一跳过（R12）</li>
 *   <li>NULL → 空串（R15）；link 字段不导入（R16）</li>
 *   <li>任一步骤抛 {@link ServiceException} 整体回滚（R19）</li>
 * </ul>
 * 日志只记录列名/行号/跳过原因等元数据，不记录单元格值（R26/PII）。
 *
 * @author ruoyi
 */
@Service
public class NoteDwtableImportServiceImpl implements INoteDwtableImportService
{
    private static final Logger log = LoggerFactory.getLogger(NoteDwtableImportServiceImpl.class);

    /**
     * 排除类型清单（R12）：18 单向关联 / 20 公式 / 21 双向链接 / 23 数学公式 /
     * 24 集合运算 / 25 语义关联 / 26 lookup。
     * 参考 {@link NoteDwtableExportPivotServiceImpl} DUAL_TYPES（18/21/24/25/26）
     * 额外排除 20 公式与 23 数学公式。
     */
    private static final Set<Long> EXCLUDED_TYPES = new HashSet<>(java.util.Arrays.asList(
            18L, 20L, 21L, 23L, 24L, 25L, 26L));

    /** NoteDwtableItem 批量 insert 单批上限（单元格数，Deferred 初始 500） */
    private static final int BATCH_LIMIT = 500;

    @Autowired
    private NoteColumnMapper noteColumnMapper;

    @Autowired
    private NoteRecordMapper noteRecordMapper;

    @Autowired
    private NoteDwtableItemMapper noteDwtableItemMapper;

    @Autowired
    private INoteRecordService noteRecordService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int importData(Long noteId, Long dwtableId, List<ParsedInsert> parsedList, Long userId)
    {
        if (dwtableId == null)
        {
            throw new ServiceException("导入失败：目标多维表格 id 为空");
        }
        if (parsedList == null || parsedList.isEmpty())
        {
            throw new ServiceException("导入失败：未解析到任何 INSERT 语句");
        }
        ImportContext ctx = buildContext(noteId, dwtableId, userId);
        List<NoteDwtableItem> batch = new ArrayList<>();
        int recordCount = 0;
        for (ParsedInsert parsed : parsedList)
        {
            recordCount += appendRecord(ctx, parsed, batch);
            if (batch.size() >= ctx.batchLimit)
            {
                noteDwtableItemMapper.insertNoteDwtableItems(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty())
        {
            noteDwtableItemMapper.insertNoteDwtableItems(batch);
        }
        log.info("[DWTABLE-IMPORT] 导入完成 noteId={}, dwtableId={}, userId={}, recordCount={}, columnCount={}",
                noteId, dwtableId, userId, recordCount, ctx.columnByName.size());
        return recordCount;
    }

    /**
     * 构建导入上下文：查目标表列定义、构建列名映射（排除类型列）、确定 sort 起点。
     */
    private ImportContext buildContext(Long noteId, Long dwtableId, Long userId)
    {
        NoteColumn query = new NoteColumn();
        query.setDwtableId(dwtableId);
        List<NoteColumn> columns = noteColumnMapper.selectNoteColumnList(query);
        Map<String, NoteColumn> columnByName = new HashMap<>();
        for (NoteColumn column : columns)
        {
            if (column.getName() == null || column.getType() == null)
            {
                continue;
            }
            if (EXCLUDED_TYPES.contains(column.getType()))
            {
                continue;
            }
            columnByName.put(column.getName().trim(), column);
        }
        // F2：导入 sort = 当前最大 sort + 1 递增（空表视为 0 → 从 1 开始）
        Long maxSort = noteRecordMapper.selectMaxSortByDwtableId(dwtableId);
        long firstSort = (maxSort == null ? 0L : maxSort) + 1;
        return new ImportContext(noteId, dwtableId, userId, columnByName, firstSort, BATCH_LIMIT);
    }

    /**
     * 追加一行：构建 items（列名匹配/跳过）→ 派生 name → insert NoteRecord（取自增 id）
     * → 回填 recordId → items 累积进 batch。
     *
     * @return 1（成功追加一行）
     */
    private int appendRecord(ImportContext ctx, ParsedInsert parsed, List<NoteDwtableItem> batch)
    {
        List<NoteDwtableItem> rowItems = new ArrayList<>();
        for (Map.Entry<String, String> entry : parsed.getColumnValues().entrySet())
        {
            String columnName = entry.getKey();
            NoteColumn column = ctx.columnByName.get(columnName);
            if (column == null)
            {
                // SQL 列在目标表不存在（或为排除类型列）→ 跳过（AE2/AE4，R26 不记录值）
                log.info("[DWTABLE-IMPORT] 跳过未匹配列 noteId={}, dwtableId={}, sourceRecordId={}, columnName={}",
                        ctx.noteId, ctx.dwtableId, parsed.getRecordId(), columnName);
                continue;
            }
            NoteDwtableItem item = new NoteDwtableItem();
            item.setDwtId(ctx.dwtableId);
            item.setColumnId(column.getId());
            // NULL → 空串（R15，与 insertNoteRecord 的 setValue 契约一致）；link 字段不导入（R16）
            item.setValue(entry.getValue() == null ? "" : entry.getValue());
            rowItems.add(item);
        }
        NoteRecord record = new NoteRecord();
        record.setDwtableId(ctx.dwtableId);
        record.setSort(ctx.allocateSort());
        // viewId/property/linkRecordId/linkName 保持 null（KTD2 UI 新增路径默认值）
        // name 派生（KTD2：直接调 deriveRecordName，第三参数传 NoteDwtableItem 列表）
        record.setName(noteRecordService.deriveRecordName(ctx.dwtableId, null, rowItems));
        noteRecordMapper.insertNoteRecord(record);
        for (NoteDwtableItem item : rowItems)
        {
            item.setRecordId(record.getId());
        }
        batch.addAll(rowItems);
        return 1;
    }
}
