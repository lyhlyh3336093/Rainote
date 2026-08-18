/**
 * Agent Pinia Store
 * <p>
 * 持有 agent 面板全部状态：面板开关、多标签页锁、对话流、计划执行、
 * SSE 进度订阅、心跳、上下文、速率限制、审计历史。
 *
 * 安全约束（与后端 U6 对齐）：
 * - 多标签页锁：后端 JVM 内存锁为权威，前端心跳 5s 续期，超时 15s 可接管
 * - 执行类操作前校验锁持有状态
 * - SSE 订阅前校验审计记录归属（后端 IDOR 防护）
 */
import { defineStore } from 'pinia';
import { message } from 'ant-design-vue';
import useCookie from '../hooks/useCookie';
import * as agentApi from '../api/agent';
import { setAgentSessionId } from '../api/agent';
import type {
    AgentPlan, AgentAuditLog, AgentAuditStepLog,
    ChatResult, ConfirmResult, RateLimitInfo, SseEvent,
} from '../api/agent';

const cookie = useCookie;

/** 对话流条目类型 */
export type EntryType = 'user' | 'plan' | 'query' | 'error' | 'clarify' | 'loading' | 'resume';

/** 对话流条目 */
export interface ChatEntry {
    id: string;
    type: EntryType;
    content: string;
    plan?: AgentPlan;
    queryResult?: string;
    clarifyingQuestion?: string;
    errorMessage?: string;
    clarifyRound?: number;
    retryCount?: number;
    /** 关联的用户原始输入（用于错误重试回填） */
    userInput?: string;
    /** 关联的审计日志 ID（resume 条目用，R20b） */
    auditLogId?: number;
    /** 是否已忽略（resume 条目折叠为历史项，R20b） */
    ignored?: boolean;
}

/** 面板状态 */
type PanelState = 'idle' | 'locked' | 'acquiring' | 'takeover';

/** 历史实体（R7c，localStorage 持久化） */
export interface RecentEntity {
    type: 'note' | 'dwtable' | 'column' | 'record';
    id: number;
    title: string;
    visitedAt: number;
}

let entryIdCounter = 0;
function nextEntryId(): string {
    return `entry-${++entryIdCounter}`;
}

