/**
 * 整机备份/还原 API（U3）
 * <p>
 * 封装后端 /system/note/dataBak（GET 下载备份包 zip）与 /system/note/restore
 * （POST multipart 两步还原）。
 * 与 export.ts/import.ts 同模式：RuoYi 失败语义为 HTTP 200 + {"code":500,"msg":"..."}
 * （全局异常处理），备份下载成功为二进制流，不走 useFetch（JSON 假设 + 50s 超时不足）。
 * 参考实现：notepad/src/api/export.ts（下载 blob 与触发保存）、import.ts（multipart）。
 */
import useCookie from '../hooks/useCookie';
import { API_BASE } from '../config/api';
import { parseFilename, triggerDownload } from './export';

const cookie = useCookie;
const baseUrl = API_BASE;

/** 还原结果（对齐后端 com.ruoyi.system.domain.dto.RestoreResult，U2 契约） */
export interface RestoreResult {
    /** 包内表数量 */
    tableCount: number;
    /** 包内 INSERT 记录总数 */
    recordCount: number;
    /** 包内用户文件数量 */
    fileCount: number;
    /** 包内 sys_user 账号数 */
    userCount: number;
    /** sys_user 凭据/账号差异标记（后端仅输出布尔，不含任何密码值） */
    credentialChanged: boolean;
    /** 是否已执行写入（false=校验预览摘要，true=执行结果） */
    executed: boolean;
    /** 会话判定：包内 sys_user 不含当前用户，需重新登录 */
    reloginRequired: boolean;
}

/**
 * 下载整机备份包（"数据备份"按钮，U3）。
 * 后端现场生成备份包（全库导出 + 用户文件目录打包）后流式返回，大库耗时可达分钟级。
 *
 * @param options.timeoutMs 可选，默认 600000（10 分钟，对齐 import.ts 大操作先例）
 * @throws Error 失败时抛出，message 为后端中文 msg 或网络异常提示
 */
export async function downloadBackup(options?: { timeoutMs?: number }): Promise<void> {
    const token = cookie.get('token');
    const timeoutMs = options?.timeoutMs ?? 600000;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);

    let response: Response;
    try {
        response = await fetch(`${baseUrl}/system/note/dataBak`, {
            method: 'GET',
            headers: {
                'Accept': 'application/zip, application/json',
                'Authorization': token,
            },
            credentials: 'include',
            signal: controller.signal,
        });
    } catch (e: any) {
        if (e?.name === 'AbortError') throw new Error('备份生成超时，请稍后重试');
        throw new Error('网络异常，请稍后重试');
    } finally {
        clearTimeout(timer);
    }

    if (!response.ok) {
        throw new Error(`备份失败：HTTP ${response.status}`);
    }

    const contentType = response.headers.get('content-type') || '';

    // 失败：ServiceException / 限流 → HTTP 200 + application/json
    if (contentType.includes('application/json')) {
        const body = await response.json().catch(() => ({} as any));
        throw new Error(body?.msg || `备份失败（code=${body?.code}）`);
    }

    // 成功：备份包 zip（Content-Disposition 由后端提供，解析失败兜底 backup.zip）
    if (contentType.includes('application/zip') || contentType.includes('octet-stream')) {
        const blob = await response.blob();
        triggerDownload(blob, parseFilename(response.headers.get('content-disposition'), 'backup.zip'));
        return;
    }

    // 兜底：未知 content-type，尝试 JSON 解析（错误），失败则当 zip 二进制
    const text = await response.text();
    try {
        const body = JSON.parse(text);
        throw new Error(body?.msg || `备份失败（code=${body?.code}）`);
    } catch (parseErr: any) {
        // JSON.parse 失败说明不是 JSON，当作 zip 二进制处理
        if (parseErr instanceof Error && parseErr.message && !parseErr.message.startsWith('Unexpected')) {
            throw parseErr;
        }
        const blob = new Blob([text], { type: 'application/zip' });
        triggerDownload(blob, parseFilename(response.headers.get('content-disposition'), 'backup.zip'));
    }
}

/**
 * 上传备份包执行还原两步协议（U3）。
 *
 * @param file 上传的备份包（.zip）
 * @param confirmed false=校验预览步（不写库，返回摘要）；true=执行步
 *   （后端对本次上传文件重跑全部校验，不跨请求信任预览结果）
 * @param options.timeoutMs 可选，默认 600000（预检）；执行步由调用方传更长超时
 * @throws Error 失败时抛出，message 为后端中文 msg 或网络异常提示
 */
export async function restoreUpload(
    file: File,
    confirmed: boolean,
    options?: { timeoutMs?: number },
): Promise<RestoreResult> {
    const token = cookie.get('token');
    const timeoutMs = options?.timeoutMs ?? 600000;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);

    const formData = new FormData();
    formData.append('file', file);
    formData.append('confirmed', `${confirmed}`);

    let response: Response;
    try {
        response = await fetch(`${baseUrl}/system/note/restore`, {
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
        if (e?.name === 'AbortError') {
            // 执行步超时只代表前端放弃等待，服务端可能仍在执行并最终成功（失败亦已自动回滚）；
            // 重复上传会触发二次还原（写锁仅在第一段还原进行中才互斥），文案引导确认结果而非重发
            throw new Error(confirmed
                ? '还原仍在后台执行中，请稍后刷新确认结果；请勿在结果确认前重复上传，以免二次还原'
                : '备份包校验超时，请稍后重试');
        }
        throw new Error('网络异常，请稍后重试');
    } finally {
        clearTimeout(timer);
    }

    if (!response.ok) {
        throw new Error(`还原失败：HTTP ${response.status}`);
    }

    const body = await response.json().catch(() => ({} as any));
    if (body?.code !== 200) {
        throw new Error(body?.msg || `还原失败（code=${body?.code}）`);
    }
    return body?.data as RestoreResult;
}
