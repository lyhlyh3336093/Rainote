package com.ruoyi.system.service.impl;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.agent.annotation.AgentOperation;
import com.ruoyi.system.service.BackupLockUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code note.backup} agent 操作单元测试（U4/KTD7）。
 * <p>
 * 覆盖：备份命令成功返回统计与界面下载引导（spy stub backup()，消息统计来自
 * manifest 解析）、备份写锁持有期间 agent 导入命令与备份命令双双被拒（KTD3
 * 消费者检查覆盖 agent 路径）、非 admin 经 agent 触发备份被服务层 userId==1
 * 兜底拒绝（permissionKey 之外的第二层）、@AgentOperation 注册契约（非空
 * permissionKey 映射管理员级键 note:note:delete）。
 *
 * @author ruoyi
 */
class BackupRestoreAgentTest
{
    @TempDir
    Path tempRoot;

    /** 备份落盘目录（backupPath 槽位） */
    private Path backupDir;

    /** RuoYiConfig 静态字段原值，@AfterEach 恢复防止污染其他测试类 */
    private String originalProfile;

    private String originalBackupPath;

    private BackupRestoreServiceImpl service;

    @BeforeEach
    void setUp() throws IOException
    {
        originalProfile = RuoYiConfig.getProfile();
        originalBackupPath = RuoYiConfig.getBackupPath();
        backupDir = Files.createDirectories(tempRoot.resolve("backup"));
        new RuoYiConfig().setProfile(tempRoot.resolve("profile").toString());
        new RuoYiConfig().setBackupPath(backupDir.toString());
        service = new BackupRestoreServiceImpl();
    }

    @AfterEach
    void tearDown()
    {
        // 兜底释放静态写锁，防止测试间泄漏
        BackupRestoreServiceImpl.releaseLock();
        new RuoYiConfig().setProfile(originalProfile);
        new RuoYiConfig().setBackupPath(originalBackupPath);
    }

    // ===== 场景 1：agent 备份命令成功返回统计与引导文案 =====

    @Test
    void backupForAgentShouldReturnStatsAndGuidance() throws Exception
    {
        File zip = writeBackupZip();
        BackupRestoreServiceImpl spy = Mockito.spy(service);
        Mockito.doReturn(zip).when(spy).backup();

        String message = spy.backupForAgent(1L);

        // 统计来自 manifest 解析：2 张表 / 3 条记录 / 2 个文件
        assertTrue(message.contains("2 张表"), "消息应含表数统计: " + message);
        assertTrue(message.contains("3 条记录"), "消息应含记录数统计: " + message);
        assertTrue(message.contains("2 个文件"), "消息应含文件数统计: " + message);
        // 包大小（MB，同公式重算）+ 文件名
        assertTrue(message.contains(String.format("%.2f MB", zip.length() / 1024.0 / 1024.0)),
                "消息应含包大小统计: " + message);
        assertTrue(message.contains("backup.zip"), "消息应含备份文件名: " + message);
        // 界面下载引导（agent 无文件下载通道）
        assertTrue(message.contains("数据备份"), "消息应含界面下载引导: " + message);
        Mockito.verify(spy).backup();
    }

    // ===== 场景 2：备份写锁持有期间 agent 导入命令被拒（KTD3 互斥覆盖 agent 路径） =====

    @Test
    void importSqlShouldBeRejectedWhileBackupLockHeld() throws Exception
    {
        assertTrue(BackupRestoreServiceImpl.tryAcquireBackupLock(), "应成功持有写锁");
        try
        {
            // agent 导入命令（dwtable.importSql）首行 BackupLockUtil 检查被拒，
            // 依赖字段全 null 也无碍——锁检查在任何依赖触达之前抛出
            NoteDwtableImportServiceImpl importService = new NoteDwtableImportServiceImpl();
            ServiceException importEx = assertThrows(ServiceException.class,
                    () -> importService.importSql(1L, 1L, "INSERT INTO `T` VALUES(1)", 1L));
            assertEquals(BackupLockUtil.LOCKED_MESSAGE, importEx.getMessage());

            // agent 备份命令自身同样撞写锁（backup() 入口薄壳拒绝，不产出备份包）
            ServiceException backupEx = assertThrows(ServiceException.class,
                    () -> service.backupForAgent(1L));
            assertEquals(BackupLockUtil.LOCKED_MESSAGE, backupEx.getMessage());
            assertFalse(Files.exists(backupDir.resolve("backup.zip")), "锁持有期间不应产出备份包");
        }
        finally
        {
            BackupRestoreServiceImpl.releaseLock();
        }

        // 锁释放后 agent 备份命令放行（消息含统计与引导）
        File zip = writeBackupZip();
        BackupRestoreServiceImpl spy = Mockito.spy(service);
        Mockito.doReturn(zip).when(spy).backup();
        String message = spy.backupForAgent(1L);
        assertTrue(message.contains("2 张表"));
        assertTrue(message.contains("数据备份"));
    }

