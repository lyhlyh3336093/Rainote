package com.ruoyi.web.controller.ai;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.ruoyi.common.annotation.RateLimiter;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.LimitType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.domain.AiChatMessage;
import com.ruoyi.system.domain.AiChatSession;
import com.ruoyi.system.service.IAiChatService;

/**
 * AI聊天Controller
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/ai")
public class AiChatController extends BaseController
{
    @Autowired
    private IAiChatService aiChatService;

    /**
     * 流式对话
     * 接受 { sessionId, message }，返回SseEmitter流式响应
     * R13/Group R — 基础限流：每IP每分钟10次，防止GLM API配额耗尽
     */
    @RateLimiter(time = 60, count = 10, limitType = LimitType.IP)
    @PostMapping("/chat")
    public SseEmitter chat(@RequestBody ChatRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        SseEmitter emitter = new SseEmitter(120000L);

        aiChatService.chat(
            request.getSessionId(),
            request.getMessage(),
            userId,
            // onChunk
            chunk -> {
                try
                {
                    emitter.send(SseEmitter.event().data(chunk));
                }
                catch (Exception ignored)
                {
                }
            },
            // onComplete
            () -> {
                try
                {
                    emitter.send(SseEmitter.event().data("[DONE]"));
                    emitter.complete();
                }
                catch (Exception ignored)
                {
                }
            },
            // onError
            error -> {
                try
                {
                    emitter.send(SseEmitter.event().data("[ERROR] " + error.getMessage()));
                }
                catch (Exception ignored)
                {
                }
                emitter.completeWithError(error);
            }
        );

        return emitter;
    }

    /**
     * 创建新会话
     */
    @PostMapping("/session/create")
    public AjaxResult createSession()
    {
        Long userId = SecurityUtils.getUserId();
        AiChatSession session = aiChatService.createSession(userId);
        return success(session);
    }

    /**
     * 查询当前用户的所有会话
     */
    @GetMapping("/session/list")
    public AjaxResult listSessions()
    {
        Long userId = SecurityUtils.getUserId();
        List<AiChatSession> list = aiChatService.listSessions(userId);
        return success(list);
    }

    /**
     * 软删除会话（IDOR防护：verifyOwnership + 403）
     */
    @DeleteMapping("/session/{id}")
    public AjaxResult deleteSession(@PathVariable("id") Long id)
    {
        Long userId = SecurityUtils.getUserId();
        return toAjax(aiChatService.deleteSession(id, userId));
    }

    /**
     * 查询会话消息（IDOR防护：verifyOwnership + 403）
     */
    @GetMapping("/message/list")
    public AjaxResult listMessages(@RequestParam("sessionId") Long sessionId)
    {
        Long userId = SecurityUtils.getUserId();
        List<AiChatMessage> list = aiChatService.listMessages(sessionId, userId);
        return success(list);
    }

    /**
     * 查询单个会话（IDOR防护：verifyOwnership + 403）
     * R9a/Group C — GET /ai/session/{id}
     */
    @GetMapping("/session/{id}")
    public AjaxResult getSession(@PathVariable("id") Long id)
    {
        Long userId = SecurityUtils.getUserId();
        AiChatSession session = aiChatService.getSession(id, userId);
        return success(session);
    }

    /**
     * 更新会话标题（IDOR防护：verifyOwnership + 403）
     * R4a/Group F — POST /ai/session/{id}/title
     */
    @PostMapping("/session/{id}/title")
    public AjaxResult updateTitle(@PathVariable("id") Long id, @RequestBody TitleRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        return toAjax(aiChatService.updateTitle(id, userId, request.getTitle()));
    }

    /**
     * 更新会话最后选择时间（支持R10恢复时重新排序）
     * Group O — 由 PUT 改为 POST（select 为动作语义，非资源替换）
     */
    @PostMapping("/session/{id}/select")
    public AjaxResult selectSession(@PathVariable("id") Long id)
    {
        Long userId = SecurityUtils.getUserId();
        return toAjax(aiChatService.selectSession(id, userId));
    }

    /**
     * 聊天请求体
     */
    public static class ChatRequest
    {
        private Long sessionId;

        private String message;

        public Long getSessionId()
        {
            return sessionId;
        }

        public void setSessionId(Long sessionId)
        {
            this.sessionId = sessionId;
        }

        public String getMessage()
        {
            return message;
        }

        public void setMessage(String message)
        {
            this.message = message;
        }
    }

    /**
     * 标题更新请求体（Group F/R4a）
     */
    public static class TitleRequest
    {
        private String title;

        public String getTitle()
        {
            return title;
        }

        public void setTitle(String title)
        {
            this.title = title;
        }
    }
}
