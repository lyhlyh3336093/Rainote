package com.ruoyi.system.agent.model;

/**
 * Agent LLM 上下文，封装用户当前所在页面/实体的 ID。
 * <p>
 * 传入 {@code PlanPromptBuilder} 注入 system prompt，使 GLM 知晓用户当前操作上下文。
 * <p>
 * R27 信任边界：仅传 ID（元数据），不传笔记正文/记录正文字段值。
 *
 * @see com.ruoyi.system.agent.llm.PlanPromptBuilder
 */
public class AgentContext
{
    /** 当前笔记 ID（笔记页面上下文） */
    private final Long currentNoteId;
    /** 当前多维表 ID（多维表页面上下文） */
    private final Long currentDwtableId;
    /** 当前列 ID（列编辑上下文，可选） */
    private final Long currentColumnId;
    /** 当前记录 ID（记录编辑上下文，可选） */
    private final Long currentRecordId;

    public AgentContext(Long currentNoteId, Long currentDwtableId,
                        Long currentColumnId, Long currentRecordId)
    {
        this.currentNoteId = currentNoteId;
        this.currentDwtableId = currentDwtableId;
        this.currentColumnId = currentColumnId;
        this.currentRecordId = currentRecordId;
    }

    /** 仅笔记上下文 */
    public static AgentContext ofNote(Long noteId)
    {
        return new AgentContext(noteId, null, null, null);
    }

    /** 仅多维表上下文 */
    public static AgentContext ofDwtable(Long dwtableId)
    {
        return new AgentContext(null, dwtableId, null, null);
    }

    /** 空上下文（全局意图，如"创建一个笔记"） */
    public static AgentContext empty()
    {
        return new AgentContext(null, null, null, null);
    }

    public Long getCurrentNoteId() { return currentNoteId; }
    public Long getCurrentDwtableId() { return currentDwtableId; }
    public Long getCurrentColumnId() { return currentColumnId; }
    public Long getCurrentRecordId() { return currentRecordId; }

    /** 上下文中是否持有任何 ID */
    public boolean hasContext()
    {
        return currentNoteId != null || currentDwtableId != null
                || currentColumnId != null || currentRecordId != null;
    }
}
