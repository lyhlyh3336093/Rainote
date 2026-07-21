package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.AiChatMessage;

/**
 * AI聊天消息Mapper接口
 *
 * @author ruoyi
 */
public interface AiChatMessageMapper
{
    /**
     * 查询AI聊天消息
     */
    public AiChatMessage selectAiChatMessageById(Long id);

    /**
     * 查询AI聊天消息列表
     */
    public List<AiChatMessage> selectAiChatMessageList(AiChatMessage aiChatMessage);

    /**
     * 查询会话最后N条消息 (按id倒序取limit条)
     */
    public List<AiChatMessage> selectLastNMessagesBySessionId(@Param("sessionId") Long sessionId, @Param("limit") int limit);

    /**
     * 按sessionId和userId查询消息 (join ai_chat_session 实现用户隔离)
     */
    public List<AiChatMessage> selectMessagesBySessionIdAndUserId(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    /**
     * 新增AI聊天消息
     */
    public int insertAiChatMessage(AiChatMessage aiChatMessage);

    /**
     * 删除AI聊天消息
     */
    public int deleteAiChatMessageById(Long id);

    /**
     * 按会话ID删除消息
     */
    public int deleteAiChatMessageBySessionId(Long sessionId);
}
