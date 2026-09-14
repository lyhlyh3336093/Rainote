/**
 * 多维表格导入 API
 * <p>
 * 封装后端 /system/dwtable/importData、/system/dwtable/precheckExcelImport、
 * /system/dwtable/importExcelData multipart 端点。
 * 成功返回 HTTP 200 + {"code":200,"data":{...}}，
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

/** Excel 导入两阶段共用参数（预检与导入同一文件，KTD5 文件同一性） */
export interface ExcelImportRequestParams {
    /** 多维表格归属笔记 id（NoteDwtable.noteId） */
    noteId: string | number;
    /** 目标数据表 id */
    dwtableId: string | number;
    /** 上传的 .xlsx 文件 */
    file: File;
}

/** 阶段二补参 JSON 的文件指纹（预检响应回传，KTD5 文件同一性比对） */
export interface ExcelFileFingerprint {
    /** 文件字节数 */
    size: number;
    /** 文件内容 MD5（32 位小写十六进制） */
    md5: string;
}

/** 关联列未命中名（名字 + 出现行号集，KTD7 选择器分组口径） */
export interface ExcelMissName {
    /** 未命中的记录名（单元格文本整串或逗号拆分后的名字） */
    name: string;
    /** 该名字出现的行号集（Excel 行号） */
    rowNumbers: number[] | null;
}

/** 缺参项（一个列一条，kind 区分缺列/缺值/类型违规/关联缺列/关联未命中） */
export interface ExcelMissingParam {
    /** 列 id */
    columnId: number;
    /** 列名 */
    columnName: string;
    /** 列类型（1 文本/2 数字/3 单选/4 多选/5 日期/7 复选框/18 单向关联/21 双向关联） */
    columnType: number | null;
    /** 缺参类型 */
    kind: '缺列' | '缺值' | '类型违规' | '关联缺列' | '关联未命中';
    /** 受影响行号集（缺值/类型违规时填写） */
    rowNumbers: number[] | null;
    /** 受影响总行数（缺列/关联缺列时填写） */
    totalRows: number | null;
    /** 违规原因（类型违规时填写） */
    reason: string | null;
    /** 未命中名清单（关联未命中时填写） */
    missNames: ExcelMissName[] | null;
}

/** 歧义项（同名多记录，预选 sort 靠前，R14/KTD7） */
export interface ExcelAmbiguityItem {
    /** 关联列 id */
    columnId: number;
    /** 关联列名 */
    columnName: string;
    /** 歧义记录名 */
    name: string;
    /** 候选记录数（同名记录条数） */
    candidateCount: number;
    /** 预选记录 id（sort 最靠前的同名记录） */
    preselectedRecordId: number | null;
}

/** 默认值填充项（R10：有默认值的列不进缺参弹框） */
export interface ExcelDefaultValueFill {
    /** 列 id */
    columnId: number;
    /** 列名 */
    columnName: string;
    /** 默认值（显示值） */
    defaultValue: string;
    /** 将填充的行数 */
    rowCount: number;
}

/** 新选项创建项（R19：单选/多选未命中选项自动追加） */
export interface ExcelNewOption {
    /** 列 id */
    columnId: number;
    /** 列名 */
    columnName: string;
    /** 未命中的选项文本 */
    optionText: string;
    /** 出现次数 */
    occurrences: number;
}

/** 对称写入跨表影响项（R11/R16） */
export interface ExcelSymmetricWriteImpact {
    /** 被关联表名 */
    tableName: string;
    /** 将修改的被关联表记录数 */
    affectedRecordCount: number;
}

/** 跳过的表头（R5/R6） */
export interface ExcelSkippedHeader {
    /** 表头名 */
    headerName: string;
    /** 跳过原因（中文） */
    reason: string;
}

/** 选择器候选记录 */
export interface ExcelRecordCandidate {
    /** 被关联表记录 id */
    recordId: number;
    /** 被关联表记录名 */
    name: string;
}

/** 关联列选择器候选（一个映射到的 18/21 关联列一条，KTD7 内嵌数据源） */
export interface ExcelRelationCandidates {
    /** 关联列 id */
    columnId: number;
    /** 候选记录清单（按 sort 升序前 100 条，已过归属校验） */
    candidates: ExcelRecordCandidate[] | null;
}

