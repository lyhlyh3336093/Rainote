package com.ruoyi.web.controller.system;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.VipMembership;
import com.ruoyi.system.domain.VipRechargeOrder;
import com.ruoyi.system.domain.VipTier;
import com.ruoyi.system.mapper.VipMembershipMapper;
import com.ruoyi.system.mapper.VipRechargeOrderMapper;
import com.ruoyi.system.mapper.VipTierMapper;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.service.VipService;
import com.ruoyi.web.controller.VipTestContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VipAdminController} 管理端接口测试（U6 Test scenarios）。
 * <p>
 * 覆盖：approve 条件更新成功才 openVip（并发=0 时拒绝且不开通）；
 * reject 无留言拒绝；adjust 调整走 VipService；档位 CRUD 校验。
 * （并发双审的原子性由 KTD11 条件更新 SQL 保证——此处验证受影响行数分支语义）
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VipAdminControllerTest
{
    private static final Long ADMIN = 1L;
    private static final Long USER = 2L;

    @Mock
    private VipRechargeOrderMapper orderMapper;

    @Mock
    private VipTierMapper tierMapper;

    @Mock
    private VipMembershipMapper membershipMapper;

    @Mock
    private VipService vipService;

    @Mock
    private ISysConfigService configService;

    @InjectMocks
    private VipAdminController controller;

    @BeforeEach
    void setUp()
    {
        VipTestContext.setLoginUser(ADMIN);
        // 审核完结后的孤儿凭证清理依赖订单列表查询（默认空）
        when(orderMapper.selectVipRechargeOrderList(any(VipRechargeOrder.class)))
                .thenReturn(Collections.emptyList());
    }

    @AfterEach
    void tearDown()
    {
        VipTestContext.clear();
    }

    private VipRechargeOrder pendingOrder()
    {
        VipRechargeOrder order = new VipRechargeOrder();
        order.setId(100L);
        order.setUserId(USER);
        order.setTierId(10L);
        order.setAmount(new BigDecimal("19.90"));
        order.setProofUrl("vip-proof/2/2026/11/04/x_001.png");
        order.setStatus(VipRechargeOrder.STATUS_PENDING);
        order.setPending(1);
        return order;
    }

    private VipTier monthTier()
    {
        VipTier tier = new VipTier();
        tier.setId(10L);
        tier.setName("月卡");
        tier.setDays(30);
        tier.setPrice(new BigDecimal("19.90"));
        tier.setStatus(VipTier.STATUS_ENABLED);
        return tier;
    }

    // ===== 审核 =====

    @Test
    void audit_approve_conditionalUpdateSuccess_opensVip()
    {
        when(orderMapper.selectVipRechargeOrderById(100L)).thenReturn(pendingOrder());
        when(orderMapper.auditVipRechargeOrder(any(VipRechargeOrder.class))).thenReturn(1);
        when(tierMapper.selectVipTierById(10L)).thenReturn(monthTier());

        VipRechargeOrder body = new VipRechargeOrder();
        body.setId(100L);
        body.setApprove(Boolean.TRUE);

        AjaxResult result = controller.audit(body);
        assertEquals(200, result.get("code"));
        // 开通：tier.days 传给 openVip（叠加语义在 VipService 内，Covers R2/AE4）
        verify(vipService).openVip(eq(USER), eq(30), anyString());
    }

    @Test
    void audit_concurrentUpdateZero_rejectedWithoutOpenVip()
    {
        // 条件更新受影响=0（已被并发审核）→ 拒绝且不开通（KTD11 语义）
        when(orderMapper.selectVipRechargeOrderById(100L)).thenReturn(pendingOrder());
        when(orderMapper.auditVipRechargeOrder(any(VipRechargeOrder.class))).thenReturn(0);

        VipRechargeOrder body = new VipRechargeOrder();
        body.setId(100L);
        body.setApprove(Boolean.TRUE);

        assertThrows(ServiceException.class, () -> controller.audit(body));
        verify(vipService, never()).openVip(any(), anyInt(), anyString());
    }

    @Test
    void audit_rejectWithoutRemark_rejected()
    {
        VipRechargeOrder body = new VipRechargeOrder();
        body.setId(100L);
        body.setApprove(Boolean.FALSE);
        // 驳回无留言 → 400 语义（Covers AE5）
        assertThrows(ServiceException.class, () -> controller.audit(body));
    }

    @Test
    void audit_rejectWithRemark_noVipOpen()
    {
        when(orderMapper.selectVipRechargeOrderById(100L)).thenReturn(pendingOrder());
        when(orderMapper.auditVipRechargeOrder(any(VipRechargeOrder.class))).thenReturn(1);

        VipRechargeOrder body = new VipRechargeOrder();
        body.setId(100L);
        body.setApprove(Boolean.FALSE);
        body.setAdminRemark("金额不符");

        AjaxResult result = controller.audit(body);
        assertEquals(200, result.get("code"));
        verify(vipService, never()).openVip(any(), anyInt(), anyString());
    }

    // ===== 会员调整 =====

    @Test
    void adjust_delegatesToVipService_andInvalidates()
    {
        VipMembership body = new VipMembership();
        body.setUserId(USER);
        body.setExpireTime(new Date(System.currentTimeMillis() - 86_400_000L)); // 过去时间=即时降级
        AjaxResult result = controller.adjust(body);
        assertEquals(200, result.get("code"));
        verify(vipService).adjustExpire(eq(USER), any(Date.class), anyString());
    }

    @Test
    void adjust_missingFields_rejected()
    {
        VipMembership body = new VipMembership();
        body.setUserId(USER); // 缺 expireTime
        assertThrows(ServiceException.class, () -> controller.adjust(body));
    }

    // ===== 档位 CRUD 校验 =====

    @Test
    void tierAdd_invalidDays_rejected()
    {
        VipTier tier = monthTier();
        tier.setDays(0);
        assertThrows(ServiceException.class, () -> controller.tierAdd(tier));
        verify(tierMapper, never()).insertVipTier(any(VipTier.class));
    }

    @Test
    void tierAdd_valid_inserted()
    {
        VipTier tier = monthTier();
        when(tierMapper.insertVipTier(any(VipTier.class))).thenReturn(1);
        assertEquals(200, controller.tierAdd(tier).get("code"));
        verify(tierMapper).insertVipTier(any(VipTier.class));
    }
}
