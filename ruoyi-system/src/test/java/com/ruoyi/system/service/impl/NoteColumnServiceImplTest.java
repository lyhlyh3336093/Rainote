package com.ruoyi.system.service.impl;

import com.ruoyi.common.utils.myHashMap;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.NoteDwtable;
import com.ruoyi.system.domain.vo.NoteColumnVo;
import com.ruoyi.system.domain.vo.NoteRecordVo;
import com.ruoyi.system.mapper.NoteColumnMapper;
import com.ruoyi.system.mapper.NoteDwtableItemMapper;
import com.ruoyi.system.mapper.NoteDwtableMapper;
import com.ruoyi.system.mapper.NoteRecordMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * NoteColumnServiceImpl单元测试
 * 重点验证：
 * 1. updateNoteColumn在type=21时不会用硬编码sort覆盖原列排序
 * 2. belink列获取正确的next sort
 * 3. deleteDataWhenLink在property为null时不抛NPE
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class NoteColumnServiceImplTest
{
    @Mock
    private NoteColumnMapper noteColumnMapper;

    @Mock
    private NoteDwtableMapper noteDwtableMapper;

    @Mock
    private NoteDwtableItemMapper noteDwtableItemMapper;

    @Mock
    private NoteRecordMapper noteRecordMapper;

    @InjectMocks
    private NoteColumnServiceImpl noteColumnService;

    /**
     * 模拟MyBatis的useGeneratedKeys行为：insert时给NoteColumn设置自增id
     */
    private void mockInsertWithGeneratedKey()
    {
        AtomicLong idSeq = new AtomicLong(100L);
        when(noteColumnMapper.insertNoteColumn(any(NoteColumn.class))).thenAnswer(invocation -> {
            NoteColumn c = invocation.getArgument(0);
            c.setId(idSeq.getAndIncrement());
            return 1;
        });
    }

    /**
     * 场景1：updateNoteColumn修改双向链接列(type=21)时，主列sort不应被覆盖为硬编码6
     * 修复前：noteColumn.setSort(6L) 会把原列sort改成6，导致列在UI中跳位
     * 修复后：sort保持null，XML mapper的<if test="sort != null">不会更新DB中的sort
     */
    @Test
    void testUpdateNoteColumn_type21_preservesOriginalSort()
    {
        // 原列：type=21, sort=3, property含back_field_id
        NoteColumn originColumn = new NoteColumn();
        originColumn.setId(10L);
        originColumn.setType(21L);
        originColumn.setSort(3L);
        originColumn.setDwtableId(1L);
        originColumn.setProperty("{\"back_field_id\":999}");

        when(noteColumnMapper.selectNoteColumnById(10L)).thenReturn(originColumn);

        // VO：type=21, property含table_id=2
        NoteColumnVo vo = new NoteColumnVo();
        vo.setId(10L);
        vo.setName("关联列");
        vo.setType(21L);
        vo.setDwtableId(1L);
        vo.setIsShow(1L);
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("table_id", 2);
        vo.setProperty(property);

        // 被关联表
        NoteDwtable linkDwtable = new NoteDwtable();
        linkDwtable.setId(2L);
        linkDwtable.setName("被关联表");
        when(noteDwtableMapper.selectNoteDwtableById(2L)).thenReturn(linkDwtable);

        // 当前表
        NoteDwtable thisDwtable = new NoteDwtable();
        thisDwtable.setId(1L);
        thisDwtable.setName("当前表");
        when(noteDwtableMapper.selectNoteDwtableById(1L)).thenReturn(thisDwtable);

        // 被关联表已有列最大sort=5
        NoteColumn existing = new NoteColumn();
        existing.setSort(5L);
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Collections.singletonList(existing));

        // 被关联表无记录
        when(noteRecordMapper.selectNoteRecordList(any(NoteRecordVo.class))).thenReturn(Collections.emptyList());

        mockInsertWithGeneratedKey();

        noteColumnService.updateNoteColumn(vo);

        // 捕获所有updateNoteColumn调用，最后一次的noteColumn sort应为null（不覆盖原sort）
        ArgumentCaptor<NoteColumn> captor = ArgumentCaptor.forClass(NoteColumn.class);
        verify(noteColumnMapper, atLeastOnce()).updateNoteColumn(captor.capture());
        NoteColumn lastUpdated = captor.getValue();
        assertNull(lastUpdated.getSort(), "主列sort不应被硬编码覆盖，应保持null以保留原排序");
    }

    /**
     * 场景2：updateNoteColumn修改双向链接列(type=21)时，belink列应获取被关联表的next sort
     * 修复前：belinkColumn.setSort(6L) 硬编码为6
     * 修复后：belinkColumn.setSort(getNextSort(tableId))，即maxSort+1
     */
    @Test
    void testUpdateNoteColumn_type21_belinkGetsNextSort()
    {
        NoteColumn originColumn = new NoteColumn();
        originColumn.setId(10L);
        originColumn.setType(21L);
        originColumn.setSort(3L);
        originColumn.setDwtableId(1L);
        originColumn.setProperty("{\"back_field_id\":999}");

        when(noteColumnMapper.selectNoteColumnById(10L)).thenReturn(originColumn);

        NoteColumnVo vo = new NoteColumnVo();
        vo.setId(10L);
        vo.setName("关联列");
        vo.setType(21L);
        vo.setDwtableId(1L);
        vo.setIsShow(1L);
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("table_id", 2);
        vo.setProperty(property);

        NoteDwtable linkDwtable = new NoteDwtable();
        linkDwtable.setId(2L);
        linkDwtable.setName("被关联表");
        when(noteDwtableMapper.selectNoteDwtableById(2L)).thenReturn(linkDwtable);

        NoteDwtable thisDwtable = new NoteDwtable();
        thisDwtable.setId(1L);
        thisDwtable.setName("当前表");
        when(noteDwtableMapper.selectNoteDwtableById(1L)).thenReturn(thisDwtable);

        // 被关联表已有列最大sort=8
        NoteColumn existing1 = new NoteColumn();
        existing1.setSort(3L);
        NoteColumn existing2 = new NoteColumn();
        existing2.setSort(8L);
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(Arrays.asList(existing1, existing2));

        when(noteRecordMapper.selectNoteRecordList(any(NoteRecordVo.class))).thenReturn(Collections.emptyList());

        mockInsertWithGeneratedKey();

        noteColumnService.updateNoteColumn(vo);

        // 捕获insertNoteColumn调用，belinkColumn的sort应为9（maxSort 8 + 1）
        ArgumentCaptor<NoteColumn> captor = ArgumentCaptor.forClass(NoteColumn.class);
        verify(noteColumnMapper).insertNoteColumn(captor.capture());
        NoteColumn insertedBelink = captor.getValue();
        assertEquals(9L, insertedBelink.getSort(), "belink列sort应为被关联表maxSort+1");
    }

    /**
     * 场景3：deleteNoteColumnByIds删除type=25且property为null的列时不应抛NPE
     * 修复前：deleteDataWhenLink直接parseObject(null)再调用isEmpty()导致NPE
     * 修复后：property为null时直接返回false
     */
    @Test
    void testDeleteNoteColumnByIds_type25_nullProperty_noNPE()
    {
        NoteColumn column = new NoteColumn();
        column.setId(50L);
        column.setType(25L);
        column.setProperty(null);

        when(noteColumnMapper.selectNoteColumnByIds(any(String[].class))).thenReturn(Collections.singletonList(column));

        assertDoesNotThrow(() -> noteColumnService.deleteNoteColumnByIds(new String[]{"50"}));
    }

    /**
     * 场景4：insertNoteColumn新增双向链接列(type=21)时，主列和belink列都应获取各自表的next sort
     */
    @Test
    void testInsertNoteColumn_type21_bothColumnsGetNextSort()
    {
        NoteColumnVo vo = new NoteColumnVo();
        vo.setName("关联列");
        vo.setType(21L);
        vo.setDwtableId(1L);
        vo.setIsShow(1L);
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("table_id", 2);
        vo.setProperty(property);

        NoteDwtable linkDwtable = new NoteDwtable();
        linkDwtable.setId(2L);
        linkDwtable.setName("被关联表");
        when(noteDwtableMapper.selectNoteDwtableById(2L)).thenReturn(linkDwtable);

        NoteDwtable thisDwtable = new NoteDwtable();
        thisDwtable.setId(1L);
        thisDwtable.setName("当前表");
        when(noteDwtableMapper.selectNoteDwtableById(1L)).thenReturn(thisDwtable);

        // 当前表maxSort=4，被关联表maxSort=7
        // 由于selectNoteColumnList被调用两次（主列dwtableId=1, belink列dwtableId=2），
        // 用thenAnswer根据dwtableId返回不同数据
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenAnswer(invocation -> {
            NoteColumn query = invocation.getArgument(0);
            Long dwtId = query.getDwtableId();
            NoteColumn c = new NoteColumn();
            if (dwtId != null && dwtId.longValue() == 1L) {
                c.setSort(4L);
            } else if (dwtId != null && dwtId.longValue() == 2L) {
                c.setSort(7L);
            }
            return Collections.singletonList(c);
        });

        when(noteRecordMapper.selectNoteRecordList(any(NoteRecordVo.class))).thenReturn(Collections.emptyList());

        mockInsertWithGeneratedKey();

        noteColumnService.insertNoteColumn(vo);

        // 捕获两次insertNoteColumn：主列sort=5, belink列sort=8
        ArgumentCaptor<NoteColumn> captor = ArgumentCaptor.forClass(NoteColumn.class);
        verify(noteColumnMapper, times(2)).insertNoteColumn(captor.capture());
        List<NoteColumn> inserted = captor.getAllValues();
        // 第一次插入的是主列
        assertEquals(5L, inserted.get(0).getSort(), "主列sort应为当前表maxSort+1");
        // 第二次插入的是belink列
        assertEquals(8L, inserted.get(1).getSort(), "belink列sort应为被关联表maxSort+1");
    }
}