/** Excel 导入预检结果（阶段一，R9/R11，对齐后端 ExcelImportPrecheckResult） */
export interface ExcelImportPrecheckResult {
    /** 文件指纹（size + md5） */
    fileFingerprint: ExcelFileFingerprint;
    /** 缺参项清单 */
    missingParams: ExcelMissingParam[];
    /** 歧义项清单 */
    ambiguityItems: ExcelAmbiguityItem[];
    /** 默认值填充项清单 */
    defaultValueFills: ExcelDefaultValueFill[];
    /** 新选项创建项清单 */
    newOptions: ExcelNewOption[];
    /** 对称写入跨表影响清单 */
    symmetricWriteImpact: ExcelSymmetricWriteImpact[];
    /** 忽略的 sheet 名清单 */
    ignoredSheets: string[];
    /** 跳过的表头清单 */
    skippedHeaders: ExcelSkippedHeader[];
    /** 关联列选择器候选清单 */
    relationCandidates: ExcelRelationCandidates[];
    /** 是否存在阻断性问题（缺参或歧义非空） */
    hasBlockingIssues: boolean;
}

/** 阶段二补参 JSON（对齐后端 ExcelImportParams 契约，KTD5） */
export interface ExcelImportParamsPayload {
    /** 预检响应返回的文件指纹 */
    fileFingerprint: ExcelFileFingerprint;
    /** 预检基线（反向漂移 fail-fast 比对基线） */
    precheckBaseline: {
        /** 预检时缺参列名（含全部 kind 涉及的列） */
        missingColumns: string[];
        /** 预检时全部未命中名（键=列名+名） */
        missNames: { columnName: string; name: string }[];
        /** 预检时歧义项（键=列名+名，含预选记录 id） */
        ambiguity: { columnName: string; name: string; preselectedRecordId: number | null }[];
    };
    /** 基础列/违规列统一补值：键=列名，值=统一值（可为空串=显式补空） */
    columnValues: Record<string, string>;
    /** 关联列逐值选择：每个（列名+未命中名/歧义名）一条，recordId=null 表示显式留空 */
    relationSelections: { columnName: string; name: string; recordId: number | null }[];
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

/**
 * Excel 导入阶段一：预检（不写库，R9）。
 *
 * @param params noteId / dwtableId / file
 * @param options.timeoutMs 可选，默认 600000（与 importDwtable 一致——预检含全量文本匹配）
 * @returns 预检结果清单（缺参/歧义/影响面/候选/文件指纹）
 * @throws Error 失败时抛出，message 为后端中文 msg 或网络异常提示
 */
export async function precheckExcelImport(
    params: ExcelImportRequestParams,
    options?: { timeoutMs?: number },
): Promise<ExcelImportPrecheckResult> {
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
        response = await fetch(`${baseUrl}/system/dwtable/precheckExcelImport`, {
            method: 'POST',
            headers: {
                'Authorization': token,
            },
            credentials: 'include',
            body: formData,
            signal: controller.signal,
        });
    } catch (e: any) {
        if (e?.name === 'AbortError') {
            throw new Error('预检超时，请稍后重试');
        }
        throw new Error('网络异常，请稍后重试');
    } finally {
        clearTimeout(timer);
    }

    if (!response.ok) {
        throw new Error(`预检失败：HTTP ${response.status}`);
    }

    const body = await response.json().catch(() => ({} as any));
    if (body?.code !== 200) {
        throw new Error(body?.msg || `预检失败（code=${body?.code}）`);
    }
    return body?.data as ExcelImportPrecheckResult;
}

/**
 * Excel 导入阶段二：携带补参 JSON 单事务写入（KTD5）。
 *
 * @param params noteId / dwtableId / file（须与预检同一文件，指纹校验）
 * @param payload 补参 JSON（文件指纹 + 预检基线 + 基础列统一值 + 关联列逐值选择）
 * @param options.timeoutMs 可选，默认 600000
 * @returns 成功导入的记录数
 * @throws Error 失败时抛出（含"数据已变化，请重新预检"），message 为后端中文 msg
 */
export async function importExcelData(
    params: ExcelImportRequestParams,
    payload: ExcelImportParamsPayload,
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
    formData.append('params', JSON.stringify(payload));

    let response: Response;
    try {
        response = await fetch(`${baseUrl}/system/dwtable/importExcelData`, {
            method: 'POST',
            headers: {
                'Authorization': token,
            },
            credentials: 'include',
            body: formData,
            signal: controller.signal,
        });
    } catch (e: any) {
        // 导入为纯追加、无幂等键：超时只代表前端放弃等待，服务端事务可能仍在执行并最终成功提交，
        // 此时立即重试会造成整批重复导入——文案必须引导先确认结果而非重发（与 importDwtable 一致）
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
