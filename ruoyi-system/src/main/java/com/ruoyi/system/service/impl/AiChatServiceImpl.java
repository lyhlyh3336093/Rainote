package com.ruoyi.system.service.impl;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.system.config.GlmConfig;
import com.ruoyi.system.domain.AiChatMessage;
import com.ruoyi.system.domain.AiChatSession;
import com.ruoyi.system.mapper.AiChatMessageMapper;
import com.ruoyi.system.mapper.AiChatSessionMapper;
import com.ruoyi.system.service.IAiChatService;

/**
 * AI聊天Service实现
 * 通过Hutool HttpUtil代理GLM API调用，使用回调实现流式响应
 *
 * @author ruoyi
 */
@Service
public class AiChatServiceImpl implements IAiChatService
{
    private static final Logger log = LoggerFactory.getLogger(AiChatServiceImpl.class);

    @Autowired
    private GlmConfig glmConfig;

    @Autowired
    private AiChatSessionMapper sessionMapper;

    @Autowired
    private AiChatMessageMapper messageMapper;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 流式对话
     * 5步流程：校验会话归属 → 持久化用户消息 → 加载N轮上下文 → 调用GLM → 转发SSE → 持久化助手回复
     * 步骤重排（Group L）：先校验会话归属，再持久化用户消息，防止向不属于自己的会话注入消息
     */
    @Override
    public void chat(Long sessionId, String message, Long userId,
                     Consumer<String> onChunk, Runnable onComplete, Consumer<Exception> onError)
    {
        executor.execute(() -> {
            try
            {
                // Step 1: 校验会话归属（Group L/R9a — 先校验后持久化）
                AiChatSession session = sessionMapper.selectAiChatSessionByIdAndUserId(sessionId, userId);
                if (session == null)
                {
                    onChunk.accept("[ERROR] Session not found or access denied");
                    onComplete.run();
                    return;
                }

                // Step 2: 持久化用户消息（已校验归属后）
                AiChatMessage userMsg = new AiChatMessage();
                userMsg.setSessionId(sessionId);
                userMsg.setRole("user");
                userMsg.setContent(message);
                userMsg.setCreateTime(DateUtils.getNowDate());
                messageMapper.insertAiChatMessage(userMsg);

                // 加载最近N轮上下文（N轮 = N*2条消息，按id倒序取后正序排列）
                int limit = glmConfig.getContextRounds() * 2;
                List<AiChatMessage> history = messageMapper.selectLastNMessagesBySessionId(sessionId, limit);
                Collections.reverse(history);

                // Step 3: 构建GLM请求
                JSONArray messagesArray = new JSONArray();
                for (AiChatMessage msg : history)
                {
                    JSONObject obj = new JSONObject();
                    obj.put("role", msg.getRole());
                    obj.put("content", msg.getContent());
                    messagesArray.add(obj);
                }

                JSONObject requestBody = new JSONObject();
                requestBody.put("model", glmConfig.getModel());
                requestBody.put("messages", messagesArray);
                requestBody.put("stream", true);
                requestBody.put("max_tokens", glmConfig.getMaxTokens());

                // Step 4: 调用GLM API并流式转发
                StringBuilder fullResponse = new StringBuilder();

                try (HttpResponse response = HttpRequest.post(glmConfig.getApiUrl())
                        .header("Authorization", "Bearer " + glmConfig.getApiKey())
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .body(requestBody.toJSONString())
                        .executeAsync())
                {
                    if (response.getStatus() != 200)
                    {
                        String errBody = response.body();
                        onChunk.accept("[ERROR] GLM API returned " + response.getStatus() + ": " + errBody);
                        onComplete.run();
                        return;
                    }

                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.bodyStream(), StandardCharsets.UTF_8)))
                    {
                        String line;
                        while ((line = reader.readLine()) != null)
                        {
                            if (!line.startsWith("data:"))
                            {
                                continue;
                            }
                            String data = line.substring(5).trim();
                            if ("[DONE]".equals(data))
                            {
                                break;
                            }
                            if (data.isEmpty())
                            {
                                continue;
                            }
                            JSONObject chunk = JSON.parseObject(data);
                            JSONArray choices = chunk.getJSONArray("choices");
                            if (choices != null && !choices.isEmpty())
                            {
                                JSONObject firstChoice = choices.getJSONObject(0);
                                JSONObject delta = firstChoice.getJSONObject("delta");
                                if (delta != null)
                                {
                                    String content = delta.getString("content");
                                    if (content != null)
                                    {
                                        fullResponse.append(content);
                                        onChunk.accept(content);
                                    }
                                }
                            }
                        }
                    }
                }

                // Step 5: 持久化助手消息 + 标题自动派生
                AiChatMessage assistantMsg = new AiChatMessage();
                assistantMsg.setSessionId(sessionId);
                assistantMsg.setRole("assistant");
                assistantMsg.setContent(fullResponse.toString());
                assistantMsg.setCreateTime(DateUtils.getNowDate());
                messageMapper.insertAiChatMessage(assistantMsg);

                if (session.getTitle() == null)
                {
                    // 标题长度上限 20（Group R9a 收紧）
                    String title = message.length() > 20 ? message.substring(0, 20) : message;
                    sessionMapper.updateAiChatSessionTitle(sessionId, title);
                }

                onComplete.run();

            }
            catch (Exception e)
            {
                log.error("AI chat streaming failed", e);
                onError.accept(e);
            }
        });
    }

    /**
     * 创建新会话
     */
    @Override
    public AiChatSession createSession(Long userId)
    {
        AiChatSession session = new AiChatSession();
        session.setUserId(userId);
        session.setDelFlag(0L);
        session.setCreateTime(DateUtils.getNowDate());
        session.setLastSelectedAt(DateUtils.getNowDate());
        sessionMapper.insertAiChatSession(session);
        return session;
    }

    /**
     * 查询当前用户的所有未删除会话（按lastSelectedAt倒序）
     */
    @Override
    public List<AiChatSession> listSessions(Long userId)
    {
        AiChatSession query = new AiChatSession();
        query.setUserId(userId);
        query.setDelFlag(0L);
        return sessionMapper.selectAiChatSessionList(query);
    }

    /**
     * 软删除会话（IDOR防护：先校验归属，再软删除）
     * R9a/Group C — 校验失败抛 ServiceException(403)
     */
    @Override
    public int deleteSession(Long id, Long userId)
    {
        verifyOwnership(id, userId);
        return sessionMapper.deleteAiChatSessionById(id);
    }

    /**
     * 查询会话消息（IDOR防护：先校验归属，再查询消息）
     * R9a/Group C — 校验失败抛 ServiceException(403)
     */
    @Override
    public List<AiChatMessage> listMessages(Long sessionId, Long userId)
    {
        verifyOwnership(sessionId, userId);
        // verifyOwnership 已校验归属，userId 过滤为纵深防御
        return messageMapper.selectMessagesBySessionIdAndUserId(sessionId, userId);
    }

    /**
     * 更新会话最后选择时间（IDOR防护：先校验归属）
     * R9a/Group C — 校验失败抛 ServiceException(403)
     */
    @Override
    public int selectSession(Long id, Long userId)
    {
        verifyOwnership(id, userId);
        return sessionMapper.updateLastSelectedAt(id, userId);
    }

    /**
     * 查询单个会话（IDOR防护）
     * R9a/Group C — GET /ai/session/{id}
     */
    @Override
    public AiChatSession getSession(Long id, Long userId)
    {
        return verifyOwnership(id, userId);
    }

    /**
     * 更新会话标题（IDOR防护 + 长度限制）
     * R4a/Group F — POST /ai/session/{id}/title，标题上限 50
     */
    @Override
    public int updateTitle(Long id, Long userId, String title)
    {
        verifyOwnership(id, userId);
        String safeTitle = title == null ? "" : title.trim();
        if (safeTitle.length() > 50)
        {
            safeTitle = safeTitle.substring(0, 50);
        }
        return sessionMapper.updateAiChatSessionTitle(id, safeTitle);
    }

    /**
     * 校验会话归属：按 id+userId 查询，未匹配则抛 ServiceException(403)
     * R9a/Group C — 所有按 id 访问会话的端点必须调用
     */
    private AiChatSession verifyOwnership(Long sessionId, Long userId)
    {
        AiChatSession session = sessionMapper.selectAiChatSessionByIdAndUserId(sessionId, userId);
        if (session == null)
        {
            throw new ServiceException("无权访问该会话", HttpStatus.FORBIDDEN);
        }
        return session;
    }
}
