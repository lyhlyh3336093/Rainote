package com.ruoyi.system.service;

/**
 * 还原流程的调度器回调接口（KTD6 跨模块接口反转）。
 * <p>
 * Scheduler/SysJobService 在 ruoyi-quartz 模块，而 {@code BackupRestoreServiceImpl}
 * 在 ruoyi-system（两者 pom 无依赖）——接口定义在 ruoyi-system，ruoyi-quartz 提供实现
 * 注入，避免循环依赖。还原期间暂停调度器（防止调度任务在库重建窗口写半还原数据），
 * 成功后 clear 并从还原后的 sys_job 重载（失败由调用方走失败回放分支）。
 * <p>
 * 实现方将 SchedulerException/TaskException 包装为 RuntimeException 抛出。
 *
 * @author ruoyi
 */
public interface RestoreSchedulerHook
{
    /**
     * 暂停调度器（standby：不再触发任务）
     */
    void pause();

    /**
     * 恢复调度器（start；幂等，未暂停时调用无害）
     */
    void resume();

    /**
     * 清空调度器并从当前 sys_job 表全量重载任务。
     * <p>
     * 恶意包植入无效 cron 会让任务创建抛异常——该异常必须向上传播，
     * 由还原流程走失败回放分支回滚库与文件。
     */
    void reload();
}
