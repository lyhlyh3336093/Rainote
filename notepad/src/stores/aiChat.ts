import { defineStore } from 'pinia';
import { message } from 'ant-design-vue';
import { useCookie } from '../hooks';
import {
    listSessions as fetchListSessions,
    createSession as fetchCreateSession,
    deleteSession as fetchDeleteSession,
    listMessages as fetchListMessages,
    selectSession as fetchSelectSession,
    updateTitle as fetchUpdateTitle,
} from '../api/aiChat';

/** 会话类型 */
export interface Session {
    id: number;
    userId: number;
    title: string | null;
    lastSelectedAt: string;
    delFlag: number;
    createTime: string;
}

/** 消息类型 */
export interface ChatMessage {
    id?: number;
    sessionId: number;
    role: 'user' | 'assistant';
    content: string;
    createTime?: string;
}

/**
 * AI聊天 Pinia Store
 * 持有所有AI聊天状态：会话列表、当前会话、消息列表、流式状态
 */
export const useAiChatStore = defineStore('aiChat', {
    state: () => ({
        /** 会话列表 */
        sessions: [] as Session[],
        /** 当前选中会话ID */
        activeSessionId: null as number | null,
        /** 当前会话的消息列表 */
        messages: [] as ChatMessage[],
        /** 是否正在流式接收AI回复 */
        isStreaming: false,
        /** 会话列表加载中 */
        isLoadingSessions: false,
        /** 消息列表加载中 */
        isLoadingMessages: false,
        /** 当前 SSE 请求的 AbortController（R5a/Group D1 — 停止按钮 & R5b/Group D2 — 取消流） */
        currentAbortController: null as AbortController | null,
    }),
    actions: {
        /** 加载会话列表 */
        async loadSessions() {
            this.isLoadingSessions = true;
            try {
                const { data } = await fetchListSessions();
                if (data.value?.code === 200) {
                    this.sessions = data.value.data || [];
                }
            } finally {
                this.isLoadingSessions = false;
            }
        },

        /** 加载指定会话的消息 */
        async loadMessages(sessionId: number) {
            this.isLoadingMessages = true;
            try {
                const { data } = await fetchListMessages(sessionId);
                if (data.value?.code === 200) {
                    this.messages = data.value.data || [];
                }
            } finally {
                this.isLoadingMessages = false;
            }
        },

        /** 创建新会话 */
        async createSession() {
            const { data } = await fetchCreateSession();
            if (data.value?.code === 200) {
                const session = data.value.data;
                this.sessions.unshift(session);
                this.activeSessionId = session.id;
                this.messages = [];
            }
        },

        /**
         * 选择会话（更新lastSelectedAt并加载消息）
         * R5b/Group D2 — 切换会话前取消正在进行的 SSE 流
         */
        async selectSession(id: number) {
            if (this.isStreaming && this.activeSessionId !== id) {
                this.abortStream();
            }
            this.activeSessionId = id;
            await fetchSelectSession(id);
            await this.loadMessages(id);
        },

        /**
         * 删除会话
         * R5b/Group D2 — 删除当前流式会话前取消 SSE
         */
        async deleteSession(id: number) {
            if (this.isStreaming && this.activeSessionId === id) {
                this.abortStream();
            }
            const { data } = await fetchDeleteSession(id);
            if (data.value?.code === 200) {
                this.sessions = this.sessions.filter(s => s.id !== id);
                if (this.activeSessionId === id) {
                    this.activeSessionId = null;
                    this.messages = [];
                }
            }
        },

        /**
         * 更新会话标题（R4a/Group F）
         */
        async updateTitle(id: number, title: string) {
            const { data } = await fetchUpdateTitle(id, title);
            if (data.value?.code === 200) {
                const session = this.sessions.find(s => s.id === id);
                if (session) {
                    session.title = title.trim();
                }
            }
        },

        /**
         * 中断当前 SSE 流（R5a/Group D1 停止按钮 / R5b/Group D2 关闭弹框/切换会话）
         * AbortController.abort() 会触发 fetch promise 的 AbortError，
         * sendMessage 的 catch 分支据此清理状态（不弹错误提示）。
         */
        abortStream() {
            if (this.currentAbortController) {
                this.currentAbortController.abort();
                this.currentAbortController = null;
            }
            this.isStreaming = false;
        },

        /**
         * 发送消息（SSE流式）
         * 使用原生fetch + ReadableStream读取SSE响应
         * R5a/Group D1 — 通过 AbortController 支持停止生成
         */
        async sendMessage(content: string) {
            if (!this.activeSessionId || this.isStreaming) return;

            // 添加用户消息到本地状态
            this.messages.push({
                sessionId: this.activeSessionId,
                role: 'user',
                content,
            });

            // 添加空的助手消息用于流式填充
            const assistantMsg: ChatMessage = {
                sessionId: this.activeSessionId,
                role: 'assistant',
                content: '',
            };
            this.messages.push(assistantMsg);

            this.isStreaming = true;

            // 创建 AbortController 用于停止生成（R5a/Group D1）
            const abortController = new AbortController();
            this.currentAbortController = abortController;

            // 获取认证token和baseUrl（与useFetch拦截器相同的逻辑）
            const cookie = useCookie;
            const token = cookie.get('token');
            const baseUrl = window.location.origin === 'http://www.rainote.cn'
                ? 'http://www.rainote.cn:8089'
                : '/api';

            try {
                const response = await fetch(`${baseUrl}/ai/chat`, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'Authorization': token,
                    },
                    body: JSON.stringify({
                        sessionId: this.activeSessionId,
                        message: content,
                    }),
                    signal: abortController.signal,
                });

                if (!response.ok) {
                    throw new Error(`HTTP ${response.status}`);
                }

                const reader = response.body!.getReader();
                const decoder = new TextDecoder();
                let buffer = '';

                while (true) {
                    const { done, value } = await reader.read();
                    if (done) break;

                    buffer += decoder.decode(value, { stream: true });
                    const lines = buffer.split('\n');
                    buffer = lines.pop() || '';

                    for (const line of lines) {
                        if (!line.startsWith('data:')) {
                            continue;
                        }
                        const data = line.slice(5);
                        if (data === '[DONE]') {
                            this.isStreaming = false;
                            this.currentAbortController = null;
                            return;
                        }
                        if (data.startsWith('[ERROR]')) {
                            throw new Error(data);
                        }
                        // 追加到助手消息内容
                        assistantMsg.content += data;
                    }
                }
            } catch (e: any) {
                // R5a/Group D1 — 用户主动停止，不弹错误提示
                if (e?.name === 'AbortError') {
                    // 部分回复在 v1 不持久化（D3 deferred），此处仅清理 UI 状态
                    if (!assistantMsg.content) {
                        this.messages = this.messages.filter(m => m !== assistantMsg);
                    }
                } else {
                    console.error('SSE stream error:', e);
                    message.error('AI 服务暂时不可用，请稍后重试');
                    // 如果助手消息为空，移除它
                    if (!assistantMsg.content) {
                        this.messages = this.messages.filter(m => m !== assistantMsg);
                    }
                }
            } finally {
                this.isStreaming = false;
                this.currentAbortController = null;
            }
        },
    },
});
