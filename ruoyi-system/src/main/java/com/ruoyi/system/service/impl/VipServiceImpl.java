package com.ruoyi.system.service.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.exception.VipLimitException;
import com.ruoyi.common.exception.VipRequiredException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.SysUploadRecord;
import com.ruoyi.system.domain.VipMembership;
import com.ruoyi.system.domain.dto.VipStatusDto;
import com.ruoyi.system.mapper.NoteNoteMapper;
import com.ruoyi.system.mapper.SysUploadRecordMapper;
import com.ruoyi.system.mapper.VipMembershipMapper;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.service.VipService;

/**
 * VIP 会员统一鉴权服务实现（KTD2）
 * <p>
 * 状态缓存：{@code vip:status:{userId}} 值=到期时间毫秒（0=非会员），TTL 30 分钟；
 * 非会员也缓存防穿透；开通/调整时延迟双删（提交后删一次、约 500ms 后再删一次）
 * 规避读回填竞态。到期判断惰性比较——缓存内仅存到期时间，比较发生在读取时。
 *
 * @author ruoyi
 * @date 2026-11-04
 */
@Service
public class VipServiceImpl implements VipService
{
    private static final Logger log = LoggerFactory.getLogger(VipServiceImpl.class);

    /** 状态缓存 TTL（分钟） */
    private static final int STATUS_TTL_MINUTES = 30;

    /** AI/凭证每日计数 TTL（小时），跨日自然过期免清理 */
    private static final int DAILY_COUNT_TTL_HOURS = 48;

    /** 延迟双删二次删除延迟（毫秒） */
    private static final long DOUBLE_DELETE_DELAY_MS = 500;

    /** 高级列类型（R11：仅 VIP 可新建） */
    private static final int[] ADVANCED_COLUMN_TYPES = {21, 24, 25, 26};

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** sys_config 键（KTD4：限额数值在线可调） */
    private static final String CFG_FILE_MAX_NORMAL = "vip.upload.file.max.mb.normal";
    private static final String CFG_FILE_MAX_VIP = "vip.upload.file.max.mb.vip";
    private static final String CFG_TOTAL_MAX_NORMAL = "vip.upload.total.max.mb.normal";
    private static final String CFG_TOTAL_MAX_VIP = "vip.upload.total.max.mb.vip";
    private static final String CFG_NOTE_LIMIT_NORMAL = "vip.note.limit.normal";
    private static final String CFG_AI_DAILY_NORMAL = "vip.ai.daily.normal";
    private static final String CFG_AI_DAILY_VIP = "vip.ai.daily.vip";
    private static final String CFG_RECYCLE_DAYS_NORMAL = "vip.recycle.days.normal";
    private static final String CFG_RECYCLE_DAYS_VIP = "vip.recycle.days.vip";
    private static final String CFG_PROOF_DAILY_LIMIT = "vip.proof.daily.limit";

    @Autowired
    private VipMembershipMapper vipMembershipMapper;

    @Autowired
    private SysUploadRecordMapper sysUploadRecordMapper;

    @Autowired
    private NoteNoteMapper noteNoteMapper;

    @Autowired
    private ISysConfigService configService;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private RedisTemplate redisTemplate;

