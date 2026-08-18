/**
 * Agent API 客户端
 * <p>
 * 封装后端 /agent/* 端点调用。普通请求使用统一 request 函数（原生 fetch），
 * 可完整返回 {code, msg, data} 以便 store 层按错误码（429/403/409）做分支处理。
 * SSE 进度推送使用 fetch + ReadableStream（与 aiChat store 一致），
 * 可设置 Authorization header，无需 event-source-polyfill。
 */
import useCookie from '../hooks/useCookie';

const cookie = useCookie;

const baseUrl = window.location.origin === 'http://www.rainote.cn'
    ? 'http://www.rainote.cn:8089'
    : '/api';

/** 后端统一响应格式 */
export interface ApiResult<T = any> {
    code: number;
    msg: string;
    data?: T;
}

/** Agent 计划类型 */
export type PlanType = 'PLAN' | 'QUERY' | 'CLARIFY' | 'ERROR';

/** Agent 步骤 */
export interface AgentStep {
    stepId: string;
    operationName: string;
    operationType: 'query' | 'create' | 'update' | 'delete';
    params: Record<string, any>;
    bulkGroupId?: string;
    destructive: boolean;
    dependsOn: string[];
}

/** Agent 计划 */
export interface AgentPlan {
    type: PlanType;
    steps: AgentStep[];
    queryResult?: string;
    clarifyingQuestion?: string;
    errorMessage?: string;
    needsSummary: boolean;
}

/** 步骤审计日志 */
export interface AgentAuditStepLog {
    id?: number;
    auditLogId: number;
    stepIndex: number;
    stepId: string;
    operationName: string;
    status: 'executing' | 'success' | 'failed' | 'skipped' | 'blocked' | 'pending_confirm';
    paramsJson?: string;
    errorMessage?: string;
    stepRequestId?: string;
    executedAt?: string;
}

/** 计划审计日志 */
export interface AgentAuditLog {
    id: number;
    userId: number;
    sessionId: string;
    userInput: string;
    planJson: string;
    status: 'executing' | 'completed' | 'interrupted';
    degraded: boolean;
    createdAt: string;
    completedAt?: string;
    stepLogs: AgentAuditStepLog[];
}

/** 速率限制信息 */
export interface RateLimitInfo {
    minuteUsed: number;
    minuteLimit: number;
    dayUsed: number;
    dayLimit: number;
}

/** chat 端点返回数据 */
export interface ChatResult {
    type: PlanType;
    plan: AgentPlan;
    queryResult?: string;
    clarifyingQuestion?: string;
    errorMessage?: string;
    needsSummary: boolean;
    rateLimit: RateLimitInfo;
}

/** confirm 端点返回数据 */
export interface ConfirmResult {
    auditLogId: number;
    stepLogs: AgentAuditStepLog[];
    hasPendingDestructive: boolean;
    isCompleted: boolean;
}

/** 步骤操作返回数据 */
export interface StepActionResult {
    hasPendingDestructive: boolean;
    isCompleted: boolean;
    isInterrupted: boolean;
}

/** SSE 事件 */
export interface SseEvent {
    event: string;
    data: any;
}

/**
 * 当前 Agent 会话 ID。
 * openPanel 成功后由 store 设置，closePanel 时清除。
 * request() 自动将其作为 X-Agent-Session header 发送，确保 acquireLock 与
 * 后续 heartbeat/执行类端点使用同一 sessionId，避免后端 resolveSessionId 随机生成新 UUID。
 */
let agentSessionId: string | null = null;

/** 设置当前 Agent 会话 ID（供 request 自动注入 X-Agent-Session header） */
export function setAgentSessionId(id: string | null) {
    agentSessionId = id;
}

/**
 * 统一请求函数（原生 fetch）。
 * 返回完整 {code, msg, data}，由调用方按 code 分支处理。
 */
