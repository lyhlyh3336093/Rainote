package com.ruoyi.web.controller.agent;

import com.ruoyi.system.agent.model.AgentPlan;
import com.ruoyi.system.agent.registry.AgentExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 计划会话管理器（JVM 内存）。
 * <p>
 * 存储 auditLogId → 执行上下文映射，供步骤确认/跳过/重试等端点
 * 获取原始计划与执行上下文，无需从前端重复传递或从审计日志反序列化。
 * <p>
 * MVP 单实例内存存储；多实例部署时需升级为 Redis 或数据库存储。
 * 计划完成或取消后自动清理。
 */
@Component
public class PlanSessionManager
{
    private static final Logger log = LoggerFactory.getLogger(PlanSessionManager.class);

    private final ConcurrentHashMap<Long, PlanSession> sessions = new ConcurrentHashMap<>();

    /**
     * 存储计划会话。
     */
    public void put(Long auditLogId, AgentPlan plan, AgentExecutionContext context,
                    String sessionId, String userInput)
    {
        if (auditLogId == null) return;
        PlanSession session = new PlanSession(plan, context, sessionId, userInput);
        sessions.put(auditLogId, session);
        log.debug("计划会话已存储: auditLogId={}, steps={}",
                auditLogId, plan != null ? plan.getSteps().size() : 0);
    }

    /**
     * 获取计划会话。
     */
    public PlanSession get(Long auditLogId)
    {
        if (auditLogId == null) return null;
        return sessions.get(auditLogId);
    }

    /**
     * 移除计划会话（计划完成/取消时调用）。
     */
    public void remove(Long auditLogId)
    {
        if (auditLogId == null) return;
        sessions.remove(auditLogId);
        log.debug("计划会话已移除: auditLogId={}", auditLogId);
    }

    /**
     * 检查会话是否存在。
     */
    public boolean exists(Long auditLogId)
    {
        return auditLogId != null && sessions.containsKey(auditLogId);
    }

    /**
     * 计划会话数据。
     */
    public static class PlanSession
    {
        private final AgentPlan plan;
        private final AgentExecutionContext context;
        private final String sessionId;
        private final String userInput;

        PlanSession(AgentPlan plan, AgentExecutionContext context,
                    String sessionId, String userInput)
        {
            this.plan = plan;
            this.context = context;
            this.sessionId = sessionId;
            this.userInput = userInput;
        }

        public AgentPlan getPlan() { return plan; }
        public AgentExecutionContext getContext() { return context; }
        public String getSessionId() { return sessionId; }
        public String getUserInput() { return userInput; }
    }
}
