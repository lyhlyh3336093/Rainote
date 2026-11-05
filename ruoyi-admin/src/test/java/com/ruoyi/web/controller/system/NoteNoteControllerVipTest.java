package com.ruoyi.web.controller.system;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.VipRequiredException;
import com.ruoyi.system.domain.NoteNote;
import com.ruoyi.system.service.INoteNoteService;
import com.ruoyi.system.service.IPersonalExportService;
import com.ruoyi.system.service.VipService;
import com.ruoyi.web.controller.VipTestContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NoteNoteController} 笔记创建入口 VIP 配额断言测试（U3/R10）。
 * <p>
 * 覆盖：/add 与 /saveAsNewNote 两个创建入口均先断言后写入；
 * 普通用户超限（20 篇）抛 602 且 service 不被触达（Covers AE1）。
 *
 * @author ruoyi
 */
class NoteNoteControllerVipTest
{
    private static final Long USER = 2L;

    private NoteNoteController controller;

    private INoteNoteService noteNoteService;

    private VipService vipService;

    private IPersonalExportService personalExportService;

    @BeforeEach
    void setUp()
    {
        controller = new NoteNoteController();
        noteNoteService = mock(INoteNoteService.class);
        vipService = VipTestContext.mockPermissiveVipService();
        personalExportService = mock(IPersonalExportService.class);
        ReflectionTestUtils.setField(controller, "noteNoteService", noteNoteService);
        ReflectionTestUtils.setField(controller, "vipService", vipService);
        ReflectionTestUtils.setField(controller, "personalExportService", personalExportService);
        VipTestContext.setLoginUser(USER);
    }

    @AfterEach
    void tearDown()
    {
        VipTestContext.clear();
    }

    @Test
    void add_normalUserAtLimit_throwsVipRequired_serviceUntouched()
    {
        doThrow(new VipRequiredException("笔记数量已达普通用户上限"))
                .when(vipService).assertNoteQuota(USER);
        NoteNote note = new NoteNote();
        note.setTitle("第21篇");
        assertThrows(VipRequiredException.class, () -> controller.add(note));
        verify(noteNoteService, never()).insertNoteNote(any(NoteNote.class));
    }

    @Test
    void add_withinLimit_authBoundAndInserted()
    {
        when(noteNoteService.insertNoteNote(any(NoteNote.class))).thenReturn(1);
        NoteNote note = new NoteNote();
        note.setTitle("普通笔记");
        AjaxResult result = controller.add(note);
        assertEquals(200, result.get("code"));
        // 断言先于写入且 auth 绑定当前用户
        verify(vipService).assertNoteQuota(USER);
        assertEquals(USER, note.getAuth());
    }

    @Test
    void saveAsNewNote_sameQuotaAsserted()
    {
        when(noteNoteService.saveAsNewNote(any(NoteNote.class))).thenReturn(1);
        NoteNote note = new NoteNote();
        note.setTitle("另存为");
        controller.saveAsNewNote(note);
        verify(vipService).assertNoteQuota(USER);
    }

    @Test
    void saveAsNewNote_overLimit_rejected()
    {
        doThrow(new VipRequiredException("笔记数量已达普通用户上限"))
                .when(vipService).assertNoteQuota(USER);
        NoteNote note = new NoteNote();
        note.setTitle("绕过尝试");
        assertThrows(VipRequiredException.class, () -> controller.saveAsNewNote(note));
        verify(noteNoteService, never()).saveAsNewNote(any(NoteNote.class));
    }

    // ===== U9 个人导出端点 =====

    @Test
    void myExport_nonVip_rejectedWith602_zipUntouched() throws Exception
    {
        doThrow(new VipRequiredException("个人数据导出为 VIP 专属能力"))
                .when(vipService).assertPersonalExport(USER);
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();
        assertThrows(VipRequiredException.class, () -> controller.myExport(response));
        verify(personalExportService, never()).writeZipTo(anyLong(), any());
    }

    @Test
    void myExport_vip_streamsZipWithDisposition() throws Exception
    {
        // VIP 放行：zip 流式写出 + attachment 头（U9 场景 1 端点层）
        doNothing().when(personalExportService).writeZipTo(eq(USER), any());
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();
        controller.myExport(response);
        assertEquals("application/zip", response.getContentType());
        org.junit.jupiter.api.Assertions.assertTrue(
                response.getHeader("Content-Disposition").startsWith("attachment; filename=personal-export-2-"),
                "导出文件名应含用户标识");
        verify(personalExportService).writeZipTo(eq(USER), any());
    }
}
