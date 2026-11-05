package com.ruoyi.web.controller.agent;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.VipRequiredException;
import com.ruoyi.system.service.VipService;
import com.ruoyi.web.controller.VipTestContext;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentController#chat} VIP 身份门槛测试（U3/R12）。
 * <p>
 * 覆盖：普通用户调 Agent 抛 602（置于 AgentRateLimiter 之前——非 VIP 不消耗限流计数）；
 * VIP 放行进入限流。
 *
 * @author ruoyi
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentControllerVipTest
{
    private static final Long USER = 2L;

    @Mock private com.ruoyi.system.agent.lock.AgentRateLimiter rateLimiter;

    @Mock private VipService vipService;

    private AgentController controller;

    @BeforeEach
    void setUp()
    {
        controller = new AgentController();
        ReflectionTestUtils.setField(controller, "llmService", mock(com.ruoyi.system.agent.llm.AgentLlmService.class));
        ReflectionTestUtils.setField(controller, "rateLimiter", rateLimiter);
        ReflectionTestUtils.setField(controller, "vipService", vipService);
        when(rateLimiter.tryAcquire(any())).thenReturn(true);
        when(rateLimiter.getRateLimitInfo(any()))
                .thenReturn(mock(com.ruoyi.system.agent.lock.AgentRateLimiter.RateLimitInfo.class));
        VipTestContext.setLoginUser(USER);
    }

    @AfterEach
    void tearDown()
    {
        VipTestContext.clear();
    }

    private JSONObject body()
    {
        JSONObject body = new JSONObject();
        body.put("input", "帮我建个表");
        return body;
    }

    @Test
    void chat_nonVip_throwsVipRequired_beforeRateLimiter()
    {
        doThrow(new VipRequiredException("AI Agent 为 VIP 专属能力")).when(vipService).assertAgent(USER);
        assertThrows(VipRequiredException.class, () -> controller.chat(body()));
        // VIP 门槛先于限流：非 VIP 不消耗限流计数
        verify(rateLimiter, org.mockito.Mockito.never()).tryAcquire(anyLong());
    }

    @Test
    void chat_vip_passesGateIntoRateLimit()
    {
        // VIP 放行：进入限流（tryAcquire 被调用）；llm 返回 null 时返回错误响应但不抛 602
        assertDoesNotThrow(() -> controller.chat(body()));
        verify(vipService).assertAgent(USER);
        verify(rateLimiter).tryAcquire(USER);
    }
}
