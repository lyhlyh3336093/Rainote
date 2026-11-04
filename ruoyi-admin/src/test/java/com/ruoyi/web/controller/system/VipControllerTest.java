package com.ruoyi.web.controller.system;

import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.VipRechargeOrder;
import com.ruoyi.system.domain.VipTier;
import com.ruoyi.system.mapper.VipRechargeOrderMapper;
import com.ruoyi.system.mapper.VipTierMapper;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.service.VipService;
import com.ruoyi.web.controller.VipTestContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VipController} 用户端接口测试（U5 Test scenarios）。
 * <p>
 * 覆盖：order 提交（属主绑定/tier 校验/proofUrl 属主前缀/唯一待审核）；
 * proof 通道（每日限次拒绝/超大拒绝/成功不写容量记录）；info 聚合；
 * 凭证下载（属主前缀校验/非属主拒绝）；IDOR（list 强制按当前用户过滤）。
 * 匿名 401 由 SecurityConfig 前置规则承担（集成验证留给手工冒烟）。
 */
class VipControllerTest
{
    private static final Long USER_A = 2L;
    private static final Long USER_B = 3L;

    @TempDir
    Path tempRoot;

    private String originalProfile;

    private VipController controller;

    private VipRechargeOrderMapper orderMapper;

    private VipTierMapper tierMapper;

    private VipService vipService;

