package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.VipRechargeOrder;

/**
 * VIP充值申请订单Mapper接口
 *
 * @author ruoyi
 * @date 2026-11-04
 */
public interface VipRechargeOrderMapper
{
    /**
     * 新增充值申请（status=0, pending=1；唯一键 uk_user_pending 防并发重复待审）
     */
    public int insertVipRechargeOrder(VipRechargeOrder order);

    /**
     * 根据订单ID查询（联表用户名/档位信息）
     */
    public VipRechargeOrder selectVipRechargeOrderById(Long id);

    /**
     * 查询订单列表（全状态，联表用户名/档位，支持 status/userId 过滤）
     */
    public List<VipRechargeOrder> selectVipRechargeOrderList(VipRechargeOrder order);

    /**
     * 查询用户当前待审核订单
     */
    public VipRechargeOrder selectPendingOrderByUserId(Long userId);

    /**
     * 审核条件更新（KTD11）：仅当 status='0' AND pending=1 时生效，
     * 返回受影响行数（1=审核成功，0=已被并发审核）
     */
    public int auditVipRechargeOrder(VipRechargeOrder order);
}
