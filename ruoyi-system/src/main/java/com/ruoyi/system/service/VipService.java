package com.ruoyi.system.service;

import java.util.Date;
import com.ruoyi.system.domain.dto.VipStatusDto;

/**
 * VIP 会员统一鉴权服务（KTD2）
 * <p>
 * 全部限额点（上传/笔记/高级列/AI/Agent/共享/个人导出/回收站保留期）的唯一后端判断入口，
 * 禁止各处自行查库。VIP 状态经 Redis 缓存（key {@code vip:status:{userId}}，TTL 30 分钟），
 * 到期判断以到期时间字段惰性比较为准。
 * <p>
 * 异常语义：{@code VipRequiredException}(602)=身份门槛未达需升级；
 * {@code VipLimitException}(603)=已达当前身份配额上限。管理员（userId==1）豁免全部断言。
 *
 * @author ruoyi
 * @date 2026-11-04
 */
public interface VipService
{
    /** VIP 状态 Redis key 前缀：vip:status:{userId} */
    String STATUS_KEY_PREFIX = "vip:status:";

    /** AI 每日计数 Redis key 模板：vip:ai:count:{userId}:{yyyyMMdd} */
    String AI_COUNT_KEY_TEMPLATE = "vip:ai:count:%d:%s";

    /** 凭证上传每日计数 Redis key 模板：vip:proof:count:{userId}:{yyyyMMdd} */
    String PROOF_COUNT_KEY_TEMPLATE = "vip:proof:count:%d:%s";

    /**
     * 查询用户 VIP 状态与配额信息（admin 豁免态按 VIP 口径）
     */
    VipStatusDto getStatus(Long userId);

    /**
     * 用户当前是否为有效 VIP（admin 视为已豁免，返回 true）
     */
    boolean isVip(Long userId);

    /**
     * 开通/续费 VIP：新到期时间 = max(now, 当前到期时间) + days（R2 叠加语义），随后失效缓存（延迟双删）
     */
    void openVip(Long userId, int days, String operator);

    /**
     * 手动调整到期时间（可设为过去时间即时降级），随后失效缓存
     */
    void adjustExpire(Long userId, Date expireTime, String operator);

    /**
     * 删除用户 VIP 状态缓存（状态变化时调用）
     */
    void invalidate(Long userId);

    /**
     * 笔记创建配额断言（/add 与 /saveAsNewNote）：普通用户活跃笔记数 ≥ 上限时抛 VipRequiredException；
     * VIP 不限；admin 豁免
     */
    void assertNoteQuota(Long userId);

    /**
     * 上传配额断言（单文件 + 总容量）。普通用户超限抛 VipRequiredException(602)，
     * VIP 超限抛 VipLimitException(603)；admin 豁免
     *
     * @param userId 用户ID
     * @param incomingBytes 本次上传大小（字节）
     * @param additionalBytes 同批已计入的其他文件大小（批量上传累计；单文件传 0）
     */
    void assertUploadQuota(Long userId, long incomingBytes, long additionalBytes);

    /**
     * 高级列创建断言：type ∈ {21,24,25,26} 且非 VIP 时抛 VipRequiredException；admin 豁免
     */
    void assertAdvancedColumn(Long userId, Integer type);

    /**
     * Agent 使用断言：非 VIP 抛 VipRequiredException；admin 豁免
     */
    void assertAgent(Long userId);

    /**
     * 个人数据导出断言：非 VIP 抛 VipRequiredException；admin 豁免
     */
    void assertPersonalExport(Long userId);

    /**
     * 共享创建断言：非 VIP 抛 VipRequiredException；admin 豁免
     */
    void assertShare(Long userId);

    /**
     * AI 对话每日配额：Redis 原子自增后比较（发起即扣次，LLM 报错不返还）。
     * 普通用户超限抛 VipRequiredException(602)，VIP 超限抛 VipLimitException(603)；admin 豁免
     */
    void aiDailyTryAcquire(Long userId);

    /**
     * 凭证上传每日次数（防滥用，超限抛 ServiceException——非升级引导语义）；admin 豁免
     */
    void proofDailyTryAcquire(Long userId);

    /**
     * 回收站保留天数：VIP 30 / 普通 7（sys_config 可调），admin 按 VIP 口径
     */
    int getRecycleRetentionDays(Long userId);

    /**
     * 记录一次成功上传（写入 sys_upload_record 计入容量，KTD3）
     */
    void recordUpload(Long userId, String fileName, String filePath, long fileSize, String bizType);

    /**
     * 用户已用总容量（字节）
     */
    long getUsedBytes(Long userId);
}
