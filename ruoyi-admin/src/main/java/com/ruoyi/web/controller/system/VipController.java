package com.ruoyi.web.controller.system;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.file.FileUploadUtils;
import com.ruoyi.common.utils.file.FileUtils;
import com.ruoyi.common.utils.file.MimeTypeUtils;
import com.ruoyi.common.utils.uuid.Seq;
import com.ruoyi.system.domain.VipRechargeOrder;
import com.ruoyi.system.domain.VipTier;
import com.ruoyi.system.domain.dto.VipStatusDto;
import com.ruoyi.system.mapper.VipRechargeOrderMapper;
import com.ruoyi.system.mapper.VipTierMapper;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.service.VipService;

/**
 * VIP 会员用户端接口（U5：充值申请提交与状态查询，身份强制绑定当前登录用户）。
 * <p>
 * 安全（KTD12/评审 P1 修复）：
 * <ul>
 *   <li>SecurityConfig 已将 /system/vip/** 前置声明为 authenticated（/system/** 整体 permitAll 之下）</li>
 *   <li>每个 handler 首行绑定 {@link SecurityUtils#getUserId()}，user_id 一律取当前登录用户（防 IDOR）</li>
 *   <li>凭证图片存 profile 之外的私有目录（{@link RuoYiConfig#getVipPrivatePath()}），不经 /profile/** 静态映射；
 *       预览走鉴权下载端点（属主或 admin）</li>
 *   <li>凭证通道每用户每日 10 次（Redis 计数），不写 sys_upload_record 不计容量</li>
 * </ul>
 *
 * @author ruoyi
 * @date 2026-11-04
 */
@RestController
@RequestMapping("/system/vip")
public class VipController extends BaseController
{
    /** 凭证图片大小上限（字节，KTD12） */
    private static final long PROOF_MAX_SIZE = 10L * 1024 * 1024;

    /** 凭证私有路径前缀模板：vip-proof/{userId}/（order 提交校验 proofUrl 属主前缀） */
    private static final String PROOF_URL_PREFIX_TEMPLATE = "vip-proof/%d/";

    @Autowired
    private VipService vipService;

    @Autowired
    private VipRechargeOrderMapper vipRechargeOrderMapper;

    @Autowired
    private VipTierMapper vipTierMapper;

    @Autowired
    private ISysConfigService configService;

    /**
     * VIP 聚合信息：状态/配额 + 启用档位 + 收款码 URL + 当前待审核申请（R5）
     */
    @GetMapping("/info")
    public AjaxResult info()
    {
        Long userId = getUserId();
        VipStatusDto status = vipService.getStatus(userId);
        VipTier query = new VipTier();
        query.setStatus(VipTier.STATUS_ENABLED);
        List<VipTier> tiers = vipTierMapper.selectVipTierList(query);
        VipRechargeOrder pendingOrder = vipRechargeOrderMapper.selectPendingOrderByUserId(userId);

        AjaxResult ajax = AjaxResult.success();
        ajax.put("status", status);
        ajax.put("tiers", tiers);
        ajax.put("wechatQr", configService.selectConfigByKey("vip.pay.wechat.qr"));
        ajax.put("alipayQr", configService.selectConfigByKey("vip.pay.alipay.qr"));
        ajax.put("pendingOrder", pendingOrder);
        return ajax;
    }

    /**
     * 支付凭证上传（R6/KTD12：独立通道，私有目录，不计容量，每日限次）
     *
     * @return proofUrl（私有相对路径，提交订单时回传）
     */
    @PostMapping("/proof")
    public AjaxResult proof(@RequestParam("file") MultipartFile file) throws Exception
    {
        Long userId = getUserId();
        if (file == null || file.isEmpty())
        {
            throw new ServiceException("凭证图片不能为空");
        }
        // 图片白名单 + 10MB 上限（KTD10 参数化断言；先于每日计数——被拒的非法上传不占防滥用额度，评审修复）
        FileUploadUtils.assertAllowed(file, MimeTypeUtils.IMAGE_EXTENSION, PROOF_MAX_SIZE);
        // 每用户每日次数限制（防滥用：免费图床/磁盘填充向量）
        vipService.proofDailyTryAcquire(userId);

        // 落盘私有目录：{vipPrivatePath}/vip-proof/{userId}/{datePath}/{name}_{seq}.{ext}
        String relativePath = buildProofRelativePath(userId, file);
        File dest = new File(RuoYiConfig.getVipPrivatePath(), relativePath);
        if (!dest.getParentFile().exists())
        {
            dest.getParentFile().mkdirs();
        }
        file.transferTo(Paths.get(dest.getAbsolutePath()));

        AjaxResult ajax = AjaxResult.success();
        ajax.put("proofUrl", relativePath);
        return ajax;
    }

