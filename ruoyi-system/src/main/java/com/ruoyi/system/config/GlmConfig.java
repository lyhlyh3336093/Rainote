package com.ruoyi.system.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * GLM API配置
 * 读取 application.yml 中 prefix="glm" 的配置块
 *
 * @author ruoyi
 */
@Component
@ConfigurationProperties(prefix = "glm")
public class GlmConfig
{
    /** API密钥 */
    private String apiKey;

    /** 模型名称 */
    private String model;

    /** API地址 */
    private String apiUrl;

    /** 上下文轮数 */
    private int contextRounds;

    /** 最大token数 */
    private int maxTokens;

    public String getApiKey()
    {
        return apiKey;
    }

    public void setApiKey(String apiKey)
    {
        this.apiKey = apiKey;
    }

    public String getModel()
    {
        return model;
    }

    public void setModel(String model)
    {
        this.model = model;
    }

    public String getApiUrl()
    {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl)
    {
        this.apiUrl = apiUrl;
    }

    public int getContextRounds()
    {
        return contextRounds;
    }

    public void setContextRounds(int contextRounds)
    {
        this.contextRounds = contextRounds;
    }

    public int getMaxTokens()
    {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens)
    {
        this.maxTokens = maxTokens;
    }
}
