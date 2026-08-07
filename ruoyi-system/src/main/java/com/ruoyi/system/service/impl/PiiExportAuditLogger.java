package com.ruoyi.system.service.impl;

import java.time.Instant;
import java.util.List;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.domain.dto.ExportColumn;
import com.ruoyi.system.domain.dto.ExportMatrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PII 导出审计 logger（KTD11）。
 * <p>
 * 导出含 PII 列（人员 11、创建人 1003、修改人 1004）的表时，写一条 JSON 行到
 * {@code logs/pii-export-audit.log}，与 {@code sys_oper_log} 解耦。
 * <p>
 * 审计字段（R27 + 数据量字段）：
 * operatorUserId、operatorName、noteId、tableName、format、timestamp、containsPii、
 * recordCount、shardCount、zipBytes。
 * <p>
 * 审计写入失败不阻断导出（catch + error log）。
 * 需在 logback.xml 配置 {@code piiExportAudit} logger 的 appender（独立文件 + 轮转）。
 *
 * @author ruoyi
 */
@Component
public class PiiExportAuditLogger
{
    /** 专用 logger 名称，logback.xml 中需配置独立 appender 指向 logs/pii-export-audit.log */
    private static final Logger piiAuditLog = LoggerFactory.getLogger("piiExportAudit");

    /** PII 列类型集合——人员(11)、创建人(1003)、修改人(1004) */
    private static final java.util.Set<Long> PII_TYPES = NoteDwtableExportPivotServiceImpl.PII_TYPES;

    /**
     * 检查并记录含 PII 列的数据表审计日志。
     * 不含 PII 列的表不写专用日志（仍写基线 @Log）。
     *
     * @param matrix      单表导出矩阵
     * @param noteId      导出来源 noteId
     * @param format      导出格式
     * @param operatorUserId 导出人 userId
     * @param operatorName   导出人名称
     * @param zipBytes    整次导出物字节大小
     * @param shardCount  该表分片数
     */
    public void logIfPii(ExportMatrix matrix, Long noteId, String format,
            Long operatorUserId, String operatorName, long zipBytes, int shardCount)
    {
        if (matrix == null || matrix.getColumns() == null)
        {
            return;
        }
        boolean containsPii = matrix.getColumns().stream()
                .anyMatch(c -> c.getType() != null && PII_TYPES.contains(c.getType()));
        if (!containsPii)
        {
            return;
        }
        try
        {
            JSONObject record = new JSONObject();
            record.put("operatorUserId", operatorUserId);
            record.put("operatorName", operatorName);
            record.put("noteId", noteId);
            record.put("tableName", matrix.getTableName());
            record.put("format", format);
            record.put("timestamp", Instant.now().toString());
            record.put("containsPii", true);
            record.put("recordCount", matrix.getRows() != null ? matrix.getRows().size() : 0);
            record.put("shardCount", shardCount);
            record.put("zipBytes", zipBytes);
            piiAuditLog.info(record.toJSONString());
        }
        catch (Exception e)
        {
            // 审计写入失败不阻断导出，但记 error log 供监控告警
            LoggerFactory.getLogger(PiiExportAuditLogger.class)
                    .error("[PII-AUDIT] 审计写入失败 noteId={}, table={}, error={}",
                            noteId, matrix.getTableName(), e.getMessage(), e);
        }
    }
}
