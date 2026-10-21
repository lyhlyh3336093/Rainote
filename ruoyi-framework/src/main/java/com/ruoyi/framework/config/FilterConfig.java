package com.ruoyi.framework.config;

import java.util.HashMap;
import java.util.Map;
import javax.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.ruoyi.common.filter.RepeatableFilter;
import com.ruoyi.common.filter.XssFilter;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.framework.config.filter.BackupAuthFilter;
import com.ruoyi.framework.web.service.TokenService;

/**
 * Filter配置
 *
 * @author ruoyi
 */
@Configuration
public class FilterConfig
{
    @Value("${xss.excludes}")
    private String excludes;

    @Value("${xss.urlPatterns}")
    private String urlPatterns;

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Bean
    @ConditionalOnProperty(value = "xss.enabled", havingValue = "true")
    public FilterRegistrationBean xssFilterRegistration()
    {
        FilterRegistrationBean registration = new FilterRegistrationBean();
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setFilter(new XssFilter());
        registration.addUrlPatterns(StringUtils.split(urlPatterns, ","));
        registration.setName("xssFilter");
        registration.setOrder(FilterRegistrationBean.HIGHEST_PRECEDENCE);
        Map<String, String> initParameters = new HashMap<String, String>();
        initParameters.put("excludes", excludes);
        registration.setInitParameters(initParameters);
        return registration;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Bean
    public FilterRegistrationBean someFilterRegistration()
    {
        FilterRegistrationBean registration = new FilterRegistrationBean();
        registration.setFilter(new RepeatableFilter());
        registration.addUrlPatterns("/*");
        registration.setName("repeatableFilter");
        registration.setOrder(FilterRegistrationBean.LOWEST_PRECEDENCE);
        return registration;
    }

    /**
     * 备份还原上传通道前置防线（U3）：/system/* 下匿名 POST 且请求体超 1MB 直接 413 拒绝。
     * Spring MVC 在处理器调用前已完成 multipart 落盘，@PreAuthorize/@RateLimiter 均晚于
     * 512MB 请求体到达，本 Filter 是唯一的 pre-body 防线。
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Bean
    public FilterRegistrationBean backupAuthFilterRegistration(TokenService tokenService)
    {
        FilterRegistrationBean registration = new FilterRegistrationBean();
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setFilter(new BackupAuthFilter(tokenService));
        registration.addUrlPatterns("/system/*");
        registration.setName("backupAuthFilter");
        // 紧随 xssFilter（HIGHEST_PRECEDENCE）之后、repeatableFilter（LOWEST）之前，
        // 保证在任何请求体读取前完成拦截
        registration.setOrder(FilterRegistrationBean.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

}
