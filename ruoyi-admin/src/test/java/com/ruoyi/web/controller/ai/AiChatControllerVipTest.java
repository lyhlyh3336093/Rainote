package com.ruoyi.web.controller.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.VipLimitException;
import com.ruoyi.common.exception.VipRequiredException;
import com.ruoyi.system.service.IAiChatService;
import com.ruoyi.system.service.VipService;
import com.ruoyi.web.controller.VipTestContext;
import com.ruoyi.web.controller.ai.AiChatController.ChatRequest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link AiChatController#chat} VIP 每日配额断言测试（U3/R12）。
 * <p>
 * 覆盖：普通用户当日第 11 次抛 602（Covers AE6）；VIP 第 101 次抛 603；
 * 放行时 AI 服务正常触达。
 *
 * @author ruoyi
 */
class AiChatControllerVipTest
{
    private static final Long USER = 2L;

    private AiChatController controller;

    private IAiChatService aiChatService;

    private VipService vipService;

    @BeforeEach
    void setUp()
    {
        controller = new AiChatController();
        aiChatService = mock(IAiChatService.class);
        vipService = VipTestContext.mockPermissiveVipService();
        ReflectionTestUtils.setField(controller, "aiChatService", aiChatService);
        ReflectionTestUtils.setField(controller, "vipService", vipService);
        VipTestContext.setLoginUser(USER);
    }

    @AfterEach
    void tearDown()
    {
        VipTestContext.clear();
    }

    private ChatRequest request()
    {
        ChatRequest request = new ChatRequest();
        request.setMessage("你好");
        return request;
    }

    @Test
    void chat_normalUserDailyExhausted_throwsVipRequired()
    {
        doThrow(new VipRequiredException("今日 AI 对话次数已用完")).when(vipService).aiDailyTryAcquire(USER);
        assertThrows(VipRequiredException.class, () -> controller.chat(request()));
        verifyNoInteractions(aiChatService);
    }

    @Test
    void chat_vipDailyExhausted_throwsVipLimit()
    {
        // VIP 第 101 次 → 603（已达上限，非升级引导）
        doThrow(new VipLimitException("今日 AI 对话次数已用完")).when(vipService).aiDailyTryAcquire(USER);
        assertThrows(VipLimitException.class, () -> controller.chat(request()));
        verifyNoInteractions(aiChatService);
    }

    @Test
    void chat_withinDailyLimit_emitterCreated()
    {
        assertDoesNotThrow(() -> controller.chat(request()));
        verify(vipService).aiDailyTryAcquire(USER);
        verify(aiChatService).chat(any(), any(), anyLong(), any(), any(), any());
    }
}
