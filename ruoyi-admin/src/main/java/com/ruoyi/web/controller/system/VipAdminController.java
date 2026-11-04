package com.ruoyi.web.controller.system;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.file.FileUploadUtils;
import com.ruoyi.common.utils.file.MimeTypeUtils;
import com.ruoyi.system.domain.VipMembership;
import com.ruoyi.system.domain.VipRechargeOrder;
import com.ruoyi.system.domain.VipTier;
import com.ruoyi.system.mapper.VipMembershipMapper;
import com.ruoyi.system.mapper.VipRechargeOrderMapper;
import com.ruoyi.system.mapper.VipTierMapper;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.service.VipService;

/**
 * VIP 管理端接口（U6：审核/会员管理/档位与收款码配置）。
 * <p>
 * 审核（KTD11 条件更新防并发双审）：{@code UPDATE ... WHERE id=? AND status='0' AND pending=1}，
 * 受影响行数=1 才执行 openVip/驳回——两管理员并发审核同一订单仅一方生效，时长不叠加。
 * 凭证孤儿清理（KTD12）：订单终态后凭证文件若无其他待审订单引用则删除。
 *
 * @author ruoyi
 * @date 2026-11-04
 */
@RestController
@RequestMapping("/system/vip/admin")
public class VipAdminController extends BaseController
{
    @Autowired
    private VipRechargeOrderMapper orderMapper;

    @Autowired
    private VipTierMapper tierMapper;

    @Autowired
    private VipMembershipMapper membershipMapper;

    @Autowired
    private VipService vipService;

    @Autowired
    private ISysConfigService configService;

    // ==================== 充值订单审核 ====================

    /**
     * 订单列表（全状态，status/userName 过滤——承载 origin R25 历史申请记录）
     */
    @PreAuthorize("@ss.hasPermi('system:vip:order:list')")
    @GetMapping("/order/list")
    public TableDataInfo orderList(VipRechargeOrder query)
    {
        startPage();
        List<VipRechargeOrder> list = orderMapper.selectVipRechargeOrderList(query);
        return getDataTable(list);
    }

    /**
     * 订单详情
     */
    @PreAuthorize("@ss.hasPermi('system:vip:order:query')")
    @GetMapping("/order/{orderId}")
    public AjaxResult orderInfo(@PathVariable("orderId") Long orderId)
    {
        return success(orderMapper.selectVipRechargeOrderById(orderId));
    }

    /**
     * 审核（R8：approve → openVip 叠计+失效缓存；reject → 留言必填）
     *
     * @param body {orderId, approve(boolean), adminRemark}
     */
    @PreAuthorize("@ss.hasPermi('system:vip:order:audit')")
    @Log(title = "VIP充值审核", businessType = BusinessType.UPDATE)
    @PutMapping("/order/audit")
    public AjaxResult audit(@RequestBody VipRechargeOrder body)
    {
        if (body.getId() == null || body.getApprove() == null)
        {
            throw new ServiceException("缺少订单ID或审核决定");
        }
        // 驳回留言必填（用户侧可见驳回原因，Covers AE5）
        if (Boolean.FALSE.equals(body.getApprove()) && StringUtils.isEmpty(body.getAdminRemark()))
        {
            throw new ServiceException("驳回时必须填写留言说明原因");
        }
        VipRechargeOrder order = orderMapper.selectVipRechargeOrderById(body.getId());
        if (order == null)
        {
            throw new ServiceException("订单不存在");
        }

        // KTD11 条件更新：仅当仍为待审核时生效（受影响=1 才继续，双审并发仅一方成功）
        VipRechargeOrder audit = new VipRechargeOrder();
        audit.setId(body.getId());
        audit.setStatus(Boolean.TRUE.equals(body.getApprove())
                ? VipRechargeOrder.STATUS_APPROVED : VipRechargeOrder.STATUS_REJECTED);
        audit.setAdminRemark(body.getAdminRemark());
        audit.setAuditedBy(getUsername());
        int updated = orderMapper.auditVipRechargeOrder(audit);
        if (updated != 1)
        {
            throw new ServiceException("订单已被并发审核，请刷新后重试");
        }

        if (Boolean.TRUE.equals(body.getApprove()))
        {
            // 开通：叠加续费 + 失效缓存（用户无需重登，Covers AE4/F1）
            VipTier tier = tierMapper.selectVipTierById(order.getTierId());
            vipService.openVip(order.getUserId(), tier.getDays(), getUsername());
        }
        // 凭证孤儿清理（KTD12）：订单终态后，若无其他待审订单引用该凭证则删除文件
        cleanupOrphanProof(order.getProofUrl(), body.getId());
        return success();
    }

