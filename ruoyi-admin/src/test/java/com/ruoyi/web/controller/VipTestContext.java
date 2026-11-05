package com.ruoyi.web.controller;

import java.util.Collections;
import java.util.HashSet;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.system.domain.dto.VipStatusDto;
import com.ruoyi.system.service.VipService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * VIP 限额 controller 单元测试辅助（U3）：SecurityContext 登录用户 + 放行态 VipService mock。
 * <p>
 * 既有 controller 测试直接实例化 controller（无 MockMvc/无 Spring 上下文），
 * U3 断言引入 getUserId() 后需要登录上下文；本辅助统一提供。
 *
 * @author ruoyi
 */
public final class VipTestContext
{
    private VipTestContext()
    {
    }

    /**
     * 设置登录用户上下文（BaseController.getUserId()/SecurityUtils 可用）
     */
    public static void setLoginUser(Long userId)
    {
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.setUserName("user" + userId);
        LoginUser loginUser = new LoginUser(userId, 100L, user, new HashSet<>(Collections.singletonList("*:*:*")));
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(loginUser, null);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /**
     * 清理登录上下文（防止测试间泄漏）
     */
    public static void clear()
    {
        SecurityContextHolder.clearContext();
    }

    /**
     * 全放行 VipService mock（断言 no-op、状态按 VIP 口径、recordUpload no-op）
     */
    public static VipService mockPermissiveVipService()
    {
        VipService vipService = mock(VipService.class);
        doNothing().when(vipService).assertNoteQuota(anyLong());
        doNothing().when(vipService).assertUploadQuota(anyLong(), anyLong(), anyLong());
        doNothing().when(vipService).assertAdvancedColumn(anyLong(), any());
        doNothing().when(vipService).assertAgent(anyLong());
        doNothing().when(vipService).assertPersonalExport(anyLong());
        doNothing().when(vipService).assertShare(anyLong());
        doNothing().when(vipService).aiDailyTryAcquire(anyLong());
        doNothing().when(vipService).proofDailyTryAcquire(anyLong());
        doNothing().when(vipService).recordUpload(anyLong(), anyString(), anyString(), anyLong(), anyString());
        when(vipService.getRecycleRetentionDays(anyLong())).thenReturn(30);
        when(vipService.getUsedBytes(anyLong())).thenReturn(0L);
        VipStatusDto status = new VipStatusDto();
        status.setAdmin(false);
        status.setVip(true);
        status.setNoteLimit(-1L);
        status.setFileMaxBytes(512L * 1024 * 1024);
        status.setTotalMaxBytes(10240L * 1024 * 1024);
        status.setAiDailyLimit(100);
        status.setRecycleDays(30);
        when(vipService.getStatus(anyLong())).thenReturn(status);
        return vipService;
    }
}
