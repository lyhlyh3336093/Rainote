import { useEventBus } from '@vueuse/core'

export const Bus = useEventBus<string>('eventbus');

export { jumpToTableCell } from './jumpToTableCell';
export type { JumpToTableCellPayload, LocatePayload } from './jumpToTableCell';
// 安全解析 JSON，处理末尾逗号等非标准格式
export const safeParseJson = (value: any): any => {
    if (typeof value !== 'string') return value;
    // 去除末尾逗号，确保标准 JSON 格式
    const cleaned = value.replace(/,\s*([}\]])/g, '$1');
    try {
        return JSON.parse(cleaned);
    } catch {
        console.warn('JSON 解析失败:', value);
        return {};
    }
};