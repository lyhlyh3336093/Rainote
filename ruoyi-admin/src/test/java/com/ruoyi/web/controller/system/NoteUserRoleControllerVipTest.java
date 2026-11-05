package com.ruoyi.web.controller.system;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.VipRequiredException;
import com.ruoyi.system.domain.NoteUserRole;
import com.ruoyi.system.service.INoteUserRoleService;
import com.ruoyi.system.service.VipService;
import com.ruoyi.web.controller.VipTestContext;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NoteUserRoleController#add} 共享创建 VIP 断言测试（U3/R14）。
 * <p>
 * 覆盖：普通用户创建共享抛 602 且 service 不触达；VIP/admin 放行。
 *
 * @author ruoyi
 */
class NoteUserRoleControllerVipTest
{
    private static final Long USER = 2L;

    private NoteUserRoleController controller;

    private INoteUserRoleService noteUserRoleService;

    private VipService vipService;

    @BeforeEach
    void setUp()
    {
        controller = new NoteUserRoleController();
        noteUserRoleService = mock(INoteUserRoleService.class);
        when(noteUserRoleService.insertNoteUserRole(any(NoteUserRole.class))).thenReturn(1);
        vipService = VipTestContext.mockPermissiveVipService();
        ReflectionTestUtils.setField(controller, "noteUserRoleService", noteUserRoleService);
        ReflectionTestUtils.setField(controller, "vipService", vipService);
        VipTestContext.setLoginUser(USER);
    }

    @AfterEach
    void tearDown()
    {
        VipTestContext.clear();
    }

    @Test
    void add_nonVip_throwsVipRequired()
    {
        doThrow(new VipRequiredException("创建共享为 VIP 专属能力")).when(vipService).assertShare(USER);
        NoteUserRole userRole = new NoteUserRole();
        assertThrows(VipRequiredException.class, () -> controller.add(userRole));
        verify(noteUserRoleService, never()).insertNoteUserRole(any(NoteUserRole.class));
    }

    @Test
    void add_vipOrAdmin_allowed()
    {
        NoteUserRole userRole = new NoteUserRole();
        assertDoesNotThrow(() -> controller.add(userRole));
        verify(vipService).assertShare(USER);
        verify(noteUserRoleService).insertNoteUserRole(any(NoteUserRole.class));
    }
}