    /**
     * 删除已无待审订单引用的凭证文件（best-effort）
     */
    private void cleanupOrphanProof(String proofUrl, Long excludeOrderId)
    {
        try
        {
            if (StringUtils.isEmpty(proofUrl))
            {
                return;
            }
            List<VipRechargeOrder> pendingList = orderMapper.selectVipRechargeOrderList(new VipRechargeOrder());
            boolean stillReferenced = false;
            for (VipRechargeOrder pending : pendingList)
            {
                if (VipRechargeOrder.STATUS_PENDING.equals(pending.getStatus())
                        && proofUrl.equals(pending.getProofUrl())
                        && !pending.getId().equals(excludeOrderId))
                {
                    stillReferenced = true;
                    break;
                }
            }
            if (!stillReferenced)
            {
                File file = new File(RuoYiConfig.getVipPrivatePath(), proofUrl);
                if (file.exists() && file.toPath().normalize()
                        .startsWith(new File(RuoYiConfig.getVipPrivatePath()).toPath().normalize()))
                {
                    file.delete();
                }
            }
        }
        catch (Exception e)
        {
            logger.warn("凭证孤儿清理失败（不阻断审核）: {}", proofUrl, e);
        }
    }

    // ==================== 会员管理 ====================

    /**
     * 会员列表（LEFT JOIN sys_user，含非会员；origin R25）
     */
    @PreAuthorize("@ss.hasPermi('system:vip:member:list')")
    @GetMapping("/member/list")
    public TableDataInfo memberList(VipMembership query)
    {
        startPage();
        List<VipMembership> list = membershipMapper.selectVipMemberList(query);
        return getDataTable(list);
    }

    /**
     * 手动调整会员到期时间（R25：可设为过去时间即时降级，调后失效缓存）
     */
    @PreAuthorize("@ss.hasPermi('system:vip:member:adjust')")
    @Log(title = "VIP会员调整", businessType = BusinessType.UPDATE)
    @PutMapping("/member/adjust")
    public AjaxResult adjust(@RequestBody VipMembership body)
    {
        if (body.getUserId() == null || body.getExpireTime() == null)
        {
            throw new ServiceException("缺少用户ID或到期时间");
        }
        vipService.adjustExpire(body.getUserId(), body.getExpireTime(), getUsername());
        return success();
    }

    // ==================== 档位 CRUD ====================

    /**
     * 档位列表
     */
    @PreAuthorize("@ss.hasPermi('system:vip:tier:list')")
    @GetMapping("/tier/list")
    public TableDataInfo tierList(VipTier query)
    {
        startPage();
        List<VipTier> list = tierMapper.selectVipTierList(query);
        return getDataTable(list);
    }

    /**
     * 新增档位
     */
    @PreAuthorize("@ss.hasPermi('system:vip:tier:add')")
    @Log(title = "VIP档位", businessType = BusinessType.INSERT)
    @PostMapping("/tier")
    public AjaxResult tierAdd(@RequestBody VipTier vipTier)
    {
        validateTier(vipTier);
        return toAjax(tierMapper.insertVipTier(vipTier));
    }

    /**
     * 修改档位
     */
    @PreAuthorize("@ss.hasPermi('system:vip:tier:edit')")
    @Log(title = "VIP档位", businessType = BusinessType.UPDATE)
    @PutMapping("/tier")
    public AjaxResult tierEdit(@RequestBody VipTier vipTier)
    {
        if (vipTier.getId() == null)
        {
            throw new ServiceException("缺少档位ID");
        }
        validateTier(vipTier);
        return toAjax(tierMapper.updateVipTier(vipTier));
    }

