package com.ruoyi.system.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.utils.myHashMap;
import com.ruoyi.system.domain.NoteBlock;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.NoteDwtable;
import com.ruoyi.system.domain.NoteDwtableItem;
import com.ruoyi.system.domain.NoteNotelink;
import com.ruoyi.system.domain.vo.NoteColumnVo;
import com.ruoyi.system.domain.vo.NoteRecordVo;
import com.ruoyi.system.mapper.NoteBlockMapper;
import com.ruoyi.system.mapper.NoteColumnMapper;
import com.ruoyi.system.mapper.NoteDwtableItemMapper;
import com.ruoyi.system.mapper.NoteDwtableMapper;
import com.ruoyi.system.mapper.NoteRecordMapper;
import com.ruoyi.system.service.INoteNotelinkService;
import com.ruoyi.system.service.INoteRecordService;
import com.ruoyi.system.service.NoteBlockContentService;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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

    @Mock
    private INoteRecordService noteRecordService;

    // type=25 级联删除新增依赖（U3）
    @Mock
    private INoteNotelinkService noteNotelinkService;

    @Mock
    private NoteBlockContentService noteBlockContentService;

    @Mock
    private NoteBlockMapper noteBlockMapper;

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

    /**
     * 场景5（R6/AE3触发）：type=26 lookup列，dedupe从false变为true，应触发全量重算
     */
    @Test
    void testUpdateNoteColumn_type26_dedupeFalseToTrue_triggersRecompute()
    {
        NoteColumn originColumn = new NoteColumn();
        originColumn.setId(100L);
        originColumn.setType(26L);
        originColumn.setDwtableId(1L);
        originColumn.setProperty("{\"dedupe\":false,\"double_link_column_id\":\"200\",\"source_column_id\":\"300\"}");

        when(noteColumnMapper.selectNoteColumnById(100L)).thenReturn(originColumn);

        NoteColumnVo vo = new NoteColumnVo();
        vo.setId(100L);
        vo.setName("lookup列");
        vo.setType(26L);
        vo.setDwtableId(1L);
        vo.setIsShow(1L);
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("dedupe", true);
        property.put("double_link_column_id", "200");
        property.put("source_column_id", "300");
        vo.setProperty(property);

        noteColumnService.updateNoteColumn(vo);

        verify(noteRecordService).recomputeLookupColumnValues(any(NoteColumn.class));
    }

    /**
     * 场景6（R6）：type=26 lookup列，dedupe从true变为false，应触发全量重算
     */
    @Test
    void testUpdateNoteColumn_type26_dedupeTrueToFalse_triggersRecompute()
    {
        NoteColumn originColumn = new NoteColumn();
        originColumn.setId(100L);
        originColumn.setType(26L);
        originColumn.setDwtableId(1L);
        originColumn.setProperty("{\"dedupe\":true,\"double_link_column_id\":\"200\",\"source_column_id\":\"300\"}");

        when(noteColumnMapper.selectNoteColumnById(100L)).thenReturn(originColumn);

        NoteColumnVo vo = new NoteColumnVo();
        vo.setId(100L);
        vo.setName("lookup列");
        vo.setType(26L);
        vo.setDwtableId(1L);
        vo.setIsShow(1L);
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("dedupe", false);
        property.put("double_link_column_id", "200");
        property.put("source_column_id", "300");
        vo.setProperty(property);

        noteColumnService.updateNoteColumn(vo);

        verify(noteRecordService).recomputeLookupColumnValues(any(NoteColumn.class));
    }

    /**
     * 场景7（R6）：type=26 lookup列，dedupe未变化（true→true），不应触发重算
     */
    @Test
    void testUpdateNoteColumn_type26_dedupeUnchanged_noRecompute()
    {
        NoteColumn originColumn = new NoteColumn();
        originColumn.setId(100L);
        originColumn.setType(26L);
        originColumn.setDwtableId(1L);
        originColumn.setProperty("{\"dedupe\":true,\"double_link_column_id\":\"200\",\"source_column_id\":\"300\"}");

        when(noteColumnMapper.selectNoteColumnById(100L)).thenReturn(originColumn);

        NoteColumnVo vo = new NoteColumnVo();
        vo.setId(100L);
        vo.setName("lookup列");
        vo.setType(26L);
        vo.setDwtableId(1L);
        vo.setIsShow(1L);
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("dedupe", true);
        property.put("double_link_column_id", "200");
        property.put("source_column_id", "300");
        vo.setProperty(property);

        noteColumnService.updateNoteColumn(vo);

        verify(noteRecordService, never()).recomputeLookupColumnValues(any(NoteColumn.class));
    }

    /**
     * 场景8（R6）：列类型非26（如type=1普通文本列），不应触发重算
     */
    @Test
    void testUpdateNoteColumn_typeNot26_noRecompute()
    {
        NoteColumn originColumn = new NoteColumn();
        originColumn.setId(50L);
        originColumn.setType(1L);
        originColumn.setDwtableId(1L);
        originColumn.setProperty(null);

        when(noteColumnMapper.selectNoteColumnById(50L)).thenReturn(originColumn);

        NoteColumnVo vo = new NoteColumnVo();
        vo.setId(50L);
        vo.setName("文本列");
        vo.setType(1L);
        vo.setDwtableId(1L);
        vo.setIsShow(1L);

        noteColumnService.updateNoteColumn(vo);

        verify(noteRecordService, never()).recomputeLookupColumnValues(any(NoteColumn.class));
    }

    /**
     * 场景9（R2）：property中无dedupe字段（视为false），新property的dedupe=true，应触发重算
     */
    @Test
    void testUpdateNoteColumn_type26_dedupeMissingToTrue_triggersRecompute()
    {
        NoteColumn originColumn = new NoteColumn();
        originColumn.setId(100L);
        originColumn.setType(26L);
        originColumn.setDwtableId(1L);
        originColumn.setProperty("{\"double_link_column_id\":\"200\",\"source_column_id\":\"300\"}");

        when(noteColumnMapper.selectNoteColumnById(100L)).thenReturn(originColumn);

        NoteColumnVo vo = new NoteColumnVo();
        vo.setId(100L);
        vo.setName("lookup列");
        vo.setType(26L);
        vo.setDwtableId(1L);
        vo.setIsShow(1L);
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("dedupe", true);
        property.put("double_link_column_id", "200");
        property.put("source_column_id", "300");
        vo.setProperty(property);

        noteColumnService.updateNoteColumn(vo);

        verify(noteRecordService).recomputeLookupColumnValues(any(NoteColumn.class));
    }

    /**
     * 场景10（R6）：originColumn的property为null，不应触发重算（避免NPE）
     */
    @Test
    void testUpdateNoteColumn_type26_nullProperty_noRecompute()
    {
        NoteColumn originColumn = new NoteColumn();
        originColumn.setId(100L);
        originColumn.setType(26L);
        originColumn.setDwtableId(1L);
        originColumn.setProperty(null);

        when(noteColumnMapper.selectNoteColumnById(100L)).thenReturn(originColumn);

        NoteColumnVo vo = new NoteColumnVo();
        vo.setId(100L);
        vo.setName("lookup列");
        vo.setType(26L);
        vo.setDwtableId(1L);
        vo.setIsShow(1L);

        noteColumnService.updateNoteColumn(vo);

        verify(noteRecordService, never()).recomputeLookupColumnValues(any(NoteColumn.class));
    }

    /**
     * 场景11：property非null但非合法JSON（parseObject返回null），不应NPE，不触发重算
     */
    @Test
    void testUpdateNoteColumn_type26_malformedPropertyJson_noNpe_noRecompute()
    {
        NoteColumn originColumn = new NoteColumn();
        originColumn.setId(100L);
        originColumn.setType(26L);
        originColumn.setDwtableId(1L);
        originColumn.setProperty("not-a-json");

        when(noteColumnMapper.selectNoteColumnById(100L)).thenReturn(originColumn);

        NoteColumnVo vo = new NoteColumnVo();
        vo.setId(100L);
        vo.setName("lookup列");
        vo.setType(26L);
        vo.setDwtableId(1L);
        vo.setIsShow(1L);
        myHashMap<String, Object> property = new myHashMap<>();
        property.put("dedupe", true);
        property.put("double_link_column_id", "200");
        property.put("source_column_id", "300");
        vo.setProperty(property);

        // 不应抛NPE，updateNoteColumn正常完成
        noteColumnService.updateNoteColumn(vo);

        verify(noteRecordService, never()).recomputeLookupColumnValues(any(NoteColumn.class));
    }

    // ============ U4: 列结构变更触发按表重算 name ============

    /**
     * U4 场景1（happy）：deleteNoteColumnById 删除 type=1 列 → 触发该表重算
     */
    @Test
    void testDeleteNoteColumnById_triggersRecomputeRecordNames()
    {
        NoteColumn column = new NoteColumn();
        column.setId(50L);
        column.setType(1L);
        column.setDwtableId(1L);

        when(noteColumnMapper.selectNoteColumnById(50L)).thenReturn(column);

        noteColumnService.deleteNoteColumnById(50L);

        verify(noteDwtableItemMapper).deleteNoteDwtableItemByColumnId(50L);
        verify(noteColumnMapper).deleteNoteColumnById(50L);
        verify(noteRecordService).recomputeRecordNamesForTable(1L);
    }

    /**
     * U4 场景2（integration）：deleteNoteColumnByIds 删除涉及多表的列 → 每个表都重算
     */
    @Test
    void testDeleteNoteColumnByIds_multipleTables_recomputeEach()
    {
        NoteColumn col1 = new NoteColumn(); col1.setId(10L); col1.setType(1L); col1.setDwtableId(1L);
        NoteColumn col2 = new NoteColumn(); col2.setId(20L); col2.setType(1L); col2.setDwtableId(2L);
        when(noteColumnMapper.selectNoteColumnByIds(any(String[].class))).thenReturn(Arrays.asList(col1, col2));

        noteColumnService.deleteNoteColumnByIds(new String[]{"10", "20"});

        // 每个表各重算一次
        verify(noteRecordService).recomputeRecordNamesForTable(1L);
        verify(noteRecordService).recomputeRecordNamesForTable(2L);
    }

    /**
     * U4 场景3（edge）：deleteNoteColumnById 列不存在（column==null）→ 不触发重算
     */
    @Test
    void testDeleteNoteColumnById_columnNotFound_noRecompute()
    {
        when(noteColumnMapper.selectNoteColumnById(999L)).thenReturn(null);

        noteColumnService.deleteNoteColumnById(999L);

        verify(noteColumnMapper).deleteNoteColumnById(999L);
        verify(noteRecordService, never()).recomputeRecordNamesForTable(any());
    }

    /**
     * U4 场景4（edge，幂等）：deleteNoteColumnByIds 删除非源列（type=2）→ 重算仍运行（幂等）
     */
    @Test
    void testDeleteNoteColumnByIds_nonSourceColumn_recomputeStillRuns()
    {
        NoteColumn col = new NoteColumn();
        col.setId(10L);
        col.setType(2L); // 非源列
        col.setDwtableId(1L);
        when(noteColumnMapper.selectNoteColumnByIds(any(String[].class))).thenReturn(Collections.singletonList(col));

        noteColumnService.deleteNoteColumnByIds(new String[]{"10"});

        // 即使删的是非源列，重算仍运行（KTD-6 无条件幂等）
        verify(noteRecordService).recomputeRecordNamesForTable(1L);
    }

    /**
     * U4 场景5（happy）：updateNoteColumn 修改 type=1 列名称 → 末尾触发重算
     */
    @Test
    void testUpdateNoteColumn_typeOne_triggersRecomputeRecordNames()
    {
        NoteColumn originColumn = new NoteColumn();
        originColumn.setId(50L);
        originColumn.setType(1L);
        originColumn.setDwtableId(1L);
        originColumn.setProperty(null);

        when(noteColumnMapper.selectNoteColumnById(50L)).thenReturn(originColumn);

        NoteColumnVo vo = new NoteColumnVo();
        vo.setId(50L);
        vo.setName("新名称");
        vo.setType(1L);
        vo.setDwtableId(1L);
        vo.setIsShow(1L);

        noteColumnService.updateNoteColumn(vo);

        verify(noteRecordService).recomputeRecordNamesForTable(1L);
    }

    /**
     * U4 场景6（happy）：updateSort 重排 → 重算受影响表
     */
    @Test
    void testUpdateSort_triggersRecomputeRecordNames()
    {
        NoteColumn col = new NoteColumn();
        col.setId(10L);
        col.setType(1L);
        col.setDwtableId(1L);
        col.setSort(1L);
        when(noteColumnMapper.selectNoteColumnById(10L)).thenReturn(col);

        java.util.Map<String, Object> sortData = new java.util.HashMap<>();
        sortData.put("id", "10");
        sortData.put("sort", "5");

        noteColumnService.updateSort(Collections.singletonList(sortData));

        verify(noteColumnMapper).updateNoteColumn(any(NoteColumn.class));
        verify(noteRecordService).recomputeRecordNamesForTable(1L);
    }

    /**
     * U4 场景7（integration）：updateSort 涉及多表 → 每个表都重算（去重）
     */
    @Test
    void testUpdateSort_multipleTables_recomputeEach()
    {
        NoteColumn col1 = new NoteColumn(); col1.setId(10L); col1.setDwtableId(1L); col1.setSort(1L);
        NoteColumn col2 = new NoteColumn(); col2.setId(20L); col2.setDwtableId(2L); col2.setSort(1L);
        NoteColumn col3 = new NoteColumn(); col3.setId(30L); col3.setDwtableId(1L); col3.setSort(1L); // 与 col1 同表
        when(noteColumnMapper.selectNoteColumnById(10L)).thenReturn(col1);
        when(noteColumnMapper.selectNoteColumnById(20L)).thenReturn(col2);
        when(noteColumnMapper.selectNoteColumnById(30L)).thenReturn(col3);

        java.util.Map<String, Object> d1 = new java.util.HashMap<>(); d1.put("id", "10"); d1.put("sort", "2");
        java.util.Map<String, Object> d2 = new java.util.HashMap<>(); d2.put("id", "20"); d2.put("sort", "2");
        java.util.Map<String, Object> d3 = new java.util.HashMap<>(); d3.put("id", "30"); d3.put("sort", "3");

        noteColumnService.updateSort(Arrays.asList(d1, d2, d3));

        // 表1被重算一次（col1 + col3 去重），表2被重算一次
        verify(noteRecordService, times(1)).recomputeRecordNamesForTable(1L);
        verify(noteRecordService, times(1)).recomputeRecordNamesForTable(2L);
    }

    // ============ deduplicate 接口 ============

    /**
     * dedupe 从 false → true：切换开关并触发两个重算
     */
    @Test
    void testDeduplicate_dedupeFalseToTrue_togglesAndRecomputes()
    {
        NoteColumn column = new NoteColumn();
        column.setId(100L);
        column.setType(26L);
        column.setDwtableId(1L);
        column.setProperty("{\"dedupe\":false,\"double_link_column_id\":\"200\",\"source_column_id\":\"300\"}");
        when(noteColumnMapper.selectNoteColumnById(100L)).thenReturn(column);

        int result = noteColumnService.deduplicate(100L);

        assertEquals(1, result);
        ArgumentCaptor<NoteColumn> captor = ArgumentCaptor.forClass(NoteColumn.class);
        verify(noteColumnMapper).updateNoteColumn(captor.capture());
        JSONObject updatedProp = JSONObject.parseObject(captor.getValue().getProperty());
        assertTrue(updatedProp.getBoolean("dedupe"));
        verify(noteRecordService).recomputeLookupColumnValues(any(NoteColumn.class));
        verify(noteRecordService).recomputeSetOperationsForLookup(any(NoteColumn.class));
    }

    /**
     * dedupe 从 true → false
     */
    @Test
    void testDeduplicate_dedupeTrueToFalse_togglesAndRecomputes()
    {
        NoteColumn column = new NoteColumn();
        column.setId(100L);
        column.setType(26L);
        column.setDwtableId(1L);
        column.setProperty("{\"dedupe\":true,\"double_link_column_id\":\"200\",\"source_column_id\":\"300\"}");
        when(noteColumnMapper.selectNoteColumnById(100L)).thenReturn(column);

        int result = noteColumnService.deduplicate(100L);

        assertEquals(1, result);
        ArgumentCaptor<NoteColumn> captor = ArgumentCaptor.forClass(NoteColumn.class);
        verify(noteColumnMapper).updateNoteColumn(captor.capture());
        JSONObject updatedProp = JSONObject.parseObject(captor.getValue().getProperty());
        assertFalse(updatedProp.getBoolean("dedupe"));
        verify(noteRecordService).recomputeLookupColumnValues(any(NoteColumn.class));
        verify(noteRecordService).recomputeSetOperationsForLookup(any(NoteColumn.class));
    }

    /**
     * dedupe 字段缺失，默认 false → true
     */
    @Test
    void testDeduplicate_dedupeMissing_defaultsFalseToTrue()
    {
        NoteColumn column = new NoteColumn();
        column.setId(100L);
        column.setType(26L);
        column.setDwtableId(1L);
        column.setProperty("{\"double_link_column_id\":\"200\",\"source_column_id\":\"300\"}");
        when(noteColumnMapper.selectNoteColumnById(100L)).thenReturn(column);

        int result = noteColumnService.deduplicate(100L);

        assertEquals(1, result);
        ArgumentCaptor<NoteColumn> captor = ArgumentCaptor.forClass(NoteColumn.class);
        verify(noteColumnMapper).updateNoteColumn(captor.capture());
        JSONObject updatedProp = JSONObject.parseObject(captor.getValue().getProperty());
        assertTrue(updatedProp.getBoolean("dedupe"));
        verify(noteRecordService).recomputeLookupColumnValues(any(NoteColumn.class));
        verify(noteRecordService).recomputeSetOperationsForLookup(any(NoteColumn.class));
    }

    /**
     * 列不存在 → 返回0，不触发重算
     */
    @Test
    void testDeduplicate_columnNotFound_returnsZero()
    {
        when(noteColumnMapper.selectNoteColumnById(999L)).thenReturn(null);

        int result = noteColumnService.deduplicate(999L);

        assertEquals(0, result);
        verify(noteColumnMapper, never()).updateNoteColumn(any(NoteColumn.class));
        verify(noteRecordService, never()).recomputeLookupColumnValues(any(NoteColumn.class));
        verify(noteRecordService, never()).recomputeSetOperationsForLookup(any(NoteColumn.class));
    }

    /**
     * 非 type=26 → 返回0
     */
    @Test
    void testDeduplicate_notType26_returnsZero()
    {
        NoteColumn column = new NoteColumn();
        column.setId(100L);
        column.setType(21L);
        column.setProperty("{\"dedupe\":false}");
        when(noteColumnMapper.selectNoteColumnById(100L)).thenReturn(column);

        int result = noteColumnService.deduplicate(100L);

        assertEquals(0, result);
        verify(noteColumnMapper, never()).updateNoteColumn(any(NoteColumn.class));
        verify(noteRecordService, never()).recomputeLookupColumnValues(any(NoteColumn.class));
    }

    /**
     * property 为 null → 返回0
     */
    @Test
    void testDeduplicate_nullProperty_returnsZero()
    {
        NoteColumn column = new NoteColumn();
        column.setId(100L);
        column.setType(26L);
        column.setProperty(null);
        when(noteColumnMapper.selectNoteColumnById(100L)).thenReturn(column);

        int result = noteColumnService.deduplicate(100L);

        assertEquals(0, result);
        verify(noteColumnMapper, never()).updateNoteColumn(any(NoteColumn.class));
        verify(noteRecordService, never()).recomputeLookupColumnValues(any(NoteColumn.class));
    }

    /**
     * property 非合法JSON → 返回0，不抛异常（修复后）
     */
    @Test
    void testDeduplicate_malformedPropertyJson_returnsZero()
    {
        NoteColumn column = new NoteColumn();
        column.setId(100L);
        column.setType(26L);
        column.setProperty("not-a-json");
        when(noteColumnMapper.selectNoteColumnById(100L)).thenReturn(column);

        int result = noteColumnService.deduplicate(100L);

        assertEquals(0, result);
        verify(noteColumnMapper, never()).updateNoteColumn(any(NoteColumn.class));
        verify(noteRecordService, never()).recomputeLookupColumnValues(any(NoteColumn.class));
    }

    // ============ U3: type=25 级联删除核心测试 ============

    /**
     * AE1（REVERSE-only）：列有 NoteNotelink 无 FORWARD NoteDwtableItem
     * → NoteNotelink 删除 + REVERSE 锚点按 data-link-id 恢复
     */
    @Test
    void testDeleteNoteColumnByIds_type25_reverseOnly_restoresAnchorAndDeletesLinks()
    {
        NoteColumn column = new NoteColumn();
        column.setId(50L);
        column.setType(25L);
        column.setDwtableId(1L);

        when(noteColumnMapper.selectNoteColumnByIds(any(String[].class)))
                .thenReturn(Collections.singletonList(column));
        // 无 FORWARD
        when(noteDwtableItemMapper.selectItemsByColumnIdWithLinkBlockId(50L))
                .thenReturn(Collections.emptyList());
        // 有 REVERSE：NoteNotelink(id=100, blockId=200)
        NoteNotelink link = new NoteNotelink();
        link.setId(100L);
        link.setBlockId(200L);
        link.setLinkColumnId(50L);
        when(noteNotelinkService.selectNoteNotelinkList(any(NoteNotelink.class)))
                .thenReturn(Collections.singletonList(link));
        // 受影响 NoteBlock
        NoteBlock block = new NoteBlock();
        block.setId(200L);
        block.setProperty("{\"data\":{\"text\":\"<a data-link-id=\\\"100\\\">[X]</a>\"}}");
        when(noteBlockMapper.selectNoteBlockById(200L)).thenReturn(block);
        // 文本恢复返回新 property
        when(noteBlockContentService.restoreAnchors(anyString(), eq(50L), anySet(), anySet()))
                .thenReturn("{\"data\":{\"text\":\"X\"}}");

        noteColumnService.deleteNoteColumnByIds(new String[]{"50"});

        // R4: 删除 REVERSE 的 NoteNotelink（传被删列自身 id）
        verify(noteNotelinkService).deleteNoteNotelinkByColumnId(50L);
        // R5: 删除 FORWARD 的 NoteDwtableItem
        verify(noteDwtableItemMapper).deleteNoteDwtableItemByColumnId(50L);
        // R7/R8: 文本恢复写回
        verify(noteBlockMapper).updateNoteBlock(any(NoteBlock.class));
        // 列本身删除
        verify(noteColumnMapper).deleteNoteColumnByIds(any(String[].class));
        // 表名重算
        verify(noteRecordService).recomputeRecordNamesForTable(1L);
    }

    /**
     * AE2（FORWARD-only）：列有 NoteDwtableItem(linkBlockId 非空) 无 NoteNotelink
     * → FORWARD 收集 + 锚点按 data-column-id 恢复
     */
    @Test
    void testDeleteNoteColumnByIds_type25_forwardOnly_restoresAnchorAndDeletesItems()
    {
        NoteColumn column = new NoteColumn();
        column.setId(50L);
        column.setType(25L);
        column.setDwtableId(1L);

        when(noteColumnMapper.selectNoteColumnByIds(any(String[].class)))
                .thenReturn(Collections.singletonList(column));
        // 有 FORWARD：NoteDwtableItem(linkBlockId=200, dwtId=1, recordId=2)
        NoteDwtableItem item = new NoteDwtableItem();
        item.setLinkBlockId(200L);
        item.setDwtId(1L);
        item.setRecordId(2L);
        when(noteDwtableItemMapper.selectItemsByColumnIdWithLinkBlockId(50L))
                .thenReturn(Collections.singletonList(item));
        // 无 REVERSE
        when(noteNotelinkService.selectNoteNotelinkList(any(NoteNotelink.class)))
                .thenReturn(Collections.emptyList());
        // 受影响 NoteBlock
        NoteBlock block = new NoteBlock();
        block.setId(200L);
        block.setProperty("{\"data\":{\"text\":\"<a data-column-id=\\\"50\\\">[X]</a>\"}}");
        when(noteBlockMapper.selectNoteBlockById(200L)).thenReturn(block);
        when(noteBlockContentService.restoreAnchors(anyString(), eq(50L), anySet(), anySet()))
                .thenReturn("{\"data\":{\"text\":\"X\"}}");

        noteColumnService.deleteNoteColumnByIds(new String[]{"50"});

        verify(noteNotelinkService).deleteNoteNotelinkByColumnId(50L);
        verify(noteDwtableItemMapper).deleteNoteDwtableItemByColumnId(50L);
        verify(noteBlockMapper).updateNoteBlock(any(NoteBlock.class));
        verify(noteColumnMapper).deleteNoteColumnByIds(any(String[].class));
        verify(noteRecordService).recomputeRecordNamesForTable(1L);
    }

    /**
     * AE3（corrupted block）：一个 NoteBlock property 畸形 → 该块 skip + log.error
     * + 其他块正常 + 列与记录仍删除（best-effort，R10）
     */
    @Test
    void testDeleteNoteColumnByIds_type25_corruptedBlockSkipped_othersRestored_columnStillDeleted()
    {
        NoteColumn column = new NoteColumn();
        column.setId(50L);
        column.setType(25L);
        column.setDwtableId(1L);

        when(noteColumnMapper.selectNoteColumnByIds(any(String[].class)))
                .thenReturn(Collections.singletonList(column));
        // 两个 FORWARD item：blockId=200(正常), blockId=201(畸形)
        NoteDwtableItem item1 = new NoteDwtableItem();
        item1.setLinkBlockId(200L);
        item1.setDwtId(1L);
        item1.setRecordId(2L);
        NoteDwtableItem item2 = new NoteDwtableItem();
        item2.setLinkBlockId(201L);
        item2.setDwtId(1L);
        item2.setRecordId(3L);
        when(noteDwtableItemMapper.selectItemsByColumnIdWithLinkBlockId(50L))
                .thenReturn(Arrays.asList(item1, item2));
        when(noteNotelinkService.selectNoteNotelinkList(any(NoteNotelink.class)))
                .thenReturn(Collections.emptyList());

        // block 200 正常
        NoteBlock goodBlock = new NoteBlock();
        goodBlock.setId(200L);
        goodBlock.setProperty("{\"data\":{\"text\":\"a\"}}");
        when(noteBlockMapper.selectNoteBlockById(200L)).thenReturn(goodBlock);
        // block 201 畸形：restoreAnchors 抛异常模拟 property 解析失败
        NoteBlock badBlock = new NoteBlock();
        badBlock.setId(201L);
        badBlock.setProperty("malformed-json");
        when(noteBlockMapper.selectNoteBlockById(201L)).thenReturn(badBlock);
        // 正常块恢复成功
        when(noteBlockContentService.restoreAnchors(eq("{\"data\":{\"text\":\"a\"}}"), eq(50L), anySet(), anySet()))
                .thenReturn("{\"data\":{\"text\":\"restored\"}}");
        // 畸形块抛异常（模拟 JSON 解析失败）
        when(noteBlockContentService.restoreAnchors(eq("malformed-json"), eq(50L), anySet(), anySet()))
                .thenThrow(new IllegalArgumentException("bad json"));

        // 不应抛异常（per-block catch）
        assertDoesNotThrow(() -> noteColumnService.deleteNoteColumnByIds(new String[]{"50"}));

        // 正常块被更新
        verify(noteBlockMapper, times(1)).updateNoteBlock(any(NoteBlock.class));
        // 即使有块失败，列与记录仍删除（R10）
        verify(noteNotelinkService).deleteNoteNotelinkByColumnId(50L);
        verify(noteDwtableItemMapper).deleteNoteDwtableItemByColumnId(50L);
        verify(noteColumnMapper).deleteNoteColumnByIds(any(String[].class));
        verify(noteRecordService).recomputeRecordNamesForTable(1L);
    }

    /**
     * AE4（empty column）：无 cell value 无 NoteNotelink → 走 F2 路径
     * → R4/R5 防御性删除，无文本恢复（不调 restoreAnchors / updateNoteBlock）
     */
    @Test
    void testDeleteNoteColumnByIds_type25_emptyColumn_f2PathDefensiveDeleteNoRestore()
    {
        NoteColumn column = new NoteColumn();
        column.setId(50L);
        column.setType(25L);
        column.setDwtableId(1L);

        when(noteColumnMapper.selectNoteColumnByIds(any(String[].class)))
                .thenReturn(Collections.singletonList(column));
        when(noteDwtableItemMapper.selectItemsByColumnIdWithLinkBlockId(50L))
                .thenReturn(Collections.emptyList());
        when(noteNotelinkService.selectNoteNotelinkList(any(NoteNotelink.class)))
                .thenReturn(Collections.emptyList());

        noteColumnService.deleteNoteColumnByIds(new String[]{"50"});

        // F2: 防御性删除
        verify(noteDwtableItemMapper).deleteNoteDwtableItemByColumnId(50L);
        verify(noteNotelinkService).deleteNoteNotelinkByColumnId(50L);
        verify(noteColumnMapper).deleteNoteColumnByIds(any(String[].class));
        verify(noteRecordService).recomputeRecordNamesForTable(1L);
        // 无文本恢复
        verify(noteBlockContentService, never()).restoreAnchors(anyString(), anyLong(), anySet(), anySet());
        verify(noteBlockMapper, never()).updateNoteBlock(any(NoteBlock.class));
    }

    /**
     * linkBlockId 不变量：FORWARD-only cell value 被清空（linkBlockId 保留）
     * → selectItemsByColumnIdWithLinkBlockId 仍命中 → F1 路径执行 FORWARD 文本恢复
     */
    @Test
    void testDeleteNoteColumnByIds_type25_clearedValueLinkBlockIdPreserved_forwardRestored()
    {
        NoteColumn column = new NoteColumn();
        column.setId(50L);
        column.setType(25L);
        column.setDwtableId(1L);

        when(noteColumnMapper.selectNoteColumnByIds(any(String[].class)))
                .thenReturn(Collections.singletonList(column));
        // value 为空但 linkBlockId 保留 → selectItemsByColumnIdWithLinkBlockId 命中
        NoteDwtableItem item = new NoteDwtableItem();
        item.setLinkBlockId(200L);
        item.setDwtId(1L);
        item.setRecordId(2L);
        item.setValue(""); // value 被清空
        when(noteDwtableItemMapper.selectItemsByColumnIdWithLinkBlockId(50L))
                .thenReturn(Collections.singletonList(item));
        when(noteNotelinkService.selectNoteNotelinkList(any(NoteNotelink.class)))
                .thenReturn(Collections.emptyList());
        NoteBlock block = new NoteBlock();
        block.setId(200L);
        block.setProperty("{\"data\":{\"text\":\"<a data-column-id=\\\"50\\\">[X]</a>\"}}");
        when(noteBlockMapper.selectNoteBlockById(200L)).thenReturn(block);
        when(noteBlockContentService.restoreAnchors(anyString(), eq(50L), anySet(), anySet()))
                .thenReturn("{\"data\":{\"text\":\"X\"}}");

        noteColumnService.deleteNoteColumnByIds(new String[]{"50"});

        // 即使 value 为空，FORWARD 文本恢复仍执行
        verify(noteBlockMapper).updateNoteBlock(any(NoteBlock.class));
        verify(noteDwtableItemMapper).deleteNoteDwtableItemByColumnId(50L);
    }

    /**
     * 删除时 linkColumnId 匹配：只删本列 NoteNotelink（传被删列自身 id 50，非 back_field_id）
     */
    @Test
    void testDeleteNoteColumnByIds_type25_deletesOwnNotelinkByOwnColumnId()
    {
        NoteColumn column = new NoteColumn();
        column.setId(50L);
        column.setType(25L);
        column.setDwtableId(1L);

        when(noteColumnMapper.selectNoteColumnByIds(any(String[].class)))
                .thenReturn(Collections.singletonList(column));
        when(noteDwtableItemMapper.selectItemsByColumnIdWithLinkBlockId(50L))
                .thenReturn(Collections.emptyList());
        when(noteNotelinkService.selectNoteNotelinkList(any(NoteNotelink.class)))
                .thenReturn(Collections.emptyList());

        noteColumnService.deleteNoteColumnByIds(new String[]{"50"});

        // R4: 传被删列自身 id（50），不是 back_field_id
        verify(noteNotelinkService).deleteNoteNotelinkByColumnId(50L);
        verify(noteNotelinkService, never()).deleteNoteNotelinkByColumnId(999L);
    }

    /**
     * 多列批量删除（ids 含多个 type=25）：每列独立收集与恢复，互不干扰
     */
    @Test
    void testDeleteNoteColumnByIds_multipleType25Columns_independentCascade()
    {
        NoteColumn col1 = new NoteColumn();
        col1.setId(50L);
        col1.setType(25L);
        col1.setDwtableId(1L);
        NoteColumn col2 = new NoteColumn();
        col2.setId(60L);
        col2.setType(25L);
        col2.setDwtableId(2L);

        when(noteColumnMapper.selectNoteColumnByIds(any(String[].class)))
                .thenReturn(Arrays.asList(col1, col2));
        // 两列都无数据（F2）
        when(noteDwtableItemMapper.selectItemsByColumnIdWithLinkBlockId(anyLong()))
                .thenReturn(Collections.emptyList());
        when(noteNotelinkService.selectNoteNotelinkList(any(NoteNotelink.class)))
                .thenReturn(Collections.emptyList());

        noteColumnService.deleteNoteColumnByIds(new String[]{"50", "60"});

        // 每列各自删除 NoteNotelink 和 NoteDwtableItem
        verify(noteNotelinkService).deleteNoteNotelinkByColumnId(50L);
        verify(noteNotelinkService).deleteNoteNotelinkByColumnId(60L);
        verify(noteDwtableItemMapper).deleteNoteDwtableItemByColumnId(50L);
        verify(noteDwtableItemMapper).deleteNoteDwtableItemByColumnId(60L);
        // 两个表都重算
        verify(noteRecordService).recomputeRecordNamesForTable(1L);
        verify(noteRecordService).recomputeRecordNamesForTable(2L);
    }

    /**
     * F1 路径顺序约束：先 SELECT 收集 → 文本恢复 → DELETE records
     * （验证 deleteNoteDwtableItemByColumnId 在 restoreAnchors 之后调用，通过 InOrder）
     */
    @Test
    void testDeleteNoteColumnByIds_type25_correctOrder_selectBeforeDelete()
    {
        NoteColumn column = new NoteColumn();
        column.setId(50L);
        column.setType(25L);
        column.setDwtableId(1L);

        when(noteColumnMapper.selectNoteColumnByIds(any(String[].class)))
                .thenReturn(Collections.singletonList(column));
        NoteDwtableItem item = new NoteDwtableItem();
        item.setLinkBlockId(200L);
        item.setDwtId(1L);
        item.setRecordId(2L);
        when(noteDwtableItemMapper.selectItemsByColumnIdWithLinkBlockId(50L))
                .thenReturn(Collections.singletonList(item));
        when(noteNotelinkService.selectNoteNotelinkList(any(NoteNotelink.class)))
                .thenReturn(Collections.emptyList());
        NoteBlock block = new NoteBlock();
        block.setId(200L);
        block.setProperty("{\"data\":{\"text\":\"x\"}}");
        when(noteBlockMapper.selectNoteBlockById(200L)).thenReturn(block);
        when(noteBlockContentService.restoreAnchors(anyString(), eq(50L), anySet(), anySet()))
                .thenReturn("{\"data\":{\"text\":\"restored\"}}");

        noteColumnService.deleteNoteColumnByIds(new String[]{"50"});

        // 顺序：selectItemsByColumnIdWithLinkBlockId 在 deleteNoteDwtableItemByColumnId 之前
        org.mockito.InOrder inOrder = inOrder(noteDwtableItemMapper);
        inOrder.verify(noteDwtableItemMapper).selectItemsByColumnIdWithLinkBlockId(50L);
        inOrder.verify(noteDwtableItemMapper).deleteNoteDwtableItemByColumnId(50L);
    }
}
