package com.ruoyi.system.service;

/**
 * 多维表格导出编排服务。
 * <p>
 * 串联归属校验 → pivot → 渲染 → 打包 → PII 审计链路，产出 zip byte[]。
 *
 * @author ruoyi
 */
public interface INoteDwtableExportService
{
    /**
     * 导出多维表格为 zip 字节。
     *
     * @param noteId 多维表格（笔记）ID
     * @param format 导出格式：excel / sql
     * @param userId 当前操作用户ID
     * @param userName 当前操作用户名
     * @return zip 字节
     */
    byte[] export(Long noteId, String format, Long userId, String userName);
}
