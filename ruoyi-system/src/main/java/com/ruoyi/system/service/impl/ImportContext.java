package com.ruoyi.system.service.impl;

import java.util.Map;

import com.ruoyi.system.domain.NoteColumn;

/**
 * 导入上下文：承载一次导入的表级元数据（KTD2/KTD4/KTD5）。
 * <p>
 * 由 {@link NoteDwtableImportServiceImpl} 在导入开始时构建，
 * 贯穿行循环避免长参数列表：
 * <ul>
 *   <li>noteId/dwtableId/userId：目标与操作者（日志审计，R26 不含单元格值）</li>
 *   <li>columnByName：目标表列名→列定义映射（已排除排除类型列，列名 trim，
 *       匹配时区分大小写 {@code String.equals}，KTD4）</li>
 *   <li>sort 起点：目标表当前最大 sort + 1，逐行递增分配（F2 追加语义）</li>
 *   <li>batchLimit：NoteDwtableItem 批量 insert 单批单元格上限（Deferred 初始 500）</li>
 * </ul>
 *
 * @author ruoyi
 */
class ImportContext
{
    /** 目标笔记 id */
    final Long noteId;

    /** 目标多维表格 id */
    final Long dwtableId;

    /** 操作用户 id（仅日志审计） */
    final Long userId;

    /** 目标表列名（trim）→ 列定义映射（不含排除类型列） */
    final Map<String, NoteColumn> columnByName;

    /** name 派生源列 id（目标表按 sort 排序的首个 type=1 列；无则为 null，name 派生为空串） */
    final Long nameColumnId;

    /** 下一行分配的 sort 值（初始 = 目标表当前最大 sort + 1） */
    private long nextSort;

    /** NoteDwtableItem 批量 insert 单批上限（单元格数） */
    final int batchLimit;

    ImportContext(Long noteId, Long dwtableId, Long userId,
                  Map<String, NoteColumn> columnByName, Long nameColumnId,
                  long firstSort, int batchLimit)
    {
        this.noteId = noteId;
        this.dwtableId = dwtableId;
        this.userId = userId;
        this.columnByName = columnByName;
        this.nameColumnId = nameColumnId;
        this.nextSort = firstSort;
        this.batchLimit = batchLimit;
    }

    /**
     * 分配当前行的 sort 并推进下一行（F2：导入记录 sort 递增，
     * 区别于 UI 新增路径的 sort=null）。
     */
    long allocateSort()
    {
        return nextSort++;
    }
}