    /** 延迟双删调度线程（daemon，单线程足够） */
    private final ScheduledExecutorService doubleDeleteScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "vip-cache-double-delete");
        t.setDaemon(true);
        return t;
    });

    // =========================================
    // 状态读取与缓存
    // =========================================

    @Override
    public VipStatusDto getStatus(Long userId)
    {
        boolean admin = SecurityUtils.isAdmin(userId);
        boolean vip = isEffectiveVip(userId);
        VipStatusDto dto = new VipStatusDto();
        dto.setAdmin(admin);
        dto.setVip(vip);
        dto.setExpireTime(vip ? loadExpireTime(userId) : null);
        // admin 豁免：按 VIP 口径返回配额（豁免即无限制，数值仅供前端展示）
        boolean vipQuota = admin || vip;
        dto.setNoteLimit(vipQuota ? -1L : getLongConfig(CFG_NOTE_LIMIT_NORMAL, 20L));
        dto.setFileMaxBytes(getLongConfig(vipQuota ? CFG_FILE_MAX_VIP : CFG_FILE_MAX_NORMAL, vipQuota ? 512L : 50L) * 1024 * 1024);
        dto.setTotalMaxBytes(getLongConfig(vipQuota ? CFG_TOTAL_MAX_VIP : CFG_TOTAL_MAX_NORMAL, vipQuota ? 10240L : 1024L) * 1024 * 1024);
        dto.setAiDailyLimit(getIntConfig(vipQuota ? CFG_AI_DAILY_VIP : CFG_AI_DAILY_NORMAL, vipQuota ? 100 : 10));
        dto.setRecycleDays(getIntConfig(vipQuota ? CFG_RECYCLE_DAYS_VIP : CFG_RECYCLE_DAYS_NORMAL, vipQuota ? 30 : 7));
        return dto;
    }

    @Override
    public boolean isVip(Long userId)
    {
        return isEffectiveVip(userId);
    }

    /**
     * 有效 VIP 判断（admin 视为已豁免=true；到期惰性比较）
     */
    private boolean isEffectiveVip(Long userId)
    {
        if (SecurityUtils.isAdmin(userId))
        {
            return true;
        }
        Long expireMillis = loadCachedExpireMillis(userId);
        return expireMillis != null && expireMillis > System.currentTimeMillis();
    }

    /**
     * 读取缓存到期毫秒：miss 时查库回填（非会员缓存 0 防穿透）
     */
    private Long loadCachedExpireMillis(Long userId)
    {
        String key = STATUS_KEY_PREFIX + userId;
        Long cached = redisCache.getCacheObject(key);
        if (cached != null)
        {
            return cached;
        }
        VipMembership membership = vipMembershipMapper.selectVipMembershipByUserId(userId);
        long expireMillis = membership != null && membership.getExpireTime() != null
                ? membership.getExpireTime().getTime() : 0L;
        redisCache.setCacheObject(key, expireMillis, STATUS_TTL_MINUTES, TimeUnit.MINUTES);
        return expireMillis;
    }

    /**
     * 查库取到期时间（不走缓存，openVip/展示用）
     */
    private Date loadExpireTime(Long userId)
    {
        VipMembership membership = vipMembershipMapper.selectVipMembershipByUserId(userId);
        return membership != null ? membership.getExpireTime() : null;
    }

    // =========================================
    // 开通/调整/失效
    // =========================================

    @Override
    public void openVip(Long userId, int days, String operator)
    {
        if (days <= 0)
        {
            throw new ServiceException("开通天数必须为正数");
        }
        Date now = new Date();
        Date current = loadExpireTime(userId);
        // R2 叠加：未到期续费自旧到期顺延；已到期/无身份自当日起算
        Date base = (current != null && current.after(now)) ? current : now;
        Date newExpire = Date.from(LocalDateTime.ofInstant(base.toInstant(), ZoneId.systemDefault())
                .plusDays(days).atZone(ZoneId.systemDefault()).toInstant());
        upsertExpire(userId, newExpire, operator);
        log.info("VIP开通: userId={}, days={}, 新到期={}", userId, days, newExpire);
    }

    @Override
    public void adjustExpire(Long userId, Date expireTime, String operator)
    {
        upsertExpire(userId, expireTime, operator);
        log.info("VIP手动调整: userId={}, 到期={}, 操作人={}", userId, expireTime, operator);
    }

    private void upsertExpire(Long userId, Date expireTime, String operator)
    {
        VipMembership membership = new VipMembership();
        membership.setUserId(userId);
        membership.setExpireTime(expireTime);
        membership.setUpdateBy(operator);
        if (vipMembershipMapper.selectVipMembershipByUserId(userId) != null)
        {
            vipMembershipMapper.updateVipMembershipExpire(membership);
        }
        else
        {
            vipMembershipMapper.insertVipMembership(membership);
        }
        invalidateWithDoubleDelete(userId);
    }

    @Override
    public void invalidate(Long userId)
    {
        redisCache.deleteObject(STATUS_KEY_PREFIX + userId);
    }

    /**
     * 延迟双删（KTD2）：立即删一次，~500ms 后再删一次，规避读回填竞态
     */
    private void invalidateWithDoubleDelete(Long userId)
    {
        String key = STATUS_KEY_PREFIX + userId;
        redisCache.deleteObject(key);
        doubleDeleteScheduler.schedule(() -> {
            try
            {
                redisCache.deleteObject(key);
            }
            catch (Exception e)
            {
                log.warn("VIP缓存延迟双删失败: userId={}", userId, e);
            }
        }, DOUBLE_DELETE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    // =========================================
    // 断言族（admin 全部豁免）
    // =========================================

    @Override
    public void assertNoteQuota(Long userId)
    {
        if (SecurityUtils.isAdmin(userId))
        {
            return;
        }
        if (isEffectiveVip(userId))
        {
            return; // VIP 笔记不限
        }
        long limit = getLongConfig(CFG_NOTE_LIMIT_NORMAL, 20L);
        int count = noteNoteMapper.countUserActiveNotes(userId);
        if (count >= limit)
        {
            throw new VipRequiredException(StringUtils.format(
                    "笔记数量已达普通用户上限（{} 篇），升级 VIP 后不限量创建", limit));
        }
    }

    @Override
    public void assertUploadQuota(Long userId, long incomingBytes, long additionalBytes)
    {
        if (SecurityUtils.isAdmin(userId))
        {
            return;
        }
        boolean vip = isEffectiveVip(userId);
        long fileMax = getLongConfig(vip ? CFG_FILE_MAX_VIP : CFG_FILE_MAX_NORMAL, vip ? 512L : 50L) * 1024 * 1024;
        long totalMax = getLongConfig(vip ? CFG_TOTAL_MAX_VIP : CFG_TOTAL_MAX_NORMAL, vip ? 10240L : 1024L) * 1024 * 1024;

        // 单文件上限
        if (incomingBytes > fileMax)
        {
            if (vip)
            {
                throw new VipLimitException(StringUtils.format(
                        "单文件大小超过 VIP 上限（{}MB），请压缩后再上传", fileMax / 1024 / 1024));
            }
            throw new VipRequiredException(StringUtils.format(
                    "单文件大小超过普通用户上限（{}MB），升级 VIP 后可上传最大 {}MB",
                    fileMax / 1024 / 1024,
                    getLongConfig(CFG_FILE_MAX_VIP, 512L)));
        }
        // 总容量上限（含同批已计入部分）
        long used = getUsedBytes(userId);
        if (used + additionalBytes + incomingBytes > totalMax)
        {
            if (vip)
            {
                throw new VipLimitException(StringUtils.format(
                        "存储空间已满（已用 {}MB / 上限 {}MB），请清理空间后重试",
                        used / 1024 / 1024, totalMax / 1024 / 1024));
            }
            throw new VipRequiredException(StringUtils.format(
                    "存储空间已满（上限 {}MB），升级 VIP 可扩容至 {}MB",
                    totalMax / 1024 / 1024,
                    getLongConfig(CFG_TOTAL_MAX_VIP, 10240L)));
        }
    }

    @Override
    public void assertAdvancedColumn(Long userId, Long type)
    {
        if (type == null || !isAdvancedColumn(type) || SecurityUtils.isAdmin(userId))
        {
            return;
        }
        if (!isEffectiveVip(userId))
        {
            throw new VipRequiredException("高级列类型（双向关联/集合运算/语义关联/查找引用）为 VIP 专属，升级后可创建");
        }
    }

    @Override
    public void assertAgent(Long userId)
    {
        assertVipOnly(userId, "AI Agent 为 VIP 专属能力，升级后可使用");
    }

    @Override
    public void assertPersonalExport(Long userId)
    {
        assertVipOnly(userId, "个人数据导出为 VIP 专属能力，升级后可使用");
    }

    @Override
    public void assertShare(Long userId)
    {
        assertVipOnly(userId, "创建共享为 VIP 专属能力，升级后可使用");
    }

    private void assertVipOnly(Long userId, String message)
    {
        if (SecurityUtils.isAdmin(userId))
        {
            return;
        }
        if (!isEffectiveVip(userId))
        {
            throw new VipRequiredException(message);
        }
    }

    private boolean isAdvancedColumn(Long type)
    {
        for (int t : ADVANCED_COLUMN_TYPES)
        {
            if (type != null && type == t)
            {
                return true;
            }
        }
        return false;
    }

    // =========================================
    // 每日计数（Redis 原子自增）
    // =========================================

    @Override
    public void aiDailyTryAcquire(Long userId)
    {
        if (SecurityUtils.isAdmin(userId))
        {
            return;
        }
        boolean vip = isEffectiveVip(userId);
        int limit = getIntConfig(vip ? CFG_AI_DAILY_VIP : CFG_AI_DAILY_NORMAL, vip ? 100 : 10);
        long count = dailyIncr(AI_COUNT_KEY_TEMPLATE, userId);
        if (count > limit)
        {
            if (vip)
            {
                throw new VipLimitException(StringUtils.format(
                        "今日 AI 对话次数已用完（{} 次/日），明日重试", limit));
            }
            throw new VipRequiredException(StringUtils.format(
                    "今日 AI 对话次数已用完（普通用户 {} 次/日），升级 VIP 可享 {} 次/日",
                    limit, getIntConfig(CFG_AI_DAILY_VIP, 100)));
        }
    }

    @Override
    public void proofDailyTryAcquire(Long userId)
    {
        if (SecurityUtils.isAdmin(userId))
        {
            return;
        }
        int limit = getIntConfig(CFG_PROOF_DAILY_LIMIT, 10);
        long count = dailyIncr(PROOF_COUNT_KEY_TEMPLATE, userId);
        if (count > limit)
        {
            throw new ServiceException(StringUtils.format("凭证上传次数已达每日上限（{} 次/日）", limit));
        }
    }

    /**
     * 每日计数自增：首日（返回 1）时设置 TTL 48h，跨日自然过期归零
     */
    private long dailyIncr(String keyTemplate, Long userId)
    {
        String key = String.format(keyTemplate, userId, LocalDate.now().format(DAY_FMT));
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1)
        {
            redisTemplate.expire(key, DAILY_COUNT_TTL_HOURS, TimeUnit.HOURS);
        }
        return count == null ? 0 : count;
    }

    // =========================================
    // 回收站保留期与容量记录
    // =========================================

    @Override
    public int getRecycleRetentionDays(Long userId)
    {
        boolean vipQuota = SecurityUtils.isAdmin(userId) || isEffectiveVip(userId);
        return getIntConfig(vipQuota ? CFG_RECYCLE_DAYS_VIP : CFG_RECYCLE_DAYS_NORMAL, vipQuota ? 30 : 7);
    }

    @Override
    public void recordUpload(Long userId, String fileName, String filePath, long fileSize, String bizType)
    {
        SysUploadRecord record = new SysUploadRecord();
        record.setUserId(userId);
        record.setFileName(fileName);
        record.setFilePath(filePath);
        record.setFileSize(fileSize);
        record.setBizType(bizType);
        sysUploadRecordMapper.insertSysUploadRecord(record);
    }

    @Override
    public long getUsedBytes(Long userId)
    {
        Long sum = sysUploadRecordMapper.sumFileSizeByUserId(userId);
        return sum != null ? sum : 0L;
    }

    // =========================================
    // 配置读取（KTD4：sys_config，失败回退默认值）
    // =========================================

    private long getLongConfig(String key, long defaultValue)
    {
        try
        {
            String value = configService.selectConfigByKey(key);
            return StringUtils.isNotEmpty(value) ? Long.parseLong(value.trim()) : defaultValue;
        }
        catch (Exception e)
        {
            log.warn("读取VIP配置失败，使用默认值: key={}, default={}", key, defaultValue);
            return defaultValue;
        }
    }

    private int getIntConfig(String key, int defaultValue)
    {
        return (int) getLongConfig(key, defaultValue);
    }
}
