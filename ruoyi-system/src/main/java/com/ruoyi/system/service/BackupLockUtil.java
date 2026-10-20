package com.ruoyi.system.service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.service.impl.BackupRestoreServiceImpl;

/**
 * 备份/还原写锁消费者检查工具（KTD3）。
 * <p>
 * 备份/还原进行期间全库与文件目录处于冻结状态，既有写操作入口（导入两个服务、
 * agent 写操作、removeAllData/removeAll 等清空入口）须在方法开头调用
 * {@link #checkNotLocked()}——锁被持有时抛业务异常拒绝写入，防止备份包与
 * 实际数据出现不一致。锁标志由 {@link BackupRestoreServiceImpl} 类内静态
 * AtomicBoolean 承载（备份/还原共用同一触发级锁）。
 *
 * @author ruoyi
 */
public final class BackupLockUtil
{
    /** 写锁持有期间的统一拒绝文案 */
    public static final String LOCKED_MESSAGE = "系统备份/还原进行中，请稍后";

    private BackupLockUtil()
    {
    }

    /**
     * 消费者写操作入口检查：备份/还原写锁被持有时抛 ServiceException 拒绝。
     */
    public static void checkNotLocked()
    {
        if (BackupRestoreServiceImpl.isWriteLocked())
        {
            throw new ServiceException(LOCKED_MESSAGE);
        }
    }
}
