package com.ruoyi.web.controller.system;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import com.ruoyi.system.domain.dto.ParsedInsert;
import com.ruoyi.system.service.INoteDwtableService;
import com.ruoyi.system.service.INoteDwtableImportService;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NoteDwtableController#importData} 单元测试。
 * <p>
 * 覆盖 .sql/.zip 成功导入、不支持类型、归属校验失败、noteId 不匹配、
 * 解析失败传播、空文件、限流/日志/路径注解契约、必传参数契约。
 *
 * @author ruoyi
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NoteDwtableControllerImportTest
{
    private static final Long NOTE_ID = 50L;
    private static final Long DWTABLE_ID = 60L;
    private static final Long USER_ID = 70L;

    @Mock
    private INoteDwtableService noteDwtableService;

    @Mock
    private AgentOwnershipChecker agentOwnershipChecker;

    @Mock
    private INoteDwtableImportService noteDwtableImportService;

    @InjectMocks
    private NoteDwtableController controller;

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
        when(noteDwtableImportService.importData(eq(NOTE_ID), eq(DWTABLE_ID), anyList(), eq(USER_ID)))
                .thenReturn(3);
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
    }

    private MockMultipartFile sqlFile(String filename, String content)
    {
        return new MockMultipartFile("file", filename, "application/sql",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private MockMultipartFile zipFile(String filename, String[] names, String[] contents)
            throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos))
        {
            for (int i = 0; i < names.length; i++)
            {
                zos.putNextEntry(new ZipEntry(names[i]));
                zos.write(contents[i].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return new MockMultipartFile("file", filename, "application/zip", baos.toByteArray());
    }

    @Test
    void importData_sqlFile_returnsRecordCount() throws IOException
    {
        MockMultipartFile file = sqlFile("T_p1.sql",
                "INSERT INTO `T` (`record_id`,`名称`) VALUES (10,'foo')");

        AjaxResult result = controller.importData(NOTE_ID, DWTABLE_ID, file);

        assertEquals(200, result.get("code"));
        Map<?, ?> data = (Map<?, ?>) result.get("data");
        assertEquals(3, data.get("recordCount"));
        ArgumentCaptor<List<ParsedInsert>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(noteDwtableImportService, times(1))
                .importData(eq(NOTE_ID), eq(DWTABLE_ID), captor.capture(), eq(USER_ID));
        assertEquals(1, captor.getValue().size());
        assertEquals("foo", captor.getValue().get(0).getColumnValues().get("名称"));
    }

    @Test
    void importData_zipFile_parsesInNameOrder() throws IOException
    {
        // 写入顺序 p2 在前，解析顺序应按文件名升序 p1 → p2（R8/AE7）
        MockMultipartFile file = zipFile("tables.zip",
                new String[] {"T_p2.sql", "T_p1.sql"},
                new String[] {
                        "INSERT INTO `T` (`record_id`,`名称`) VALUES (11,'second')",
                        "INSERT INTO `T` (`record_id`,`名称`) VALUES (10,'first')"});

        AjaxResult result = controller.importData(NOTE_ID, DWTABLE_ID, file);

        assertEquals(200, result.get("code"));
        ArgumentCaptor<List<ParsedInsert>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(noteDwtableImportService, times(1))
                .importData(eq(NOTE_ID), eq(DWTABLE_ID), captor.capture(), eq(USER_ID));
        List<ParsedInsert> parsed = captor.getValue();
        assertEquals(2, parsed.size());
        assertEquals("first", parsed.get(0).getColumnValues().get("名称"));
        assertEquals("second", parsed.get(1).getColumnValues().get("名称"));
    }

    @Test
    void importData_unsupportedFileType_throwsServiceException()
    {
        MockMultipartFile file = sqlFile("data.csv", "a,b,c");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.importData(NOTE_ID, DWTABLE_ID, file));
        assertTrue(ex.getMessage().contains("不支持的文件类型"));
    }

    @Test
    void importData_ownershipCheckFails_throwsServiceException()
    {
        doThrow(new ServiceException("无权操作他人数据")).when(agentOwnershipChecker)
                .checkDwtableOwnership(DWTABLE_ID, USER_ID);
        MockMultipartFile file = sqlFile("T_p1.sql", "INSERT INTO `T` (`record_id`) VALUES (1)");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.importData(NOTE_ID, DWTABLE_ID, file));
        assertEquals("无权操作他人数据", ex.getMessage());
    }

    @Test
    void importData_noteIdMismatch_throwsServiceException()
    {
        NoteDwtable otherNoteTable = new NoteDwtable();
        otherNoteTable.setId(DWTABLE_ID);
        otherNoteTable.setNoteId(999L);
        when(noteDwtableService.selectNoteDwtableById(DWTABLE_ID)).thenReturn(otherNoteTable);
        MockMultipartFile file = sqlFile("T_p1.sql", "INSERT INTO `T` (`record_id`) VALUES (1)");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.importData(NOTE_ID, DWTABLE_ID, file));
        assertTrue(ex.getMessage().contains("不匹配"));
    }

    @Test
    void importData_parseFailure_propagatesServiceException()
    {
        MockMultipartFile file = sqlFile("T_p1.sql", "INSERT INTO T (a) (1)");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.importData(NOTE_ID, DWTABLE_ID, file));
        assertTrue(ex.getMessage().contains("SQL 解析失败"));
    }

    @Test
    void importData_emptyFile_throwsServiceException()
    {
        MockMultipartFile file = new MockMultipartFile("file", "T_p1.sql",
                "application/sql", new byte[0]);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.importData(NOTE_ID, DWTABLE_ID, file));
        assertTrue(ex.getMessage().contains("为空"));
    }

    @Test
    void importData_annotations_rateLimiterLogAndPathConfigured() throws NoSuchMethodException
    {
        Method method = NoteDwtableController.class.getMethod("importData",
                Long.class, Long.class, MultipartFile.class);
        RateLimiter rateLimiter = method.getAnnotation(RateLimiter.class);
        assertNotNull(rateLimiter);
        assertEquals(60, rateLimiter.time());
        assertEquals(10, rateLimiter.count());
        assertEquals(LimitType.USER, rateLimiter.limitType());
        Log logAnnotation = method.getAnnotation(Log.class);
        assertNotNull(logAnnotation);
        assertEquals(BusinessType.IMPORT, logAnnotation.businessType());
        PostMapping postMapping = method.getAnnotation(PostMapping.class);
        assertNotNull(postMapping);
        assertArrayEquals(new String[] {"/importData"}, postMapping.value());
    }

    @Test
    void importData_requiredParams_mandatory() throws NoSuchMethodException
    {
        // 缺 noteId/dwtableId/file 时 Spring MVC 参数绑定返回 400（required 默认 true）
        Method method = NoteDwtableController.class.getMethod("importData",
                Long.class, Long.class, MultipartFile.class);
        for (java.lang.reflect.Parameter parameter : method.getParameters())
        {
            RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
            assertNotNull(requestParam, "参数 " + parameter.getName() + " 缺少 @RequestParam");
            assertTrue(requestParam.required(), "参数 " + parameter.getName() + " 必须必传");
        }
    }
}
