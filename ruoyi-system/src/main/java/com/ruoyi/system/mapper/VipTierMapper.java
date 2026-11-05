package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.VipTier;

/**
 * VIP时长档位Mapper接口
 *
 * @author ruoyi
 * @date 2026-11-04
 */
public interface VipTierMapper
{
    /**
     * 根据档位ID查询
     */
    public VipTier selectVipTierById(Long id);

    /**
     * 查询档位列表（status 过滤可选）
     */
    public List<VipTier> selectVipTierList(VipTier vipTier);

    /**
     * 新增档位
     */
    public int insertVipTier(VipTier vipTier);

    /**
     * 修改档位
     */
    public int updateVipTier(VipTier vipTier);

    /**
     * 删除档位
     */
    public int deleteVipTierById(Long id);
}
