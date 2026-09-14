package com.ruoyi.web.controller.system;

import java.lang.reflect.Method;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.annotation.RateLimiter;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.enums.LimitType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.agent.security.AgentOwnershipChecker;
import com.ruoyi.system.domain.NoteDwtable;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult;
import com.ruoyi.system.service.INoteDwtableService;
import com.ruoyi.system.service.INoteDwtableExcelImportService;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NoteDwtableController#precheckExcelImport} 单元测试。
 * <p>
 * 覆盖预检成功响应结构、归属校验失败、noteId 不匹配、空文件、
 * 注解契约（R24 限流 USER 60s/10 次、R25 预检清单禁入 sys_oper_log）、必传参数契约。
 *
 * @author ruoyi
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NoteDwtableControllerExcelImportTest
{
    private static final Long NOTE_ID = 50L;

    private static final Long DWTABLE_ID = 60L;

    private static final Long USER_ID = 70L;

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Mock
    private INoteDwtableService noteDwtableService;

    @Mock
    private AgentOwnershipChecker agentOwnershipChecker;

    @Mock
    private INoteDwtableExcelImportService noteDwtableExcelImportService;

    @InjectMocks
    private NoteDwtableController controller;

    private ExcelImportPrecheckResult sampleResult;

    @BeforeEach
    void setUp()
    {
        LoginUser loginUser = new LoginUser(USER_ID, null, null, null);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
        NoteDwtable dwtable = new NoteDwtable();
        dwtable.setId(DWTABLE_ID);
        dwtable.setNoteId(NOTE_ID);
        when(noteDwtableService.selectNoteDwtableById(DWTABLE_ID)).thenReturn(dwtable);

        sampleResult = new ExcelImportPrecheckResult();
        sampleResult.setHasBlockingIssues(false);
        sampleResult.getFileFingerprint().setSize(3L);
        sampleResult.getFileFingerprint().setMd5("d41d8cd98f00b204e9800998ecf8427e");
        when(noteDwtableExcelImportService.precheck(eq(NOTE_ID), eq(DWTABLE_ID),
                any(MultipartFile.class), eq(USER_ID))).thenReturn(sampleResult);
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
    }

    private MockMultipartFile xlsxFile()
    {
        return new MockMultipartFile("file", "T.xlsx", XLSX_CONTENT_TYPE, new byte[] {1, 2, 3});
    }

    @Test
    void precheckExcelImport_returnsPrecheckDataAsResponsePayload()
    {
        AjaxResult result = controller.precheckExcelImport(NOTE_ID, DWTABLE_ID, xlsxFile());

        assertEquals(200, result.get("code"));
        // data 即预检清单（含文件指纹，供前端分流与阶段二比对）
        assertSame(sampleResult, result.get("data"));
        assertFalse(((ExcelImportPrecheckResult) result.get("data")).isHasBlockingIssues());
        assertEquals("d41d8cd98f00b204e9800998ecf8427e",
                ((ExcelImportPrecheckResult) result.get("data")).getFileFingerprint().getMd5());
        verify(noteDwtableExcelImportService, times(1))
                .precheck(eq(NOTE_ID), eq(DWTABLE_ID), any(MultipartFile.class), eq(USER_ID));
        // 归属校验先行（照 importData 模式）
        verify(agentOwnershipChecker, times(1)).checkDwtableOwnership(DWTABLE_ID, USER_ID);
    }

    @Test
    void precheckExcelImport_ownershipCheckFails_throwsServiceException()
    {
        doThrow(new ServiceException("无权操作他人数据")).when(agentOwnershipChecker)
                .checkDwtableOwnership(DWTABLE_ID, USER_ID);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.precheckExcelImport(NOTE_ID, DWTABLE_ID, xlsxFile()));

        assertEquals("无权操作他人数据", ex.getMessage());
        verify(noteDwtableExcelImportService, times(0))
                .precheck(eq(NOTE_ID), eq(DWTABLE_ID), any(MultipartFile.class), eq(USER_ID));
    }

    @Test
    void precheckExcelImport_noteIdMismatch_throwsServiceException()
    {
        NoteDwtable otherNoteTable = new NoteDwtable();
        otherNoteTable.setId(DWTABLE_ID);
        otherNoteTable.setNoteId(999L);
        when(noteDwtableService.selectNoteDwtableById(DWTABLE_ID)).thenReturn(otherNoteTable);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.precheckExcelImport(NOTE_ID, DWTABLE_ID, xlsxFile()));

        assertTrue(ex.getMessage().contains("不匹配"));
        verify(noteDwtableExcelImportService, times(0))
                .precheck(eq(NOTE_ID), eq(DWTABLE_ID), any(MultipartFile.class), eq(USER_ID));
    }

    @Test
    void precheckExcelImport_emptyFile_throwsServiceException()
    {
        MockMultipartFile empty = new MockMultipartFile("file", "T.xlsx",
                XLSX_CONTENT_TYPE, new byte[0]);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.precheckExcelImport(NOTE_ID, DWTABLE_ID, empty));

        assertTrue(ex.getMessage().contains("为空"));
        verify(noteDwtableExcelImportService, times(0))
                .precheck(eq(NOTE_ID), eq(DWTABLE_ID), any(MultipartFile.class), eq(USER_ID));
    }

    @Test
    void precheckExcelImport_annotations_rateLimiterLogAndPathConfigured() throws NoSuchMethodException
    {
        Method method = NoteDwtableController.class.getMethod("precheckExcelImport",
                Long.class, Long.class, MultipartFile.class);

        // R24：按用户限流（对称导入端点 60s/10 次）
        RateLimiter rateLimiter = method.getAnnotation(RateLimiter.class);
        assertNotNull(rateLimiter);
        assertEquals(60, rateLimiter.time());
        assertEquals(10, rateLimiter.count());
        assertEquals(LimitType.USER, rateLimiter.limitType());

        // R25：预检清单含单元格派生值，请求与响应均禁入 sys_oper_log
        Log logAnnotation = method.getAnnotation(Log.class);
        assertNotNull(logAnnotation);
        assertEquals("多维表格Excel导入预检", logAnnotation.title());
        assertEquals(BusinessType.IMPORT, logAnnotation.businessType());
        assertFalse(logAnnotation.isSaveRequestData());
        assertFalse(logAnnotation.isSaveResponseData());

        PostMapping postMapping = method.getAnnotation(PostMapping.class);
        assertNotNull(postMapping);
        assertArrayEquals(new String[] {"/precheckExcelImport"}, postMapping.value());
    }

    @Test
    void precheckExcelImport_requiredParams_mandatory() throws NoSuchMethodException
    {
        // 缺 noteId/dwtableId/file 时 Spring MVC 参数绑定返回 400（required 默认 true）
        Method method = NoteDwtableController.class.getMethod("precheckExcelImport",
                Long.class, Long.class, MultipartFile.class);
        for (java.lang.reflect.Parameter parameter : method.getParameters())
        {
            RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
            assertNotNull(requestParam, "参数 " + parameter.getName() + " 缺少 @RequestParam");
            assertTrue(requestParam.required(), "参数 " + parameter.getName() + " 必须必传");
        }
    }
}
