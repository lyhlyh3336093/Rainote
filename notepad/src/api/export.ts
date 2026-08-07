/**
 * 多维表格导出 API
 * <p>
 * 封装后端 /system/dwtable/exportData 端点调用。
 * 后端成功返回 application/zip blob，失败（ServiceException / 限流）返回
 * HTTP 200 + application/json body {"code":500,"msg":"..."}（RuoYi 全局异常处理），
 * 因此 useFetch（假设 JSON 响应）不适合，改用原生 fetch + Content-Type 检测。
 * 参考实现：notepad/src/api/agent.ts（baseUrl 与 token 模式）。
 */
import useCookie from '../hooks/useCookie';

const cookie = useCookie;
const baseUrl = window.location.origin === 'http://www.rainote.cn'
    ? 'http://www.rainote.cn:8089'
    : '/api';

export type ExportFormat = 'excel' | 'sql';

/**
 * 触发多维表格导出并下载 zip。
 *
 * @param noteId 多维表格归属笔记 id（NoteDwtable.noteId）
 * @param format 'excel' | 'sql'
 * @param options.timeoutMs 可选，默认 120000（2 分钟）
 * @throws Error 失败时抛出，message 为后端中文 msg 或网络异常提示
 */
export async function exportDwtable(
    noteId: string | number,
    format: ExportFormat,
    options?: { timeoutMs?: number },
): Promise<void> {
    const token = cookie.get('token');
    const timeoutMs = options?.timeoutMs ?? 120000;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);

    let response: Response;
    try {
        response = await fetch(
            `${baseUrl}/system/dwtable/exportData?noteId=${encodeURIComponent(noteId)}&format=${encodeURIComponent(format)}`,
            {
                method: 'POST',
                headers: {
                    'Accept': 'application/zip, application/json',
                    'Authorization': token,
                },
                credentials: 'include',
                signal: controller.signal,
            },
        );
    } catch (e: any) {
        if (e?.name === 'AbortError') throw new Error('导出超时，请稍后重试');
        throw new Error('网络异常，请稍后重试');
    } finally {
        clearTimeout(timer);
    }

    if (!response.ok) {
        throw new Error(`导出失败：HTTP ${response.status}`);
    }

    const contentType = response.headers.get('content-type') || '';

    // 失败：ServiceException / 限流 → HTTP 200 + application/json
    if (contentType.includes('application/json')) {
        const body = await response.json().catch(() => ({} as any));
        throw new Error(body?.msg || `导出失败（code=${body?.code}）`);
    }

    // 成功：application/zip
    if (contentType.includes('application/zip') || contentType.includes('octet-stream')) {
        const blob = await response.blob();
        const filename = parseFilename(response.headers.get('content-disposition'));
        triggerDownload(blob, filename);
        return;
    }

    // 兜底：未知 content-type，尝试 JSON 解析（错误），失败则当 zip 二进制
    const text = await response.text();
    try {
        const body = JSON.parse(text);
        throw new Error(body?.msg || `导出失败（code=${body?.code}）`);
    } catch (parseErr: any) {
        // JSON.parse 失败说明不是 JSON，当作 zip 二进制处理
        if (parseErr instanceof Error && parseErr.message && !parseErr.message.startsWith('Unexpected')) {
            throw parseErr;
        }
        const blob = new Blob([text], { type: 'application/zip' });
        triggerDownload(blob, parseFilename(response.headers.get('content-disposition')));
    }
}

/**
 * 从 Content-Disposition 解析文件名，失败兜底 'multitable-export.zip'。
 * 后端固定返回 attachment; filename="multitable-export.zip"，无需 URL decode。
 */
function parseFilename(contentDisposition: string | null): string {
    if (contentDisposition) {
        const m = contentDisposition.match(/filename="?([^";]+)"?/i);
        if (m && m[1]) return m[1];
    }
    return 'multitable-export.zip';
}

/**
 * 触发浏览器下载 blob（无 file-saver 依赖，手动实现）。
 * Firefox 必须将 <a> 挂载到 DOM 才能 click()，下一帧释放 blobUrl 避免内存泄漏。
 */
function triggerDownload(blob: Blob, filename: string): void {
    const blobUrl = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = blobUrl;
    a.download = filename;
    a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(blobUrl), 0);
}
