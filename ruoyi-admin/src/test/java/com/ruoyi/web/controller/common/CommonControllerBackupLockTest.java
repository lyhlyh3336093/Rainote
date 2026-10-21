package com.ruoyi.web.controller.common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.config.ServerConfig;
import com.ruoyi.system.service.BackupLockUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommonController} 上传通道备份/还原写锁检查测试（KTD3 消费者覆盖）。
 * <p>
 * /common/upload 与 /common/uploads 直接写 profile 上传目录，属于备份包
 * files/ 目录的冻结范围——锁持有时两个入口均被拒且零写入，锁释放后放行
 * （真实落盘 + mock ServerConfig 拼接 URL，与 BackupRestoreServiceImplTest
 * consumerChecks 同款模式：持锁拒绝、释放放行）。
 *
 * @author ruoyi
 */
class CommonControllerBackupLockTest
{
    @TempDir
    Path tempRoot;

    /** RuoYiConfig 静态字段原值，@AfterEach 恢复防止污染其他测试类 */
    private String originalProfile;

    private String originalBackupPath;

    private CommonController controller;

    @BeforeEach
    void setUp()
    {
        originalProfile = RuoYiConfig.getProfile();
        originalBackupPath = RuoYiConfig.getBackupPath();
        new RuoYiConfig().setProfile(tempRoot.resolve("profile").toString());
        new RuoYiConfig().setBackupPath(tempRoot.resolve("backup").toString());
        controller = new CommonController();
        ServerConfig serverConfig = Mockito.mock(ServerConfig.class);
        Mockito.when(serverConfig.getUrl()).thenReturn("http://test");
        ReflectionTestUtils.setField(controller, "serverConfig", serverConfig);
    }

    @AfterEach
    void tearDown()
    {
        // 兜底释放静态写锁，防止测试间泄漏
        BackupLockUtil.releaseLock();
        new RuoYiConfig().setProfile(originalProfile);
        new RuoYiConfig().setBackupPath(originalBackupPath);
    }

    @Test
    void upload_whileLocked_rejectedZeroWrite() throws Exception
    {
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain",
                "内容".getBytes(StandardCharsets.UTF_8));

        assertTrue(BackupLockUtil.tryAcquireBackupLock());
        try
        {
            // 单文件通道：方法首行锁检查被拒，不触任何依赖
            ServiceException singleEx = assertThrows(ServiceException.class,
                    () -> controller.uploadFile(file));
            assertEquals(BackupLockUtil.LOCKED_MESSAGE, singleEx.getMessage());

            // 多文件通道同样被拒
            ServiceException multiEx = assertThrows(ServiceException.class,
                    () -> controller.uploadFiles(Collections.singletonList(file)));
            assertEquals(BackupLockUtil.LOCKED_MESSAGE, multiEx.getMessage());

            // 零写入：上传目录不应有任何产物
            assertFalse(Files.exists(tempRoot.resolve("profile").resolve("upload")),
                    "锁持有期间不应写入上传目录");
        }
        finally
        {
            BackupLockUtil.releaseLock();
        }
    }

    @Test
    void upload_afterReleased_passThrough() throws Exception
    {
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain",
                "内容".getBytes(StandardCharsets.UTF_8));

        // 单文件通道放行：真实落盘 + 200 响应
        AjaxResult single = controller.uploadFile(file);
        assertEquals(200, single.get("code"), "锁释放后单文件上传应放行");
        assertNotNull(single.get("url"));
        assertTrue(countFiles(tempRoot.resolve("profile").resolve("upload")) > 0,
                "放行后上传目录应有落盘产物");

        // 多文件通道放行
        AjaxResult multi = controller.uploadFiles(Collections.singletonList(file));
        assertEquals(200, multi.get("code"), "锁释放后多文件上传应放行");
        assertNotNull(multi.get("urls"));
    }

    /** 统计目录树下普通文件数（放行断言用） */
    private long countFiles(Path dir) throws Exception
    {
        if (!Files.isDirectory(dir))
        {
            return 0L;
        }
        try (Stream<Path> walk = Files.walk(dir))
        {
            return walk.filter(Files::isRegularFile).count();
        }
    }
}
