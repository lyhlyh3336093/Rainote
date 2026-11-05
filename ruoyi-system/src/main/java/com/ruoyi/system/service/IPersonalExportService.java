package com.ruoyi.system.service;

import java.io.OutputStream;

/**
 * VIP 个人数据导出服务（U9/R14 调整后：VIP 备份权益以按用户范围的导出实现）。
 * <p>
 * 只打包当前用户本人的数据（笔记 auth=userId 及其关联的表格/列/视图/记录/单元格 + 本人上传文件），
 * 与整机备份是两个隔离的信任边界——绝不调用 BackupRestoreServiceImpl 的整机路径
 * （整机含全站全部用户数据与密码哈希，保持仅管理员）。
 *
 * @author ruoyi
 * @date 2026-11-04
 */
public interface IPersonalExportService
{
    /**
     * 将用户本人数据以 zip 流式写出至输出流
     *
     * @param userId 用户ID（数据属主）
     * @param out 目标输出流（调用方负责关闭）
     */
    void writeZipTo(Long userId, OutputStream out) throws Exception;
}
