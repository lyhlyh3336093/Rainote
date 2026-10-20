package com.ruoyi.system.service;

import java.io.File;

/**
 * 整机迁移备份/还原Service接口
 *
 * @author ruoyi
 */
public interface IBackupRestoreService
{
    /**
     * 触发整机备份（KTD3 入口薄壳）：加触发级写锁后委托 exportInternal 导出，
     * 产出备份包落盘 backupPath 备份槽位（backup.zip，保留最近 1 份）。
     *
     * @return 备份包文件
     */
    public File backup();
}