async function request<T = any>(method: string, path: string, body?: any): Promise<ApiResult<T>> {
    const token = cookie.get('token');
    const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        'Authorization': token,
    };
    if (agentSessionId) {
        headers['X-Agent-Session'] = agentSessionId;
    }
    const response = await fetch(`${baseUrl}${path}`, {
        method,
        headers,
        body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    return response.json();
}

/** POST /agent/chat — 意图解析与计划生成 */
export function chat(input: string, context: Record<string, any>) {
    return request<ChatResult>('POST', '/agent/chat', { input, context });
}

/** POST /agent/confirm — 计划确认执行 */
export function confirm(plan: AgentPlan, userInput: string, context: Record<string, any>) {
    return request<ConfirmResult>('POST', '/agent/confirm', {
        plan: JSON.stringify(plan),
        userInput,
        context,
    });
}

/** POST /agent/step/{auditLogId}/{stepId}/confirm — 破坏性步骤二次确认 */
export function confirmStep(auditLogId: number, stepId: string) {
    return request<StepActionResult>('POST', `/agent/step/${auditLogId}/${stepId}/confirm`);
}

/** POST /agent/step/{auditLogId}/{stepId}/skip — 跳过步骤 */
export function skipStep(auditLogId: number, stepId: string) {
    return request<StepActionResult>('POST', `/agent/step/${auditLogId}/${stepId}/skip`);
}

/** POST /agent/step/{auditLogId}/{stepId}/retry — 重试步骤 */
export function retryStep(auditLogId: number, stepId: string) {
    return request<StepActionResult>('POST', `/agent/step/${auditLogId}/${stepId}/retry`);
}

/** POST /agent/plan/{auditLogId}/pause — 暂停 */
export function pause(auditLogId: number) {
    return request('POST', `/agent/plan/${auditLogId}/pause`);
}

/** POST /agent/plan/{auditLogId}/resume — 继续 */
export function resume(auditLogId: number) {
    return request('POST', `/agent/plan/${auditLogId}/resume`);
}

/** POST /agent/plan/{auditLogId}/cancel — 取消 */
export function cancel(auditLogId: number) {
    return request('POST', `/agent/plan/${auditLogId}/cancel`);
}

/** GET /agent/audit/recent — 最近 50 条历史 */
export function recentHistory() {
    return request<AgentAuditLog[]>('GET', '/agent/audit/recent');
}

/** GET /agent/audit/{id} — 审计详情 */
export function auditDetail(id: number) {
    return request<AgentAuditLog>('GET', `/agent/audit/${id}`);
}

/** POST /agent/audit/{id}/resume — 路径 A 恢复 */
export function resumeFromAudit(id: number) {
    return request<any>('POST', `/agent/audit/${id}/resume`);
}

/** POST /agent/lock/acquire — 获取锁 */
export function acquireLock() {
    return request<{ sessionId: string; heartbeatInterval: number; lockTimeout: number }>(
        'POST', '/agent/lock/acquire'
    );
}

/** POST /agent/lock/heartbeat — 心跳 */
export function heartbeat() {
    return request('POST', '/agent/lock/heartbeat');
}

/** POST /agent/lock/release — 释放锁 */
export function releaseLock() {
    return request('POST', '/agent/lock/release');
}

/**
 * 订阅 SSE 进度推送（GET /agent/plan/{auditLogId}/stream）。
 * <p>
 * 使用 fetch + ReadableStream（与 aiChat store 一致），可设置 Authorization header。
 * 解析 SSE 协议格式（event: / data: 行），通过回调推送事件。
 *
 * @param auditLogId 审计日志 ID
 * @param onEvent    事件回调
 * @param onError    错误回调
 * @returns AbortController（用于主动断开）
 */
export function subscribeSse(
    auditLogId: number,
    onEvent: (event: SseEvent) => void,
    onError?: (error: Error) => void,
): AbortController {
    const token = cookie.get('token');
    const controller = new AbortController();

    fetch(`${baseUrl}/agent/plan/${auditLogId}/stream`, {
        method: 'GET',
        headers: {
            'Accept': 'text/event-stream',
            'Authorization': token,
        },
        signal: controller.signal,
    })
        .then(response => {
            if (!response.ok || !response.body) {
                throw new Error(`SSE 连接失败: HTTP ${response.status}`);
            }
            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            let buffer = '';
            let currentEvent = '';
            let currentData = '';

            const processLine = (line: string) => {
                if (line.startsWith('event:')) {
                    currentEvent = line.slice(6).trim();
                } else if (line.startsWith('data:')) {
                    currentData += line.slice(5).trim();
                } else if (line === '') {
                    // 空行 = 事件边界
                    if (currentEvent || currentData) {
                        let parsedData: any = currentData;
                        try {
                            parsedData = currentData ? JSON.parse(currentData) : {};
                        } catch {
                            // 非 JSON 数据保持原始字符串
                        }
                        onEvent({ event: currentEvent || 'message', data: parsedData });
                        currentEvent = '';
                        currentData = '';
                    }
                }
            };

            const read = (): Promise<void> => {
                return reader.read().then(({ done, value }) => {
                    if (done) return;
                    buffer += decoder.decode(value, { stream: true });
                    const lines = buffer.split('\n');
                    buffer = lines.pop() || '';
                    lines.forEach(processLine);
                    return read();
                });
            };

            return read();
        })
        .catch(err => {
            if (err?.name === 'AbortError') return;
            onError?.(err);
        });

    return controller;
}