    @BeforeEach
    void setUp()
    {
        originalProfile = RuoYiConfig.getProfile();
        new RuoYiConfig().setProfile(tempRoot.resolve("profile").toString());
        controller = new VipController();
        vipService = VipTestContext.mockPermissiveVipService();
        orderMapper = mock(VipRechargeOrderMapper.class);
        tierMapper = mock(VipTierMapper.class);
        ReflectionTestUtils.setField(controller, "vipService", vipService);
        ReflectionTestUtils.setField(controller, "vipRechargeOrderMapper", orderMapper);
        ReflectionTestUtils.setField(controller, "vipTierMapper", tierMapper);
        ReflectionTestUtils.setField(controller, "configService", mock(ISysConfigService.class));
        VipTestContext.setLoginUser(USER_A);
        // startPage()（PageHelper）依赖 RequestContextHolder 请求上下文
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest();
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown()
    {
        new RuoYiConfig().setProfile(originalProfile);
        VipTestContext.clear();
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    private VipTier enabledTier()
    {
        VipTier tier = new VipTier();
        tier.setId(10L);
        tier.setName("月卡");
        tier.setDays(30);
        tier.setPrice(new java.math.BigDecimal("19.90"));
        tier.setStatus(VipTier.STATUS_ENABLED);
        return tier;
    }

    // ===== order 提交 =====

    @Test
    void order_valid_submissionBindsCurrentUserAndPriceSnapshot()
    {
        when(tierMapper.selectVipTierById(10L)).thenReturn(enabledTier());
        when(orderMapper.selectPendingOrderByUserId(USER_A)).thenReturn(null);

        VipRechargeOrder body = new VipRechargeOrder();
        body.setTierId(10L);
        body.setProofUrl("vip-proof/2/2026/11/04/pay_001.png");
        body.setPayChannel("wechat");
        body.setUserRemark("尾号8888");

        AjaxResult result = controller.order(body);
        assertEquals(200, result.get("code"));
        verify(orderMapper).insertVipRechargeOrder(argThat(o ->
                o.getUserId().equals(USER_A)
                        && o.getAmount().compareTo(new java.math.BigDecimal("19.90")) == 0
                        && "0".equals(o.getStatus())
                        && o.getPending() == 1));
    }

    @Test
    void order_pendingExists_rejected()
    {
        when(tierMapper.selectVipTierById(10L)).thenReturn(enabledTier());
        when(orderMapper.selectPendingOrderByUserId(USER_A)).thenReturn(new VipRechargeOrder());
        VipRechargeOrder body = new VipRechargeOrder();
        body.setTierId(10L);
        body.setProofUrl("vip-proof/2/x.png");
        assertThrows(ServiceException.class, () -> controller.order(body));
        verify(orderMapper, never()).insertVipRechargeOrder(any(VipRechargeOrder.class));
    }

    @Test
    void order_foreignProofUrlPrefix_rejected()
    {
        // 用户 A 提交用户 B 的凭证路径 → 拒绝（Covers U5 proofUrl 前缀校验）
        when(tierMapper.selectVipTierById(10L)).thenReturn(enabledTier());
        when(orderMapper.selectPendingOrderByUserId(USER_A)).thenReturn(null);
        VipRechargeOrder body = new VipRechargeOrder();
        body.setTierId(10L);
        body.setProofUrl("vip-proof/3/2026/11/04/stolen_001.png");
        assertThrows(ServiceException.class, () -> controller.order(body));
    }

    @Test
    void order_disabledTier_rejected()
    {
        VipTier disabled = enabledTier();
        disabled.setStatus(VipTier.STATUS_DISABLED);
        when(tierMapper.selectVipTierById(10L)).thenReturn(disabled);
        VipRechargeOrder body = new VipRechargeOrder();
        body.setTierId(10L);
        body.setProofUrl("vip-proof/2/x.png");
        assertThrows(ServiceException.class, () -> controller.order(body));
    }

    // ===== proof 凭证通道 =====

    @Test
    void proof_overSize_rejected() throws Exception
    {
        // 12MB > 10MB 上限（Covers R6 超大分支）
        byte[] content = new byte[12 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile("file", "pay.png", "image/png", content);
        assertThrows(Exception.class, () -> controller.proof(file));
    }

    @Test
    void proof_dailyLimitExhausted_rejected() throws Exception
    {
        // 当日第 11 次 → ServiceException（Covers R6 每日限次）
        doThrow(new ServiceException("凭证上传次数已达每日上限"))
                .when(vipService).proofDailyTryAcquire(USER_A);
        MockMultipartFile file = new MockMultipartFile("file", "pay.png", "image/png",
                new byte[1024]);
        assertThrows(ServiceException.class, () -> controller.proof(file));
    }

    @Test
    void proof_valid_storedUnderOwnerPrivateDir_noCapacityRecord() throws Exception
    {
        MockMultipartFile file = new MockMultipartFile("file", "pay.png", "image/png",
                new byte[1024]);
        AjaxResult result = controller.proof(file);
        assertEquals(200, result.get("code"));
        String proofUrl = (String) result.get("proofUrl");
        assertNotNull(proofUrl);
        assertTrue(proofUrl.startsWith("vip-proof/2/"), "凭证必须落在属主私有目录 vip-proof/{userId}/ 下");
        // 私有目录在 profile 之外（不经 /profile 静态映射）
        assertTrue(java.nio.file.Files.exists(
                java.nio.file.Paths.get(RuoYiConfig.getVipPrivatePath(), proofUrl)));
        // 不写容量记录（KTD3：凭证不占用配额）
        verify(vipService, never()).recordUpload(anyLong(), anyString(), anyString(), anyLong(), anyString());
    }

    // ===== info 聚合 =====

    @Test
    void info_aggregatesStatusTiersAndQrUrls()
    {
        when(tierMapper.selectVipTierList(any(VipTier.class)))
                .thenReturn(Collections.singletonList(enabledTier()));
        when(orderMapper.selectPendingOrderByUserId(USER_A)).thenReturn(null);
        AjaxResult result = controller.info();
        assertEquals(200, result.get("code"));
        assertNotNull(result.get("status"));
        assertNotNull(result.get("tiers"));
    }

    // ===== 凭证下载鉴权 =====

    @Test
    void proofDownload_ownerPrefixEnforced() throws Exception
    {
        // 用户 A 请求用户 B 的凭证路径 → 拒绝
        assertThrows(ServiceException.class, () -> invokeDownload("vip-proof/3/x.png"));
    }

    @Test
    void proofDownload_ownerPath_served() throws Exception
    {
        // 属主路径：先落一个凭证文件再下载
        MockMultipartFile file = new MockMultipartFile("file", "pay.png", "image/png",
                new byte[1024]);
        AjaxResult uploaded = controller.proof(file);
        String proofUrl = (String) uploaded.get("proofUrl");
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();
        controller.proofDownload(proofUrl, response);
        assertEquals("application/octet-stream", response.getContentType());
        assertEquals(1024, response.getContentAsByteArray().length, "凭证文件内容应完整写出");
    }

    private void invokeDownload(String proofUrl) throws Exception
    {
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();
        controller.proofDownload(proofUrl, response);
    }

    // ===== IDOR：list 强制按当前用户 =====

    @Test
    void orderList_alwaysFilteredByCurrentUser()
    {
        controller.orderList();
        verify(orderMapper).selectVipRechargeOrderList(argThat(q -> USER_A.equals(q.getUserId())));
    }

    /** ArgumentMatcher 辅助（泛型推断用） */
    private static <T> T argThat(org.mockito.ArgumentMatcher<T> matcher)
    {
        return org.mockito.ArgumentMatchers.argThat(matcher);
    }
}
