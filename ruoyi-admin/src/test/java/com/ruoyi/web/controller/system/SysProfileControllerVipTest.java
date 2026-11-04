package com.ruoyi.web.controller.system;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.service.BackupLockUtil;
import com.ruoyi.system.service.ISysUserService;
import com.ruoyi.system.service.VipService;
import com.ruoyi.web.controller.VipTestContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SysProfileController#avatar} 头像上传 VIP 限额闸门测试（U3/R9/KTD3）。
 * <p>
 * 覆盖：头像计入总容量（断言调用）；普通用户容量超限抛 602；
 * 放行后写入容量记录（biz_type=avatar）。
 *
 * @author ruoyi
 */
class SysProfileControllerVipTest
{
    private static final Long USER = 2L;

    @TempDir
    Path tempRoot;

    private String originalProfile;
    private String originalBackupPath;

    private SysProfileController controller;

    private ISysUserService userService;

    private VipService vipService;

    @BeforeEach
    void setUp()
    {
        originalProfile = RuoYiConfig.getProfile();
        originalBackupPath = RuoYiConfig.getBackupPath();
        new RuoYiConfig().setProfile(tempRoot.resolve("profile").toString());
        new RuoYiConfig().setBackupPath(tempRoot.resolve("backup").toString());
        controller = new SysProfileController();
        userService = mock(ISysUserService.class);
        when(userService.updateUserAvatar(any(), any())).thenReturn(true);
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "tokenService", mock(TokenService.class));
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

    @Test
    void avatar_overCapacity_throwsVipRequired()
    {
        MockMultipartFile file = new MockMultipartFile("avatarfile", "a.png", "image/png",
                new byte[1024]);
        doThrow(new VipRequiredException("存储空间已满"))
                .when(vipService).assertUploadQuota(USER, file.getSize(), 0L);
        assertThrows(VipRequiredException.class, () -> controller.avatar(file));
        verify(userService, Mockito.never()).updateUserAvatar(any(), any());
    }

    @Test
    void avatar_withinLimit_recordsUploadAsAvatarBizType() throws Exception
    {
        MockMultipartFile file = new MockMultipartFile("avatarfile", "a.png", "image/png",
                new byte[1024]);
        AjaxResult result = controller.avatar(file);
        assertEquals(200, result.get("code"));
        // 头像计入容量：biz_type=avatar（KTD3）
        verify(vipService).recordUpload(eq(USER), eq("a.png"), anyString(), eq(1024L), eq("avatar"));
    }

    @Test
    void avatar_overCapacity_recordNeverCalled()
    {
        MockMultipartFile file = new MockMultipartFile("avatarfile", "a.png", "image/png",
                "png".getBytes(StandardCharsets.UTF_8));
        doThrow(new VipRequiredException("存储空间已满"))
                .when(vipService).assertUploadQuota(anyLong(), anyLong(), anyLong());
        assertThrows(VipRequiredException.class, () -> controller.avatar(file));
        verify(vipService, never()).recordUpload(anyLong(), anyString(), anyString(), anyLong(), anyString());
    }
}
