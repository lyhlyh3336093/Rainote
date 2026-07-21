package com.ruoyi.system.service;

import java.util.List;
import java.util.function.Consumer;
import com.ruoyi.system.domain.AiChatMessage;
import com.ruoyi.system.domain.AiChatSession;

/**
 * AI聊天Service接口
 *
 * @author ruoyi
 */
public interface IAiChatService
{
    /**
     * 与GLM进行流式对话
     * 异步执行：通过回调推送流式数据，方法立即返回
     *
     * @param sessionId  会话ID
     * @param message    用户消息
     * @param userId     当前用户ID（用于IDOR防护）
     * @param onChunk    每个文本片段的回调
     * @param onComplete 流式结束回调
     * @param onError    异常回调
     */
    void chat(Long sessionId, String message, Long userId,
              Consumer<String> onChunk, Runnable onComplete, Consumer<Exception> onError);

    /**
     * 创建新会话
     */
    AiChatSession createSession(Long userId);

    /**
     * 查询当前用户的所有未删除会话（按lastSelectedAt倒序）
     */
    List<AiChatSession> listSessions(Long userId);

    /**
     * 软删除会话（IDOR防护：先校验归属，再软删除）
     * R9a/Group C — 校验失败抛 ServiceException(403)
     */
    int deleteSession(Long id, Long userId);

    /**
     * 查询会话消息（IDOR防护：先校验归属，再查询消息）
     * R9a/Group C — 校验失败抛 ServiceException(403)
     */
    List<AiChatMessage> listMessages(Long sessionId, Long userId);

    /**
     * 更新会话最后选择时间（IDOR防护：先校验归属）
     * R9a/Group C — 校验失败抛 ServiceException(403)
     */
    int selectSession(Long id, Long userId);

    /**
     * 查询单个会话（IDOR防护）
     * R9a/Group C — GET /ai/session/{id}
     */
    AiChatSession getSession(Long id, Long userId);

    /**
     * 更新会话标题（IDOR防护 + 长度限制）
     * R4a/Group F — POST /ai/session/{id}/title，标题上限 50
     */
    int updateTitle(Long id, Long userId, String title);
}
