package com.ruoyi.framework.web.exception;

import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.VipLimitException;
import com.ruoyi.common.exception.VipRequiredException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link GlobalExceptionHandler} VIP 错误码映射测试（U3/R15 计划场景收口，评审补强）。
 * <p>
 * VipRequiredException -> {code:602}；VipLimitException -> {code:603}
 * （RuoYi 失败语义：HTTP 200 + JSON body，前端按 code 弹升级引导/纯提示）。
 */
class GlobalExceptionHandlerVipTest
{
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private final HttpServletRequest request = new MockHttpServletRequest();

    @Test
    void vipRequiredException_mappedTo602()
    {
        AjaxResult result = handler.handleVipRequiredException(
                new VipRequiredException("升级 VIP 后可使用"), request);
        assertEquals(602, result.get("code"));
        assertEquals("升级 VIP 后可使用", result.get("msg"));
    }

    @Test
    void vipLimitException_mappedTo603()
    {
        AjaxResult result = handler.handleVipLimitException(
                new VipLimitException("今日 AI 对话次数已用完"), request);
        assertEquals(603, result.get("code"));
        assertEquals("今日 AI 对话次数已用完", result.get("msg"));
    }
}
