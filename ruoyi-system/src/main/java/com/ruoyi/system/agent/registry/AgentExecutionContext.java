package com.ruoyi.system.agent.registry;

import java.util.Collections;
import java.util.Set;

/**
 * Agent 执行上下文，由 Controller 调用线程封装、显式传入 AgentPlanExecutor（非 ThreadLocal）。
 * <p>
 * F3 决策：{@code @Async} 线程丢失 SecurityContext，改为显式传递 context 而非依赖
 * {@code SecurityContextHolder} 的 ThreadLocal。Executor 内所有 userId/权限校验均读取此 context。
 * <p>
 * 不可变对象，线程安全。
 * <p>
 * 除 userId/权限外，还携带规划期 {@link com.ruoyi.system.agent.model.AgentContext} 的页面 ID
 * （currentNoteId/currentDwtableId 等），供执行器在 LLM 未填入这些 ID 时进行框架注入
 * （例如 record.create 的 dwtableId 注入），避免 record 永远收到 null。
 */
public final class AgentExecutionContext
{
    /** 当前登录用户 ID */
    private final Long userId;
    /** 当前用户持有的权限键集合 */
    private final Set<String> permissionKeys;
    /** 当前笔记 ID（执行期页面上下文，可为 null） */
    private final Long currentNoteId;
    /** 当前多维表 ID（执行期页面上下文，可为 null；record.create 注入用） */
    private final Long currentDwtableId;
    /** 当前列 ID（执行期页面上下文，可为 null） */
    private final Long currentColumnId;
    /** 当前记录 ID（执行期页面上下文，可为 null） */
    private final Long currentRecordId;

    public AgentExecutionContext(Long userId, Set<String> permissionKeys)
    {
        this(userId, permissionKeys, null, null, null, null);
    }

    public AgentExecutionContext(Long userId, Set<String> permissionKeys,
                                  Long currentNoteId, Long currentDwtableId,
                                  Long currentColumnId, Long currentRecordId)
    {
        this.userId = userId;
        this.permissionKeys = permissionKeys != null
                ? Collections.unmodifiableSet(permissionKeys)
                : Collections.emptySet();
        this.currentNoteId = currentNoteId;
        this.currentDwtableId = currentDwtableId;
        this.currentColumnId = currentColumnId;
        this.currentRecordId = currentRecordId;
    }

    public Long getUserId() { return userId; }

    public Set<String> getPermissionKeys() { return permissionKeys; }

    public Long getCurrentNoteId() { return currentNoteId; }

    public Long getCurrentDwtableId() { return currentDwtableId; }

    public Long getCurrentColumnId() { return currentColumnId; }

    public Long getCurrentRecordId() { return currentRecordId; }

    /**
     * 校验当前 context 是否持有指定权限键。
     *
     * @param permissionKey 权限键，如 "note:note:delete"
     * @return true 若持有该权限或权限键为空（无权限要求）
     */
    public boolean hasPermission(String permissionKey)
    {
        if (permissionKey == null || permissionKey.isEmpty())
        {
            return true;
        }
        return permissionKeys.contains(permissionKey);
    }
}
