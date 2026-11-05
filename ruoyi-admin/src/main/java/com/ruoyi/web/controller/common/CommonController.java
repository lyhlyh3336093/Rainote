package com.ruoyi.web.controller.common;

import java.util.ArrayList;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.file.FileUploadUtils;
import com.ruoyi.common.utils.file.FileUtils;
import com.ruoyi.common.utils.file.MimeTypeUtils;
import com.ruoyi.framework.config.ServerConfig;
import com.ruoyi.system.service.BackupLockUtil;
import com.ruoyi.system.service.VipService;

/**
 * 通用请求处理
 * 
 * @author ruoyi
 */
@RestController
@RequestMapping("/common")
public class CommonController
{
    private static final Logger log = LoggerFactory.getLogger(CommonController.class);

    @Autowired
    private ServerConfig serverConfig;

    @Autowired
    private VipService vipService;

    private static final String FILE_DELIMETER = ",";

    /**
     * 通用下载请求
     * 
     * @param fileName 文件名称
     * @param delete 是否删除
     */
    @GetMapping("/download")
    public void fileDownload(String fileName, Boolean delete, HttpServletResponse response, HttpServletRequest request)
    {
        try
        {
            if (!FileUtils.checkAllowDownload(fileName))
            {
                throw new Exception(StringUtils.format("文件名称({})非法，不允许下载。 ", fileName));
            }
            String realFileName = System.currentTimeMillis() + fileName.substring(fileName.indexOf("_") + 1);
            String filePath = RuoYiConfig.getDownloadPath() + fileName;

            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            FileUtils.setAttachmentResponseHeader(response, realFileName);
            FileUtils.writeBytes(filePath, response.getOutputStream());
            if (delete)
            {
                FileUtils.deleteFile(filePath);
            }
        }
        catch (Exception e)
        {
            log.error("下载文件失败", e);
        }
    }

    /**
     * 通用上传请求（单个）—— VIP 限额闸门（U3/R9）：单文件+总容量按身份断言，成功后写入容量记录
     */
    @PostMapping("/upload")
    public AjaxResult uploadFile(MultipartFile file) throws Exception
    {
        // 备份/还原写锁检查：进行中则拒绝写入 profile 上传目录（KTD3）
        BackupLockUtil.checkNotLocked();
        Long userId = SecurityUtils.getUserId();
        // VIP 限额断言（admin 豁免；普通超限 602，VIP 超限 603）
        vipService.assertUploadQuota(userId, file.getSize(), 0);
        long fileMaxBytes = vipService.getStatus(userId).getFileMaxBytes();
        try
        {
            // 上传文件路径
            String filePath = RuoYiConfig.getUploadPath();
            // 上传并返回新文件名称（KTD10 参数化上限：按身份传入）
            String fileName = FileUploadUtils.upload(filePath, file, MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION, fileMaxBytes);
            // 写入容量记录（KTD3：头像与通用上传均计入；记录失败则删除已落盘文件防漏计）
            try
            {
                vipService.recordUpload(userId, file.getOriginalFilename(), fileName, file.getSize(), "common");
            }
            catch (Exception recordEx)
            {
                FileUtils.deleteFile(RuoYiConfig.getProfile() + StringUtils.substringAfter(fileName, Constants.RESOURCE_PREFIX));
                throw recordEx;
            }
            String url = serverConfig.getUrl() + fileName;
            AjaxResult ajax = AjaxResult.success();
            ajax.put("url", url);
            ajax.put("fileName", fileName);
            ajax.put("newFileName", FileUtils.getName(fileName));
            ajax.put("originalFilename", file.getOriginalFilename());
            return ajax;
        }
        catch (Exception e)
        {
            return AjaxResult.error(e.getMessage());
        }
    }