export const useAgentStore = defineStore('agent', {
    state: () => ({
        // ===== 面板与锁 =====
        /** 面板是否展开 */
        panelOpen: false,
        /** 面板状态 */
        panelState: 'idle' as PanelState,
        /** 当前会话 ID（锁标识） */
        sessionId: '' as string,
        /** 心跳定时器 */
        heartbeatTimer: null as ReturnType<typeof setInterval> | null,
        /** 接管倒计时（秒） */
        takeoverCountdown: 0,
        takeoverTimer: null as ReturnType<typeof setInterval> | null,

        // ===== 对话流 =====
        /** 对话流条目列表 */
        entries: [] as ChatEntry[],
        /** 输入框文本 */
        inputText: '',
        /** 是否正在等待 AI 响应 */
        isWaiting: false,
        /** 排队消息（执行期间新消息入队） */
        queuedMessages: [] as string[],

        // ===== 上下文 =====
        /** 当前上下文 */
        context: {
            noteId: null as number | null,
            dwtableId: null as number | null,
            columnId: null as number | null,
            recordId: null as number | null,
        },

        // ===== 计划执行 =====
        /** 当前执行的计划 */
        currentPlan: null as AgentPlan | null,
        /** 当前审计日志 ID */
        auditLogId: null as number | null,
        /** 步骤日志列表 */
        stepLogs: [] as AgentAuditStepLog[],
        /** 是否有待确认的破坏性步骤 */
        hasPendingDestructive: false,
        /** 计划是否已完成 */
        isCompleted: false,
        /** 计划是否被中断 */
        isInterrupted: false,
        /** 是否正在执行 */
        isExecuting: false,

        // ===== SSE =====
        /** SSE 订阅控制器 */
        sseController: null as AbortController | null,
        /** SSE 重连次数 */
        sseRetryCount: 0,
        /** SSE 是否已断连 */
        sseDisconnected: false,

        // ===== 速率限制 =====
        rateLimit: {
            minuteUsed: 0,
            minuteLimit: 10,
            dayUsed: 0,
            dayLimit: 200,
        } as RateLimitInfo,

        // ===== 澄清轮次 =====
        clarifyRound: 0,

        // ===== 审计历史 =====
        history: [] as AgentAuditLog[],
        isLoadingHistory: false,

        // ===== 历史实体选择器（R7c，localStorage 持久化） =====
        /** 最近访问的实体栈（最多 20 条，按访问时间倒序） */
        recentEntities: [] as RecentEntity[],
    }),

    getters: {
        /** 上下文是否为空 */
        hasContext: (state) =>
            state.context.noteId !== null ||
            state.context.dwtableId !== null ||
            state.context.columnId !== null ||
            state.context.recordId !== null,

        /** 速率限制剩余分钟配额 */
        minuteRemaining: (state) =>
            Math.max(0, state.rateLimit.minuteLimit - state.rateLimit.minuteUsed),

        /** 上下文描述文本 */
        contextDescription: (state) => {
            const parts: string[] = [];
            if (state.context.noteId) parts.push(`笔记 #${state.context.noteId}`);
            if (state.context.dwtableId) parts.push(`多维表 #${state.context.dwtableId}`);
            if (state.context.columnId) parts.push(`列 #${state.context.columnId}`);
            if (state.context.recordId) parts.push(`记录 #${state.context.recordId}`);
            return parts.join(' · ') || '无上下文';
        },
    },

    actions: {
        // ==================== 面板与锁 ====================

        /**
         * 切换面板开关。
         * 打开时获取锁，关闭时释放锁。
         */
        async togglePanel() {
            if (this.panelOpen) {
                await this.closePanel();
            } else {
                await this.openPanel();
            }
        },

        /** 打开面板并获取锁 */
        async openPanel() {
            if (this.panelState === 'acquiring' || this.panelState === 'takeover') return;

            this.panelState = 'acquiring';
            try {
                const res = await agentApi.acquireLock();
                if (res.code === 200 && res.data) {
                    this.sessionId = res.data.sessionId;
                    setAgentSessionId(this.sessionId);
                    this.panelOpen = true;
                    this.panelState = 'locked';
                    this.startHeartbeat();
                    // 加载历史实体栈（R7c）
                    this.loadRecentEntities();
                    // 加载历史
                    this.loadHistory();
                    // 检查未完成审计记录（路径 A 恢复）
                    this.checkIncompleteAudit();
                } else if (res.code === 409) {
                    // 被其他标签页持有
                    this.panelState = 'locked';
                    this.panelOpen = true;
                    message.warning('Agent 已在另一标签页打开，可点击"在此标签页接管"');
                } else {
                    this.panelState = 'idle';
                    message.error(res.msg || '打开 Agent 失败');
                }
            } catch {
                this.panelState = 'idle';
                message.error('网络异常，无法打开 Agent');
            }
        },

        /** 关闭面板并释放锁 */
        async closePanel() {
            this.stopHeartbeat();
            this.unsubscribeSse();
            if (this.sessionId) {
                try {
                    await agentApi.releaseLock();
                } catch {
                    // 忽略释放失败
                }
            }
            this.panelOpen = false;
            this.panelState = 'idle';
            this.sessionId = '';
            setAgentSessionId(null);
        },

        /** 启动心跳（5s 间隔） */
        startHeartbeat() {
            this.stopHeartbeat();
            this.heartbeatTimer = setInterval(async () => {
                try {
                    const res = await agentApi.heartbeat();
                    if (res.code !== 200) {
                        // 锁失效
                        this.stopHeartbeat();
                        this.panelState = 'idle';
                        this.panelOpen = false;
                        message.warning('Agent 锁已失效，请重新打开');
                    }
                } catch {
                    // 网络异常不立即断开，下次心跳重试
                }
            }, 5000);
        },

        /** 停止心跳 */
        stopHeartbeat() {
            if (this.heartbeatTimer) {
                clearInterval(this.heartbeatTimer);
                this.heartbeatTimer = null;
            }
        },

        /**
         * 发起接管（15s 超时等待）。
         * 前端检测到锁被其他标签页持有时，用户可主动接管。
         */
        async startTakeover() {
            this.panelState = 'takeover';
            this.takeoverCountdown = 15;
            this.takeoverTimer = setInterval(() => {
                this.takeoverCountdown--;
                if (this.takeoverCountdown <= 0) {
                    this.cancelTakeover();
                    // 超时后再次尝试获取锁
                    this.openPanel();
                }
            }, 1000);
            // 立即尝试获取锁
            await this.tryAcquireForTakeover();
        },

        async tryAcquireForTakeover() {
            const res = await agentApi.acquireLock();
            if (res.code === 200 && res.data) {
                this.cancelTakeover();
                this.sessionId = res.data.sessionId;
                setAgentSessionId(this.sessionId);
                this.panelState = 'locked';
                this.startHeartbeat();
                this.loadHistory();
            }
        },

        /** 取消接管 */
        cancelTakeover() {
            if (this.takeoverTimer) {
                clearInterval(this.takeoverTimer);
                this.takeoverTimer = null;
            }
            this.takeoverCountdown = 0;
            if (this.panelState === 'takeover') {
                this.panelState = 'locked';
            }
        },

        // ==================== 对话流 ====================

        /**
         * 发送消息（调用 /agent/chat）。
         * 执行期间消息入队，完成后自动处理。
         */
        async sendMessage(text?: string) {
            const input = (text ?? this.inputText).trim();
            if (!input || this.isWaiting) return;

            // 执行期间消息入队
            if (this.isExecuting) {
                this.queuedMessages.push(input);
                message.info(`已排队：${this.queuedMessages.length} 条`);
                return;
            }

            this.inputText = '';

            // 添加用户消息条目
            this.entries.push({
                id: nextEntryId(),
                type: 'user',
                content: input,
                userInput: input,
            });

            // 添加 loading 条目
            const loadingEntry: ChatEntry = {
                id: nextEntryId(),
                type: 'loading',
                content: 'AI 思考中…',
                retryCount: 0,
            };
            this.entries.push(loadingEntry);
            this.isWaiting = true;

            try {
                const res = await agentApi.chat(input, this.context);
                if (res.code === 429) {
                    // 速率限制
                    this.removeEntry(loadingEntry.id);
                    this.entries.push({
                        id: nextEntryId(),
                        type: 'error',
                        content: res.msg || '请求过于频繁，请稍后再试',
                        userInput: input,
                    });
                    if (res.data?.rateLimit) {
                        this.rateLimit = res.data.rateLimit;
                    }
                } else if (res.code === 200 && res.data) {
                    this.removeEntry(loadingEntry.id);
                    this.handleChatResult(res.data, input);
                    if (res.data.rateLimit) {
                        this.rateLimit = res.data.rateLimit;
                    }
                } else {
                    this.removeEntry(loadingEntry.id);
                    this.entries.push({
                        id: nextEntryId(),
                        type: 'error',
                        content: res.msg || 'AI 处理失败',
                        userInput: input,
                    });
                }
            } catch {
                this.removeEntry(loadingEntry.id);
                this.entries.push({
                    id: nextEntryId(),
                    type: 'error',
                    content: '网络异常，请稍后重试',
                    userInput: input,
                });
            } finally {
                this.isWaiting = false;
            }
        },

        /** 处理 chat 返回结果 */
        handleChatResult(result: ChatResult, userInput: string) {
            switch (result.type) {
                case 'PLAN':
                    this.currentPlan = result.plan;
                    this.entries.push({
                        id: nextEntryId(),
                        type: 'plan',
                        content: '已生成操作计划',
                        plan: result.plan,
                        userInput,
                    });
                    break;
                case 'QUERY':
                    this.entries.push({
                        id: nextEntryId(),
                        type: 'query',
                        content: result.queryResult || '查询完成',
                        queryResult: result.queryResult,
                    });
                    break;
                case 'CLARIFY':
                    this.clarifyRound++;
                    this.entries.push({
                        id: nextEntryId(),
                        type: 'clarify',
                        content: result.clarifyingQuestion || '请补充更多信息',
                        clarifyingQuestion: result.clarifyingQuestion,
                        clarifyRound: this.clarifyRound,
                    });
                    break;
                case 'ERROR':
                    this.entries.push({
                        id: nextEntryId(),
                        type: 'error',
                        content: result.errorMessage || 'AI 处理失败',
                        userInput,
                    });
                    break;
            }
        },

        /** 重试上一次失败的消息 */
        retryLastMessage(entry: ChatEntry) {
            if (entry.userInput) {
                this.sendMessage(entry.userInput);
            }
        },

        /** 从队列中移除指定消息（R17a） */
        removeQueuedMessage(index: number) {
            this.queuedMessages.splice(index, 1);
        },

        /** 编辑排队消息（回填输入框，从队列移除，R17a） */
        editQueuedMessage(index: number) {
            const msg = this.queuedMessages.splice(index, 1)[0];
            if (msg) this.inputText = msg;
        },

        /** 移除条目 */
        removeEntry(id: string) {
            this.entries = this.entries.filter(e => e.id !== id);
        },

        // ==================== 计划执行 ====================

        /**
         * 删除计划中的步骤（R14）。
         * 破坏性步骤需要二次确认（由组件层处理），此处直接删除。
         */
        deleteStep(stepId: string) {
            if (!this.currentPlan) return;
            this.currentPlan.steps = this.currentPlan.steps.filter(s => s.stepId !== stepId);
            // 同步对话流中的 plan 条目
            this.syncPlanToEntries();
        },

        /**
         * 调整步骤顺序（R14）。
         * @param stepId 要移动的步骤 ID
         * @param direction 'up' | 'down'
         */
        moveStep(stepId: string, direction: 'up' | 'down') {
            if (!this.currentPlan) return;
            const steps = this.currentPlan.steps;
            const idx = steps.findIndex(s => s.stepId === stepId);
            if (idx < 0) return;
            const targetIdx = direction === 'up' ? idx - 1 : idx + 1;
            if (targetIdx < 0 || targetIdx >= steps.length) return;
            // 交换
            [steps[idx], steps[targetIdx]] = [steps[targetIdx], steps[idx]];
            this.currentPlan.steps = [...steps];
            this.syncPlanToEntries();
        },

        /**
         * 更新步骤参数（R14 行内编辑）。
         */
        updateStepParam(stepId: string, paramKey: string, value: any) {
            if (!this.currentPlan) return;
            const step = this.currentPlan.steps.find(s => s.stepId === stepId);
            if (!step) return;
            step.params = { ...step.params, [paramKey]: value };
            this.syncPlanToEntries();
        },

        /**
         * 校验步骤参数（R14 实时校验）。
         * 返回违规字段列表，空数组表示通过。
         */
        validateStep(stepId: string): string[] {
            if (!this.currentPlan) return [];
            const step = this.currentPlan.steps.find(s => s.stepId === stepId);
            if (!step) return [];
            const violations: string[] = [];
            for (const [key, val] of Object.entries(step.params || {})) {
                if (val === null || val === undefined || val === '') {
                    violations.push(`${key} 不能为空`);
                }
            }
            return violations;
        },

        /** 同步 currentPlan 到对话流中的 plan 条目 */
        syncPlanToEntries() {
            const planEntry = this.entries.find(e => e.type === 'plan' && e.plan);
            if (planEntry && this.currentPlan) {
                planEntry.plan = { ...this.currentPlan };
            }
        },

        /** 确认执行计划 */
        async confirmPlan() {
            if (!this.currentPlan || !this.currentPlan.steps?.length) return;

            const userInput = this.entries
                .filter(e => e.type === 'user')
                .pop()?.userInput || '';

            this.isExecuting = true;
            try {
                const res = await agentApi.confirm(this.currentPlan, userInput, this.context);
                if (res.code === 409) {
                    message.error('未持有 Agent 执行锁，请重新打开面板');
                    this.isExecuting = false;
                    return;
                }
                if (res.code === 200 && res.data) {
                    const data: ConfirmResult = res.data;
                    this.auditLogId = data.auditLogId;
                    this.stepLogs = data.stepLogs || [];
                    this.hasPendingDestructive = data.hasPendingDestructive;
                    this.isCompleted = data.isCompleted;

                    // 订阅 SSE
                    this.subscribeProgress(data.auditLogId);

                    if (data.isCompleted) {
                        this.onPlanCompleted();
                    }
                } else {
                    message.error(res.msg || '计划执行失败');
                }
            } catch {
                message.error('网络异常，计划执行失败');
            } finally {
                if (!this.hasPendingDestructive && !this.isCompleted) {
                    this.isExecuting = false;
                }
            }
        },

        /** 确认破坏性步骤 */
        async confirmDestructiveStep(stepId: string) {
            if (!this.auditLogId) return;
            const res = await agentApi.confirmStep(this.auditLogId, stepId);
            if (res.code === 200 && res.data) {
                this.hasPendingDestructive = res.data.hasPendingDestructive;
                this.isCompleted = res.data.isCompleted;
                this.isInterrupted = res.data.isInterrupted;
                if (res.data.isCompleted) this.onPlanCompleted();
            } else {
                message.error(res.msg || '步骤确认失败');
            }
        },

        /** 跳过步骤 */
        async skipDestructiveStep(stepId: string) {
            if (!this.auditLogId) return;
            const res = await agentApi.skipStep(this.auditLogId, stepId);
            if (res.code === 200 && res.data) {
                this.hasPendingDestructive = res.data.hasPendingDestructive;
                this.isCompleted = res.data.isCompleted;
                this.isInterrupted = res.data.isInterrupted;
                if (res.data.isCompleted) this.onPlanCompleted();
            }
        },

        /** 重试步骤 */
        async retryFailedStep(stepId: string) {
            if (!this.auditLogId) return;
            const res = await agentApi.retryStep(this.auditLogId, stepId);
            if (res.code === 200 && res.data) {
                this.hasPendingDestructive = res.data.hasPendingDestructive;
                this.isCompleted = res.data.isCompleted;
                this.isInterrupted = res.data.isInterrupted;
                if (res.data.isCompleted) this.onPlanCompleted();
            }
        },

        /** 暂停计划 */
        async pausePlan() {
            if (!this.auditLogId) return;
            await agentApi.pause(this.auditLogId);
        },

        /** 继续计划 */
        async resumePlan() {
            if (!this.auditLogId) return;
            await agentApi.resume(this.auditLogId);
        },

        /** 取消计划 */
        async cancelPlan() {
            if (!this.auditLogId) return;
            const res = await agentApi.cancel(this.auditLogId);
            if (res.code === 200) {
                this.unsubscribeSse();
                this.resetExecution();
                message.success('计划已取消');
            }
        },

        /** 计划完成处理 */
        onPlanCompleted() {
            this.isExecuting = false;
            this.isCompleted = true;
            this.unsubscribeSse();

            // 处理排队消息
            if (this.queuedMessages.length > 0) {
                const next = this.queuedMessages.shift()!;
                this.sendMessage(next);
            }
        },

        /** 重置执行状态 */
        resetExecution() {
            this.currentPlan = null;
            this.auditLogId = null;
            this.stepLogs = [];
            this.hasPendingDestructive = false;
            this.isCompleted = false;
            this.isInterrupted = false;
            this.isExecuting = false;
        },

        // ==================== SSE 进度 ====================

        /** 订阅 SSE 进度推送 */
        subscribeProgress(auditLogId: number) {
            this.unsubscribeSse();
            this.sseDisconnected = false;
            this.sseRetryCount = 0;

            this.sseController = agentApi.subscribeSse(
                auditLogId,
                (event: SseEvent) => this.handleSseEvent(event),
                (error: Error) => this.handleSseError(error),
            );
        },

        /** 处理 SSE 事件 */
        handleSseEvent(event: SseEvent) {
            this.sseDisconnected = false;
            this.sseRetryCount = 0;

            switch (event.event) {
                case 'step_started':
                case 'step_completed':
                case 'step_blocked':
                case 'step_pending':
                    // 更新步骤状态（通过 audit/详情接口全量同步，或增量更新）
                    this.syncStepLogs();
                    if (event.event === 'step_pending') {
                        this.hasPendingDestructive = true;
                    }
                    break;
                case 'plan_completed':
                    this.isCompleted = true;
                    this.isExecuting = false;
                    this.syncStepLogs();
                    this.onPlanCompleted();
                    break;
                case 'plan_interrupted':
                    this.isInterrupted = true;
                    this.isExecuting = false;
                    this.syncStepLogs();
                    break;
                case 'plan_paused':
                case 'plan_resumed':
                    // 状态通知，无需特殊处理
                    break;
                case 'error':
                    message.error(event.data?.message || '执行错误');
                    break;
            }
        },

        /** SSE 断连处理（自动重连 3 次，指数退避） */
        handleSseError(_error: Error) {
            this.sseDisconnected = true;
            if (this.sseRetryCount >= 3) {
                // 重连失败，切换轮询兜底
                message.warning('SSE 连接断开，切换轮询模式');
                this.startPollingFallback();
                return;
            }
            this.sseRetryCount++;
            const delay = Math.pow(2, this.sseRetryCount - 1) * 1000; // 1s, 2s, 4s
            setTimeout(() => {
                if (this.auditLogId && !this.isCompleted) {
                    this.subscribeProgress(this.auditLogId);
                }
            }, delay);
        },

        /** 轮询兜底（SSE 断连后每 2s 同步） */
        startPollingFallback() {
            const poll = setInterval(async () => {
                if (!this.auditLogId || this.isCompleted) {
                    clearInterval(poll);
                    return;
                }
                await this.syncStepLogs();
            }, 2000);
        },

        /** 同步步骤日志（从后端全量拉取） */
        async syncStepLogs() {
            if (!this.auditLogId) return;
            try {
                const res = await agentApi.auditDetail(this.auditLogId);
                if (res.code === 200 && res.data) {
                    this.stepLogs = res.data.stepLogs || [];
                    if (res.data.status === 'completed') {
                        this.isCompleted = true;
                        this.isExecuting = false;
                    } else if (res.data.status === 'interrupted') {
                        this.isInterrupted = true;
                        this.isExecuting = false;
                    }
                }
            } catch {
                // 忽略同步失败
            }
        },

        /** 取消 SSE 订阅 */
        unsubscribeSse() {
            if (this.sseController) {
                this.sseController.abort();
                this.sseController = null;
            }
        },

        // ==================== 审计历史 ====================

        /** 加载最近历史 */
        async loadHistory() {
            this.isLoadingHistory = true;
            try {
                const res = await agentApi.recentHistory();
                if (res.code === 200 && res.data) {
                    this.history = res.data;
                }
            } catch {
                // 忽略
            } finally {
                this.isLoadingHistory = false;
            }
        },

        /**
         * 检查未完成审计记录（R20b 刷新恢复）。
         * 页面加载时调用，在对话流顶部插入持久"未完成计划恢复卡片"。
         */
        async checkIncompleteAudit() {
            await this.loadHistory();
            const incomplete = this.history.find(h => h.status === 'executing');
            if (incomplete) {
                // 解析原计划
                let plan: AgentPlan | undefined;
                try {
                    if (incomplete.planJson) plan = JSON.parse(incomplete.planJson);
                } catch { /* ignore parse error */ }

                // 在对话流顶部插入恢复卡片
                this.entries.unshift({
                    id: nextEntryId(),
                    type: 'resume',
                    content: incomplete.userInput || '未完成的计划',
                    plan,
                    auditLogId: incomplete.id,
                    ignored: false,
                });
                // 预存审计日志 ID 和步骤日志
                this.auditLogId = incomplete.id;
                this.stepLogs = incomplete.stepLogs || [];
            }
        },

        /** 忽略恢复卡片（R20b：折叠为历史项） */
        ignoreResumeEntry(entryId: string) {
            const entry = this.entries.find(e => e.id === entryId);
            if (entry) {
                entry.ignored = true;
            }
        },

        /** 恢复未完成计划（R20b） */
        async resumeIncompletePlan(auditLogId: number) {
            await this.resumeFromAudit(auditLogId);
            // 移除恢复卡片
            this.entries = this.entries.filter(e => !(e.type === 'resume' && e.auditLogId === auditLogId));
        },

        /** 从审计记录恢复执行 */
        async resumeFromAudit(id: number) {
            const res = await agentApi.resumeFromAudit(id);
            if (res.code === 200 && res.data) {
                this.auditLogId = id;
                this.currentPlan = res.data.plan;
                this.stepLogs = res.data.stepLogs || [];
                this.subscribeProgress(id);
                this.isExecuting = true;
                message.success('计划已恢复');
            } else if (res.code === 409) {
                message.error('未持有 Agent 执行锁，请重新打开面板');
            } else {
                message.error(res.msg || '恢复失败');
            }
        },

        // ==================== 上下文 ====================

        /** 设置上下文 */
        setContext(ctx: Partial<typeof this.context>) {
            Object.assign(this.context, ctx);
        },

        /** 清空上下文 */
        clearContext() {
            this.context = {
                noteId: null,
                dwtableId: null,
                columnId: null,
                recordId: null,
            };
        },

        /**
         * 手动设置上下文（R7a 上下文编辑器）。
         * 与 setContext 的区别：手动设置的上下文会触发实体校验。
         */
        setContextManual(ctx: Partial<typeof this.context>) {
            Object.assign(this.context, ctx);
        },

        // ==================== 历史实体选择器（R7c） ====================

        /** 从 localStorage 加载历史实体 */
        loadRecentEntities() {
            try {
                const stored = localStorage.getItem('agent-recent-entities');
                if (stored) {
                    this.recentEntities = JSON.parse(stored);
                }
            } catch {
                this.recentEntities = [];
            }
        },

        /**
         * 推送最近访问的实体到栈顶（去重，最多 20 条）。
         * 用户打开笔记/多维表/记录时自动调用。
         */
        pushRecentEntity(entity: RecentEntity) {
            // 去重（同 type+id）
            this.recentEntities = this.recentEntities.filter(
                e => !(e.type === entity.type && e.id === entity.id),
            );
            // 头部插入
            this.recentEntities.unshift(entity);
            // 最多 20 条
            if (this.recentEntities.length > 20) {
                this.recentEntities = this.recentEntities.slice(0, 20);
            }
            // 持久化
            try {
                localStorage.setItem('agent-recent-entities', JSON.stringify(this.recentEntities));
            } catch {
                // localStorage 满或禁用，忽略
            }
        },

        /** 从历史实体栈中移除指定实体 */
        removeRecentEntity(type: string, id: number) {
            this.recentEntities = this.recentEntities.filter(
                e => !(e.type === type && e.id === id),
            );
            try {
                localStorage.setItem('agent-recent-entities', JSON.stringify(this.recentEntities));
            } catch { /* ignore */ }
        },

        /** 选择历史实体作为上下文 */
        selectRecentEntity(entity: RecentEntity) {
            switch (entity.type) {
                case 'note':
                    this.setContextManual({ noteId: entity.id });
                    break;
                case 'dwtable':
                    this.setContextManual({ dwtableId: entity.id });
                    break;
                case 'column':
                    this.setContextManual({ columnId: entity.id });
                    break;
                case 'record':
                    this.setContextManual({ recordId: entity.id });
                    break;
            }
        },

        /** 重置整个 store（面板关闭时） */
        reset() {
            this.entries = [];
            this.inputText = '';
            this.isWaiting = false;
            this.queuedMessages = [];
            this.clarifyRound = 0;
            this.resetExecution();
            this.unsubscribeSse();
        },
    },
});
