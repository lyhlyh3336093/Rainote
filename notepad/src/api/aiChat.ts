import { useFetch } from '../hooks';

/**
 * AI聊天 API 客户端
 * 封装 useFetch 调用后端 /ai/* 端点
 * 认证token由 useFetch 拦截器自动注入
 */

/** 查询当前用户的所有会话 */
export function listSessions() {
    return useFetch('/ai/session/list').get().json();
}

/** 创建新会话 */
export function createSession() {
    return useFetch('/ai/session/create').post().json();
}

/** 软删除会话 */
export function deleteSession(id: number) {
    return useFetch(`/ai/session/${id}`).delete().json();
}

/** 查询会话消息 */
export function listMessages(sessionId: number) {
    return useFetch(`/ai/message/list?sessionId=${sessionId}`).get().json();
}

/** 更新会话最后选择时间（Group O — 由 PUT 改为 POST） */
export function selectSession(id: number) {
    return useFetch(`/ai/session/${id}/select`).post().json();
}

/** 更新会话标题（R4a/Group F） */
export function updateTitle(id: number, title: string) {
    return useFetch(`/ai/session/${id}/title`).post({ title }).json();
}