    /**
     * 凭证图片鉴权下载（R6/KTD12：属主或 admin，替代静态 URL 预览）
     */
    @GetMapping("/proof/download")
    public void proofDownload(@RequestParam("proofUrl") String proofUrl, HttpServletResponse response) throws Exception
    {
        Long userId = getUserId();
        // 先 resolve+normalize 得到真实目标路径（../ 与绝对路径在规范化后再校验，防穿越绕过属主检查）
        java.nio.file.Path target = resolveProofPath(proofUrl);
        boolean isAdmin = SecurityUtils.isAdmin(userId);
        // 属主校验（规范化后）：非 admin 仅限本人 vip-proof/{userId}/ 目录内
        if (!isAdmin)
        {
            java.nio.file.Path ownerRoot = proofOwnerRoot(userId);
            if (!target.startsWith(ownerRoot))
            {
                throw new ServiceException("无权访问该凭证");
            }
        }
        File file = target.toFile();
        if (!file.exists() || file.isDirectory())
        {
            throw new ServiceException("凭证不存在");
        }
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition", "attachment; filename=" + file.getName());
        FileUtils.writeBytes(file.getAbsolutePath(), response.getOutputStream());
    }

    /**
     * 凭证路径解析（评审修复：规范化先于属主校验）——resolve+normalize 后必须仍在私有目录内，
     * 拒绝 ../ 穿越、绝对路径与逃逸私有根的一切形式。
     */
    private java.nio.file.Path resolveProofPath(String proofUrl)
    {
        if (StringUtils.isEmpty(proofUrl))
        {
            throw new ServiceException("凭证路径不能为空");
        }
        java.nio.file.Path root = java.nio.file.Paths.get(RuoYiConfig.getVipPrivatePath()).normalize();
        java.nio.file.Path target = root.resolve(proofUrl).normalize();
        if (!target.startsWith(root))
        {
            throw new ServiceException("非法路径");
        }
        return target;
    }

    /** 属主凭证目录（规范化）：{vipPrivatePath}/vip-proof/{userId} */
    private java.nio.file.Path proofOwnerRoot(Long userId)
    {
        return java.nio.file.Paths.get(RuoYiConfig.getVipPrivatePath())
                .resolve("vip-proof").resolve(String.valueOf(userId)).normalize();
    }

    /**
     * 提交充值申请（R7：user_id 取当前用户；tier 启用校验；proofUrl 属主前缀校验；
     * 唯一待审核——KTD11 可空 pending 唯一键兜底并发）
     */
    @PostMapping("/order")
    public AjaxResult order(@RequestBody VipRechargeOrder body)
    {
        Long userId = getUserId();
        // 档位校验
        if (body.getTierId() == null)
        {
            throw new ServiceException("请选择充值档位");
        }
        VipTier tier = vipTierMapper.selectVipTierById(body.getTierId());
        if (tier == null || !VipTier.STATUS_ENABLED.equals(tier.getStatus()))
        {
            throw new ServiceException("档位不存在或已停用");
        }
        // 凭证路径属主校验（评审修复：规范化后按属主目录校验，防伪造/穿越引用他人凭证）
        java.nio.file.Path proofTarget;
        try
        {
            proofTarget = resolveProofPath(body.getProofUrl());
        }
        catch (ServiceException e)
        {
            throw new ServiceException("请先上传支付凭证");
        }
        if (!proofTarget.startsWith(proofOwnerRoot(userId)) || !java.nio.file.Files.exists(proofTarget))
        {
            throw new ServiceException("请先上传支付凭证");
        }
        // 唯一待审核（应用层前置检查；并发由 UNIQUE(user_id, pending) 兜底）
        if (vipRechargeOrderMapper.selectPendingOrderByUserId(userId) != null)
        {
            throw new ServiceException("已有待审核的充值申请，请等待管理员处理");
        }

        VipRechargeOrder order = new VipRechargeOrder();
        order.setUserId(userId);
        order.setTierId(tier.getId());
        order.setAmount(tier.getPrice());
        order.setPayChannel(body.getPayChannel());
        order.setProofUrl(body.getProofUrl());
        order.setUserRemark(body.getUserRemark());
        order.setStatus(VipRechargeOrder.STATUS_PENDING);
        order.setPending(1);
        vipRechargeOrderMapper.insertVipRechargeOrder(order);
        return success(order);
    }

    /**
     * 我的申请列表（R7：强制按当前 userId 过滤，IDOR 防护）
     */
    @GetMapping("/order/list")
    public TableDataInfo orderList()
    {
        Long userId = getUserId();
        startPage();
        VipRechargeOrder query = new VipRechargeOrder();
        query.setUserId(userId);
        List<VipRechargeOrder> list = vipRechargeOrderMapper.selectVipRechargeOrderList(query);
        return getDataTable(list);
    }

    /**
     * 生成凭证私有相对路径：vip-proof/{userId}/{datePath}/{basename}_{seq}.{ext}
     */
    private String buildProofRelativePath(Long userId, MultipartFile file)
    {
        String extension = FileUploadUtils.getExtension(file);
        String baseName = FilenameUtils.getBaseName(file.getOriginalFilename());
        return StringUtils.format("vip-proof/{}/{}_{}.{}", userId, DateUtils.datePath(),
                baseName, Seq.getId(Seq.uploadSeqType), extension);
    }
}