    // ===== 场景 3：非 admin 经 agent 触发备份被拒（服务层 userId==1 兜底） =====

    @Test
    void backupForAgentShouldRejectNonAdmin() throws Exception
    {
        File zip = writeBackupZip();
        BackupRestoreServiceImpl spy = Mockito.spy(service);
        Mockito.doReturn(zip).when(spy).backup();

        ServiceException ex = assertThrows(ServiceException.class, () -> spy.backupForAgent(2L));
        assertEquals("只有管理员才可执行此操作", ex.getMessage());

        // userId=null 同样拒绝（框架注入不可达时保守拒绝）
        ServiceException nullEx = assertThrows(ServiceException.class, () -> spy.backupForAgent(null));
        assertEquals("只有管理员才可执行此操作", nullEx.getMessage());

        // 零副作用：backup() 未被触达、备份槽位无产物
        Mockito.verify(spy, Mockito.never()).backup();
        assertFalse(Files.exists(backupDir.resolve("backup.zip")), "拒绝路径不应产出备份包");
    }

    // ===== 注册契约：非空 permissionKey 映射管理员级权限键（KTD7 核心红线） =====

    @Test
    void backupOperationShouldDeclareAdminPermissionKey() throws Exception
    {
        AgentOperation op = BackupRestoreServiceImpl.class
                .getMethod("backupForAgent", Long.class).getAnnotation(AgentOperation.class);
        assertNotNull(op, "backupForAgent 应声明 @AgentOperation");
        assertEquals("note.backup", op.name());
        // 非空 permissionKey：空键时 agent 权限闸门放行，任一登录用户即可触发全库导出——
        // 映射管理员级键（与界面 dataBak/restore 端点的 @PreAuthorize 同键）
        assertEquals("note:note:delete", op.permissionKey());
        // 备份为只读导出，非破坏性操作（不暂停等待二次确认）
        assertFalse(op.destructive());
        // 描述覆盖备份能力与数据范围（全库+文件目录），供 agent 提示词使用
        assertTrue(op.description().contains("全库"));
        assertTrue(op.description().contains("upload/avatar/import"));
        assertTrue(op.description().contains("管理员"));
    }

    // ===== 构造工具 =====

    /**
     * 手工构造落盘备份包（含 2 表/3 记录/2 文件统计的 manifest），
     * 供 spy stub backup() 返回——消息统计断言的 fixture。
     * 写入独立 stub 目录（不落 backupPath 槽位，避免干扰零副作用断言）。
     */
    private File writeBackupZip() throws IOException
    {
        Path zip = Files.createDirectories(tempRoot.resolve("stub")).resolve("backup.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip)))
        {
            writeEntry(zos, "schema/SYS_USER.sql", "DROP TABLE IF EXISTS `SYS_USER`;".getBytes(StandardCharsets.UTF_8));
            writeEntry(zos, "files/upload/a.txt", "a".getBytes(StandardCharsets.UTF_8));
            writeEntry(zos, "files/avatar/b.png", "b".getBytes(StandardCharsets.UTF_8));
            String manifest = "{\"packageVersion\":1,\"schemaFingerprint\":\"f64\""
                    + ",\"createdAt\":\"2026-10-20T00:00:00Z\""
                    + ",\"tables\":[{\"name\":\"SYS_USER\",\"rows\":2},{\"name\":\"NOTE_NOTE\",\"rows\":1}]"
                    + ",\"fileStats\":{\"totalFiles\":2,\"totalBytes\":2}}";
            writeEntry(zos, "manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
        }
        return zip.toFile();
    }

    private void writeEntry(ZipOutputStream zos, String name, byte[] content) throws IOException
    {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content);
        zos.closeEntry();
    }
}
