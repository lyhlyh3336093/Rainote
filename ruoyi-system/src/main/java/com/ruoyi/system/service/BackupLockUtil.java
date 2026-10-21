package com.ruoyi.system.service;

import java.util.concurrent.atomic.AtomicBoolean;

import com.ruoyi.common.exception.ServiceException;

/**
 * 备份/还原写锁工具（KTD3）：锁状态与消费者检查的统一承载。
 * <p>
 * 备份/还原进行期间全库与文件目录处于冻结状态，既有写操作入口（导入两个服务、
 * agent 写操作、removeAllData/removeAll 等清空入口）须在方法开头调用
 * {@link #checkNotLocked()}——锁被持有时抛业务异常拒绝写入，防止备份包与
 * 实际数据出现不一致。触发级写锁标志（备份/还原共用）由本类静态
 * AtomicBoolean 承载，获取/释放/查询与消费者检查收拢到同一工具类
 * （原承载于 BackupRestoreServiceImpl，已归位至此）。
 *
 * @author ruoyi
 */
public final class BackupLockUtil
{
    /** 写锁持有期间的统一拒绝文案 */
    public static final String LOCKED_MESSAGE = "系统备份/还原进行中，请稍后";

    /** 触发级写锁标志（KTD3）：备份/还原共用，消费者经本工具检查同一标志 */
    private static final AtomicBoolean WRITE_LOCK = new AtomicBoolean(false);

    private BackupLockUtil()
    {
    }

    /**
     * 尝试获取触发级写锁（KTD3）：备份/还原共用，任一持有时其他触发被拒绝。
     *
     * @return true=获取成功（调用方 finally 释放）；false=已有备份/还原进行中
     */
    public static boolean tryAcquireBackupLock()
    {
        return WRITE_LOCK.compareAndSet(false, true);
    }

    /**
     * 释放触发级写锁
     */
    public static void releaseLock()
    {
        WRITE_LOCK.set(false);
    }

    /**
     * 写锁是否被持有（消费者检查与测试用）
     */
    public static boolean isWriteLocked()
    {
        return WRITE_LOCK.get();
    }

    /**
     * 消费者写操作入口检查：备份/还原写锁被持有时抛 ServiceException 拒绝。
     */
    public static void checkNotLocked()
    {
        if (isWriteLocked())
        {
            throw new ServiceException(LOCKED_MESSAGE);
        }
    }
}