    /**
     * 通用上传请求（多个）—— VIP 限额闸门（U3/R9/AE-P2）：整批求和一次容量断言（原子拒绝，不出现"传了一半"）
     */
    @PostMapping("/uploads")
    public AjaxResult uploadFiles(List<MultipartFile> files) throws Exception
    {
        // 备份/还原写锁检查：进行中则拒绝写入 profile 上传目录（KTD3）
        BackupLockUtil.checkNotLocked();
        Long userId = SecurityUtils.getUserId();
        long fileMaxBytes = vipService.getStatus(userId).getFileMaxBytes();
        // 单文件上限前置整批校验（评审修复：任一文件超限在落盘前拒绝，避免"传了一半"违反 AE-P2 原子口径）
        for (MultipartFile file : files)
        {
            if (file.getSize() > fileMaxBytes)
            {
                // 复用单文件断言的身份分支语义（普通 602 引导升级 / VIP 603 纯提示）
                vipService.assertUploadQuota(userId, file.getSize(), 0);
            }
        }
        // 整批容量断言：对全部文件求和（additionalBytes=除首个外之和，首文件作 incoming 参与断言）
        long totalBytes = 0;
        for (MultipartFile file : files)
        {
            totalBytes += file.getSize();
        }
        if (!files.isEmpty())
        {
            vipService.assertUploadQuota(userId, files.get(0).getSize(), totalBytes - files.get(0).getSize());
        }
        // 整批预校验（评审修复：扩展名/文件名长度在落盘前拒绝，配合容量断言实现全有或全无的 AE-P2 原子口径）
        for (MultipartFile file : files)
        {
            FileUploadUtils.assertAllowed(file, MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION, fileMaxBytes);
        }
        // 本批已落盘路径（任一后续失败时整批回滚删除）
        List<String> landedFiles = new ArrayList<String>();
        try
        {
            // 上传文件路径
            String filePath = RuoYiConfig.getUploadPath();
            List<String> urls = new ArrayList<String>();
            List<String> fileNames = new ArrayList<String>();
            List<String> newFileNames = new ArrayList<String>();
            List<String> originalFilenames = new ArrayList<String>();
            for (MultipartFile file : files)
            {
                // 上传并返回新文件名称（KTD10 参数化上限）
                String fileName = FileUploadUtils.upload(filePath, file, MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION, fileMaxBytes);
                landedFiles.add(fileName);
                String url = serverConfig.getUrl() + fileName;
                urls.add(url);
                fileNames.add(fileName);
                newFileNames.add(FileUtils.getName(fileName));
                originalFilenames.add(file.getOriginalFilename());
                // 写入容量记录（失败则整批回滚已落盘文件防漏计，评审修复：由单文件补偿升级为整批补偿）
                try
                {
                    vipService.recordUpload(userId, file.getOriginalFilename(), fileName, file.getSize(), "common");
                }
                catch (Exception recordEx)
                {
                    rollbackLandedFiles(landedFiles);
                    throw recordEx;
                }
            }
            AjaxResult ajax = AjaxResult.success();
            ajax.put("urls", StringUtils.join(urls, FILE_DELIMETER));
            ajax.put("fileNames", StringUtils.join(fileNames, FILE_DELIMETER));
            ajax.put("newFileNames", StringUtils.join(newFileNames, FILE_DELIMETER));
            ajax.put("originalFilenames", StringUtils.join(originalFilenames, FILE_DELIMETER));
            return ajax;
        }
        catch (Exception e)
        {
            // 任一文件失败：整批回滚已落盘产物（AE-P2 不出现"传了一半"）
            rollbackLandedFiles(landedFiles);
            return AjaxResult.error(e.getMessage());
        }
    }

    /** 删除本批已落盘文件（best-effort，AE-P2 整批原子回滚） */
    private void rollbackLandedFiles(List<String> landedFiles)
    {
        for (String fileName : landedFiles)
        {
            try
            {
                FileUtils.deleteFile(RuoYiConfig.getProfile() + StringUtils.substringAfter(fileName, Constants.RESOURCE_PREFIX));
            }
            catch (Exception ignore)
            {
                // 回滚尽力而为：残留文件可由管理员对账清理
            }
        }
    }

    /**
     * 本地资源通用下载
     */
    @GetMapping("/download/resource")
    public void resourceDownload(String resource, HttpServletRequest request, HttpServletResponse response)
            throws Exception
    {
        try
        {
            if (!FileUtils.checkAllowDownload(resource))
            {
                throw new Exception(StringUtils.format("资源文件({})非法，不允许下载。 ", resource));
            }
            // 本地资源路径
            String localPath = RuoYiConfig.getProfile();
            // 数据库资源地址
            String downloadPath = localPath + StringUtils.substringAfter(resource, Constants.RESOURCE_PREFIX);
            // 下载名称
            String downloadName = StringUtils.substringAfterLast(downloadPath, "/");
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            FileUtils.setAttachmentResponseHeader(response, downloadName);
            FileUtils.writeBytes(downloadPath, response.getOutputStream());
        }
        catch (Exception e)
        {
            log.error("下载文件失败", e);
        }
    }
}
