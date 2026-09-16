package com.ruoyi.system.service.impl;

import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.CloudApp;
import com.ruoyi.system.mapper.CloudAppMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * CloudAppServiceImpl 单元测试
 * 重点验证 validateBeforeDelete 的 R4-R7 校验逻辑:
 * - R4: 存在性(selectCloudAppById 返回 null 即视为不存在或已软删除)
 * - R5: 默认应用(creater="1")仅管理员可删
 * - R6: 创建者权限(creater=userId 字符串,非创建者且非 admin 禁止)
 * - R7: 任一 id 校验失败即整体中止
 *
 * 同时验证 R6 的 null-safe 比较(creater=null 不抛 NPE)。
 *
 * SecurityUtils 静态方法通过 SecurityContextHolder 注入 mock LoginUser 实现,
 * 无需 mockito-inline。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class CloudAppServiceImplTest
{
    @Mock
    private CloudAppMapper cloudAppMapper;

    @InjectMocks
    private CloudAppServiceImpl cloudAppService;

    @AfterEach
    void clearSecurityContext()
    {
        SecurityContextHolder.clearContext();
    }

    /**
     * 设置当前登录用户ID,模拟 SecurityUtils.getUserId() 的返回值。
     * isAdmin 由 SecurityUtils.isAdmin(userId) 判断(userId=1 即 admin)。
     */
    private void setCurrentUser(Long userId)
    {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(userId);
        Authentication auth = new UsernamePasswordAuthenticationToken(loginUser, null);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private CloudApp buildApp(Long id, String creater)
    {
        CloudApp app = new CloudApp();
        app.setId(id);
        app.setCreater(creater);
        return app;
    }

    // ===== T3.1 删除不存在的 id =====
    @Test
    void testValidateBeforeDelete_idNotExist_throwsServiceException()
    {
        setCurrentUser(2L);
        when(cloudAppMapper.selectCloudAppById(999L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> cloudAppService.validateBeforeDelete(new Long[]{999L}));
        assertEquals("应用不存在或已删除", ex.getMessage());
    }

    // ===== T3.2 已软删除的 id(selectCloudAppById 返回 null,与 T3.1 等价)=====
    @Test
    void testValidateBeforeDelete_softDeletedId_throwsServiceException()
    {
        setCurrentUser(2L);
        when(cloudAppMapper.selectCloudAppById(5L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> cloudAppService.validateBeforeDelete(new Long[]{5L}));
        assertEquals("应用不存在或已删除", ex.getMessage());
    }

    // ===== T3.3 非 admin 删除默认应用(creater="1")→ R5 失败 =====
    @Test
    void testValidateBeforeDelete_nonAdminDeleteDefaultApp_throws()
    {
        setCurrentUser(2L); // 非 admin
        when(cloudAppMapper.selectCloudAppById(1L)).thenReturn(buildApp(1L, "1"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> cloudAppService.validateBeforeDelete(new Long[]{1L}));
        assertEquals("系统默认应用仅管理员可删除", ex.getMessage());
    }

    // ===== T3.4 非创建者且非 admin 删除他人应用(creater="3")→ R6 已放开，通过 =====
    @Test
    void testValidateBeforeDelete_nonOwnerNonAdminDelete_r6Relaxed_passes()
    {
        setCurrentUser(2L); // 当前用户 id=2
        when(cloudAppMapper.selectCloudAppById(10L)).thenReturn(buildApp(10L, "3")); // creater=3

        // R6 已放开：普通应用删除不再校验创建者，非管理员可删除他人应用
        assertDoesNotThrow(() -> cloudAppService.validateBeforeDelete(new Long[]{10L}));
    }

    // ===== T3.5 创建者本人删除自己的应用(creater="2",当前用户 id=2)→ 通过 =====
    @Test
    void testValidateBeforeDelete_ownerDeleteOwnApp_passes()
    {
        setCurrentUser(2L);
        when(cloudAppMapper.selectCloudAppById(10L)).thenReturn(buildApp(10L, "2"));

        // 不抛异常即通过
        cloudAppService.validateBeforeDelete(new Long[]{10L});
    }

    // ===== T3.6 admin 删除非默认应用(creater="3",admin userId=1)→ 通过(R6 旁路)=====
    @Test
    void testValidateBeforeDelete_adminDeleteNonDefaultApp_passes()
    {
        setCurrentUser(1L); // admin
        when(cloudAppMapper.selectCloudAppById(10L)).thenReturn(buildApp(10L, "3"));

        cloudAppService.validateBeforeDelete(new Long[]{10L});
    }

    // ===== T3.7 admin 删除默认应用(creater="1",admin userId=1)→ 通过(R5 admin 旁路)=====
    @Test
    void testValidateBeforeDelete_adminDeleteDefaultApp_passes()
    {
        setCurrentUser(1L); // admin
        when(cloudAppMapper.selectCloudAppById(1L)).thenReturn(buildApp(1L, "1"));

        cloudAppService.validateBeforeDelete(new Long[]{1L});
    }

    // ===== T3.8 creater=null:非 admin → R6 已放开(无 NPE);admin → 通过 =====
    @Test
    void testValidateBeforeDelete_nullCreater_nonAdmin_r6Relaxed_noNPE()
    {
        setCurrentUser(2L);
        when(cloudAppMapper.selectCloudAppById(10L)).thenReturn(buildApp(10L, null));

        // R6 已放开：null creater 不抛 NPE，也不抛权限异常
        assertDoesNotThrow(() -> cloudAppService.validateBeforeDelete(new Long[]{10L}));
    }

    @Test
    void testValidateBeforeDelete_nullCreater_admin_passes_noNPE()
    {
        setCurrentUser(1L); // admin
        when(cloudAppMapper.selectCloudAppById(10L)).thenReturn(buildApp(10L, null));

        // 不抛 NPE 即通过
        cloudAppService.validateBeforeDelete(new Long[]{10L});
    }

    // ===== T3.9 批量[存在id, 不存在id] → 整体中止(R7)=====
    @Test
    void testValidateBeforeDelete_batchWithNotExistId_aborts()
    {
        setCurrentUser(2L);
        when(cloudAppMapper.selectCloudAppById(10L)).thenReturn(buildApp(10L, "2")); // 存在,本人
        when(cloudAppMapper.selectCloudAppById(999L)).thenReturn(null); // 不存在

        ServiceException ex = assertThrows(ServiceException.class,
                () -> cloudAppService.validateBeforeDelete(new Long[]{10L, 999L}));
        assertEquals("应用不存在或已删除", ex.getMessage());
    }

    // ===== T3.10 非 admin 批量[普通应用, 默认应用] → 整体中止(R5 + R7)=====
    @Test
    void testValidateBeforeDelete_nonAdminBatchWithDefault_aborts()
    {
        setCurrentUser(2L); // 非 admin
        when(cloudAppMapper.selectCloudAppById(10L)).thenReturn(buildApp(10L, "2")); // 自己的
        when(cloudAppMapper.selectCloudAppById(1L)).thenReturn(buildApp(1L, "1")); // 默认应用

        ServiceException ex = assertThrows(ServiceException.class,
                () -> cloudAppService.validateBeforeDelete(new Long[]{10L, 1L}));
        assertEquals("系统默认应用仅管理员可删除", ex.getMessage());
    }

    // ===== T3.11 admin 批量[普通应用, 默认应用] → 全部通过(admin 双旁路)=====
    @Test
    void testValidateBeforeDelete_adminBatchAllTypes_passes()
    {
        setCurrentUser(1L); // admin
        when(cloudAppMapper.selectCloudAppById(10L)).thenReturn(buildApp(10L, "3")); // 他人应用
        when(cloudAppMapper.selectCloudAppById(1L)).thenReturn(buildApp(1L, "1")); // 默认应用

        cloudAppService.validateBeforeDelete(new Long[]{10L, 1L});
    }

    // ===== T3.12 非 admin 批量[自己应用, 他人应用] → R6 已放开，通过 =====
    @Test
    void testValidateBeforeDelete_nonAdminBatchWithOtherApp_r6Relaxed_passes()
    {
        setCurrentUser(2L); // 非 admin
        when(cloudAppMapper.selectCloudAppById(10L)).thenReturn(buildApp(10L, "2")); // 自己的
        when(cloudAppMapper.selectCloudAppById(11L)).thenReturn(buildApp(11L, "3")); // 他人的

        // R6 已放开：批量删除含他人应用不再中止
        assertDoesNotThrow(() -> cloudAppService.validateBeforeDelete(new Long[]{10L, 11L}));
    }

    // ===== 边界:空数组(不应抛异常,for 循环不执行)=====
    @Test
    void testValidateBeforeDelete_emptyArray_passes()
    {
        setCurrentUser(2L);
        cloudAppService.validateBeforeDelete(new Long[]{});
    }
}
