package com.ruoyi.system.domain.dto;

/**
 * 还原校验/执行结果 DTO（U2）。
 * <p>
 * 校验阶段（previewRestore / restore confirmed=false）返回统计预览：表数/记录数/文件数、
 * 包内 sys_user 账号数与"凭据已变更"布尔——差异检测只比对行数与 password 列哈希，
 * password 值与哈希不进入任何输出（R5 告警口径）。执行阶段（confirmed=true）附加
 * 会话判定标记（reloginRequired）。
 *
 * @author ruoyi
 */
public class RestoreResult
{
    /** 包内表数量（manifest 表清单大小） */
    private int tableCount;

    /** 包内 INSERT 记录总数 */
    private long recordCount;

    /** 包内用户文件数量（files/ 前缀条目） */
    private long fileCount;

    /** 包内 sys_user 账号数 */
    private int userCount;

    /** sys_user 差异标记：行数或任一 password 哈希与现库不同即 true（不含任何密码值） */
    private boolean credentialChanged;

    /** 是否已执行写入（false=仅校验预览） */
    private boolean executed;

    /** 会话判定：包内 sys_user 不含当前 userId 时需重新登录 */
    private boolean reloginRequired;

    public int getTableCount()
    {
        return tableCount;
    }

    public void setTableCount(int tableCount)
    {
        this.tableCount = tableCount;
    }

    public long getRecordCount()
    {
        return recordCount;
    }

    public void setRecordCount(long recordCount)
    {
        this.recordCount = recordCount;
    }

    public long getFileCount()
    {
        return fileCount;
    }

    public void setFileCount(long fileCount)
    {
        this.fileCount = fileCount;
    }

    public int getUserCount()
    {
        return userCount;
    }

    public void setUserCount(int userCount)
    {
        this.userCount = userCount;
    }

    public boolean isCredentialChanged()
    {
        return credentialChanged;
    }

    public void setCredentialChanged(boolean credentialChanged)
    {
        this.credentialChanged = credentialChanged;
    }

    public boolean isExecuted()
    {
        return executed;
    }

    public void setExecuted(boolean executed)
    {
        this.executed = executed;
    }

    public boolean isReloginRequired()
    {
        return reloginRequired;
    }

    public void setReloginRequired(boolean reloginRequired)
    {
        this.reloginRequired = reloginRequired;
    }
}
