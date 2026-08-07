package com.ruoyi.system.service.impl;

import java.util.ArrayList;
import java.util.List;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.agent.security.AgentOwnershipChecker;
import com.ruoyi.system.domain.dto.ExportFile;
import com.ruoyi.system.domain.dto.ExportMatrix;
import com.ruoyi.system.service.INoteDwtableExportService;
import com.ruoyi.system.service.INoteDwtableExportPivotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 多维表格导出编排服务实现。
 * <p>
 * 链路：归属校验 → pivot → 渲染（Excel/SQL） → 打包（zip+manifest） → PII 审计。
 * <p>
 * 安全（KTD8-KTD11）：
 * <ul>
 *   <li>归属校验：{@link AgentOwnershipChecker#checkNoteOwnership(Long, Long)}，admin 通行</li>
 *   <li>PII 审计：含 PII 列的表经 {@link PiiExportAuditLogger} 写专用日志</li>
 *   <li>SQL 转义/zip slip 防护在渲染/打包层处理</li>
 * </ul>
 *
 * @author ruoyi
 */
@Service
public class NoteDwtableExportServiceImpl implements INoteDwtableExportService
{
    private static final Logger log = LoggerFactory.getLogger(NoteDwtableExportServiceImpl.class);

    /** 多表叠加总记录数上限兜底（初始建议 10000，Deferred 校准） */
    private static final int TOTAL_RECORDS_LIMIT = 10000;

    @Autowired
    private AgentOwnershipChecker agentOwnershipChecker;

    @Autowired
    private INoteDwtableExportPivotService pivotService;

    @Autowired
    private NoteDwtableExcelRenderer excelRenderer;

    @Autowired
    private NoteDwtableSqlRenderer sqlRenderer;

    @Autowired
    private NoteDwtableExportPackager packager;

    @Autowired
    private PiiExportAuditLogger piiAuditLogger;

    @Override
    public byte[] export(Long noteId, String format, Long userId, String userName)
    {
        // 1. 归属校验（R19）：admin 通行
        agentOwnershipChecker.checkNoteOwnership(noteId, userId);

        // 2. pivot EAV → 矩阵
        List<ExportMatrix> matrices = pivotService.pivot(noteId);

        // 3. 多表叠加超时兜底
        int totalRecords = matrices.stream()
                .mapToInt(m -> m.getRows() != null ? m.getRows().size() : 0)
                .sum();
        if (totalRecords > TOTAL_RECORDS_LIMIT)
        {
            throw new ServiceException("数据量过大（" + totalRecords + " 条记录），请缩小数据范围后重试");
        }

        // 4. 渲染
        List<ExportFile> files = new ArrayList<>();
        if ("sql".equalsIgnoreCase(format))
        {
            for (ExportMatrix matrix : matrices)
            {
                files.addAll(sqlRenderer.render(matrix));
            }
        }
        else
        {
            // 默认 Excel
            format = "excel";
            ExportFile workbook = excelRenderer.render(matrices);
            files.add(workbook);
        }

        // 5. 打包 zip + manifest
        byte[] zipBytes = packager.packageExport(files, noteId, format, matrices);

        // 6. PII 审计（KTD11）：含 PII 列的表写专用日志
        for (ExportMatrix matrix : matrices)
        {
            int rows = matrix.getRows() != null ? matrix.getRows().size() : 0;
            int shardCount = rows <= NoteDwtableSqlRenderer.SHARD_SIZE ? 1
                    : (rows + NoteDwtableSqlRenderer.SHARD_SIZE - 1) / NoteDwtableSqlRenderer.SHARD_SIZE;
            piiAuditLogger.logIfPii(matrix, noteId, format, userId, userName, zipBytes.length, shardCount);
        }

        log.info("[EXPORT] noteId={}, format={}, tables={}, totalRecords={}, zipBytes={}",
                noteId, format, matrices.size(), totalRecords, zipBytes.length);
        return zipBytes;
    }
}
