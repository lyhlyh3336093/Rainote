package com.ruoyi.web.controller.system;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.annotation.RateLimiter;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.enums.LimitType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.config.filter.BackupAuthFilter;
import com.ruoyi.system.domain.dto.RestoreResult;
import com.ruoyi.system.service.IBackupRestoreService;
import com.ruoyi.system.service.INoteNoteService;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NoteNoteController#dataBak}/{@link NoteNoteController#restore} 单元测试（U3）。
 * <p>
 * 覆盖：非 admin 双端点拒绝（匿名=401 ServiceException、登录非 admin=管理员兜底拦截，
 * 均零副作用）、admin 备份 200 文件流（Content-Disposition/no-store/字节一致）、
 * 还原两步协议（confirmed 传参与预览不写库）、注解契约（@PreAuthorize/@Log/@RateLimiter）。
 *
 * @author ruoyi
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NoteNoteControllerBackupTest
{
    private static final Long ADMIN_ID = 1L;

    private static final Long NORMAL_USER_ID = 70L;

    @Mock
    private INoteNoteService noteNoteService;

    @Mock
    private IBackupRestoreService backupRestoreService;

    @InjectMocks
    private NoteNoteController controller;

    private MockMultipartFile backupFile;

    @BeforeEach
    void setUp()
    {
        backupFile = new MockMultipartFile("file", "backup.zip", "application/zip",
                new byte[] {0x50, 0x4B, 0x03, 0x04});
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
    }

    /** 设置当前登录用户（userId=null 表示匿名，不向 SecurityContext 写入认证信息） */
    private void loginAs(Long userId)
    {
        if (userId == null)
        {
            SecurityContextHolder.getContext().setAuthentication(null);
            return;
        }
        LoginUser loginUser = new LoginUser(userId, null, null, null);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    // ==================================================================
    // 场景 1：非 admin 拒绝（零副作用）
    // ==================================================================

    @Test
    void dataBak_nonAdmin_rejectedWithServiceException_noSideEffect()
    {
        loginAs(NORMAL_USER_ID);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.dataBak(new MockHttpServletResponse()));

        assertEquals("只有管理员才可执行此操作", ex.getMessage());
        verify(backupRestoreService, times(0)).backup();
    }

    @Test
    void dataBak_anonymous_rejectedWith401ServiceException()
    {
        loginAs(null);

        // 匿名请求实际语义：SecurityUtils.getUserId 抛 401 ServiceException（HTTP 200 body）
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.dataBak(new MockHttpServletResponse()));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getCode());
        verify(backupRestoreService, times(0)).backup();
    }

    @Test
    void restore_nonAdmin_rejectedWithServiceException_noSideEffect()
    {
        loginAs(NORMAL_USER_ID);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.restore(backupFile, false));

        assertEquals("只有管理员才可执行此操作", ex.getMessage());
        verify(backupRestoreService, times(0)).restore(any(MultipartFile.class), anyBoolean());
    }

    // ==================================================================
    // 场景 2：admin 备份 200 文件流
    // ==================================================================

    @Test
    void dataBak_admin_returnsFileStreamWithNoStoreHeader() throws Exception
    {
        loginAs(ADMIN_ID);
        File temp = createTempBackupFile();
        when(backupRestoreService.backup()).thenReturn(temp);

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.dataBak(response);

        // 下载响应头契约（CommonController 模式 + KTD5 no-store）
        assertEquals("application/octet-stream", response.getContentType());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertNotNull(response.getHeaderValue("Content-Disposition"));
        assertTrue(response.getHeaderValue("Content-Disposition").toString().contains(temp.getName()));
        // 文件流字节一致
        assertArrayEquals(Files.readAllBytes(temp.toPath()), response.getContentAsByteArray());
        verify(backupRestoreService, times(1)).backup();
    }

    private File createTempBackupFile() throws IOException
    {
        File temp = Files.createTempFile("backup-test", ".zip").toFile();
        Files.write(temp.toPath(), new byte[] {0x50, 0x4B, 0x03, 0x04, 0x05, 0x06});
        temp.deleteOnExit();
        return temp;
    }

    // ==================================================================
    // 场景 3：还原两步协议（confirmed 传参；预览不写库由 U2 契约承载）
    // ==================================================================

    @Test
    void restore_previewStep_passesConfirmedFalseAndReturnsSummary() throws Exception
    {
        loginAs(ADMIN_ID);
        RestoreResult preview = sampleResult(false, false);
        when(backupRestoreService.restore(any(MultipartFile.class), eq(false))).thenReturn(preview);

        AjaxResult result = controller.restore(backupFile, false);

        assertEquals(200, result.get("code"));
        // 预览摘要原样透传（executed=false：未写库）
        assertSame(preview, result.get("data"));
        assertFalse(((RestoreResult) result.get("data")).isExecuted());
        verify(backupRestoreService, times(1)).restore(any(MultipartFile.class), eq(false));
        verify(backupRestoreService, times(0)).restore(any(MultipartFile.class), eq(true));
    }

    @Test
    void restore_executeStep_passesConfirmedTrueAndReturnsResult() throws Exception
    {
        loginAs(ADMIN_ID);
        RestoreResult executed = sampleResult(true, true);
        when(backupRestoreService.restore(any(MultipartFile.class), eq(true))).thenReturn(executed);

        AjaxResult result = controller.restore(backupFile, true);

        assertEquals(200, result.get("code"));
        RestoreResult data = (RestoreResult) result.get("data");
        assertTrue(data.isExecuted());
        assertTrue(data.isReloginRequired());
        verify(backupRestoreService, times(1)).restore(any(MultipartFile.class), eq(true));
        verify(backupRestoreService, times(0)).restore(any(MultipartFile.class), eq(false));
    }

    private RestoreResult sampleResult(boolean executed, boolean reloginRequired)
    {
        RestoreResult result = new RestoreResult();
        result.setTableCount(3);
        result.setRecordCount(10);
        result.setFileCount(5);
        result.setUserCount(1);
        result.setCredentialChanged(true);
        result.setExecuted(executed);
        result.setReloginRequired(reloginRequired);
        return result;
    }

    // ==================================================================
    // 场景 4：注解存在性与参数断言
    // ==================================================================

    @Test
    void dataBak_annotations_preAuthorizeLogAndPathConfigured() throws NoSuchMethodException
    {
        Method method = NoteNoteController.class.getMethod("dataBak",
                javax.servlet.http.HttpServletResponse.class);

        // 鉴权双层：@PreAuthorize 权限注解（沿用 removeAll 先例）+ 控制器内 userId==1 兜底
        org.springframework.security.access.prepost.PreAuthorize preAuthorize =
                method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
        assertNotNull(preAuthorize);
        assertEquals("@ss.hasPermi('note:note:delete')", preAuthorize.value());

        // @Log：下载文件流不进请求参数记录
        Log logAnnotation = method.getAnnotation(Log.class);
        assertNotNull(logAnnotation);
        assertEquals("数据备份", logAnnotation.title());
        assertEquals(BusinessType.EXPORT, logAnnotation.businessType());
        assertFalse(logAnnotation.isSaveRequestData());

        org.springframework.web.bind.annotation.GetMapping getMapping =
                method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class);
        assertNotNull(getMapping);
        assertArrayEquals(new String[] {"/dataBak"}, getMapping.value());
    }

    @Test
    void restore_annotations_preAuthorizeLogRateLimiterAndPathConfigured() throws NoSuchMethodException
    {
        Method method = NoteNoteController.class.getMethod("restore",
                MultipartFile.class, boolean.class);

        org.springframework.security.access.prepost.PreAuthorize preAuthorize =
                method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
        assertNotNull(preAuthorize);
        assertEquals("@ss.hasPermi('note:note:delete')", preAuthorize.value());

        // 响应含 sys_user 差异摘要，请求为 multipart 文件：均不进 sys_oper_log
        Log logAnnotation = method.getAnnotation(Log.class);
        assertNotNull(logAnnotation);
        assertEquals("数据还原", logAnnotation.title());
        assertEquals(BusinessType.IMPORT, logAnnotation.businessType());
        assertFalse(logAnnotation.isSaveRequestData());
        assertFalse(logAnnotation.isSaveResponseData());

        // 限流：IP 键（匿名场景 USER 键失效），参数对齐导入端点 60s/10 次先例
        RateLimiter rateLimiter = method.getAnnotation(RateLimiter.class);
        assertNotNull(rateLimiter);
        assertEquals(60, rateLimiter.time());
        assertEquals(10, rateLimiter.count());
        assertEquals(LimitType.IP, rateLimiter.limitType());

        org.springframework.web.bind.annotation.PostMapping postMapping =
                method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
        assertNotNull(postMapping);
        assertArrayEquals(new String[] {"/restore"}, postMapping.value());
    }

    /**
     * 前置 Filter 契约旁证：匿名 POST 大请求体防线由 {@link BackupAuthFilter} 承载
     * （本测试类不做 Filter 行为断言，见 BackupAuthFilterTest），此处仅保留引用契约锚点。
     */
    @Test
    void backupAuthFilter_preBodyDefenseClassExists()
    {
        assertEquals(1024L * 1024L, BackupAuthFilter.MAX_ANONYMOUS_BODY_BYTES);
    }
}
