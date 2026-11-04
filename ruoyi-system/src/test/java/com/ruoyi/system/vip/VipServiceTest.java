package com.ruoyi.system.vip;

import java.time.LocalDate;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.exception.VipLimitException;
import com.ruoyi.common.exception.VipRequiredException;
import com.ruoyi.system.domain.VipMembership;
import com.ruoyi.system.domain.dto.VipStatusDto;
import com.ruoyi.system.mapper.NoteNoteMapper;
import com.ruoyi.system.mapper.SysUploadRecordMapper;
import com.ruoyi.system.mapper.VipMembershipMapper;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.service.impl.VipServiceImpl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * VipService 单元测试（U2 Test scenarios）
 * <p>
 * 覆盖：状态缓存回填/惰性降级/openVip 叠加语义/缓存失效/
 * 笔记配额（含 admin 豁免）/上传配额（单文件+容量边界）/AI 每日计数。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VipServiceTest
{
    private static final Long ADMIN = 1L;
    private static final Long USER = 2L;

    @Mock
    private VipMembershipMapper vipMembershipMapper;

    @Mock
    private SysUploadRecordMapper sysUploadRecordMapper;

    @Mock
    private NoteNoteMapper noteNoteMapper;

    @Mock
    private ISysConfigService configService;

    @Mock
    private RedisCache redisCache;

    @Mock
    private RedisTemplate redisTemplate;

    @Mock
    private ValueOperations valueOperations;

    @InjectMocks
    private VipServiceImpl vipService;

    @BeforeEach
    void setUp()
    {
        // 默认配置：普通 50MB/1024MB/20篇/10次；VIP 512MB/10240MB/100次/30天
        when(configService.selectConfigByKey(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            switch (key)
            {
                case "vip.upload.file.max.mb.normal": return "50";
                case "vip.upload.file.max.mb.vip": return "512";
                case "vip.upload.total.max.mb.normal": return "1024";
                case "vip.upload.total.max.mb.vip": return "10240";
                case "vip.note.limit.normal": return "20";
                case "vip.ai.daily.normal": return "10";
                case "vip.ai.daily.vip": return "100";
                case "vip.recycle.days.normal": return "7";
                case "vip.recycle.days.vip": return "30";
                case "vip.proof.daily.limit": return "10";
                default: return null;
            }
        });
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private void mockCache(Long userId, Long expireMillis)
    {
        when(redisCache.getCacheObject("vip:status:" + userId)).thenReturn(expireMillis);
    }

    private void mockVipMember(Long userId, boolean vip)
    {
        VipMembership m = new VipMembership();
        m.setUserId(userId);
        m.setExpireTime(vip ? new Date(System.currentTimeMillis() + 86_400_000L) : new Date(System.currentTimeMillis() - 86_400_000L));
        when(vipMembershipMapper.selectVipMembershipByUserId(userId)).thenReturn(m);
        // 缓存 miss（返回 null）触发查库回填
        when(redisCache.getCacheObject("vip:status:" + userId)).thenReturn(null);
    }

    // ===== 状态读取与缓存 =====

    @Test
    void test_nonMember_getStatus_backfillsCacheZero()
    {
        when(vipMembershipMapper.selectVipMembershipByUserId(USER)).thenReturn(null);
        when(redisCache.getCacheObject("vip:status:" + USER)).thenReturn(null);

        VipStatusDto status = vipService.getStatus(USER);

        assertFalse(status.isVip());
        assertEquals(20L, status.getNoteLimit(), "非会员笔记上限=20（配置值）");
        assertEquals(50L * 1024 * 1024, status.getFileMaxBytes());
        // 回填缓存值 0（非会员防穿透），TTL 30 分钟
        verify(redisCache).setCacheObject(eq("vip:status:" + USER), eq(0L), eq(30), eq(TimeUnit.MINUTES));

        // 第二次读取：缓存命中（返回 0），mapper 仍只调用一次
        when(redisCache.getCacheObject("vip:status:" + USER)).thenReturn(0L);
        vipService.getStatus(USER);
        verify(vipMembershipMapper, times(1)).selectVipMembershipByUserId(USER);
    }

    @Test
    void test_expiredMembership_lazyDowngrade()
    {
        // 缓存中的到期时间已过 → 惰性降级为普通用户
        mockCache(USER, System.currentTimeMillis() - 1000);
        VipStatusDto status = vipService.getStatus(USER);
        assertFalse(status.isVip(), "到期时间已过应按普通用户处理（惰性降级，Covers F2）");
    }

    @Test
    void test_admin_exemptStatus()
    {
        VipStatusDto status = vipService.getStatus(ADMIN);
        assertTrue(status.isAdmin());
        assertTrue(status.isVip(), "admin 豁免视为 VIP 口径");
        assertEquals(-1L, status.getNoteLimit(), "admin 笔记不限");
        assertEquals(512L * 1024 * 1024, status.getFileMaxBytes());
    }

    // ===== openVip 叠加语义（R2） =====

    @Test
    void test_openVip_noMembership_startsFromNow()
    {
        when(vipMembershipMapper.selectVipMembershipByUserId(USER)).thenReturn(null);
        vipService.openVip(USER, 30, "admin");
        // 新增会员行，到期 ≈ now+30d
        verify(vipMembershipMapper).insertVipMembership(argThat(m ->
                m.getExpireTime().getTime() > System.currentTimeMillis() + 29L * 86400_000
                        && m.getExpireTime().getTime() < System.currentTimeMillis() + 31L * 86400_000));
    }

    @Test
    void test_openVip_renewal_extendsFromCurrentExpiry()
    {
        // 未到期 VIP（还剩 10 天）续费月卡 → 旧到期 + 30 天（共 40 天后）
        Date current = new Date(System.currentTimeMillis() + 10L * 86400_000);
        VipMembership m = new VipMembership();
        m.setUserId(USER);
        m.setExpireTime(current);
        when(vipMembershipMapper.selectVipMembershipByUserId(USER)).thenReturn(m);

        vipService.openVip(USER, 30, "admin");

        verify(vipMembershipMapper).updateVipMembershipExpire(argThat(u ->
                u.getExpireTime().getTime() > current.getTime() + 29L * 86400_000
                        && u.getExpireTime().getTime() < current.getTime() + 31L * 86400_000));
    }

    @Test
    void test_openVip_expiredRenewal_startsFromNow()
    {
        // 已到期续费 → 自当日起算（now+30d），不从旧到期叠加
        Date expired = new Date(System.currentTimeMillis() - 5L * 86400_000);
        VipMembership m = new VipMembership();
        m.setUserId(USER);
        m.setExpireTime(expired);
        when(vipMembershipMapper.selectVipMembershipByUserId(USER)).thenReturn(m);

        vipService.openVip(USER, 30, "admin");

        verify(vipMembershipMapper).updateVipMembershipExpire(argThat(u ->
                u.getExpireTime().getTime() > System.currentTimeMillis() + 29L * 86400_000
                        && u.getExpireTime().getTime() < System.currentTimeMillis() + 31L * 86400_000));
    }

    @Test
    void test_openVip_invalidatesCache()
    {
        when(vipMembershipMapper.selectVipMembershipByUserId(USER)).thenReturn(null);
        vipService.openVip(USER, 30, "admin");
        // 立即删一次（延迟双删的异步第二次不在断言范围）
        verify(redisCache, atLeastOnce()).deleteObject("vip:status:" + USER);
    }

    // ===== 笔记配额 =====

    @Test
    void test_noteQuota_normalUserAtLimit_throwsVipRequired()
    {
        mockCache(USER, 0L); // 非会员
        when(noteNoteMapper.countUserActiveNotes(USER)).thenReturn(20);
        VipRequiredException ex = assertThrows(VipRequiredException.class, () -> vipService.assertNoteQuota(USER));
        assertEquals(VipRequiredException.CODE, 602);
    }

    @Test
    void test_noteQuota_normalUserBelowLimit_passes()
    {
        mockCache(USER, 0L);
        when(noteNoteMapper.countUserActiveNotes(USER)).thenReturn(19);
        assertDoesNotThrow(() -> vipService.assertNoteQuota(USER));
    }

    @Test
    void test_noteQuota_vipUnlimited_adminExempt()
    {
        mockCache(USER, System.currentTimeMillis() + 86_400_000L); // VIP
        when(noteNoteMapper.countUserActiveNotes(USER)).thenReturn(999);
        assertDoesNotThrow(() -> vipService.assertNoteQuota(USER), "VIP 笔记不限");
        assertDoesNotThrow(() -> vipService.assertNoteQuota(ADMIN), "admin 豁免");
        verify(noteNoteMapper, never()).countUserActiveNotes(ADMIN);
    }

    // ===== 上传配额（单文件 + 容量） =====

    @Test
    void test_uploadQuota_normalUserSingleFileOverLimit_throwsVipRequired()
    {
        mockCache(USER, 0L); // 普通用户，单文件上限 50MB
        long sixtyMb = 60L * 1024 * 1024;
        assertThrows(VipRequiredException.class, () -> vipService.assertUploadQuota(USER, sixtyMb, 0));
    }

    @Test
    void test_uploadQuota_vipSingleFileOverLimit_throwsVipLimit()
    {
        mockCache(USER, System.currentTimeMillis() + 86_400_000L); // VIP，单文件上限 512MB
        long sixHundredMb = 600L * 1024 * 1024;
        assertThrows(VipLimitException.class, () -> vipService.assertUploadQuota(USER, sixHundredMb, 0),
                "VIP 超 512MB 应抛 603（已达上限，非升级引导）");
    }

    @Test
    void test_uploadQuota_capacityPlusIncomingOverLimit_throwsVipRequired()
    {
        mockCache(USER, 0L); // 普通用户 1024MB 上限
        when(sysUploadRecordMapper.sumFileSizeByUserId(USER)).thenReturn(1020L * 1024 * 1024);
        // 1020MB 已用 + 10MB 本次 > 1024MB → 拒绝；已上传文件不受影响（Covers AE3）
        assertThrows(VipRequiredException.class, () ->
                vipService.assertUploadQuota(USER, 10L * 1024 * 1024, 0));
    }

    @Test
    void test_uploadQuota_exactlyAtLimit_passes()
    {
        mockCache(USER, 0L);
        when(sysUploadRecordMapper.sumFileSizeByUserId(USER)).thenReturn(1014L * 1024 * 1024);
        // 已用 1014MB + 本次 10MB = 恰好 1024MB（边界等于上限放行）
        assertDoesNotThrow(() -> vipService.assertUploadQuota(USER, 10L * 1024 * 1024, 0));
    }

    @Test
    void test_uploadQuota_adminExempt()
    {
        assertDoesNotThrow(() -> vipService.assertUploadQuota(ADMIN, Long.MAX_VALUE / 2, 0));
    }

    // ===== AI 每日计数 =====

    @Test
    void test_aiDaily_normalUserAtLimit_throwsVipRequired()
    {
        mockCache(USER, 0L); // 普通用户 10 次/日
        when(valueOperations.increment(anyString())).thenReturn(11L);
        VipRequiredException ex = assertThrows(VipRequiredException.class, () -> vipService.aiDailyTryAcquire(USER));
        assertNotNull(ex);
    }

    @Test
    void test_aiDaily_vipAtLimit_throwsVipLimit()
    {
        mockCache(USER, System.currentTimeMillis() + 86_400_000L); // VIP 100 次/日
        when(valueOperations.increment(anyString())).thenReturn(101L);
        assertThrows(VipLimitException.class, () -> vipService.aiDailyTryAcquire(USER),
                "VIP 第 101 次应抛 603（Covers AE6 VIP 分支）");
    }

    @Test
    void test_aiDaily_withinLimit_passes_andFirstIncrementSetsTtl()
    {
        mockCache(USER, 0L);
        when(valueOperations.increment(anyString())).thenReturn(1L);
        assertDoesNotThrow(() -> vipService.aiDailyTryAcquire(USER));
        // 首日（返回 1）设置 TTL 48h
        verify(redisTemplate).expire(anyString(), eq(48L), eq(TimeUnit.HOURS));
    }

    @Test
    void test_aiDaily_crossDayCounterResets()
    {
        mockCache(USER, 0L);
        // 第一日已用 10 次（key 含当日日期），次日 increment 返回 1 → 放行
        String todayKey = String.format(VipServiceImpl.AI_COUNT_KEY_TEMPLATE, USER, LocalDate.now().format(DateTimeFormatterStub.FMT));
        when(valueOperations.increment(todayKey)).thenReturn(1L);
        assertDoesNotThrow(() -> vipService.aiDailyTryAcquire(USER));
    }

    /** 与 VipServiceImpl 内部 DAY_FMT 一致的格式器桩 */
    static class DateTimeFormatterStub
    {
        static final java.time.format.DateTimeFormatter FMT = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");
    }

    // ===== 凭证每日计数 =====

    @Test
    void test_proofDaily_overLimit_throwsServiceException()
    {
        mockCache(USER, 0L);
        when(valueOperations.increment(anyString())).thenReturn(11L);
        assertThrows(ServiceException.class, () -> vipService.proofDailyTryAcquire(USER));
    }

    // ===== 身份门槛断言（高级列/Agent/导出/共享） =====

    @Test
    void test_advancedColumn_nonVipRejected_vipAndBasicTypePass()
    {
        mockCache(USER, 0L);
        assertThrows(VipRequiredException.class, () -> vipService.assertAdvancedColumn(USER, 26));
        mockCache(USER, System.currentTimeMillis() + 86_400_000L);
        assertDoesNotThrow(() -> vipService.assertAdvancedColumn(USER, 21));
        // 非高级类型不受限
        mockCache(USER, 0L);
        assertDoesNotThrow(() -> vipService.assertAdvancedColumn(USER, 1));
    }

    @Test
    void test_vipOnlyAssertions()
    {
        mockCache(USER, 0L);
        assertThrows(VipRequiredException.class, () -> vipService.assertAgent(USER));
        assertThrows(VipRequiredException.class, () -> vipService.assertPersonalExport(USER));
        assertThrows(VipRequiredException.class, () -> vipService.assertShare(USER));
        // VIP 放行
        mockCache(USER, System.currentTimeMillis() + 86_400_000L);
        assertDoesNotThrow(() -> vipService.assertAgent(USER));
        assertDoesNotThrow(() -> vipService.assertPersonalExport(USER));
        assertDoesNotThrow(() -> vipService.assertShare(USER));
        // admin 豁免
        assertDoesNotThrow(() -> vipService.assertAgent(ADMIN));
    }

    // ===== 回收站保留期 =====

    @Test
    void test_recycleRetentionDays_byIdentity()
    {
        mockCache(USER, 0L);
        assertEquals(7, vipService.getRecycleRetentionDays(USER));
        mockCache(USER, System.currentTimeMillis() + 86_400_000L);
        assertEquals(30, vipService.getRecycleRetentionDays(USER));
        assertEquals(30, vipService.getRecycleRetentionDays(ADMIN), "admin 按 VIP 口径");
    }
}
