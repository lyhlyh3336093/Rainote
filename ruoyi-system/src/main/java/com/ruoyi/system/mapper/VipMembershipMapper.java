package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.VipMembership;

/**
 * VIP会员身份Mapper接口
 *
 * @author ruoyi
 * @date 2026-11-04
 */
public interface VipMembershipMapper
{
    /**
     * 根据用户ID查询会员身份
     */
    public VipMembership selectVipMembershipByUserId(Long userId);

    /**
     * 新增会员身份（首次开通）
     */
    public int insertVipMembership(VipMembership vipMembership);

    /**
     * 更新到期时间（续费/手动调整）
     */
    public int updateVipMembershipExpire(VipMembership vipMembership);

    /**
     * 查询会员列表（LEFT JOIN sys_user，含非会员用户可选过滤）
     */
    public List<VipMembership> selectVipMemberList(VipMembership vipMembership);
}
