package com.ruoyi.system.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.NoteDwtable;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.agent.security.AgentOwnershipChecker;
import com.ruoyi.common.core.domain.model.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * deleteNoteDwtableById方法单元测试
 * 测试删除多维表格数据表的级联删除逻辑，包括不同列类型的处理
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class NoteDwtableServiceImplTest
{

    @Mock
    private NoteDwtableMapper noteDwtableMapper;

    @Mock
    private NoteColumnMapper noteColumnMapper;

    @Mock
    private NoteViewMapper noteViewMapper;

    @Mock
    private NoteViewServiceImpl noteViewServiceImpl;

    @Mock
    private NoteRecordMapper noteRecordMapper;

    @Mock
    private NoteDwtableItemMapper noteDwtableItemMapper;

    @Mock
    private AgentOwnershipChecker ownershipChecker;

    @InjectMocks
    private NoteDwtableServiceImpl noteDwtableService;

    @BeforeEach
    void setUpSecurityContext()
    {
        // U5 归属校验通过 SecurityUtils.getUserId() 获取当前用户，需设置 SecurityContext
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    @AfterEach
    void clearSecurityContext()
    {
        SecurityContextHolder.clearContext();
    }

    private static final Long TEST_DWTABLE_ID = 9999L;

    /**
     * 构造包含不同列类型的测试数据
     * 列类型：1-多行文本, 2-数字, 3-单选, 21-双向关联, 26-lookup
     */
    private List<NoteColumn> buildTestColumns()
    {
        List<NoteColumn> columns = new ArrayList<>();

        // 列1：多行文本(type=1)
        NoteColumn textColumn = new NoteColumn();
        textColumn.setId(1001L);
        textColumn.setType(1L);
        textColumn.setDwtableId(TEST_DWTABLE_ID);
        textColumn.setName("文本列");
        columns.add(textColumn);

        // 列2：数字(type=2)
        NoteColumn numberColumn = new NoteColumn();
        numberColumn.setId(1002L);
        numberColumn.setType(2L);
        numberColumn.setDwtableId(TEST_DWTABLE_ID);
        numberColumn.setName("数字列");
        columns.add(numberColumn);

        // 列3：单选(type=3)
        NoteColumn selectColumn = new NoteColumn();
        selectColumn.setId(1003L);
        selectColumn.setType(3L);
        selectColumn.setDwtableId(TEST_DWTABLE_ID);
        selectColumn.setName("单选列");
        columns.add(selectColumn);

        // 列4：双向关联(type=21)，需要特殊处理
        NoteColumn doubleLinkColumn = new NoteColumn();
        doubleLinkColumn.setId(1004L);
        doubleLinkColumn.setType(21L);
        doubleLinkColumn.setDwtableId(TEST_DWTABLE_ID);
        doubleLinkColumn.setName("双向关联列");
        JSONObject prop = new JSONObject();
        prop.put("back_field_id", 2001L); // 关联列ID
        doubleLinkColumn.setProperty(prop.toJSONString());
        columns.add(doubleLinkColumn);

        // 列5：lookup(type=26)
        NoteColumn lookupColumn = new NoteColumn();
        lookupColumn.setId(1005L);
        lookupColumn.setType(26L);
        lookupColumn.setDwtableId(TEST_DWTABLE_ID);
        lookupColumn.setName("lookup列");
        JSONObject lookupProp = new JSONObject();
        lookupProp.put("double_link_column_id", "1004");
        lookupProp.put("source_column_id", "3001");
        lookupColumn.setProperty(lookupProp.toJSONString());
        columns.add(lookupColumn);

        return columns;
    }

    @BeforeEach
    void setUp()
    {
        // 基础mock：删除视图和记录
        when(noteViewServiceImpl.deleteNoteViewByDwtableId(anyLong())).thenReturn(1);
        when(noteRecordMapper.deleteNoteRecordByDwtableId(anyLong())).thenReturn(1);
        when(noteDwtableMapper.deleteNoteDwtableById(anyLong())).thenReturn(1);
    }

    /**
     * 场景1：数据表下只有普通列（文本、数字、单选），没有双向关联列
     * 预期：直接删除视图、记录、数据表，不触发双向关联的特殊处理
     */
    @Test
    void testDeleteNoteDwtableById_onlyNormalColumns()
    {
        List<NoteColumn> normalColumns = new ArrayList<>();
        // 文本列
        NoteColumn textCol = new NoteColumn();
        textCol.setId(1001L);
        textCol.setType(1L);
        textCol.setDwtableId(TEST_DWTABLE_ID);
        normalColumns.add(textCol);

        // 数字列
        NoteColumn numCol = new NoteColumn();
        numCol.setId(1002L);
        numCol.setType(2L);
        numCol.setDwtableId(TEST_DWTABLE_ID);
        normalColumns.add(numCol);

        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(normalColumns);

        int result = noteDwtableService.deleteNoteDwtableById(TEST_DWTABLE_ID);

        assertEquals(1, result);
        // 验证删除了视图
        verify(noteViewServiceImpl).deleteNoteViewByDwtableId(TEST_DWTABLE_ID);
        // 验证删除了记录
        verify(noteRecordMapper).deleteNoteRecordByDwtableId(TEST_DWTABLE_ID);
        // 验证删除了数据表
        verify(noteDwtableMapper).deleteNoteDwtableById(TEST_DWTABLE_ID);
        // 验证没有调用双向关联的特殊删除逻辑
        verify(noteDwtableItemMapper, never()).deleteNoteDwtableItemByColumnId(anyLong());
        verify(noteColumnMapper, never()).deleteNoteColumnById(anyLong());
        verify(noteDwtableItemMapper, never()).deleteNoteDwtableItemByDwtId(anyLong());
    }

    /**
     * 场景2：数据表下包含一个双向关联列(type=21)
     * 预期：先删除关联列(back_field_id)下的item，再删除关联列本身，
     *       再删除当前双向关联列下的item，最后删除数据表
     */
    @Test
    void testDeleteNoteDwtableById_withDoubleLinkColumn()
    {
        List<NoteColumn> columns = new ArrayList<>();

        // 文本列
        NoteColumn textCol = new NoteColumn();
        textCol.setId(1001L);
        textCol.setType(1L);
        textCol.setDwtableId(TEST_DWTABLE_ID);
        columns.add(textCol);

        // 双向关联列
        NoteColumn doubleLinkCol = new NoteColumn();
        doubleLinkCol.setId(1004L);
        doubleLinkCol.setType(21L);
        doubleLinkCol.setDwtableId(TEST_DWTABLE_ID);
        JSONObject prop = new JSONObject();
        prop.put("back_field_id", 2001L);
        doubleLinkCol.setProperty(prop.toJSONString());
        columns.add(doubleLinkCol);

        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(columns);
        when(noteDwtableItemMapper.deleteNoteDwtableItemByColumnId(2001L)).thenReturn(2);
        when(noteColumnMapper.deleteNoteColumnById(2001L)).thenReturn(1);
        when(noteDwtableItemMapper.deleteNoteDwtableItemByDwtId(1004L)).thenReturn(3);

        int result = noteDwtableService.deleteNoteDwtableById(TEST_DWTABLE_ID);

        assertEquals(1, result);
        // 验证删除了关联列(2001)下的item
        verify(noteDwtableItemMapper).deleteNoteDwtableItemByColumnId(2001L);
        // 验证删除了关联列(2001)本身
        verify(noteColumnMapper).deleteNoteColumnById(2001L);
        // 验证删除了当前双向关联列(1004)下的item
        verify(noteDwtableItemMapper).deleteNoteDwtableItemByDwtId(1004L);
        // 验证最终删除了数据表
        verify(noteDwtableMapper).deleteNoteDwtableById(TEST_DWTABLE_ID);
    }

    /**
     * 场景3：数据表下包含多种列类型（文本、数字、单选、双向关联、lookup）
     * 预期：只有双向关联列触发特殊删除，其他列类型不触发
     */
    @Test
    void testDeleteNoteDwtableById_withMixedColumnTypes()
    {
        List<NoteColumn> columns = buildTestColumns();

        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(columns);
        when(noteDwtableItemMapper.deleteNoteDwtableItemByColumnId(2001L)).thenReturn(2);
        when(noteColumnMapper.deleteNoteColumnById(2001L)).thenReturn(1);
        when(noteDwtableItemMapper.deleteNoteDwtableItemByDwtId(1004L)).thenReturn(3);

        int result = noteDwtableService.deleteNoteDwtableById(TEST_DWTABLE_ID);

        assertEquals(1, result);
        // 验证只有双向关联列触发了特殊删除
        verify(noteDwtableItemMapper).deleteNoteDwtableItemByColumnId(2001L);
        verify(noteColumnMapper).deleteNoteColumnById(2001L);
        verify(noteDwtableItemMapper).deleteNoteDwtableItemByDwtId(1004L);
        // 验证lookup列(1005)没有触发双向关联的特殊删除逻辑
        verify(noteDwtableItemMapper, times(1)).deleteNoteDwtableItemByColumnId(anyLong());
        verify(noteColumnMapper, times(1)).deleteNoteColumnById(anyLong());
    }

    /**
     * 场景4：数据表下包含两个双向关联列
     * 预期：两个双向关联列都触发各自关联列的删除
     */
    @Test
    void testDeleteNoteDwtableById_withTwoDoubleLinkColumns()
    {
        List<NoteColumn> columns = new ArrayList<>();

        // 第一个双向关联列
        NoteColumn doubleLink1 = new NoteColumn();
        doubleLink1.setId(1001L);
        doubleLink1.setType(21L);
        doubleLink1.setDwtableId(TEST_DWTABLE_ID);
        JSONObject prop1 = new JSONObject();
        prop1.put("back_field_id", 2001L);
        doubleLink1.setProperty(prop1.toJSONString());
        columns.add(doubleLink1);

        // 第二个双向关联列
        NoteColumn doubleLink2 = new NoteColumn();
        doubleLink2.setId(1002L);
        doubleLink2.setType(21L);
        doubleLink2.setDwtableId(TEST_DWTABLE_ID);
        JSONObject prop2 = new JSONObject();
        prop2.put("back_field_id", 2002L);
        doubleLink2.setProperty(prop2.toJSONString());
        columns.add(doubleLink2);

        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(columns);
        when(noteDwtableItemMapper.deleteNoteDwtableItemByColumnId(anyLong())).thenReturn(1);
        when(noteColumnMapper.deleteNoteColumnById(anyLong())).thenReturn(1);
        when(noteDwtableItemMapper.deleteNoteDwtableItemByDwtId(anyLong())).thenReturn(1);

        int result = noteDwtableService.deleteNoteDwtableById(TEST_DWTABLE_ID);

        assertEquals(1, result);
        // 验证两个关联列的item都被删除
        verify(noteDwtableItemMapper).deleteNoteDwtableItemByColumnId(2001L);
        verify(noteDwtableItemMapper).deleteNoteDwtableItemByColumnId(2002L);
        // 验证两个关联列本身都被删除
        verify(noteColumnMapper).deleteNoteColumnById(2001L);
        verify(noteColumnMapper).deleteNoteColumnById(2002L);
        // 验证两个双向关联列下的item都被删除
        verify(noteDwtableItemMapper).deleteNoteDwtableItemByDwtId(1001L);
        verify(noteDwtableItemMapper).deleteNoteDwtableItemByDwtId(1002L);
    }

    /**
     * 场景5：数据表下没有任何列
     * 预期：只删除视图、记录、数据表，不触发列相关的删除
     */
    @Test
    void testDeleteNoteDwtableById_noColumns()
    {
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(new ArrayList<>());

        int result = noteDwtableService.deleteNoteDwtableById(TEST_DWTABLE_ID);

        assertEquals(1, result);
        verify(noteViewServiceImpl).deleteNoteViewByDwtableId(TEST_DWTABLE_ID);
        verify(noteRecordMapper).deleteNoteRecordByDwtableId(TEST_DWTABLE_ID);
        verify(noteDwtableMapper).deleteNoteDwtableById(TEST_DWTABLE_ID);
        verify(noteDwtableItemMapper, never()).deleteNoteDwtableItemByColumnId(anyLong());
        verify(noteColumnMapper, never()).deleteNoteColumnById(anyLong());
        verify(noteDwtableItemMapper, never()).deleteNoteDwtableItemByDwtId(anyLong());
    }

    /**
     * 场景6：数据表下只有lookup列(type=26)，没有双向关联列
     * 预期：lookup列不触发双向关联的特殊删除逻辑，直接删除数据表
     */
    @Test
    void testDeleteNoteDwtableById_onlyLookupColumn()
    {
        List<NoteColumn> columns = new ArrayList<>();
        NoteColumn lookupCol = new NoteColumn();
        lookupCol.setId(1005L);
        lookupCol.setType(26L);
        lookupCol.setDwtableId(TEST_DWTABLE_ID);
        JSONObject prop = new JSONObject();
        prop.put("double_link_column_id", "1004");
        prop.put("source_column_id", "3001");
        lookupCol.setProperty(prop.toJSONString());
        columns.add(lookupCol);

        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(columns);

        int result = noteDwtableService.deleteNoteDwtableById(TEST_DWTABLE_ID);

        assertEquals(1, result);
        // lookup列不触发双向关联的特殊删除
        verify(noteDwtableItemMapper, never()).deleteNoteDwtableItemByColumnId(anyLong());
        verify(noteColumnMapper, never()).deleteNoteColumnById(anyLong());
        verify(noteDwtableItemMapper, never()).deleteNoteDwtableItemByDwtId(anyLong());
    }
}
