package com.ruoyi.system.service;

import java.io.File;

import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.system.domain.dto.RestoreResult;

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

    /**
     * 还原校验与预览（U2，不写库）：解压防护（路径穿越/体量上限）→ manifest 校验 →
     * 指纹与现库比对（KTD2 同算法）→ SQL 白名单词法级净化 → 统计预览
     * （表数/记录数/文件数/sys_user 账号数与凭据变更布尔，password 值不进任何输出）。
     * 请求结束删除本次解压临时目录。
     *
     * @param file 上传的备份包
     * @return 预览统计结果（executed=false）
     */
    public RestoreResult previewRestore(MultipartFile file);

    /**
     * 还原执行（U2，F2 时序）：confirmed=false 时等价于校验预览（不写库）；
     * confirmed=true 时对本次上传文件重跑全部校验（不信任跨请求预览）→
     * 加写锁+暂停调度器 → 快照（exportInternal 快照槽位）→ 单连接顺序执行
     * 净化后 SQL → 两段式文件替换 → 失败回放快照+staging 回移；成功后重建
     * 字典/参数缓存与调度器（经 RestoreSchedulerHook）并做会话判定。
     *
     * @param file 上传的备份包
     * @param confirmed 是否确认执行
     * @return 执行摘要（executed=true 时含 reloginRequired 判定）
     */
    public RestoreResult restore(MultipartFile file, boolean confirmed);
}
