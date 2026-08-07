package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.dto.ExportMatrix;

/**
 * 多维表格导出 pivot 服务。
 * <p>
 * 把 noteId 下每张数据表的 EAV 单元格 pivot 成行×列矩阵（{@link ExportMatrix}），
 * 按列类型渲染规则产出每行的列值（含关联列双列、记录 id 列、派生列取值语义）。
 * <p>
 * 与 {@link INoteDwtableService#selectNoteDwtableDataById} 的展示载荷不同——
 * 导出矩阵的形态（关联列双列、记录 id 列必出、隐藏列过滤）服务于离线分析，独立产出（KTD1）。
 *
 * @author ruoyi
 */
public interface INoteDwtableExportPivotService
{
    /**
     * 把 noteId 下全部数据表 pivot 成导出矩阵列表。
     *
     * @param noteId 多维表格（笔记）ID
     * @return 每张数据表对应的导出矩阵，按 NoteDwtable 查询顺序
     */
    List<ExportMatrix> pivot(Long noteId);
}
