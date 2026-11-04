/**
 * VIP 会员 API（U7）
 * <p>
 * 封装后端 /system/vip/* 端点：聚合信息、凭证上传（multipart）、充值申请提交、
 * 我的申请列表、凭证鉴权下载。RuoYi 失败语义为 HTTP 200 + {"code":xxx,"msg":"..."}
 * （全局异常处理），因此与 export.ts/agent.ts 同模式使用原生 fetch；
 * 602/603 限额错误码的统一弹窗引导见 {@link handleVipCode}（KTD5）。
 */
import { Modal, message } from 'ant-design-vue';
import useCookie from '../hooks/useCookie';
import { API_BASE } from '../config/api';
import router from '../router';

const cookie = useCookie;
const baseUrl = API_BASE;

/** VIP 状态与配额（对齐后端 com.ruoyi.system.domain.dto.VipStatusDto） */
export interface VipStatus {
    /** 是否管理员（豁免所有限额） */
    admin: boolean;
    /** 是否 VIP（expire > now） */
    vip: boolean;
    /** VIP 到期时间（非会员为 null） */
    expireTime: string | null;
    /** 笔记数上限（-1=不限） */
    noteLimit: number;
    /** 单文件上传上限（字节） */
    fileMaxBytes: number;
    /** 附件总容量上限（字节） */
    totalMaxBytes: number;
    /** AI 对话每日次数上限 */
    aiDailyLimit: number;
    /** 回收站保留天数 */
    recycleDays: number;
}

/** 充值档位 */
export interface VipTier {
    id: number;
    name: string;
    days: number;
    price: number;
    sort: number;
}

/** 充值申请（我的申请列表行 / 当前待审核申请） */
export interface VipOrder {
    id: number;
    tierId: number;
    tierName: string;
    amount: number;
    payChannel: string;
    proofUrl: string;
    userRemark: string;
    /** '0'待审核 '1'已开通 '2'已驳回 */
    status: string;
    adminRemark: string | null;
    createTime: string;
}

/** GET /system/vip/info 聚合响应（AjaxResult + 附加键） */
export interface VipInfo {
    code: number;
    msg?: string;
    status: VipStatus;
    tiers: VipTier[];
    wechatQr: string;
    alipayQr: string;
    pendingOrder: VipOrder | null;
}

/** POST /system/vip/order 请求体 */
export interface VipOrderBody {
    tierId: number;
    proofUrl: string;
    payChannel: string;
    userRemark?: string;
}

/**
 * 统一请求（原生 fetch，返回完整 body 由调用方按 code 分支）。
 * FormData 模式下浏览器自动携带 multipart boundary，勿手动设置 Content-Type。
 */
async function request<T = any>(method: string, path: string, body?: any): Promise<T> {
    const token = cookie.get('token');
    const headers: Record<string, string> = { 'Authorization': token };
    let payload: BodyInit | undefined;
    if (body instanceof FormData) {
        payload = body;
    } else if (body !== undefined) {
        headers['Content-Type'] = 'application/json';
        payload = JSON.stringify(body);
    }
    const response = await fetch(`${baseUrl}${path}`, {
        method,
        headers,
        credentials: 'include',
        body: payload,
    });
    return response.json();
}

/** GET /system/vip/info — 状态/配额 + 启用档位 + 收款码 URL + 当前待审核申请 */
export function getVipInfo() {
    return request<VipInfo>('GET', '/system/vip/info');
}

/** POST /system/vip/proof — 支付凭证上传（multipart file，图片 ≤10MB，成功返回 proofUrl） */
export function uploadProof(file: File) {
    const formData = new FormData();
    formData.append('file', file);
    return request<{ code: number; msg?: string; proofUrl?: string }>('POST', '/system/vip/proof', formData);
}

/** POST /system/vip/order — 提交充值申请（已有待审核时后端返回 code 500 + msg） */
export function submitOrder(orderBody: VipOrderBody) {
    return request<{ code: number; msg?: string }>('POST', '/system/vip/order', orderBody);
}

/** GET /system/vip/order/list — 我的申请列表（分页 TableDataInfo） */
export function listMyOrders(pageNum: number, pageSize: number) {
    return request<{ code?: number; rows: VipOrder[]; total: number }>(
        'GET',
        `/system/vip/order/list?pageNum=${pageNum}&pageSize=${pageSize}`,
    );
}

/**
 * 凭证图片鉴权下载（KTD12：凭证存私有目录，须经 Authorization 取 blob 转 objectURL 展示）。
 * 调用方在不再使用时负责 URL.revokeObjectURL 释放。
 */
export async function fetchProofImage(proofUrl: string): Promise<string> {
    const token = cookie.get('token');
    const response = await fetch(
        `${baseUrl}/system/vip/proof/download?proofUrl=${encodeURIComponent(proofUrl)}`,
        {
            headers: { 'Authorization': token },
            credentials: 'include',
        },
    );
    if (!response.ok) {
        throw new Error(`凭证加载失败：HTTP ${response.status}`);
    }
    const blob = await response.blob();
    return URL.createObjectURL(blob);
}

/**
 * VIP 限额错误码统一处理（KTD5，供 useFetch 与四条原生 fetch 路径复用）：
 * - 602 NEED_VIP：身份门槛未达 → 确认弹窗"升级 VIP 解锁此功能"，确定跳 /vip
 * - 603 VIP_LIMIT_REACHED：已达当前身份配额上限 → 纯提示（AI 场景即"明日重试"，不引导升级）
 *
 * @returns 是否已处理（true 时调用方应跳过自身默认错误提示，避免重复弹窗）
 */
export function handleVipCode(code: number | undefined | null, msg?: string): boolean {
    if (code === 602) {
        Modal.confirm({
            title: '升级 VIP 解锁此功能',
            content: msg || '该功能为 VIP 会员专属，升级后即可使用。',
            okText: '升级 VIP',
            cancelText: '取消',
            centered: true,
            onOk() {
                router.push('/vip');
            },
        });
        return true;
    }
    if (code === 603) {
        message.error(msg || '已达当前身份的配额上限');
        return true;
    }
    return false;
}
