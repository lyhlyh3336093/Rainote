package com.ruoyi.quartz.task;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.system.domain.NoteNote;
import com.ruoyi.system.mapper.NoteNoteMapper;
import com.ruoyi.system.service.BackupLockUtil;
import com.ruoyi.system.service.INoteNoteService;
import com.ruoyi.system.service.VipService;

/**
 * VIP 回收站保留期清理任务（U4/R13/KTD6）。
 * <p>
 * sys_job invokeTarget: {@code vipCleanTask.cleanExpiredGarbage()}（cron 每日 3 点）。
 * <p>
 * 语义：按属主用户**清理时刻**的 VIP 状态对应保留期（普通 7 天 / VIP 30 天，sys_config 可调），
 * 超期垃圾笔记物理清理。清理复用 {@link INoteNoteService#deleteNoteFromGarbageByIds}
 * 的服务层级联删除路径（块/表格等子数据一并清理，禁止裸 SQL 删 note_note）；
 * 该路径内部含归属校验（依赖登录上下文），故按属主分组模拟该用户的 SecurityContext 执行。
 * VIP 删除后到期降级的笔记按清理时刻的普通保留期处理（已确认的范围决策）。
 *
 * @author ruoyi
 * @date 2026-11-04
 */
@Component("vipCleanTask")
public class VipCleanTask
{
    private static final Logger log = LoggerFactory.getLogger(VipCleanTask.class);

    @Autowired
    private NoteNoteMapper noteNoteMapper;

    @Autowired
    private INoteNoteService noteNoteService;

    @Autowired
    private VipService vipService;

    /**
     * 按属主 VIP 状态清理过期回收站笔记（quartz 调度入口，无登录上下文）
     */
    public void cleanExpiredGarbage()
    {
        try
        {
            // 备份/还原进行中则跳过本次清理（下一调度周期重试；物理删除与备份冻结互斥）
            BackupLockUtil.checkNotLocked();
        }
        catch (Exception e)
        {
            log.warn("VIP回收站清理跳过：备份/还原进行中");
            return;
        }

        List<NoteNote> garbageList = noteNoteMapper.selectGarbageWithOwner();
        if (garbageList.isEmpty())
        {
            log.info("VIP回收站清理：无垃圾笔记");
            return;
        }

        // 按属主分组：每个属主一个保留期口径 + 过期集合
        Map<Long, Integer> retentionDaysByUser = new HashMap<>();
        Map<Long, List<String>> expiredIdsByUser = new HashMap<>();
        Date now = new Date();
        for (NoteNote note : garbageList)
        {
            if (note.getAuth() == null || note.getDeleteTime() == null)
            {
                continue;
            }
            Integer retentionDays;
            try
            {
                retentionDays = retentionDaysByUser.computeIfAbsent(note.getAuth(),
                        userId -> vipService.getRecycleRetentionDays(userId));
            }
            catch (Exception e)
            {
                // 单用户保留期解析失败不阻断整轮清理（与删除阶段的单用户隔离语义一致）
                log.warn("VIP回收站清理：解析用户保留期失败，跳过该用户的笔记 userId={}", note.getAuth(), e);
                continue;
            }
            long expireAt = note.getDeleteTime().getTime() + retentionDays * 86_400_000L;
            if (now.getTime() > expireAt)
            {
                expiredIdsByUser.computeIfAbsent(note.getAuth(), k -> new ArrayList<>())
                        .add(String.valueOf(note.getId()));
            }
        }

        if (expiredIdsByUser.isEmpty())
        {
            log.info("VIP回收站清理：{} 条垃圾笔记均在保留期内", garbageList.size());
            return;
        }

        int totalCleaned = 0;
        for (Map.Entry<Long, List<String>> entry : expiredIdsByUser.entrySet())
        {
            Long ownerUserId = entry.getKey();
            List<String> expiredIds = entry.getValue();
            int retention = retentionDaysByUser.get(ownerUserId);
            try
            {
                // 级联删除路径含归属校验（SecurityUtils.getUserId）——模拟属主身份执行
                loginAs(ownerUserId);
                noteNoteService.deleteNoteFromGarbageByIds(expiredIds.toArray(new String[0]));
                totalCleaned += expiredIds.size();
                log.info("VIP回收站清理：userId={} 保留期 {} 天，物理清理 {} 条过期笔记",
                        ownerUserId, retention, expiredIds.size());
            }
            catch (Exception e)
            {
                log.error("VIP回收站清理失败：userId={}, ids={}", ownerUserId, expiredIds, e);
            }
            finally
            {
                SecurityContextHolder.clearContext();
            }
        }
        log.info("VIP回收站清理完成：共物理清理 {} 条过期笔记", totalCleaned);
    }

    /**
     * 模拟属主用户的登录上下文（级联删除路径的归属校验按属主用户走）
     */
    private void loginAs(Long userId)
    {
        SysUser user = new SysUser();
        user.setUserId(userId);
        LoginUser loginUser = new LoginUser(userId, null, user, null);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
    }
}
