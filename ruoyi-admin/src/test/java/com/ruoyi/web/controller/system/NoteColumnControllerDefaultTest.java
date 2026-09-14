package com.ruoyi.web.controller.system;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.myHashMap;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.vo.NoteColumnVo;
import com.ruoyi.system.service.INoteColumnService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NoteColumnController#edit} 列更新端点 KTD6 路由分流单元测试（U2）。
 * <p>
 * 覆盖：仅 property.default 变更走 updateColumnDefault 直更入口；
 * name/isShow 等其他字段变更或未携带 default 键走 updateNoteColumn 原路径；
 * 直更路径异常（非法默认值/归属校验失败）以 ServiceException 传播。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NoteColumnControllerDefaultTest
{
    private static final Long COLUMN_ID = 1L;
    private static final Long DWTABLE_ID = 10L;
    private static final Long USER_ID = 70L;

    @Mock
    private INoteColumnService noteColumnService;

    @InjectMocks
    private NoteColumnController controller;

    @BeforeEach
    void setUp()
    {
        LoginUser loginUser = new LoginUser(USER_ID, null, null, null);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
        when(noteColumnService.updateColumnDefault(any(Long.class), any(), any(Long.class))).thenReturn(1);
        when(noteColumnService.updateNoteColumn(any(NoteColumnVo.class))).thenReturn(1);
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
    }

    private NoteColumn originColumn(String property)
    {
        NoteColumn column = new NoteColumn();
        column.setId(COLUMN_ID);
        column.setName("状态");
        column.setType(3L);
        column.setDwtableId(DWTABLE_ID);
        column.setIsShow(0L);
        column.setSort(1L);
        column.setProperty(property);
        return column;
    }

    private NoteColumnVo voWithDefault(String name, myHashMap<String, Object> property)
    {
        NoteColumnVo vo = new NoteColumnVo();
        vo.setId(COLUMN_ID);
        vo.setName(name);
        vo.setType(3L);
        vo.setDwtableId(DWTABLE_ID);
        vo.setProperty(property);
        return vo;
    }

    @Test
    void edit_defaultOnlyChange_routesToUpdateColumnDefault()
    {
        when(noteColumnService.selectNoteColumnById(COLUMN_ID))
                .thenReturn(originColumn("{\"select\":\"进行中,已完成\"}"));
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("select", "进行中,已完成");
        property.put("default", "进行中");

        controller.edit(voWithDefault("状态", property));

        verify(noteColumnService, times(1)).updateColumnDefault(COLUMN_ID, "进行中", USER_ID);
        verify(noteColumnService, never()).updateNoteColumn(any(NoteColumnVo.class));
    }

    @Test
    void edit_defaultOnlyChange_blankValue_routesWithNullDefault()
    {
        when(noteColumnService.selectNoteColumnById(COLUMN_ID))
                .thenReturn(originColumn("{\"select\":\"A,B\",\"default\":\"A\"}"));
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("select", "A,B");
        property.put("default", "");

        controller.edit(voWithDefault("状态", property));

        verify(noteColumnService, times(1)).updateColumnDefault(COLUMN_ID, null, USER_ID);
        verify(noteColumnService, never()).updateNoteColumn(any(NoteColumnVo.class));
    }

    @Test
    void edit_namePlusDefaultChange_routesToServiceOriginalPath()
    {
        when(noteColumnService.selectNoteColumnById(COLUMN_ID))
                .thenReturn(originColumn("{\"select\":\"进行中,已完成\"}"));
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("select", "进行中,已完成");
        property.put("default", "进行中");

        controller.edit(voWithDefault("新列名", property));

        verify(noteColumnService, times(1)).updateNoteColumn(any(NoteColumnVo.class));
        verify(noteColumnService, never()).updateColumnDefault(any(Long.class), any(), any(Long.class));
    }

    @Test
    void edit_isShowChange_routesToServiceOriginalPath()
    {
        when(noteColumnService.selectNoteColumnById(COLUMN_ID))
                .thenReturn(originColumn("{\"select\":\"A,B\"}"));
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("select", "A,B");
        property.put("default", "A");
        NoteColumnVo vo = voWithDefault("状态", property);
        vo.setIsShow(1L);

        controller.edit(vo);

        verify(noteColumnService, times(1)).updateNoteColumn(any(NoteColumnVo.class));
        verify(noteColumnService, never()).updateColumnDefault(any(Long.class), any(), any(Long.class));
    }

    @Test
    void edit_withoutDefaultKey_routesToServiceOriginalPath()
    {
        when(noteColumnService.selectNoteColumnById(COLUMN_ID))
                .thenReturn(originColumn("{\"select\":\"A,B\"}"));
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("select", "A,B");

        controller.edit(voWithDefault("状态", property));

        verify(noteColumnService, times(1)).updateNoteColumn(any(NoteColumnVo.class));
        verify(noteColumnService, never()).updateColumnDefault(any(Long.class), any(), any(Long.class));
    }

    @Test
    void edit_columnMissing_routesToServiceOriginalPath()
    {
        // 原列不存在时直更判定为 false，维持既有 service 路径行为
        when(noteColumnService.selectNoteColumnById(COLUMN_ID)).thenReturn(null);
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("default", "A");

        controller.edit(voWithDefault("状态", property));

        verify(noteColumnService, times(1)).updateNoteColumn(any(NoteColumnVo.class));
    }

    @Test
    void edit_invalidDefaultFromDirectUpdate_propagatesServiceException()
    {
        when(noteColumnService.selectNoteColumnById(COLUMN_ID))
                .thenReturn(originColumn("{\"select\":\"进行中,已完成\"}"));
        doThrow(new ServiceException("列“状态”的默认值必须是数字"))
                .when(noteColumnService).updateColumnDefault(eq(COLUMN_ID), eq("abc"), eq(USER_ID));
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("select", "进行中,已完成");
        property.put("default", "abc");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.edit(voWithDefault("状态", property)));
        assertTrue(ex.getMessage().contains("数字"));
    }

    @Test
    void edit_ownershipCheckFails_propagatesServiceException()
    {
        when(noteColumnService.selectNoteColumnById(COLUMN_ID))
                .thenReturn(originColumn("{\"select\":\"进行中\"}"));
        doThrow(new ServiceException("无权操作他人数据"))
                .when(noteColumnService).updateColumnDefault(eq(COLUMN_ID), eq("进行中"), eq(USER_ID));
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("select", "进行中");
        property.put("default", "进行中");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.edit(voWithDefault("状态", property)));
        assertEquals("无权操作他人数据", ex.getMessage());
    }
}
