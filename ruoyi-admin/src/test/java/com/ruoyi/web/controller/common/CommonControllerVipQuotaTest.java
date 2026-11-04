package com.ruoyi.web.controller.common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.VipRequiredException;
import com.ruoyi.framework.config.ServerConfig;
import com.ruoyi.system.service.BackupLockUtil;
import com.ruoyi.system.service.VipService;
import com.ruoyi.web.controller.VipTestContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CommonController} 上传通道 VIP 限额闸门测试（U3/R9）。
 * <p>
 * 覆盖：普通用户超单文件上限抛 602（断言先于落盘，零写入）；
 * VIP 放行真实落盘并写入容量记录（recordUpload，biz_type=common）；
 * 批量上传整批求和一次容量断言（AE-P2 原子拒绝）。
 *
 * @author ruoyi
 */
class CommonControllerVipQuotaTest
{
    private static final Long USER = 2L;

    @TempDir
    Path tempRoot;

    private String originalProfile;
    private String originalBackupPath;

    private CommonController controller;

    private VipService vipService;

    @BeforeEach
    void setUp()
    {
        originalProfile = RuoYiConfig.getProfile();
        originalBackupPath = RuoYiConfig.getBackupPath();
        new RuoYiConfig().setProfile(tempRoot.resolve("profile").toString());
        new RuoYiConfig().setBackupPath(tempRoot.resolve("backup").toString());
        controller = new CommonController();
        ServerConfig serverConfig = mock(ServerConfig.class);
        when(serverConfig.getUrl()).thenReturn("http://test");
        ReflectionTestUtils.setField(controller, "serverConfig", serverConfig);
        vipService = VipTestContext.mockPermissiveVipService();
        ReflectionTestUtils.setField(controller, "vipService", vipService);
        VipTestContext.setLoginUser(USER);
    }

    @AfterEach
    void tearDown()
    {
        BackupLockUtil.releaseLock();
        new RuoYiConfig().setProfile(originalProfile);
        new RuoYiConfig().setBackupPath(originalBackupPath);
        VipTestContext.clear();
    }

    private MockMultipartFile fileOfSize(String name, int bytes)
    {
        byte[] content = new byte[bytes];
        return new MockMultipartFile("file", name, "text/plain", content);
    }

    @Test
    void upload_normalUserOverLimit_throwsVipRequired_zeroWrite()
    {
        // 普通用户 60MB 超单文件上限 → 602（Covers AE3 单文件分支）
        MockMultipartFile file = fileOfSize("a.txt", 60 * 1024 * 1024);
        doThrow(new VipRequiredException("单文件大小超过普通用户上限"))
                .when(vipService).assertUploadQuota(USER, file.getSize(), 0L);

        VipRequiredException ex = assertThrows(VipRequiredException.class, () -> controller.uploadFile(file));
        assertEquals(602, VipRequiredException.CODE);
        // 断言先于落盘：上传目录无产物
        verify(vipService, never()).recordUpload(anyLong(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void upload_vipWithinLimit_successAndRecordsUpload() throws Exception
    {
        // VIP 60MB（KTD10 参数化上限生效：mock status.fileMaxBytes=512MB）→ 成功且写入记录
        MockMultipartFile file = fileOfSize("b.txt", 60 * 1024 * 1024);
        AjaxResult result = controller.uploadFile(file);
        assertEquals(200, result.get("code"));
        verify(vipService).recordUpload(eq(USER), eq("b.txt"), anyString(),
                eq(60L * 1024 * 1024), eq("common"));
    }

    @Test
    void uploads_batchCapacityAssertedOnceWithSum() throws Exception
    {
        // 批量 3 文件：断言参数 = 首文件 incoming + 其余之和 additional（AE-P2 整批原子断言）
        MockMultipartFile f1 = fileOfSize("1.txt", 1024);
        MockMultipartFile f2 = fileOfSize("2.txt", 2048);
        MockMultipartFile f3 = fileOfSize("3.txt", 4096);
        AjaxResult result = controller.uploadFiles(Arrays.asList(f1, f2, f3));
        assertEquals(200, result.get("code"));
        verify(vipService).assertUploadQuota(USER, 1024L, 2048L + 4096L);
        verify(vipService, Mockito.times(3)).recordUpload(anyLong(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void uploads_batchOverCapacity_rejectedBeforeAnyWrite()
    {
        MockMultipartFile f1 = fileOfSize("1.txt", 1024);
        MockMultipartFile f2 = fileOfSize("2.txt", 2048);
        doThrow(new VipRequiredException("存储空间已满"))
                .when(vipService).assertUploadQuota(USER, f1.getSize(), f2.getSize());
        assertThrows(VipRequiredException.class,
                () -> controller.uploadFiles(Arrays.asList(f1, f2)));
        verify(vipService, never()).recordUpload(anyLong(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void upload_recordFailure_deletesLandedFile() throws Exception
    {
        // 容量记录写入失败 → 删除已落盘文件防漏计（U3 Approach）
        MockMultipartFile file = fileOfSize("c.txt", 128);
        doThrow(new RuntimeException("record boom"))
                .when(vipService).recordUpload(anyLong(), anyString(), anyString(), anyLong(), anyString());
        controller.uploadFile(file);
        // 记录失败被 catch 转为 error 响应；落盘文件已被回滚删除（upload 目录为空）
        assertEquals(0, countFiles(tempRoot.resolve("profile").resolve("upload")));
    }

    private long countFiles(Path dir) throws Exception
    {
        if (!Files.isDirectory(dir))
        {
            return 0L;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir))
        {
            return walk.filter(Files::isRegularFile).count();
        }
    }
}
