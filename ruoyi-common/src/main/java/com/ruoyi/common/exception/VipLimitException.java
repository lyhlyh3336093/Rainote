package com.ruoyi.common.exception;

/**
 * 已达当前身份配额上限异常（错误码 603：VIP_LIMIT_REACHED）
 * <p>
 * 触发场景：VIP 用户超出 VIP 配额（AI 每日次数用尽/总容量触顶/单文件超 512MB）。
 * 单档 VIP 体系下无处可升级，前端识别 code=603 后仅作纯提示
 * （AI 场景"明日重试"、容量场景"清理空间"），不引导升级。
 *
 * @author ruoyi
 */
public class VipLimitException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    /** 错误码：已达当前身份配额上限 */
    public static final int CODE = 603;

    public VipLimitException(String message)
    {
        super(message);
    }
}
