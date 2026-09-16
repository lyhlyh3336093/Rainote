package com.ruoyi.web.controller.agent;

import com.alibaba.fastjson2.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE Emitter 管理器。
 * <p>
 * 管理 auditLogId → SseEmitter 的映射，支持事件缓冲：
 * <ul>
 *   <li>{@link #create} 创建 emitter（执行开始前调用，此时前端尚未订阅）</li>
 *   <li>{@link #send} 发送事件，若前端尚未订阅则缓冲，订阅后自动 flush</li>
 *   <li>{@link #subscribe} 前端订阅 SSE 时调用，返回 emitter 并 flush 缓冲事件</li>
 *   <li>{@link #complete} 计划完成时关闭 emitter</li>
 * </ul>
 * 解决时序问题：POST /agent/confirm 同步执行非破坏性步骤时事件先缓冲，
 * 前端 GET /agent/plan/{id}/stream 订阅后再 flush。
 */
@Component
public class SseEmitterManager
{
    private static final Logger log = LoggerFactory.getLogger(SseEmitterManager.class);

    /** SSE 连接超时时间（5 分钟，与计划文档 proxy_read_timeout ≥300s 对齐） */
    private static final long SSE_TIMEOUT_MS = 300_000L;

    private final ConcurrentHashMap<Long, EmitterEntry> entries = new ConcurrentHashMap<>();

    /**
     * 创建 emitter（执行开始前调用）。
     */
    public void create(Long auditLogId)
    {
        if (auditLogId == null) return;
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        EmitterEntry entry = new EmitterEntry(emitter);
        entries.put(auditLogId, entry);

        emitter.onCompletion(() ->
        {
            entries.remove(auditLogId);
            log.debug("SSE 连接关闭: auditLogId={}", auditLogId);
        });
        emitter.onTimeout(() ->
        {
            entries.remove(auditLogId);
            log.warn("SSE 连接超时: auditLogId={}", auditLogId);
            emitter.complete();
        });
        emitter.onError(e ->
        {
            entries.remove(auditLogId);
            log.warn("SSE 连接异常: auditLogId={}", auditLogId, e);
        });
    }

    /**
     * 前端订阅 SSE（GET /agent/plan/{id}/stream 调用）。
     * <p>
     * 返回 emitter 并 flush 所有缓冲事件。
     *
     * @return SseEmitter，不存在返回 null
     */
    public SseEmitter subscribe(Long auditLogId)
    {
        EmitterEntry entry = entries.get(auditLogId);
        if (entry == null) return null;

        synchronized (entry)
        {
            entry.subscribed = true;
            // flush 缓冲事件
            for (BufferedEvent e : entry.buffer)
            {
                try
                {
                    entry.emitter.send(SseEmitter.event().name(e.name).data(e.data));
                }
                catch (Exception ex)
                {
                    log.warn("flush 缓冲事件失败: auditLogId={}, event={}", auditLogId, e.name, ex);
                }
            }
            entry.buffer.clear();
        }
        return entry.emitter;
    }

    /**
     * 发送事件（ProgressCallback 调用）。
     * <p>
     * 若前端已订阅，直接发送；否则缓冲等待订阅后 flush。
     *
     * @param auditLogId 审计日志 ID
     * @param eventName  事件名（step_started / step_completed / step_blocked / step_pending / plan_completed / plan_interrupted）
     * @param data       事件数据（将转为 JSON）
     */
    public void send(Long auditLogId, String eventName, Object data)
    {
        EmitterEntry entry = entries.get(auditLogId);
        if (entry == null) return;

        String json;
        try
        {
            json = data != null ? JSON.toJSONString(data) : "{}";
        }
        catch (Exception e)
        {
            log.warn("SSE 事件序列化失败: auditLogId={}, event={}", auditLogId, eventName, e);
            return;
        }

        synchronized (entry)
        {
            if (entry.subscribed)
            {
                try
                {
                    entry.emitter.send(SseEmitter.event().name(eventName).data(json));
                }
                catch (Exception ex)
                {
                    log.warn("SSE 发送失败: auditLogId={}, event={}", auditLogId, eventName, ex);
                }
            }
            else
            {
                entry.buffer.add(new BufferedEvent(eventName, json));
            }
        }
    }

    /**
     * 发送心跳事件（保持 SSE 连接活跃，防止代理超时断开）。
     */
    public void sendHeartbeat(Long auditLogId)
    {
        send(auditLogId, "heartbeat", "{}");
    }

    /**
     * 完成并关闭 emitter。
     */
    public void complete(Long auditLogId)
    {
        EmitterEntry entry = entries.get(auditLogId);
        if (entry == null) return;
        try
        {
            entry.emitter.complete();
        }
        catch (Exception e)
        {
            log.debug("SSE complete 异常: auditLogId={}", auditLogId, e);
        }
        entries.remove(auditLogId);
    }

    /**
     * 移除 emitter（计划取消或异常时调用）。
     */
    public void remove(Long auditLogId)
    {
        EmitterEntry entry = entries.remove(auditLogId);
        if (entry != null)
        {
            try { entry.emitter.complete(); } catch (Exception ignored) { }
        }
    }

    /**
     * 检查 emitter 是否存在。
     */
    public boolean exists(Long auditLogId)
    {
        return entries.containsKey(auditLogId);
    }

    // ===== 内部类 =====

    static class EmitterEntry
    {
        final SseEmitter emitter;
        final List<BufferedEvent> buffer = new ArrayList<>();
        volatile boolean subscribed = false;

        EmitterEntry(SseEmitter emitter)
        {
            this.emitter = emitter;
        }
    }

    static class BufferedEvent
    {
        final String name;
        final String data;

        BufferedEvent(String name, String data)
        {
            this.name = name;
            this.data = data;
        }
    }
}
