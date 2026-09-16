package com.ruoyi.system.agent.llm;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.agent.audit.PiiRedactor;
import com.ruoyi.system.agent.model.AgentContext;
import com.ruoyi.system.agent.model.AgentPlan;
import com.ruoyi.system.agent.registry.AgentOperationRegistry;
import com.ruoyi.system.config.GlmConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Agent LLM 服务，调用 GLM 生成操作计划。
 * <p>
 * 复用 {@link GlmConfig} 但独立于 {@code AiChatSession}，使用非流式 JSON 模式调用。
 * <p>
 * R20a：超时 30s、重试 2 次、指数退避（1s, 3s），全部失败后返回友好错误。
 * R27：调用前对用户输入应用 {@link PiiRedactor} 脱敏。
 *
 * @see PlanPromptBuilder
 * @see PlanResponseParser
 */
@Service
public class AgentLlmService
{
    private static final Logger log = LoggerFactory.getLogger(AgentLlmService.class);

    /** R20a: HTTP 超时（毫秒） */
    private static final int HTTP_TIMEOUT_MS = 30_000;
    /** R20a: 最大重试次数（不含首次） */
    private static final int MAX_RETRIES = 2;
    /** R20a: 指数退避基数（毫秒），退避序列：1000, 3000 */
    private static final long[] BACKOFF_MS = {1_000, 3_000};

    @Autowired
    private GlmConfig glmConfig;

    @Autowired
    private AgentOperationRegistry registry;

    @Autowired
    private PlanPromptBuilder promptBuilder;

    @Autowired
    private PlanResponseParser responseParser;

    @Autowired
    private PiiRedactor piiRedactor;

    /**
     * 生成操作计划。
     * <p>
     * 完整流程：PII 脱敏 → 构造 prompt → 调用 GLM（含重试）→ 解析响应 → 返回 AgentPlan。
     * LLM 调用失败时返回 {@link AgentPlan#ofError(String)}，不抛异常。
     *
     * @param userInput 用户自然语言输入
     * @param context   当前上下文（可为 null）
     * @return AgentPlan（PLAN/QUERY/CLARIFY/ERROR）
     */
    public AgentPlan generatePlan(String userInput, AgentContext context)
    {
        if (userInput == null || userInput.trim().isEmpty())
        {
            return AgentPlan.ofError("输入不能为空");
        }

        // R27: PII 脱敏
        String redactedInput = piiRedactor.redact(userInput);

        // 构造 prompt
        String systemPrompt = promptBuilder.buildSystemPrompt(registry, context);
        String userMessage = promptBuilder.buildUserMessage(redactedInput);

        // 调用 GLM（含重试）
        String llmResponse = callGlmWithRetry(systemPrompt, userMessage);
        if (llmResponse == null)
        {
            return AgentPlan.ofError("AI 暂时无法处理，请稍后重试");
        }

        // 解析响应
        return responseParser.parse(llmResponse, registry);
    }

    /**
     * 调用 GLM API，带重试与指数退避（R20a）。
     *
     * @param systemPrompt system prompt
     * @param userMessage  用户消息（已脱敏）
     * @return GLM 返回的文本内容，全部重试失败返回 null
     */
    String callGlmWithRetry(String systemPrompt, String userMessage)
    {
        Exception lastException = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++)
        {
            if (attempt > 0)
            {
                long backoff = BACKOFF_MS[attempt - 1];
                log.info("GLM 调用重试 {}/{}，等待 {}ms", attempt, MAX_RETRIES, backoff);
                try
                {
                    Thread.sleep(backoff);
                }
                catch (InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            try
            {
                String response = callGlmOnce(systemPrompt, userMessage);
                if (response != null)
                {
                    return response;
                }
            }
            catch (Exception e)
            {
                lastException = e;
                log.warn("GLM 调用失败（尝试 {}）: {}", attempt + 1, e.getMessage());
            }
        }

        if (lastException != null)
        {
            log.error("GLM 调用全部重试失败", lastException);
        }
        return null;
    }

    /**
     * 单次调用 GLM API（非流式 JSON 模式）。
     *
     * @return GLM 返回的 content 文本，失败返回 null
     */
    private String callGlmOnce(String systemPrompt, String userMessage)
    {
        // 构造消息数组
        JSONArray messages = new JSONArray();
        JSONObject systemMsg = new JSONObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        // 构造请求体
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", glmConfig.getModel());
        requestBody.put("messages", messages);
        requestBody.put("stream", false);
        requestBody.put("max_tokens", glmConfig.getMaxTokens());
        // JSON 模式：强制 GLM 返回合法 JSON
        JSONObject responseFormat = new JSONObject();
        responseFormat.put("type", "json_object");
        requestBody.put("response_format", responseFormat);

        try (HttpResponse response = HttpRequest.post(glmConfig.getApiUrl())
                .header("Authorization", "Bearer " + glmConfig.getApiKey())
                .header("Content-Type", "application/json")
                .timeout(HTTP_TIMEOUT_MS)
                .body(requestBody.toJSONString())
                .execute())
        {
            if (response.getStatus() != 200)
            {
                log.warn("GLM API 返回 {}: {}", response.getStatus(),
                        truncate(response.body(), 200));
                return null;
            }

            String body = response.body();
            JSONObject respJson = JSON.parseObject(body);
            JSONArray choices = respJson.getJSONArray("choices");
            if (choices == null || choices.isEmpty())
            {
                log.warn("GLM 返回无 choices");
                return null;
            }

            JSONObject firstChoice = choices.getJSONObject(0);
            JSONObject message = firstChoice.getJSONObject("message");
            if (message == null)
            {
                log.warn("GLM 返回无 message");
                return null;
            }

            String content = message.getString("content");
            if (content == null || content.trim().isEmpty())
            {
                log.warn("GLM 返回空 content");
                return null;
            }

            return content;
        }
    }

    /**
     * 获取操作清单的 LLM 可读 schema（供前端预览或调试）。
     */
    public String exportOperationSchema()
    {
        return JSON.toJSONString(registry.exportSchemaForLlm());
    }

    private String truncate(String s, int max)
    {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
