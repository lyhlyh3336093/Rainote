package com.ruoyi.web.controller.system;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.service.BackupLockUtil;
import com.ruoyi.system.service.ISysUserService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SysProfileController#avatar} 头像更新入口备份/还原写锁检查测试（KTD3 消费者覆盖）。
 * <p>
 * 头像上传直接写 profile 头像目录并更新 sys_user.avatar，属于备份冻结范围——
 * 锁持有时被拒且零写入，锁释放后放行（真实落盘 + mock 用户服务与 TokenService，
 * 与 BackupRestoreServiceImplTest consumerChecks 同款模式：持锁拒绝、释放放行）。
 *
 * @author ruoyi
 */
class SysProfileControllerBackupLockTest
{
    @TempDir
    Path tempRoot;

    /** RuoYiConfig 静态字段原值，@AfterEach 恢复防止污染其他测试类 */
    private String originalProfile;

    private String originalBackupPath;

    private SysProfileController controller;

    private ISysUserService userService;

    @BeforeEach
    void setUp()
    {
        originalProfile = RuoYiConfig.getProfile();
        originalBackupPath = RuoYiConfig.getBackupPath();
        new RuoYiConfig().setProfile(tempRoot.resolve("profile").toString());
        new RuoYiConfig().setBackupPath(tempRoot.resolve("backup").toString());
        controller = new SysProfileController();
        userService = Mockito.mock(ISysUserService.class);
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "tokenService", Mockito.mock(TokenService.class));
    }

    @AfterEach
    void tearDown()
    {
        // 兜底释放静态写锁，防止测试间泄漏
        BackupLockUtil.releaseLock();
        SecurityContextHolder.clearContext();
        new RuoYiConfig().setProfile(originalProfile);
        new RuoYiConfig().setBackupPath(originalBackupPath);
    }

    /** 设置当前登录上下文（getLoginUser 经 SecurityContext 取 principal） */
    private void loginAs(long userId)
    {
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.setUserName("admin");
        LoginUser loginUser = new LoginUser(userId, null, user, null);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    @Test
    void avatar_whileLocked_rejectedZeroWrite() throws Exception
    {
        loginAs(1L);
        MockMultipartFile file = new MockMultipartFile("avatarfile", "a.png", "image/png",
                "png".getBytes(StandardCharsets.UTF_8));

        assertTrue(BackupLockUtil.tryAcquireBackupLock());
        try
        {
            // 方法首行锁检查被拒，不触任何依赖
            ServiceException ex = assertThrows(ServiceException.class, () -> controller.avatar(file));
            assertEquals(BackupLockUtil.LOCKED_MESSAGE, ex.getMessage());

            // 零写入：头像目录不应有任何产物，用户服务未被触达
            assertFalse(Files.exists(tempRoot.resolve("profile").resolve("avatar")),
                    "锁持有期间不应写入头像目录");
            Mockito.verify(userService, Mockito.never()).updateUserAvatar(Mockito.any(), Mockito.any());
        }
        finally
        {
            BackupLockUtil.releaseLock();
        }
    }

    @Test
    void avatar_afterReleased_passThrough() throws Exception
    {
        loginAs(1L);
        Mockito.when(userService.updateUserAvatar(Mockito.any(), Mockito.any())).thenReturn(true);
        MockMultipartFile file = new MockMultipartFile("avatarfile", "a.png", "image/png",
                "png".getBytes(StandardCharsets.UTF_8));

        AjaxResult result = controller.avatar(file);

        assertEquals(200, result.get("code"), "锁释放后头像更新应放行");
        assertNotNull(result.get("imgUrl"));
        assertTrue(Files.exists(tempRoot.resolve("profile").resolve("avatar")),
                "放行后头像目录应有落盘产物");
    }
}
