package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.AiChatSession;

/**
 * AI聊天会话Mapper接口
 *
 * @author ruoyi
 */
public interface AiChatSessionMapper
{
    /**
     * 查询AI聊天会话
     */
    public AiChatSession selectAiChatSessionById(Long id);

    /**
     * 按ID和userId查询会话 (IDOR mitigation)
     */
    public AiChatSession selectAiChatSessionByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 查询AI聊天会话列表
     */
    public List<AiChatSession> selectAiChatSessionList(AiChatSession aiChatSession);

    /**
     * 新增AI聊天会话
     */
    public int insertAiChatSession(AiChatSession aiChatSession);

    /**
     * 修改AI聊天会话
     */
    public int updateAiChatSession(AiChatSession aiChatSession);

    /**
     * 更新会话标题
     */
    public int updateAiChatSessionTitle(@Param("id") Long id, @Param("title") String title);

    /**
     * 更新最后选择时间
     */
    public int updateLastSelectedAt(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 软删除AI聊天会话
     */
    public int deleteAiChatSessionById(Long id);

    /**
     * 按ID和userId软删除会话 (IDOR防护)
     */
    public int deleteAiChatSessionByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}
