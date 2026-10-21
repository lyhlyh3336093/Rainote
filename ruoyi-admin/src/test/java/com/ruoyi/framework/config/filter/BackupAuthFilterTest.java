package com.ruoyi.framework.config.filter;

import javax.servlet.ServletException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.framework.web.service.TokenService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link BackupAuthFilter} 单元测试（U3）。
 * <p>
 * 覆盖：匿名 POST 超限请求体直接 413 拒绝（不进入过滤器链，不做 multipart 解析）、
 * 不设 Content-Length 的匿名 POST（chunked 语义）同样拒绝（fail-closed）、
 * 已登录用户超限放行（还原上传通道语义）、匿名小请求放行（由控制器层鉴权接管）。
 * 测试寄宿于 ruoyi-admin 测试树（ruoyi-framework 无测试依赖），包名与被测类一致。
 *
 * @author ruoyi
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BackupAuthFilterTest
{
    @Mock
    private TokenService tokenService;

    private BackupAuthFilter filter;

    @BeforeEach
    void setUp()
    {
        filter = new BackupAuthFilter(tokenService);
    }

    private MockHttpServletRequest restorePost(byte[] content)
    {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/system/note/restore");
        request.setContentType("multipart/form-data");
        request.setContent(content);
        return request;
    }

    @Test
    void anonymousOversizedPost_rejected413_chainNotInvoked() throws Exception
    {
        // 匿名 POST 且 Content-Length > 1MB：pre-body 防线直接拒绝
        byte[] oversized = new byte[(int) BackupAuthFilter.MAX_ANONYMOUS_BODY_BYTES + 1];
        MockHttpServletRequest request = restorePost(oversized);
        when(tokenService.getLoginUser(any(MockHttpServletRequest.class))).thenReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("已被拒绝"));
        assertNull(chain.getRequest(), "过滤器链不应继续（不做 multipart 解析）");
    }

    @Test
    void anonymousPostWithoutContentLength_rejected413_chainNotInvoked() throws Exception
    {
        // 不设 Content-Length 的匿名 POST（chunked 语义）：长度未知视同超限，fail-closed 拒绝
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/system/note/restore");
        request.setContentType("multipart/form-data");
        // 不 setContent：无内容时 getContentLengthLong() 返回 -1（模拟 chunked 长度未知）
        when(tokenService.getLoginUser(any(MockHttpServletRequest.class))).thenReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("已被拒绝"));
        assertNull(chain.getRequest(), "过滤器链不应继续（不做 multipart 解析）");
    }

    @Test
    void authenticatedOversizedPost_passesThrough() throws Exception
    {
        // 已登录用户超限放行：还原上传通道可达 512MB（KTD5）
        byte[] oversized = new byte[(int) BackupAuthFilter.MAX_ANONYMOUS_BODY_BYTES + 1];
        MockHttpServletRequest request = restorePost(oversized);
        LoginUser loginUser = new LoginUser(1L, null, null, null);
        when(tokenService.getLoginUser(any(MockHttpServletRequest.class))).thenReturn(loginUser);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertSame(request, chain.getRequest(), "已登录请求应进入后续过滤器链");
    }

    @Test
    void anonymousSmallPost_passesThrough() throws Exception
    {
        // 匿名小请求放行：由控制器层 @PreAuthorize + getUserId 兜底判定接管
        MockHttpServletRequest request = restorePost(new byte[1024]);
        when(tokenService.getLoginUser(any(MockHttpServletRequest.class))).thenReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertSame(request, chain.getRequest());
    }
}
