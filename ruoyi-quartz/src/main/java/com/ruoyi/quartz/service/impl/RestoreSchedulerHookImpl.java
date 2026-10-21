package com.ruoyi.quartz.service.impl;

import java.util.List;

import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.exception.job.TaskException;
import com.ruoyi.quartz.mapper.SysJobMapper;
import com.ruoyi.quartz.util.ScheduleUtils;
import com.ruoyi.system.service.RestoreSchedulerHook;

/**
 * 还原流程调度器回调实现（KTD6 跨模块接口反转）。
 * <p>
 * 接口定义在 ruoyi-system（还原服务所在模块），实现落在 ruoyi-quartz（Scheduler
 * 所在模块）——ruoyi-system 不依赖 ruoyi-quartz，由 Spring 注入本 bean 完成接线。
 * 实现对齐 {@code SysJobServiceImpl.init} 的重建方式（scheduler.clear() +
 * selectJobAll + ScheduleUtils.createScheduleJob）；异常包装为 RuntimeException
 * 向上传播，由还原流程走失败回放分支（恶意包植入无效 cron 会让任务创建抛出）。
 *
 * @author ruoyi
 */
@Component
public class RestoreSchedulerHookImpl implements RestoreSchedulerHook
{
    @Autowired
    private Scheduler scheduler;

    @Autowired
    private SysJobMapper jobMapper;

    /**
     * 暂停调度器（standby：不再触发任务，已注册任务保留）
     */
    @Override
    public void pause()
    {
        try
        {
            scheduler.standby();
        }
        catch (SchedulerException e)
        {
            throw new ServiceException("还原前暂停调度器失败：" + e.getMessage());
        }
    }

    /**
     * 恢复调度器（start；幂等，未暂停时调用无害）
     */
    @Override
    public void resume()
    {
        try
        {
            scheduler.start();
        }
        catch (SchedulerException e)
        {
            throw new ServiceException("还原后恢复调度器失败：" + e.getMessage());
        }
    }

    /**
     * 清空调度器并从当前 sys_job 表全量重载任务（还原后的库即新基准）。
     * 任何一项任务创建失败（无效 cron 等）直接抛出，由还原流程回滚。
     */
    @Override
    public void reload()
    {
        try
        {
            scheduler.clear();
            List<com.ruoyi.quartz.domain.SysJob> jobList = jobMapper.selectJobAll();
            for (com.ruoyi.quartz.domain.SysJob job : jobList)
            {
                ScheduleUtils.createScheduleJob(scheduler, job);
            }
        }
        catch (SchedulerException | TaskException e)
        {
            throw new ServiceException("还原后调度任务重载失败：" + e.getMessage());
        }
    }
}