    /**
     * 删除档位
     */
    @PreAuthorize("@ss.hasPermi('system:vip:tier:remove')")
    @Log(title = "VIP档位", businessType = BusinessType.DELETE)
    @DeleteMapping("/tier/{tierId}")
    public AjaxResult tierRemove(@PathVariable("tierId") Long tierId)
    {
        return toAjax(tierMapper.deleteVipTierById(tierId));
    }

    private void validateTier(VipTier vipTier)
    {
        if (StringUtils.isEmpty(vipTier.getName()))
        {
            throw new ServiceException("档位名称不能为空");
        }
        if (vipTier.getDays() == null || vipTier.getDays() <= 0)
        {
            throw new ServiceException("档位时长必须为正整数（天）");
        }
        if (vipTier.getPrice() == null || vipTier.getPrice().signum() < 0)
        {
            throw new ServiceException("档位价格不能为负数");
        }
    }

    // ==================== 收款码配置 ====================

    /**
     * 收款码查询（KTD9：sys_config 存 URL）
     */
    @PreAuthorize("@ss.hasPermi('system:vip:tier:list')")
    @GetMapping("/qrcode")
    public AjaxResult qrcodeInfo()
    {
        AjaxResult ajax = AjaxResult.success();
        ajax.put("wechatQr", configService.selectConfigByKey("vip.pay.wechat.qr"));
        ajax.put("alipayQr", configService.selectConfigByKey("vip.pay.alipay.qr"));
        return ajax;
    }

    /**
     * 收款码上传（R5/KTD9：存 profile/vip/qrcode/{channel}，回写 sys_config；
     * 收款码为公开营销物料，留在 profile 静态目录随整机备份，KTD8）
     */
    @PreAuthorize("@ss.hasPermi('system:vip:tier:qrcode')")
    @Log(title = "VIP收款码", businessType = BusinessType.UPDATE)
    @PostMapping("/qrcode")
    public AjaxResult qrcodeUpload(@RequestParam("channel") String channel,
            @RequestParam("file") MultipartFile file) throws Exception
    {
        if (!"wechat".equals(channel) && !"alipay".equals(channel))
        {
            throw new ServiceException("channel 仅支持 wechat/alipay");
        }
        if (file == null || file.isEmpty())
        {
            throw new ServiceException("收款码图片不能为空");
        }
        // 图片白名单 + 默认 50MB 上限（收款码图片远小于此）
        FileUploadUtils.assertAllowed(file, MimeTypeUtils.IMAGE_EXTENSION);

        // 存 profile/vip/qrcode/{channel}/（随整机备份 FILE_DIRS 含 vip，KTD8）
        String baseDir = RuoYiConfig.getProfile() + "/vip/qrcode/" + channel;
        String fileName = FileUploadUtils.upload(baseDir, file, MimeTypeUtils.IMAGE_EXTENSION);
        // 回写 sys_config（KTD9）
        String configKey = "wechat".equals(channel) ? "vip.pay.wechat.qr" : "vip.pay.alipay.qr";
        int updated = updateConfigValue(configKey, fileName);
        AjaxResult ajax = AjaxResult.success();
        ajax.put("url", fileName);
        ajax.put("configUpdated", updated > 0);
        return ajax;
    }

    /**
     * 更新 sys_config 键值（configUpdate 走 service 层带缓存刷新；不存在则插入）
     */
    private int updateConfigValue(String configKey, String value)
    {
        com.ruoyi.system.domain.SysConfig query = new com.ruoyi.system.domain.SysConfig();
        query.setConfigKey(configKey);
        List<com.ruoyi.system.domain.SysConfig> existing =
                configService.selectConfigList(query);
        if (!existing.isEmpty())
        {
            com.ruoyi.system.domain.SysConfig config = existing.get(0);
            config.setConfigValue(value);
            return configService.updateConfig(config);
        }
        com.ruoyi.system.domain.SysConfig config = new com.ruoyi.system.domain.SysConfig();
        config.setConfigName("VIP收款码");
        config.setConfigKey(configKey);
        config.setConfigValue(value);
        config.setConfigType("Y");
        config.setRemark("VIP收款码图片地址");
        return configService.insertConfig(config);
    }
}
