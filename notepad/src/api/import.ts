/**
 * 多维表格导入 API
 * <p>
 * 封装后端 /system/dwtable/importData multipart 端点。
 * 成功返回 HTTP 200 + {"code":200,"data":{"recordCount":N}}，
 * 失败（ServiceException / 限流 / 解析失败）返回 HTTP 200 + {"code":500,"msg":"..."}
 * （RuoYi 全局异常处理），与导出端一致用原生 fetch。
 * 参考实现：notepad/src/api/export.ts（baseUrl 与 token 模式）。
 */
import useCookie from '../hooks/useCookie';

const cookie = useCookie;
const baseUrl = window.location.origin === 'http://www.rainote.cn'
    ? 'http://www.rainote.cn:8089'
    : '/api';

export interface ImportDwtableParams {
    /** 多维表格归属笔记 id（NoteDwtable.noteId） */
    noteId: string | number;
    /** 目标数据表 id */
    dwtableId: string | number;
    /** 上传的 .sql / .zip 文件 */
    file: File;
}

/**
 * 导入 .sql / .zip 到目标多维表格。
 *
 * @param params noteId / dwtableId / file
 * @param options.timeoutMs 可选，默认 600000（10 分钟——大表导入远超常规请求时长，
 *   过短的超时只会在服务端事务仍在执行时让前端提前放弃）
 * @returns 成功导入的记录数
 * @throws Error 失败时抛出，message 为后端中文 msg 或网络异常提示
 */
export async function importDwtable(
    params: ImportDwtableParams,
    options?: { timeoutMs?: number },
): Promise<number> {
    const { noteId, dwtableId, file } = params;
    const token = cookie.get('token');
    const timeoutMs = options?.timeoutMs ?? 600000;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);

    const formData = new FormData();
    formData.append('noteId', `${noteId}`);
    formData.append('dwtableId', `${dwtableId}`);
    formData.append('file', file);

    let response: Response;
    try {
        response = await fetch(`${baseUrl}/system/dwtable/importData`, {
            method: 'POST',
            // FormData 模式下浏览器自动携带 multipart/form-data boundary，勿手动设置 Content-Type
            headers: {
                'Authorization': token,
            },
            credentials: 'include',
            body: formData,
            signal: controller.signal,
        });
    } catch (e: any) {
        // 导入为纯追加、无幂等键：超时只代表前端放弃等待，服务端事务可能仍在执行并最终成功提交，
        // 此时立即重试会造成整批重复导入——文案必须引导先确认结果而非重发
        if (e?.name === 'AbortError') {
            throw new Error('导入仍在后台执行中，请稍后刷新表格确认结果；请勿立即重新导入，否则可能造成数据重复');
        }
        throw new Error('网络异常，请稍后重试');
    } finally {
        clearTimeout(timer);
    }

    if (!response.ok) {
        throw new Error(`导入失败：HTTP ${response.status}`);
    }

    const body = await response.json().catch(() => ({} as any));
    if (body?.code !== 200) {
        throw new Error(body?.msg || `导入失败（code=${body?.code}）`);
    }
    const recordCount = body?.data?.recordCount;
    return typeof recordCount === 'number' ? recordCount : 0;
}
