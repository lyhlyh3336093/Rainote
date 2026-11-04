package com.ruoyi.web.controller.system;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.VipRequiredException;
import com.ruoyi.system.domain.vo.NoteColumnVo;
import com.ruoyi.system.service.INoteColumnService;
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
 * {@link NoteColumnController} 高级列创建 VIP 断言测试（U3/R11）。
 * <p>
 * 覆盖：普通用户创建 type=26（Lookup）抛 602 且 service 不触达（Covers AE2 高级列分支）；
 * type=1（文本）放行；VIP 创建 type=21（双向关联）放行。
 *
 * @author ruoyi
 */
class NoteColumnControllerVipTest
{
    private static final Long USER = 2L;

    private NoteColumnController controller;

    private INoteColumnService noteColumnService;

    private VipService vipService;

    @BeforeEach
    void setUp()
    {
        controller = new NoteColumnController();
        noteColumnService = mock(INoteColumnService.class);
        vipService = VipTestContext.mockPermissiveVipService();
        ReflectionTestUtils.setField(controller, "noteColumnService", noteColumnService);
        ReflectionTestUtils.setField(controller, "vipService", vipService);
        when(noteColumnService.insertNoteColumn(any(NoteColumnVo.class))).thenReturn(1);
        VipTestContext.setLoginUser(USER);
    }

    @AfterEach
    void tearDown()
    {
        VipTestContext.clear();
    }

    private NoteColumnVo voOfType(Long type)
    {
        NoteColumnVo vo = new NoteColumnVo();
        vo.setName("测试列");
        vo.setType(type);
        vo.setDwtableId(1L);
        return vo;
    }

    @Test
    void add_normalUserAdvancedType_throwsVipRequired()
    {
        doThrow(new VipRequiredException("高级列类型为 VIP 专属"))
                .when(vipService).assertAdvancedColumn(USER, 26L);
        assertThrows(VipRequiredException.class, () -> controller.add(voOfType(26L)));
        verify(noteColumnService, never()).insertNoteColumn(any(NoteColumnVo.class));
    }

    @Test
    void add_basicType_assertionPassedThrough()
    {
        // type=1（文本）非高级列：断言天然放行（VipService 内部类型判定），正常创建
        assertDoesNotThrow(() -> controller.add(voOfType(1L)));
        verify(vipService).assertAdvancedColumn(USER, 1L);
        verify(noteColumnService).insertNoteColumn(any(NoteColumnVo.class));
    }

    @Test
    void add_vipAdvancedType_allowed()
    {
        // VIP 创建 type=21（双向关联）：mock 放行态 → 创建成功
        assertDoesNotThrow(() -> controller.add(voOfType(21L)));
        verify(noteColumnService).insertNoteColumn(any(NoteColumnVo.class));
    }
}
