package com.ruoyi.common.exception;

/**
 * VIP 身份门槛未达异常（错误码 602：NEED_VIP）
 * <p>
 * 触发场景：普通用户触碰 VIP 专属能力（高级列创建/Agent/个人导出/共享创建）
 * 或普通用户超出普通配额（笔记数/单文件/总容量/AI 次数）。
 * 前端识别 code=602 后弹出升级引导（跳转 /vip）。
 *
 * @author ruoyi
 */
public class VipRequiredException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    /** 错误码：需要升级 VIP */
    public static final int CODE = 602;

    public VipRequiredException(String message)
    {
        super(message);
    }
}
