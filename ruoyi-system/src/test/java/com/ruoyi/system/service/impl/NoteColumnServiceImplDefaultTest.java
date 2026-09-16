package com.ruoyi.system.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import com.ruoyi.system.agent.security.AgentOwnershipChecker;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.vo.NoteColumnVo;
import com.ruoyi.system.mapper.NoteColumnMapper;
import com.ruoyi.system.service.INoteRecordService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NoteColumnServiceImpl} 列默认值路径单元测试（U2/KTD6 路由守卫）。
 * <p>
 * 覆盖：updateColumnDefault 直更路径（归属校验先行、格式校验拒绝、
 * merge 保留既有键、零 service 副作用）；updateNoteColumn 原路径
 * （name+default 变更触发 recomputeRecordNamesForTable、提交无 default
 * 键时继承原 default 键）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NoteColumnServiceImplDefaultTest
{
    private static final Long COLUMN_ID = 1L;
    private static final Long DWTABLE_ID = 10L;
    private static final Long USER_ID = 70L;

    @Mock
    private NoteColumnMapper noteColumnMapper;

    @Mock
    private AgentOwnershipChecker agentOwnershipChecker;

    @Mock
    private INoteRecordService noteRecordService;

    @InjectMocks
    private NoteColumnServiceImpl service;

    @BeforeEach
    void setUp()
    {
        // updateNoteColumn 原路径内置归属校验（SecurityUtils.getUserId()），
        // 测试环境无登录上下文，须模拟 SecurityContext（对齐 NoteNoteServiceImplOwnershipTest.loginAs 模式）
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(USER_ID);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, java.util.Collections.emptyList()));
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
    }

    private NoteColumn originColumn(Long type, String property)
    {
        NoteColumn column = new NoteColumn();
        column.setId(COLUMN_ID);
        column.setName("状态");
        column.setType(type);
        column.setDwtableId(DWTABLE_ID);
        column.setIsShow(0L);
        column.setSort(1L);
        column.setProperty(property);
        return column;
    }

    // ---------------- updateColumnDefault：直更路径 ----------------

    @Test
    void updateColumnDefault_ownershipCheckRunsBeforeAnyMapperAccess()
    {
        NoteColumn origin = originColumn(3L, "{\"select\":\"进行中,已完成\"}");
        when(noteColumnMapper.selectNoteColumnById(COLUMN_ID)).thenReturn(origin);

        service.updateColumnDefault(COLUMN_ID, "进行中", USER_ID);

        InOrder inOrder = inOrder(agentOwnershipChecker, noteColumnMapper);
        inOrder.verify(agentOwnershipChecker, times(1)).checkColumnOwnership(COLUMN_ID, USER_ID);
        inOrder.verify(noteColumnMapper).selectNoteColumnById(COLUMN_ID);
        inOrder.verify(noteColumnMapper).updateNoteColumn(any(NoteColumn.class));
    }

    @Test
    void updateColumnDefault_mergesDefaultKeepsSelect_noServiceSideEffects()
    {
        NoteColumn origin = originColumn(3L, "{\"select\":\"进行中,已完成\"}");
        when(noteColumnMapper.selectNoteColumnById(COLUMN_ID)).thenReturn(origin);

        int result = service.updateColumnDefault(COLUMN_ID, "进行中", USER_ID);

        assertEquals(1, result);
        ArgumentCaptor<NoteColumn> captor = ArgumentCaptor.forClass(NoteColumn.class);
        verify(noteColumnMapper, times(1)).updateNoteColumn(captor.capture());
        NoteColumn updated = captor.getValue();
        // 直更仅更新 property，其余字段为 null（mapper 动态 SQL 不触碰）
        assertEquals(COLUMN_ID, updated.getId());
        assertNull(updated.getName());
        assertTrue(updated.getProperty().contains("\"select\":\"进行中,已完成\""));
        assertTrue(updated.getProperty().contains("\"default\":\"进行中\""));
        // KTD6: 零 service 副作用——不触发 recompute、不触碰记录/选项/items
        verify(noteRecordService, never()).recomputeRecordNamesForTable(anyLong());
        verify(noteRecordService, never()).recomputeLookupColumnValues(any(NoteColumn.class));
        verify(noteRecordService, never()).recomputeSetOperationsForLookup(any(NoteColumn.class));
    }

    @Test
    void updateColumnDefault_nullDefaultValue_removesDefaultKey()
    {
        NoteColumn origin = originColumn(3L, "{\"select\":\"A,B\",\"default\":\"A\"}");
        when(noteColumnMapper.selectNoteColumnById(COLUMN_ID)).thenReturn(origin);

        service.updateColumnDefault(COLUMN_ID, null, USER_ID);

        ArgumentCaptor<NoteColumn> captor = ArgumentCaptor.forClass(NoteColumn.class);
        verify(noteColumnMapper).updateNoteColumn(captor.capture());
        assertTrue(captor.getValue().getProperty().contains("\"select\":\"A,B\""));
        assertFalse(captor.getValue().getProperty().contains("\"default\""));
    }

    @Test
    void updateColumnDefault_ownershipCheckFails_zeroMapperWrites()
    {
        doThrow(new ServiceException("无权操作他人数据")).when(agentOwnershipChecker)
                .checkColumnOwnership(COLUMN_ID, USER_ID);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.updateColumnDefault(COLUMN_ID, "进行中", USER_ID));
        assertEquals("无权操作他人数据", ex.getMessage());
        verify(noteColumnMapper, never()).updateNoteColumn(any(NoteColumn.class));
    }

    @Test
    void updateColumnDefault_invalidFormat_rejectedWithoutWrite()
    {
        NoteColumn origin = originColumn(2L, null);
        when(noteColumnMapper.selectNoteColumnById(COLUMN_ID)).thenReturn(origin);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.updateColumnDefault(COLUMN_ID, "abc", USER_ID));
        assertTrue(ex.getMessage().contains("数字"));
        verify(noteColumnMapper, never()).updateNoteColumn(any(NoteColumn.class));
    }

    @Test
    void updateColumnDefault_unsupportedType_rejectedWithoutWrite()
    {
        NoteColumn origin = originColumn(21L, "{\"table_id\":5,\"back_field_id\":9}");
        when(noteColumnMapper.selectNoteColumnById(COLUMN_ID)).thenReturn(origin);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.updateColumnDefault(COLUMN_ID, "x", USER_ID));
        assertTrue(ex.getMessage().contains("不支持默认值"));
        verify(noteColumnMapper, never()).updateNoteColumn(any(NoteColumn.class));
    }

    @Test
    void updateColumnDefault_columnMissing_throwsServiceException()
    {
        when(noteColumnMapper.selectNoteColumnById(COLUMN_ID)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.updateColumnDefault(COLUMN_ID, "x", USER_ID));
        assertEquals("列不存在", ex.getMessage());
    }

    // ---------------- updateNoteColumn：service 原路径 ----------------

    @Test
    void updateNoteColumn_namePlusDefaultChange_keepsDefaultAndTriggersRecompute()
    {
        NoteColumn origin = originColumn(3L, "{\"select\":\"进行中,已完成\"}");
        when(noteColumnMapper.selectNoteColumnById(COLUMN_ID)).thenReturn(origin);

        myHashMap<String, Object> property = new myHashMap<>();
        property.put("select", "进行中,已完成");
        property.put("default", "已完成");
        NoteColumnVo vo = new NoteColumnVo();
        vo.setId(COLUMN_ID);
        vo.setName("新列名");
        vo.setType(3L);
        vo.setDwtableId(DWTABLE_ID);
        vo.setIsShow(0L);
        vo.setProperty(property);

        int result = service.updateNoteColumn(vo);

        assertEquals(1, result);
        // 路由守卫：携带 name 变更走 service 原路径 → recomputeRecordNamesForTable 触发
        verify(noteRecordService, times(1)).recomputeRecordNamesForTable(DWTABLE_ID);
        // property 覆写保留 default 键（提交携带，以提交为准）
        ArgumentCaptor<NoteColumn> captor = ArgumentCaptor.forClass(NoteColumn.class);
        verify(noteColumnMapper, atLeastOnce()).updateNoteColumn(captor.capture());
        boolean hasDefault = captor.getAllValues().stream()
                .anyMatch(c -> c.getProperty() != null && c.getProperty().contains("\"default\":\"已完成\""));
        assertTrue(hasDefault, "service 原路径覆写 property 须保留提交的 default 键");
    }

    @Test
    void updateNoteColumn_selectChangeWithoutDefaultKey_inheritsOriginDefault()
    {
        NoteColumn origin = originColumn(3L, "{\"select\":\"进行中,已完成\",\"default\":\"进行中\"}");
        when(noteColumnMapper.selectNoteColumnById(COLUMN_ID)).thenReturn(origin);

        myHashMap<String, Object> property = new myHashMap<>();
        property.put("select", "进行中,已完成,已取消");
        NoteColumnVo vo = new NoteColumnVo();
        vo.setId(COLUMN_ID);
        vo.setName("状态");
        vo.setType(3L);
        vo.setDwtableId(DWTABLE_ID);
        vo.setIsShow(0L);
        vo.setProperty(property);

        service.updateNoteColumn(vo);

        ArgumentCaptor<NoteColumn> captor = ArgumentCaptor.forClass(NoteColumn.class);
        verify(noteColumnMapper, atLeastOnce()).updateNoteColumn(captor.capture());
        boolean merged = captor.getAllValues().stream().anyMatch(c -> c.getProperty() != null
                && c.getProperty().contains("\"select\":\"进行中,已完成,已取消\"")
                && c.getProperty().contains("\"default\":\"进行中\""));
        assertTrue(merged, "提交 property 不含 default 键时须从原列继承，不得静默清除");
    }
}
