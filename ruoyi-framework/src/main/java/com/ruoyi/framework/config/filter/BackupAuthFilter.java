package com.ruoyi.framework.config.filter;

import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.framework.web.service.TokenService;

/**
 * 备份还原上传通道前置防线（U3）。
 * <p>
 * Spring MVC 在处理器调用前已完成 multipart 请求体的解析落盘，@PreAuthorize/@RateLimiter
 * 均晚于 512MB 请求体到达——本 Filter 是唯一的 pre-body 防线：/system/** 路径下
 * 未携带有效 token 的 POST 请求且 Content-Length 超过 1MB 或未知（chunked 传输
 * 编码）时直接拒绝（不做 multipart
 * 解析），阻断匿名可达大请求体的资源耗尽放大面（KTD5）。已登录用户与小请求不受影响，
 * 由后续控制器层的鉴权注解与 userId==1 兜底判定接管。
 *
 * @author ruoyi
 */
public class BackupAuthFilter implements Filter
{
    /** 匿名 POST 请求体上限（字节）：超过即拒绝，1MB 覆盖常规 JSON 表单 */
    public static final long MAX_ANONYMOUS_BODY_BYTES = 1024L * 1024L;

    private final TokenService tokenService;

    public BackupAuthFilter(TokenService tokenService)
    {
        this.tokenService = tokenService;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException
    {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        // 短路顺序：先判请求体大小（零成本），仅长度未知或超限时才查 token（Redis 读）
        if ("POST".equalsIgnoreCase(httpRequest.getMethod()))
        {
            // Content-Length 未知（chunked 传输编码，返回 -1）视同超限 fail-closed：
            // 浏览器/SPA 的 POST 与 multipart 上传必带 Content-Length，正常通道不受影响
            long len = httpRequest.getContentLengthLong();
            if ((len < 0 || len > MAX_ANONYMOUS_BODY_BYTES)
                    && tokenService.getLoginUser(httpRequest) == null)
            {
                // 413（Payload Too Large）：匿名过大或无长度会话请求直接拒绝，不进入后续过滤器链
                httpResponse.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                httpResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
                httpResponse.setCharacterEncoding("UTF-8");
                httpResponse.getWriter().write(JSON.toJSONString(
                        AjaxResult.error("未登录状态的过大上传请求已被拒绝，请先登录")));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
