package com.ruoyi.system.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.NoteDwtable;
import com.ruoyi.system.domain.NoteDwtableItem;
import com.ruoyi.system.domain.NoteRecord;
import com.ruoyi.system.domain.vo.NoteRecordVo;
import com.ruoyi.system.mapper.NoteColumnMapper;
import com.ruoyi.system.mapper.NoteDwtableItemMapper;
import com.ruoyi.system.mapper.NoteDwtableMapper;
import com.ruoyi.system.mapper.NoteRecordMapper;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * resolveLookupValue方法单元测试
 * 测试lookup列(type=26)的值解析逻辑，包括sourceColumn也是lookup列时的链式追踪
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class NoteRecordServiceImplTest
{

    @Mock
    private NoteColumnMapper noteColumnMapper;

    @Mock
    private NoteDwtableItemMapper noteDwtableItemMapper;

    @Mock
    private NoteDwtableMapper noteDwtableMapper;

    @Mock
    private NoteRecordMapper noteRecordMapper;

    @InjectMocks
    private NoteRecordServiceImpl noteRecordService;

    private NoteColumn lookupColumn;
    private NoteDwtableItem doubleLinkItem;

    @BeforeEach
    void setUp()
    {
        // 构造一个lookup列，property中包含double_link_column_id和source_column_id
        lookupColumn = new NoteColumn();
        lookupColumn.setId(100L);
        lookupColumn.setType(26L);
        JSONObject property = new JSONObject();
        property.put("double_link_column_id", "200");
        property.put("source_column_id", "300");
        lookupColumn.setProperty(property.toJSONString());

        // 构造双向链接item
        doubleLinkItem = new NoteDwtableItem();
        doubleLinkItem.setRecordId(1L);
        doubleLinkItem.setLinkColumnId(200L);
        doubleLinkItem.setLinkRecordId("10,20");
    }

    /**
     * 场景1：sourceColumn是普通列（非lookup），直接查找值
     * lookup列 -> double_link -> 关联记录 -> 普通列值
     */
    @Test
    void testResolveLookupValue_sourceColumnIsNormal()
    {
        // sourceColumn是普通文本列(type=1)
        NoteColumn sourceColumn = new NoteColumn();
        sourceColumn.setId(300L);
        sourceColumn.setType(1L);

        when(noteColumnMapper.selectNoteColumnById(300L)).thenReturn(sourceColumn);

        // 模拟关联记录10的值
        NoteDwtableItem valueItem10 = new NoteDwtableItem();
        valueItem10.setValue("张三");
        // 模拟关联记录20的值
        NoteDwtableItem valueItem20 = new NoteDwtableItem();
        valueItem20.setValue("李四");

        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(any(NoteDwtableItem.class)))
                .thenReturn(doubleLinkItem)   // 查找双向链接item
                .thenReturn(valueItem10)       // 查找记录10的值
                .thenReturn(valueItem20);      // 查找记录20的值

        Object result = noteRecordService.resolveLookupValue(lookupColumn, 1L);

        assertEquals("张三,李四", result);
    }

    /**
     * 场景2：sourceColumn也是lookup列，需要沿source_column_id链追踪到最终普通列
     * lookup列A -> sourceColumn(B, lookup) -> sourceColumn(C, 普通列)
     * 修复前的bug：解析itemColumn.getProperty()导致sourceColumnId没有更新
     * 修复后：解析sourceColumn.getProperty()，正确追踪到最终的sourceColumnId
     */
    @Test
    void testResolveLookupValue_sourceColumnIsLookup()
    {
        // sourceColumn B 也是lookup列，它的source_column_id指向列C(400)
        NoteColumn sourceColumnB = new NoteColumn();
        sourceColumnB.setId(300L);
        sourceColumnB.setType(26L);
        JSONObject propB = new JSONObject();
        propB.put("double_link_column_id", "500");
        propB.put("source_column_id", "400");
        sourceColumnB.setProperty(propB.toJSONString());

        // 最终的普通列 C
        NoteColumn sourceColumnC = new NoteColumn();
        sourceColumnC.setId(400L);
        sourceColumnC.setType(1L);

        when(noteColumnMapper.selectNoteColumnById(300L)).thenReturn(sourceColumnB);
        when(noteColumnMapper.selectNoteColumnById(400L)).thenReturn(sourceColumnC);

        // 模拟关联记录10和20在列400上的值
        NoteDwtableItem valueItem10 = new NoteDwtableItem();
        valueItem10.setValue("北京");
        NoteDwtableItem valueItem20 = new NoteDwtableItem();
        valueItem20.setValue("上海");

        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(any(NoteDwtableItem.class)))
                .thenReturn(doubleLinkItem)   // 查找双向链接item
                .thenReturn(valueItem10)       // 查找记录10在列400的值
                .thenReturn(valueItem20);      // 查找记录20在列400的值

        Object result = noteRecordService.resolveLookupValue(lookupColumn, 1L);

        // 修复后应该返回最终普通列C(400)上的值
        assertEquals("北京,上海", result);
    }

    /**
     * 场景3：多层lookup嵌套（3层），验证链式追踪到最终普通列
     * lookup列A -> lookup列B -> lookup列C -> 普通列D
     */
    @Test
    void testResolveLookupValue_deepNestedLookup()
    {
        // sourceColumn B 是lookup列，指向列C(400)
        NoteColumn sourceColumnB = new NoteColumn();
        sourceColumnB.setId(300L);
        sourceColumnB.setType(26L);
        JSONObject propB = new JSONObject();
        propB.put("double_link_column_id", "500");
        propB.put("source_column_id", "400");
        sourceColumnB.setProperty(propB.toJSONString());

        // sourceColumn C 也是lookup列，指向列D(600)
        NoteColumn sourceColumnC = new NoteColumn();
        sourceColumnC.setId(400L);
        sourceColumnC.setType(26L);
        JSONObject propC = new JSONObject();
        propC.put("double_link_column_id", "700");
        propC.put("source_column_id", "600");
        sourceColumnC.setProperty(propC.toJSONString());

        // 最终的普通列 D
        NoteColumn sourceColumnD = new NoteColumn();
        sourceColumnD.setId(600L);
        sourceColumnD.setType(1L);

        when(noteColumnMapper.selectNoteColumnById(300L)).thenReturn(sourceColumnB);
        when(noteColumnMapper.selectNoteColumnById(400L)).thenReturn(sourceColumnC);
        when(noteColumnMapper.selectNoteColumnById(600L)).thenReturn(sourceColumnD);

        NoteDwtableItem valueItem10 = new NoteDwtableItem();
        valueItem10.setValue("最终值1");
        NoteDwtableItem valueItem20 = new NoteDwtableItem();
        valueItem20.setValue("最终值2");

        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(any(NoteDwtableItem.class)))
                .thenReturn(doubleLinkItem)
                .thenReturn(valueItem10)
                .thenReturn(valueItem20);

        Object result = noteRecordService.resolveLookupValue(lookupColumn, 1L);

        assertEquals("最终值1,最终值2", result);
    }

    /**
     * 场景4：双向链接item不存在，返回空字符串
     */
    @Test
    void testResolveLookupValue_noDoubleLinkItem()
    {
        NoteColumn sourceColumn = new NoteColumn();
        sourceColumn.setId(300L);
        sourceColumn.setType(1L);

        when(noteColumnMapper.selectNoteColumnById(300L)).thenReturn(sourceColumn);
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(any(NoteDwtableItem.class)))
                .thenReturn(null);

        Object result = noteRecordService.resolveLookupValue(lookupColumn, 1L);

        assertEquals("", result);
    }

    /**
     * 场景5：双向链接item的linkRecordId为空，返回空字符串
     */
    @Test
    void testResolveLookupValue_emptyLinkRecordId()
    {
        NoteColumn sourceColumn = new NoteColumn();
        sourceColumn.setId(300L);
        sourceColumn.setType(1L);

        NoteDwtableItem emptyLinkItem = new NoteDwtableItem();
        emptyLinkItem.setLinkRecordId("");

        when(noteColumnMapper.selectNoteColumnById(300L)).thenReturn(sourceColumn);
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(any(NoteDwtableItem.class)))
                .thenReturn(emptyLinkItem);

        Object result = noteRecordService.resolveLookupValue(lookupColumn, 1L);

        assertEquals("", result);
    }

    /**
     * 场景6：lookup链超过5层深度限制，返回空字符串
     * lookupColumn的source_column_id=300，链路为：300->301->302->303->304->305(超过5层)
     */
    @Test
    void testResolveLookupValue_exceedMaxDepth()
    {
        // 构造6层lookup链：300->301->302->303->304->305
        NoteColumn lookup300 = new NoteColumn();
        lookup300.setId(300L);
        lookup300.setType(26L);
        lookup300.setProperty(new JSONObject().fluentPut("source_column_id", "301").toJSONString());

        NoteColumn lookup301 = new NoteColumn();
        lookup301.setId(301L);
        lookup301.setType(26L);
        lookup301.setProperty(new JSONObject().fluentPut("source_column_id", "302").toJSONString());

        NoteColumn lookup302 = new NoteColumn();
        lookup302.setId(302L);
        lookup302.setType(26L);
        lookup302.setProperty(new JSONObject().fluentPut("source_column_id", "303").toJSONString());

        NoteColumn lookup303 = new NoteColumn();
        lookup303.setId(303L);
        lookup303.setType(26L);
        lookup303.setProperty(new JSONObject().fluentPut("source_column_id", "304").toJSONString());

        NoteColumn lookup304 = new NoteColumn();
        lookup304.setId(304L);
        lookup304.setType(26L);
        lookup304.setProperty(new JSONObject().fluentPut("source_column_id", "305").toJSONString());

        NoteColumn lookup305 = new NoteColumn();
        lookup305.setId(305L);
        lookup305.setType(26L);
        lookup305.setProperty(new JSONObject().fluentPut("source_column_id", "306").toJSONString());

        when(noteColumnMapper.selectNoteColumnById(300L)).thenReturn(lookup300);
        when(noteColumnMapper.selectNoteColumnById(301L)).thenReturn(lookup301);
        when(noteColumnMapper.selectNoteColumnById(302L)).thenReturn(lookup302);
        when(noteColumnMapper.selectNoteColumnById(303L)).thenReturn(lookup303);
        when(noteColumnMapper.selectNoteColumnById(304L)).thenReturn(lookup304);
        when(noteColumnMapper.selectNoteColumnById(305L)).thenReturn(lookup305);

        Object result = noteRecordService.resolveLookupValue(lookupColumn, 1L);

        assertEquals("", result);
    }

    /**
     * 场景7：关联记录只有一个（非逗号分隔）
     */
    @Test
    void testResolveLookupValue_singleLinkRecord()
    {
        NoteColumn sourceColumn = new NoteColumn();
        sourceColumn.setId(300L);
        sourceColumn.setType(1L);

        NoteDwtableItem singleLinkItem = new NoteDwtableItem();
        singleLinkItem.setRecordId(1L);
        singleLinkItem.setLinkColumnId(200L);
        singleLinkItem.setLinkRecordId("10");

        NoteDwtableItem valueItem = new NoteDwtableItem();
        valueItem.setValue("唯一值");

        when(noteColumnMapper.selectNoteColumnById(300L)).thenReturn(sourceColumn);
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(any(NoteDwtableItem.class)))
                .thenReturn(singleLinkItem)
                .thenReturn(valueItem);

        Object result = noteRecordService.resolveLookupValue(lookupColumn, 1L);

        assertEquals("唯一值", result);
    }

    /**
     * 场景8（AE1）：dedupe=true，3条关联记录值"苹果/苹果/梨"，去重后为"苹果,梨"
     */
    @Test
    void testResolveLookupValue_dedupeTrue()
    {
        NoteColumn dedupeColumn = new NoteColumn();
        dedupeColumn.setId(100L);
        dedupeColumn.setType(26L);
        JSONObject property = new JSONObject();
        property.put("double_link_column_id", "200");
        property.put("source_column_id", "300");
        property.put("dedupe", true);
        dedupeColumn.setProperty(property.toJSONString());

        NoteColumn sourceColumn = new NoteColumn();
        sourceColumn.setId(300L);
        sourceColumn.setType(1L);

        NoteDwtableItem linkItem = new NoteDwtableItem();
        linkItem.setRecordId(1L);
        linkItem.setLinkColumnId(200L);
        linkItem.setLinkRecordId("10,20,30");

        NoteDwtableItem value10 = new NoteDwtableItem();
        value10.setValue("苹果");
        NoteDwtableItem value20 = new NoteDwtableItem();
        value20.setValue("苹果");
        NoteDwtableItem value30 = new NoteDwtableItem();
        value30.setValue("梨");

        when(noteColumnMapper.selectNoteColumnById(300L)).thenReturn(sourceColumn);
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(any(NoteDwtableItem.class)))
                .thenReturn(linkItem)
                .thenReturn(value10)
                .thenReturn(value20)
                .thenReturn(value30);

        Object result = noteRecordService.resolveLookupValue(dedupeColumn, 1L);

        assertEquals("苹果,梨", result);
    }

    /**
     * 场景9（AE2）：dedupe=false，同上数据，结果保留全部重复值"苹果,苹果,梨"
     */
    @Test
    void testResolveLookupValue_dedupeFalse()
    {
        NoteColumn dedupeColumn = new NoteColumn();
        dedupeColumn.setId(100L);
        dedupeColumn.setType(26L);
        JSONObject property = new JSONObject();
        property.put("double_link_column_id", "200");
        property.put("source_column_id", "300");
        property.put("dedupe", false);
        dedupeColumn.setProperty(property.toJSONString());

        NoteColumn sourceColumn = new NoteColumn();
        sourceColumn.setId(300L);
        sourceColumn.setType(1L);

        NoteDwtableItem linkItem = new NoteDwtableItem();
        linkItem.setRecordId(1L);
        linkItem.setLinkColumnId(200L);
        linkItem.setLinkRecordId("10,20,30");

        NoteDwtableItem value10 = new NoteDwtableItem();
        value10.setValue("苹果");
        NoteDwtableItem value20 = new NoteDwtableItem();
        value20.setValue("苹果");
        NoteDwtableItem value30 = new NoteDwtableItem();
        value30.setValue("梨");

        when(noteColumnMapper.selectNoteColumnById(300L)).thenReturn(sourceColumn);
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(any(NoteDwtableItem.class)))
                .thenReturn(linkItem)
                .thenReturn(value10)
                .thenReturn(value20)
                .thenReturn(value30);

        Object result = noteRecordService.resolveLookupValue(dedupeColumn, 1L);

        assertEquals("苹果,苹果,梨", result);
    }

    /**
     * 场景10（R2）：property中无dedupe字段，按false处理，保留全部重复值
     */
    @Test
    void testResolveLookupValue_dedupeMissing()
    {
        NoteColumn sourceColumn = new NoteColumn();
        sourceColumn.setId(300L);
        sourceColumn.setType(1L);

        NoteDwtableItem linkItem = new NoteDwtableItem();
        linkItem.setRecordId(1L);
        linkItem.setLinkColumnId(200L);
        linkItem.setLinkRecordId("10,20,30");

        NoteDwtableItem value10 = new NoteDwtableItem();
        value10.setValue("苹果");
        NoteDwtableItem value20 = new NoteDwtableItem();
        value20.setValue("苹果");
        NoteDwtableItem value30 = new NoteDwtableItem();
        value30.setValue("梨");

        when(noteColumnMapper.selectNoteColumnById(300L)).thenReturn(sourceColumn);
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(any(NoteDwtableItem.class)))
                .thenReturn(linkItem)
                .thenReturn(value10)
                .thenReturn(value20)
                .thenReturn(value30);

        Object result = noteRecordService.resolveLookupValue(lookupColumn, 1L);

        assertEquals("苹果,苹果,梨", result);
    }

    /**
     * 场景11（R5）：多个空value，dedupe=true时去重为一个空值",梨"
     */
    @Test
    void testResolveLookupValue_dedupeEmptyValues()
    {
        NoteColumn dedupeColumn = new NoteColumn();
        dedupeColumn.setId(100L);
        dedupeColumn.setType(26L);
        JSONObject property = new JSONObject();
        property.put("double_link_column_id", "200");
        property.put("source_column_id", "300");
        property.put("dedupe", true);
        dedupeColumn.setProperty(property.toJSONString());

        NoteColumn sourceColumn = new NoteColumn();
        sourceColumn.setId(300L);
        sourceColumn.setType(1L);

        NoteDwtableItem linkItem = new NoteDwtableItem();
        linkItem.setRecordId(1L);
        linkItem.setLinkColumnId(200L);
        linkItem.setLinkRecordId("10,20,30");

        NoteDwtableItem value10 = new NoteDwtableItem();
        value10.setValue("");
        NoteDwtableItem value20 = new NoteDwtableItem();
        value20.setValue("");
        NoteDwtableItem value30 = new NoteDwtableItem();
        value30.setValue("梨");

        when(noteColumnMapper.selectNoteColumnById(300L)).thenReturn(sourceColumn);
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(any(NoteDwtableItem.class)))
                .thenReturn(linkItem)
                .thenReturn(value10)
                .thenReturn(value20)
                .thenReturn(value30);

        Object result = noteRecordService.resolveLookupValue(dedupeColumn, 1L);

        assertEquals(",梨", result);
    }

    /**
     * 场景12（R4）：dedupe=true，值顺序"梨/苹果/苹果/梨"，结果"梨,苹果"，保持首次出现顺序
     */
    @Test
    void testResolveLookupValue_dedupePreservesOrder()
    {
        NoteColumn dedupeColumn = new NoteColumn();
        dedupeColumn.setId(100L);
        dedupeColumn.setType(26L);
        JSONObject property = new JSONObject();
        property.put("double_link_column_id", "200");
        property.put("source_column_id", "300");
        property.put("dedupe", true);
        dedupeColumn.setProperty(property.toJSONString());

        NoteColumn sourceColumn = new NoteColumn();
        sourceColumn.setId(300L);
        sourceColumn.setType(1L);

        NoteDwtableItem linkItem = new NoteDwtableItem();
        linkItem.setRecordId(1L);
        linkItem.setLinkColumnId(200L);
        linkItem.setLinkRecordId("10,20,30,40");

        NoteDwtableItem value10 = new NoteDwtableItem();
        value10.setValue("梨");
        NoteDwtableItem value20 = new NoteDwtableItem();
        value20.setValue("苹果");
        NoteDwtableItem value30 = new NoteDwtableItem();
        value30.setValue("苹果");
        NoteDwtableItem value40 = new NoteDwtableItem();
        value40.setValue("梨");

        when(noteColumnMapper.selectNoteColumnById(300L)).thenReturn(sourceColumn);
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(any(NoteDwtableItem.class)))
                .thenReturn(linkItem)
                .thenReturn(value10)
                .thenReturn(value20)
                .thenReturn(value30)
                .thenReturn(value40);

        Object result = noteRecordService.resolveLookupValue(dedupeColumn, 1L);

        assertEquals("梨,苹果", result);
    }

    /**
     * 场景13：source column 是 lookup 列（链式解析）+ dedupe=true，去重仍生效
     */
    @Test
    void testResolveLookupValue_dedupeWithChain()
    {
        NoteColumn dedupeColumn = new NoteColumn();
        dedupeColumn.setId(100L);
        dedupeColumn.setType(26L);
        JSONObject property = new JSONObject();
        property.put("double_link_column_id", "200");
        property.put("source_column_id", "300");
        property.put("dedupe", true);
        dedupeColumn.setProperty(property.toJSONString());

        NoteColumn sourceColumnB = new NoteColumn();
        sourceColumnB.setId(300L);
        sourceColumnB.setType(26L);
        JSONObject propB = new JSONObject();
        propB.put("double_link_column_id", "500");
        propB.put("source_column_id", "400");
        sourceColumnB.setProperty(propB.toJSONString());

        NoteColumn sourceColumnC = new NoteColumn();
        sourceColumnC.setId(400L);
        sourceColumnC.setType(1L);

        when(noteColumnMapper.selectNoteColumnById(300L)).thenReturn(sourceColumnB);
        when(noteColumnMapper.selectNoteColumnById(400L)).thenReturn(sourceColumnC);

        NoteDwtableItem linkItem = new NoteDwtableItem();
        linkItem.setRecordId(1L);
        linkItem.setLinkColumnId(200L);
        linkItem.setLinkRecordId("10,20,30");

        NoteDwtableItem value10 = new NoteDwtableItem();
        value10.setValue("北京");
        NoteDwtableItem value20 = new NoteDwtableItem();
        value20.setValue("北京");
        NoteDwtableItem value30 = new NoteDwtableItem();
        value30.setValue("上海");

        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(any(NoteDwtableItem.class)))
                .thenReturn(linkItem)
                .thenReturn(value10)
                .thenReturn(value20)
                .thenReturn(value30);

        Object result = noteRecordService.resolveLookupValue(dedupeColumn, 1L);

        assertEquals("北京,上海", result);
    }

    /**
     * 场景14：直接测试 resolveLookupValues，验证去重后 linkRecordId 为首次出现值对应的 ID
     */
    @Test
    void testResolveLookupValues_dedupeKeepsFirstLinkRecordId()
    {
        NoteColumn dedupeColumn = new NoteColumn();
        dedupeColumn.setId(100L);
        dedupeColumn.setType(26L);
        JSONObject property = new JSONObject();
        property.put("double_link_column_id", "200");
        property.put("source_column_id", "300");
        property.put("dedupe", true);
        dedupeColumn.setProperty(property.toJSONString());

        NoteColumn sourceColumn = new NoteColumn();
        sourceColumn.setId(300L);
        sourceColumn.setType(1L);

        NoteDwtableItem value10 = new NoteDwtableItem();
        value10.setValue("苹果");
        NoteDwtableItem value20 = new NoteDwtableItem();
        value20.setValue("苹果");
        NoteDwtableItem value30 = new NoteDwtableItem();
        value30.setValue("梨");

        when(noteColumnMapper.selectNoteColumnById(300L)).thenReturn(sourceColumn);
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(any(NoteDwtableItem.class)))
                .thenReturn(value10)
                .thenReturn(value20)
                .thenReturn(value30);

        java.util.List<NoteRecordServiceImpl.LookupResult> results =
                noteRecordService.resolveLookupValues(dedupeColumn, java.util.Arrays.asList("10", "20", "30"));

        assertEquals(2, results.size());
        assertEquals("苹果", results.get(0).getValue());
        assertEquals("10", results.get(0).getLinkRecordId());
        assertEquals("梨", results.get(1).getValue());
        assertEquals("30", results.get(1).getLinkRecordId());
    }

    /**
     * 场景15（R9/U2对齐验证）：dedupe=true时，从resolveLookupValues结果派生的recordIds与values长度一致、索引对齐
     * 这验证了集合运算4个block中 aRecordIds/aValues 同源对齐的不变量
     */
    @Test
    void testSetOpAlignmentPattern_dedupeTrue()
    {
        NoteColumn dedupeColumn = new NoteColumn();
        dedupeColumn.setId(100L);
        dedupeColumn.setType(26L);
        JSONObject property = new JSONObject();
        property.put("double_link_column_id", "200");
        property.put("source_column_id", "300");
        property.put("dedupe", true);
        dedupeColumn.setProperty(property.toJSONString());

        NoteColumn sourceColumn = new NoteColumn();
        sourceColumn.setId(300L);
        sourceColumn.setType(1L);

        NoteDwtableItem value10 = new NoteDwtableItem();
        value10.setValue("苹果");
        NoteDwtableItem value20 = new NoteDwtableItem();
        value20.setValue("苹果");
        NoteDwtableItem value30 = new NoteDwtableItem();
        value30.setValue("梨");

        when(noteColumnMapper.selectNoteColumnById(300L)).thenReturn(sourceColumn);
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(any(NoteDwtableItem.class)))
                .thenReturn(value10)
                .thenReturn(value20)
                .thenReturn(value30);

        java.util.List<NoteRecordServiceImpl.LookupResult> results =
                noteRecordService.resolveLookupValues(dedupeColumn, java.util.Arrays.asList("10", "20", "30"));

        // 模拟U2中4个block的派生模式：recordIds和values都从同一results派生
        java.util.List<String> derivedRecordIds = results.stream()
                .map(NoteRecordServiceImpl.LookupResult::getLinkRecordId)
                .collect(java.util.stream.Collectors.toList());
        java.util.List<String> derivedValues = results.stream()
                .map(NoteRecordServiceImpl.LookupResult::getValue)
                .collect(java.util.stream.Collectors.toList());

        // 对齐不变量：长度一致
        assertEquals(derivedRecordIds.size(), derivedValues.size());
        // 去重后2条
        assertEquals(2, derivedRecordIds.size());
        // 索引对齐：recordId 与 value 一一对应
        assertEquals("10", derivedRecordIds.get(0));
        assertEquals("苹果", derivedValues.get(0));
        assertEquals("30", derivedRecordIds.get(1));
        assertEquals("梨", derivedValues.get(1));
    }

    /**
     * 场景16（R9/U2对齐验证）：dedupe=false时，recordIds与values同样长度一致、索引对齐（无回归）
     */
    @Test
    void testSetOpAlignmentPattern_dedupeFalse()
    {
        NoteColumn dedupeColumn = new NoteColumn();
        dedupeColumn.setId(100L);
        dedupeColumn.setType(26L);
        JSONObject property = new JSONObject();
        property.put("double_link_column_id", "200");
        property.put("source_column_id", "300");
        property.put("dedupe", false);
        dedupeColumn.setProperty(property.toJSONString());

        NoteColumn sourceColumn = new NoteColumn();
        sourceColumn.setId(300L);
        sourceColumn.setType(1L);

        NoteDwtableItem value10 = new NoteDwtableItem();
        value10.setValue("苹果");
        NoteDwtableItem value20 = new NoteDwtableItem();
        value20.setValue("苹果");
        NoteDwtableItem value30 = new NoteDwtableItem();
        value30.setValue("梨");

        when(noteColumnMapper.selectNoteColumnById(300L)).thenReturn(sourceColumn);
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(any(NoteDwtableItem.class)))
                .thenReturn(value10)
                .thenReturn(value20)
                .thenReturn(value30);

        java.util.List<NoteRecordServiceImpl.LookupResult> results =
                noteRecordService.resolveLookupValues(dedupeColumn, java.util.Arrays.asList("10", "20", "30"));

        java.util.List<String> derivedRecordIds = results.stream()
                .map(NoteRecordServiceImpl.LookupResult::getLinkRecordId)
                .collect(java.util.stream.Collectors.toList());
        java.util.List<String> derivedValues = results.stream()
                .map(NoteRecordServiceImpl.LookupResult::getValue)
                .collect(java.util.stream.Collectors.toList());

        // 对齐不变量：长度一致
        assertEquals(derivedRecordIds.size(), derivedValues.size());
        // dedupe=false 时保留全部3条
        assertEquals(3, derivedRecordIds.size());
        // 索引对齐
        assertEquals("10", derivedRecordIds.get(0));
        assertEquals("苹果", derivedValues.get(0));
        assertEquals("20", derivedRecordIds.get(1));
        assertEquals("苹果", derivedValues.get(1));
        assertEquals("30", derivedRecordIds.get(2));
        assertEquals("梨", derivedValues.get(2));
    }

    /**
     * 场景17（AE3/R7）：recomputeLookupColumnValues遍历5条记录，全部更新为去重值。
     * 每条记录的doubleLink关联10、20两条记录，值均为"苹果/苹果"，dedupe=true后应更新为"苹果"。
     * 每条记录已有lookup item，验证updateNoteDwtableItem被调用5次，insert不被调用。
     */
    @Test
    void testRecomputeLookupColumnValues_updatesAllRecords()
    {
        NoteColumn dedupeColumn = new NoteColumn();
        dedupeColumn.setId(100L);
        dedupeColumn.setType(26L);
        dedupeColumn.setDwtableId(1L);
        JSONObject property = new JSONObject();
        property.put("double_link_column_id", "200");
        property.put("source_column_id", "300");
        property.put("dedupe", true);
        dedupeColumn.setProperty(property.toJSONString());

        NoteColumn sourceColumn = new NoteColumn();
        sourceColumn.setId(300L);
        sourceColumn.setType(1L);

        when(noteColumnMapper.selectNoteColumnById(300L)).thenReturn(sourceColumn);

        // 5条记录
        NoteRecord r1 = new NoteRecord(); r1.setId(1L);
        NoteRecord r2 = new NoteRecord(); r2.setId(2L);
        NoteRecord r3 = new NoteRecord(); r3.setId(3L);
        NoteRecord r4 = new NoteRecord(); r4.setId(4L);
        NoteRecord r5 = new NoteRecord(); r5.setId(5L);
        when(noteRecordMapper.selectNoteRecordList(any())).thenReturn(Arrays.asList(r1, r2, r3, r4, r5));

        // 每条记录的doubleLinkItem关联10、20，值均为"苹果/苹果"
        NoteDwtableItem doubleLinkItem = new NoteDwtableItem();
        doubleLinkItem.setLinkRecordId("10,20");

        NoteDwtableItem valueItem = new NoteDwtableItem();
        valueItem.setValue("苹果");

        NoteDwtableItem existingItem = new NoteDwtableItem();
        existingItem.setId(1000L);

        // 区分3种查询：linkColumnId != null → doubleLink；columnId=100 → existing item；columnId=300 → value
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(any(NoteDwtableItem.class)))
                .thenAnswer(invocation -> {
                    NoteDwtableItem query = invocation.getArgument(0);
                    if (query.getLinkColumnId() != null) {
                        return doubleLinkItem;
                    }
                    if (query.getColumnId() != null && query.getColumnId() == 100L) {
                        return existingItem;
                    }
                    // value query
                    return valueItem;
                });

        noteRecordService.recomputeLookupColumnValues(dedupeColumn);

        // 5条记录都有existing item，应全部update，不insert
        verify(noteDwtableItemMapper, times(5)).updateNoteDwtableItem(any(NoteDwtableItem.class));
        verify(noteDwtableItemMapper, never()).insertNoteDwtableItem(any(NoteDwtableItem.class));
    }

    /**
     * 场景18（R7）：重算时某记录无existing lookup item，应新建item并写入value
     */
    @Test
    void testRecomputeLookupColumnValues_noItem_createsNewItem()
    {
        NoteColumn dedupeColumn = new NoteColumn();
        dedupeColumn.setId(100L);
        dedupeColumn.setType(26L);
        dedupeColumn.setDwtableId(1L);
        JSONObject property = new JSONObject();
        property.put("double_link_column_id", "200");
        property.put("source_column_id", "300");
        property.put("dedupe", true);
        dedupeColumn.setProperty(property.toJSONString());

        NoteColumn sourceColumn = new NoteColumn();
        sourceColumn.setId(300L);
        sourceColumn.setType(1L);
        when(noteColumnMapper.selectNoteColumnById(300L)).thenReturn(sourceColumn);

        NoteRecord r1 = new NoteRecord(); r1.setId(1L);
        when(noteRecordMapper.selectNoteRecordList(any())).thenReturn(Collections.singletonList(r1));

        NoteDwtableItem doubleLinkItem = new NoteDwtableItem();
        doubleLinkItem.setLinkRecordId("10,20");

        NoteDwtableItem valueItem = new NoteDwtableItem();
        valueItem.setValue("苹果");

        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(any(NoteDwtableItem.class)))
                .thenAnswer(invocation -> {
                    NoteDwtableItem query = invocation.getArgument(0);
                    if (query.getLinkColumnId() != null) {
                        return doubleLinkItem;
                    }
                    if (query.getColumnId() != null && query.getColumnId() == 100L) {
                        // existing item不存在
                        return null;
                    }
                    return valueItem;
                });

        noteRecordService.recomputeLookupColumnValues(dedupeColumn);

        // 应insert新item，不update
        verify(noteDwtableItemMapper).insertNoteDwtableItem(any(NoteDwtableItem.class));
        verify(noteDwtableItemMapper, never()).updateNoteDwtableItem(any(NoteDwtableItem.class));
    }

    /**
     * 场景19（R8）：第3条记录重算时抛异常，第4、5条仍正常处理，不中断整体流程
     */
    @Test
    void testRecomputeLookupColumnValues_singleFailure_continuesProcessing()
    {
        NoteColumn dedupeColumn = new NoteColumn();
        dedupeColumn.setId(100L);
        dedupeColumn.setType(26L);
        dedupeColumn.setDwtableId(1L);
        JSONObject property = new JSONObject();
        property.put("double_link_column_id", "200");
        property.put("source_column_id", "300");
        property.put("dedupe", true);
        dedupeColumn.setProperty(property.toJSONString());

        NoteColumn sourceColumn = new NoteColumn();
        sourceColumn.setId(300L);
        sourceColumn.setType(1L);
        when(noteColumnMapper.selectNoteColumnById(300L)).thenReturn(sourceColumn);

        NoteRecord r1 = new NoteRecord(); r1.setId(1L);
        NoteRecord r2 = new NoteRecord(); r2.setId(2L);
        NoteRecord r3 = new NoteRecord(); r3.setId(3L);
        NoteRecord r4 = new NoteRecord(); r4.setId(4L);
        NoteRecord r5 = new NoteRecord(); r5.setId(5L);
        when(noteRecordMapper.selectNoteRecordList(any())).thenReturn(Arrays.asList(r1, r2, r3, r4, r5));

        NoteDwtableItem doubleLinkItem = new NoteDwtableItem();
        doubleLinkItem.setLinkRecordId("10,20");

        NoteDwtableItem valueItem = new NoteDwtableItem();
        valueItem.setValue("苹果");

        NoteDwtableItem existingItem = new NoteDwtableItem();
        existingItem.setId(1000L);

        // 第3条记录（recordId=3）的所有查询都抛异常
        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(any(NoteDwtableItem.class)))
                .thenAnswer(invocation -> {
                    NoteDwtableItem query = invocation.getArgument(0);
                    Long recordId = query.getRecordId();
                    if (recordId != null && recordId == 3L) {
                        throw new RuntimeException("模拟第3条记录异常");
                    }
                    if (query.getLinkColumnId() != null) {
                        return doubleLinkItem;
                    }
                    if (query.getColumnId() != null && query.getColumnId() == 100L) {
                        return existingItem;
                    }
                    return valueItem;
                });

        // 不应抛异常
        noteRecordService.recomputeLookupColumnValues(dedupeColumn);

        // 第3条失败，其余4条成功update（4次updateNoteDwtableItem）
        verify(noteDwtableItemMapper, times(4)).updateNoteDwtableItem(any(NoteDwtableItem.class));
        verify(noteDwtableItemMapper, never()).insertNoteDwtableItem(any(NoteDwtableItem.class));
    }

    /**
     * 场景20（R7）：表中无记录时，recomputeLookupColumnValues不应抛异常，不做任何item操作
     */
    @Test
    void testRecomputeLookupColumnValues_emptyRecords_noOp()
    {
        NoteColumn dedupeColumn = new NoteColumn();
        dedupeColumn.setId(100L);
        dedupeColumn.setType(26L);
        dedupeColumn.setDwtableId(1L);
        JSONObject property = new JSONObject();
        property.put("double_link_column_id", "200");
        property.put("source_column_id", "300");
        property.put("dedupe", true);
        dedupeColumn.setProperty(property.toJSONString());

        when(noteRecordMapper.selectNoteRecordList(any())).thenReturn(Collections.emptyList());

        noteRecordService.recomputeLookupColumnValues(dedupeColumn);

        verify(noteDwtableItemMapper, never()).updateNoteDwtableItem(any(NoteDwtableItem.class));
        verify(noteDwtableItemMapper, never()).insertNoteDwtableItem(any(NoteDwtableItem.class));
    }

    // ============ U1: deriveRecordName + recomputeRecordNamesForTable ============

    /**
     * U1 场景1（happy）：左侧 type=1 列在 incomingItems 有新值 → 返回新值
     */
    @Test
    void testDeriveRecordName_incomingHasValue()
    {
        NoteColumn col1 = new NoteColumn(); col1.setId(1L); col1.setType(1L); col1.setSort(1L);
        NoteColumn col2 = new NoteColumn(); col2.setId(2L); col2.setType(2L); col2.setSort(2L);
        when(noteColumnMapper.selectNoteColumnList(any())).thenReturn(Arrays.asList(col1, col2));

        Map<String, Object> incoming = new HashMap<>();
        incoming.put("columnId", "1");
        incoming.put("value", "新名称");

        String name = noteRecordService.deriveRecordName(100L, Arrays.asList(incoming), null);
        assertEquals("新名称", name);
    }

    /**
     * U1 场景2（edge）：左侧 type=1 列不在 incomingItems → 回退 existingItems 值
     */
    @Test
    void testDeriveRecordName_fallbackToExisting()
    {
        NoteColumn col1 = new NoteColumn(); col1.setId(1L); col1.setType(1L); col1.setSort(1L);
        when(noteColumnMapper.selectNoteColumnList(any())).thenReturn(Collections.singletonList(col1));

        NoteDwtableItem existing = new NoteDwtableItem();
        existing.setColumnId(1L);
        existing.setValue("DB当前值");

        // incoming 是其他列的更新（不含 sourceColumnId=1）
        Map<String, Object> incoming = new HashMap<>();
        incoming.put("columnId", "2");
        incoming.put("value", "其他列值");

        String name = noteRecordService.deriveRecordName(100L,
                Arrays.asList(incoming), Collections.singletonList(existing));
        assertEquals("DB当前值", name);
    }

    /**
     * U1 场景3（edge）：表无 type=1 列 → 返回 ""
     */
    @Test
    void testDeriveRecordName_noTypeOneColumn()
    {
        NoteColumn col1 = new NoteColumn(); col1.setId(1L); col1.setType(2L); col1.setSort(1L);
        when(noteColumnMapper.selectNoteColumnList(any())).thenReturn(Collections.singletonList(col1));

        String name = noteRecordService.deriveRecordName(100L, null, null);
        assertEquals("", name);
    }

    /**
     * U1 场景4（edge）：左侧 type=1 列值为 null → 返回 ""
     */
    @Test
    void testDeriveRecordName_nullValue()
    {
        NoteColumn col1 = new NoteColumn(); col1.setId(1L); col1.setType(1L); col1.setSort(1L);
        when(noteColumnMapper.selectNoteColumnList(any())).thenReturn(Collections.singletonList(col1));

        Map<String, Object> incoming = new HashMap<>();
        incoming.put("columnId", "1");
        incoming.put("value", null);

        String name = noteRecordService.deriveRecordName(100L, Arrays.asList(incoming), null);
        assertEquals("", name);
    }

    /**
     * U1 场景5（edge）：多个 type=1 列 → 返回 sort 最小那个的值
     * selectNoteColumnList 已按 sort 升序返回，故 List 首项即 sort 最小
     */
    @Test
    void testDeriveRecordName_multipleTypeOne_returnSmallestSort()
    {
        NoteColumn col2 = new NoteColumn(); col2.setId(2L); col2.setType(1L); col2.setSort(1L);
        NoteColumn col5 = new NoteColumn(); col5.setId(5L); col5.setType(1L); col5.setSort(5L);
        when(noteColumnMapper.selectNoteColumnList(any())).thenReturn(Arrays.asList(col2, col5));

        // incoming 顺序与 sort 无关，验证取 sort 最小（col2）
        Map<String, Object> inc5 = new HashMap<>(); inc5.put("columnId", "5"); inc5.put("value", "第二文本列");
        Map<String, Object> inc2 = new HashMap<>(); inc2.put("columnId", "2"); inc2.put("value", "第一文本列");

        String name = noteRecordService.deriveRecordName(100L, Arrays.asList(inc5, inc2), null);
        assertEquals("第一文本列", name);
    }

    /**
     * U1 场景6（edge）：最左列非 type=1、次左列是 type=1 → 返回次左列值
     */
    @Test
    void testDeriveRecordName_leftmostNotTypeOne()
    {
        NoteColumn col1 = new NoteColumn(); col1.setId(1L); col1.setType(2L); col1.setSort(1L);
        NoteColumn col2 = new NoteColumn(); col2.setId(2L); col2.setType(1L); col2.setSort(2L);
        when(noteColumnMapper.selectNoteColumnList(any())).thenReturn(Arrays.asList(col1, col2));

        Map<String, Object> incoming = new HashMap<>();
        incoming.put("columnId", "2");
        incoming.put("value", "次左列值");

        String name = noteRecordService.deriveRecordName(100L, Arrays.asList(incoming), null);
        assertEquals("次左列值", name);
    }

    /**
     * U1 场景7（edge）：dwtableId 为 null → 返回 ""
     */
    @Test
    void testDeriveRecordName_nullDwtableId()
    {
        String name = noteRecordService.deriveRecordName(null, null, null);
        assertEquals("", name);
    }

    /**
     * U1 场景8（integration）：recomputeRecordNamesForTable 表内 3 条记录重算后
     * name 分别等于各自 type=1 item 值；其中 1 条无 type=1 item → name=""
     */
    @Test
    void testRecomputeRecordNamesForTable_threeRecords()
    {
        NoteColumn col1 = new NoteColumn(); col1.setId(1L); col1.setType(1L); col1.setSort(1L);
        when(noteColumnMapper.selectNoteColumnList(any())).thenReturn(Collections.singletonList(col1));

        NoteRecord r1 = new NoteRecord(); r1.setId(11L);
        NoteRecord r2 = new NoteRecord(); r2.setId(22L);
        NoteRecord r3 = new NoteRecord(); r3.setId(33L); // r3 没有 type=1 item
        when(noteRecordMapper.selectNoteRecordList(any())).thenReturn(Arrays.asList(r1, r2, r3));

        NoteDwtableItem itemR1 = new NoteDwtableItem(); itemR1.setColumnId(1L); itemR1.setValue("张三");
        NoteDwtableItem itemR2 = new NoteDwtableItem(); itemR2.setColumnId(1L); itemR2.setValue("李四");
        when(noteDwtableItemMapper.selectNoteDwtableItemList(any()))
                .thenReturn(Collections.singletonList(itemR1))
                .thenReturn(Collections.singletonList(itemR2))
                .thenReturn(Collections.emptyList());

        int updated = noteRecordService.recomputeRecordNamesForTable(100L);

        assertEquals(3, updated);
        verify(noteRecordMapper, times(3)).updateNoteRecord(any());
        assertEquals("张三", r1.getName());
        assertEquals("李四", r2.getName());
        assertEquals("", r3.getName());
    }

    /**
     * U1 场景9（edge）：recomputeRecordNamesForTable dwtableId=null → 返回 0，不查 DB
     */
    @Test
    void testRecomputeRecordNamesForTable_nullDwtableId()
    {
        int updated = noteRecordService.recomputeRecordNamesForTable(null);
        assertEquals(0, updated);
        verify(noteRecordMapper, never()).selectNoteRecordList(any());
    }

    /**
     * U1 场景10（edge，P2-3 补充）：3 条记录，第 2 条 selectNoteDwtableItemList 抛异常
     * → 第 1、3 条仍正常更新，updated=2，方法不抛异常
     */
    @Test
    void testRecomputeRecordNamesForTable_singleFailure_continuesProcessing()
    {
        NoteColumn col1 = new NoteColumn(); col1.setId(1L); col1.setType(1L); col1.setSort(1L);
        when(noteColumnMapper.selectNoteColumnList(any())).thenReturn(Collections.singletonList(col1));

        NoteRecord r1 = new NoteRecord(); r1.setId(11L);
        NoteRecord r2 = new NoteRecord(); r2.setId(22L);
        NoteRecord r3 = new NoteRecord(); r3.setId(33L);
        when(noteRecordMapper.selectNoteRecordList(any())).thenReturn(Arrays.asList(r1, r2, r3));

        NoteDwtableItem itemR1 = new NoteDwtableItem(); itemR1.setColumnId(1L); itemR1.setValue("张三");
        NoteDwtableItem itemR3 = new NoteDwtableItem(); itemR3.setColumnId(1L); itemR3.setValue("王五");
        // 第 2 条记录查 item 时抛异常
        when(noteDwtableItemMapper.selectNoteDwtableItemList(any()))
                .thenReturn(Collections.singletonList(itemR1))
                .thenThrow(new RuntimeException("模拟第2条记录异常"))
                .thenReturn(Collections.singletonList(itemR3));

        int updated = noteRecordService.recomputeRecordNamesForTable(100L);

        // 第 2 条失败，第 1、3 条成功
        assertEquals(2, updated);
        verify(noteRecordMapper, times(2)).updateNoteRecord(any());
        assertEquals("张三", r1.getName());
        assertEquals("王五", r3.getName());
    }

    // ============ U2: updateNoteRecord 始终派生 ============

    /**
     * U2 场景1（happy，回归 ce-debug bug）：已有非空 name 的记录，更新最左侧 type=1 item
     * → updateNoteRecord 被调用时 name 等于新值（KTD-3 incoming 优先）
     * items 中的 Map 不带 id，跳过 item 持久化逻辑，focus 在 name 派生
     */
    @Test
    void testUpdateNoteRecord_derivesNameFromIncomingTypeOneItem()
    {
        NoteColumn col1 = new NoteColumn(); col1.setId(1L); col1.setType(1L); col1.setSort(1L);
        when(noteColumnMapper.selectNoteColumnList(any())).thenReturn(Collections.singletonList(col1));

        // recordItems：DB 当前 type=1 列值=旧名称
        NoteDwtableItem existingItem = new NoteDwtableItem();
        existingItem.setDwtId(100L);
        existingItem.setColumnId(1L);
        existingItem.setValue("旧名称");
        when(noteDwtableItemMapper.selectNoteDwtableItemList(any())).thenReturn(Collections.singletonList(existingItem));

        // items：含 type=1 列的新值（无 id，跳过 item 持久化）
        Map<String, Object> incoming = new HashMap<>();
        incoming.put("columnId", "1");
        incoming.put("value", "新名称");

        NoteRecord record = new NoteRecord();
        record.setId(11L);
        record.setName("旧名称"); // 已有非空 name

        noteRecordService.updateNoteRecord(record, Arrays.asList(incoming));

        // 验证 name 被更新为新值（来自 incoming items，KTD-3 优先级）
        assertEquals("新名称", record.getName());
        verify(noteRecordMapper).updateNoteRecord(record);
    }

    /**
     * U2 场景2（happy）：更新非 type=1 item → name 保持等于当前 type=1 item 值（KTD-3 回退 recordItems）
     */
    @Test
    void testUpdateNoteRecord_nonTypeOneItem_keepsNameFromRecordItems()
    {
        NoteColumn col1 = new NoteColumn(); col1.setId(1L); col1.setType(1L); col1.setSort(1L);
        NoteColumn col2 = new NoteColumn(); col2.setId(2L); col2.setType(2L); col2.setSort(2L);
        when(noteColumnMapper.selectNoteColumnList(any())).thenReturn(Arrays.asList(col1, col2));

        // recordItems：DB 当前 type=1 列值=张三
        NoteDwtableItem typeOneItem = new NoteDwtableItem();
        typeOneItem.setDwtId(100L);
        typeOneItem.setColumnId(1L);
        typeOneItem.setValue("张三");
        when(noteDwtableItemMapper.selectNoteDwtableItemList(any())).thenReturn(Collections.singletonList(typeOneItem));

        // items：只更新 type=2 列（不含 type=1）
        Map<String, Object> incoming = new HashMap<>();
        incoming.put("columnId", "2");
        incoming.put("value", "数字值");

        NoteRecord record = new NoteRecord();
        record.setId(11L);
        record.setName("张三");

        noteRecordService.updateNoteRecord(record, Arrays.asList(incoming));

        // 验证 name 保持 type=1 列的当前值（来自 recordItems 回退）
        assertEquals("张三", record.getName());
        verify(noteRecordMapper).updateNoteRecord(record);
    }

    /**
     * U2 场景3（edge）：表无 type=1 列 → name=""
     */
    @Test
    void testUpdateNoteRecord_noTypeOneColumn_nameEmpty()
    {
        NoteColumn col1 = new NoteColumn(); col1.setId(1L); col1.setType(2L); col1.setSort(1L);
        when(noteColumnMapper.selectNoteColumnList(any())).thenReturn(Collections.singletonList(col1));

        NoteDwtableItem existingItem = new NoteDwtableItem();
        existingItem.setDwtId(100L);
        existingItem.setColumnId(1L);
        existingItem.setValue("数字");
        when(noteDwtableItemMapper.selectNoteDwtableItemList(any())).thenReturn(Collections.singletonList(existingItem));

        NoteRecord record = new NoteRecord();
        record.setId(11L);
        record.setName("原name");

        noteRecordService.updateNoteRecord(record, null);

        assertEquals("", record.getName());
        verify(noteRecordMapper).updateNoteRecord(record);
    }

    /**
     * U2 场景4（edge）：传入 items 中 type=1 值为空 → name=""
     */
    @Test
    void testUpdateNoteRecord_incomingTypeOneValueNull_nameEmpty()
    {
        NoteColumn col1 = new NoteColumn(); col1.setId(1L); col1.setType(1L); col1.setSort(1L);
        when(noteColumnMapper.selectNoteColumnList(any())).thenReturn(Collections.singletonList(col1));

        // recordItems：DB 当前 type=1 列值=张三
        NoteDwtableItem typeOneItem = new NoteDwtableItem();
        typeOneItem.setDwtId(100L);
        typeOneItem.setColumnId(1L);
        typeOneItem.setValue("张三");
        when(noteDwtableItemMapper.selectNoteDwtableItemList(any())).thenReturn(Collections.singletonList(typeOneItem));

        // items：type=1 列 value=null（清空）
        Map<String, Object> incoming = new HashMap<>();
        incoming.put("columnId", "1");
        incoming.put("value", null);

        NoteRecord record = new NoteRecord();
        record.setId(11L);
        record.setName("张三");

        noteRecordService.updateNoteRecord(record, Arrays.asList(incoming));

        // incoming 优先级最高，null → ""
        assertEquals("", record.getName());
        verify(noteRecordMapper).updateNoteRecord(record);
    }

    /**
     * U2 场景5（happy，R7）：前端传 name="旧" 但派生得 "新" → 落盘 name="新"，前端值被忽略
     */
    @Test
    void testUpdateNoteRecord_frontendNameOverriddenByDerived()
    {
        NoteColumn col1 = new NoteColumn(); col1.setId(1L); col1.setType(1L); col1.setSort(1L);
        when(noteColumnMapper.selectNoteColumnList(any())).thenReturn(Collections.singletonList(col1));

        // recordItems：DB 当前 type=1 列值=新
        NoteDwtableItem typeOneItem = new NoteDwtableItem();
        typeOneItem.setDwtId(100L);
        typeOneItem.setColumnId(1L);
        typeOneItem.setValue("新");
        when(noteDwtableItemMapper.selectNoteDwtableItemList(any())).thenReturn(Collections.singletonList(typeOneItem));

        // 前端传 name="旧"
        NoteRecord record = new NoteRecord();
        record.setId(11L);
        record.setName("旧");

        noteRecordService.updateNoteRecord(record, null);

        // 派生覆盖前端值
        assertEquals("新", record.getName());
        verify(noteRecordMapper).updateNoteRecord(record);
    }

    /**
     * U2 场景6（edge）：recordItems 为空 → name 不被改动（保持原值）
     */
    @Test
    void testUpdateNoteRecord_emptyRecordItems_nameUnchanged()
    {
        when(noteDwtableItemMapper.selectNoteDwtableItemList(any())).thenReturn(Collections.emptyList());

        NoteRecord record = new NoteRecord();
        record.setId(11L);
        record.setName("原name");

        noteRecordService.updateNoteRecord(record, null);

        // recordItems 为空，跳过派生，name 保持原值
        assertEquals("原name", record.getName());
        verify(noteRecordMapper).updateNoteRecord(record);
        // 不应查列（因为没派生）
        verify(noteColumnMapper, never()).selectNoteColumnList(any());
    }

    // ============ U3: insertNoteRecord 对齐派生规则 ============

    /**
     * U3 场景1（happy）：插入且最左侧 type=1 列在 items 有值 → name 等于该值
     */
    @Test
    void testInsertNoteRecord_derivesNameFromTypeOneItem()
    {
        NoteColumn col1 = new NoteColumn(); col1.setId(1L); col1.setType(1L); col1.setSort(1L);
        when(noteColumnMapper.selectNoteColumnList(any())).thenReturn(Collections.singletonList(col1));

        Map<String, Object> item = new HashMap<>();
        item.put("dwtId", "100");
        item.put("columnId", "1");
        item.put("value", "张三");

        NoteRecord record = new NoteRecord();
        record.setId(11L);
        record.setDwtableId(100L); // 已设，跳过 view 解析

        noteRecordService.insertNoteRecord(record, Arrays.asList(item));

        assertEquals("张三", record.getName());
        verify(noteRecordMapper).insertNoteRecord(record);
        verify(noteRecordMapper).updateNoteRecord(record); // 派生后回写
        verify(noteDwtableItemMapper).insertNoteDwtableItem(any(NoteDwtableItem.class));
    }

    /**
     * U3 场景2（edge，回归）：items 首项非 type=1 → name 取最左侧 type=1 列值而非 items.get(0)
     * 旧逻辑会取 items.get(0)=数字；新逻辑取最左 type=1 列=张三
     */
    @Test
    void testInsertNoteRecord_firstItemNotTypeOne_takesLeftmostTypeOne()
    {
        NoteColumn col1 = new NoteColumn(); col1.setId(1L); col1.setType(2L); col1.setSort(1L);
        NoteColumn col2 = new NoteColumn(); col2.setId(2L); col2.setType(1L); col2.setSort(2L);
        when(noteColumnMapper.selectNoteColumnList(any())).thenReturn(Arrays.asList(col1, col2));

        // items 首项是 type=2 列，次项是 type=1 列
        Map<String, Object> item1 = new HashMap<>();
        item1.put("dwtId", "100");
        item1.put("columnId", "1");
        item1.put("value", "数字");
        Map<String, Object> item2 = new HashMap<>();
        item2.put("dwtId", "100");
        item2.put("columnId", "2");
        item2.put("value", "张三");

        NoteRecord record = new NoteRecord();
        record.setId(11L);
        record.setDwtableId(100L);

        noteRecordService.insertNoteRecord(record, Arrays.asList(item1, item2));

        // 应取最左 type=1 列（col2）的值，而非 items.get(0)
        assertEquals("张三", record.getName());
    }

    /**
     * U3 场景3（edge）：插入且表无 type=1 列 → name=""
     */
    @Test
    void testInsertNoteRecord_noTypeOneColumn_nameEmpty()
    {
        NoteColumn col1 = new NoteColumn(); col1.setId(1L); col1.setType(2L); col1.setSort(1L);
        when(noteColumnMapper.selectNoteColumnList(any())).thenReturn(Collections.singletonList(col1));

        Map<String, Object> item = new HashMap<>();
        item.put("dwtId", "100");
        item.put("columnId", "1");
        item.put("value", "数字");

        NoteRecord record = new NoteRecord();
        record.setId(11L);
        record.setDwtableId(100L);

        noteRecordService.insertNoteRecord(record, Arrays.asList(item));

        assertEquals("", record.getName());
    }

    // ============ U5: recomputeAllRecordNames 一次性回填 ============

    /**
     * U5 场景1（happy）：3 张表，每张 1 条记录，全部重算成功 → 累计 totalUpdated=3
     */
    @Test
    void testRecomputeAllRecordNames_multipleTables()
    {
        NoteDwtable t1 = new NoteDwtable(); t1.setId(1L);
        NoteDwtable t2 = new NoteDwtable(); t2.setId(2L);
        NoteDwtable t3 = new NoteDwtable(); t3.setId(3L);
        when(noteDwtableMapper.selectNoteDwtableList(any())).thenReturn(Arrays.asList(t1, t2, t3));

        NoteColumn col1 = new NoteColumn(); col1.setId(1L); col1.setType(1L); col1.setSort(1L);
        when(noteColumnMapper.selectNoteColumnList(any())).thenReturn(Collections.singletonList(col1));

        // 每张表 1 条记录
        NoteRecord r = new NoteRecord(); r.setId(11L);
        when(noteRecordMapper.selectNoteRecordList(any())).thenReturn(Collections.singletonList(r));

        NoteDwtableItem item = new NoteDwtableItem();
        item.setColumnId(1L);
        item.setValue("派生名");
        when(noteDwtableItemMapper.selectNoteDwtableItemList(any())).thenReturn(Collections.singletonList(item));

        int total = noteRecordService.recomputeAllRecordNames();

        assertEquals(3, total);
        verify(noteRecordMapper, times(3)).updateNoteRecord(any());
    }

    /**
     * U5 场景2（edge）：3 张表，中间表重算抛异常 → 不中断，其余 2 表成功，totalUpdated=2
     */
    @Test
    void testRecomputeAllRecordNames_singleTableFailure_continues()
    {
        NoteDwtable t1 = new NoteDwtable(); t1.setId(1L);
        NoteDwtable t2 = new NoteDwtable(); t2.setId(2L);
        NoteDwtable t3 = new NoteDwtable(); t3.setId(3L);
        when(noteDwtableMapper.selectNoteDwtableList(any())).thenReturn(Arrays.asList(t1, t2, t3));

        NoteColumn col1 = new NoteColumn(); col1.setId(1L); col1.setType(1L); col1.setSort(1L);
        when(noteColumnMapper.selectNoteColumnList(any())).thenReturn(Collections.singletonList(col1));

        NoteDwtableItem item = new NoteDwtableItem();
        item.setColumnId(1L);
        item.setValue("派生名");
        when(noteDwtableItemMapper.selectNoteDwtableItemList(any())).thenReturn(Collections.singletonList(item));

        // 表 2 的 selectNoteRecordList 抛异常，表 1、3 正常返回 1 条记录
        when(noteRecordMapper.selectNoteRecordList(any())).thenAnswer(invocation -> {
            NoteRecordVo query = invocation.getArgument(0);
            if (query.getDwtableId() != null && query.getDwtableId() == 2L)
            {
                throw new RuntimeException("模拟表2重算异常");
            }
            NoteRecord r = new NoteRecord(); r.setId(11L);
            return Collections.singletonList(r);
        });

        int total = noteRecordService.recomputeAllRecordNames();

        // 表 2 失败，表 1、3 各更新 1 条
        assertEquals(2, total);
        verify(noteRecordMapper, times(2)).updateNoteRecord(any());
    }

    /**
     * U5 场景3（edge）：无数据表 → totalUpdated=0，不查记录
     */
    @Test
    void testRecomputeAllRecordNames_noTables()
    {
        when(noteDwtableMapper.selectNoteDwtableList(any())).thenReturn(Collections.emptyList());

        int total = noteRecordService.recomputeAllRecordNames();

        assertEquals(0, total);
        verify(noteRecordMapper, never()).selectNoteRecordList(any());
        verify(noteRecordMapper, never()).updateNoteRecord(any());
    }

    // ============ recomputeSetOperationsForLookup ============

    /**
     * 场景1（happy）：setColumn 引用 lookup 列，重算后更新结果item
     * columnA=lookup列(type=26, dedupe=true, linkIds=[10,20], source值均为"苹果"→去重为[10])
     * columnB=type=21列(linkIds=[20,30], values=[梨,香蕉])
     * union([10],[20,30])=[10,20,30] → values=[苹果,梨,香蕉]
     */
    @Test
    void testRecomputeSetOperationsForLookup_updatesResultItem()
    {
        NoteColumn lookupColumn = new NoteColumn();
        lookupColumn.setId(100L);
        lookupColumn.setType(26L);
        lookupColumn.setDwtableId(1L);
        JSONObject lookupProp = new JSONObject();
        lookupProp.put("double_link_column_id", "200");
        lookupProp.put("source_column_id", "300");
        lookupProp.put("dedupe", true);
        lookupColumn.setProperty(lookupProp.toJSONString());

        NoteColumn setColumn = new NoteColumn();
        setColumn.setId(500L);
        JSONObject setProp = new JSONObject();
        setProp.put("columnAId", "100");
        setProp.put("columnBId", "400");
        setProp.put("calcType", "union");
        setColumn.setProperty(setProp.toJSONString());

        NoteColumn columnB = new NoteColumn();
        columnB.setId(400L);
        columnB.setType(21L);

        NoteColumn sourceColumn = new NoteColumn();
        sourceColumn.setId(300L);
        sourceColumn.setType(1L);

        when(noteColumnMapper.selectSetColumnByDwtId(1L)).thenReturn(Collections.singletonList(setColumn));
        when(noteColumnMapper.selectNoteColumnById(100L)).thenReturn(lookupColumn);
        when(noteColumnMapper.selectNoteColumnById(400L)).thenReturn(columnB);
        when(noteColumnMapper.selectNoteColumnById(300L)).thenReturn(sourceColumn);

        NoteRecord r1 = new NoteRecord();
        r1.setId(1L);
        when(noteRecordMapper.selectNoteRecordList(any())).thenReturn(Collections.singletonList(r1));

        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(any(NoteDwtableItem.class)))
                .thenAnswer(invocation -> {
                    NoteDwtableItem query = invocation.getArgument(0);
                    if (query.getLinkColumnId() != null && query.getLinkColumnId() == 200L) {
                        NoteDwtableItem item = new NoteDwtableItem();
                        item.setLinkRecordId("10,20");
                        return item;
                    }
                    if (query.getColumnId() != null && query.getColumnId() == 300L) {
                        NoteDwtableItem item = new NoteDwtableItem();
                        item.setValue("苹果");
                        return item;
                    }
                    if (query.getColumnId() != null && query.getColumnId() == 400L) {
                        NoteDwtableItem item = new NoteDwtableItem();
                        item.setLinkRecordId("20,30");
                        item.setValue("梨,香蕉");
                        return item;
                    }
                    if (query.getColumnId() != null && query.getColumnId() == 500L) {
                        NoteDwtableItem item = new NoteDwtableItem();
                        item.setId(900L);
                        return item;
                    }
                    return null;
                });

        noteRecordService.recomputeSetOperationsForLookup(lookupColumn);

        ArgumentCaptor<NoteDwtableItem> captor = ArgumentCaptor.forClass(NoteDwtableItem.class);
        verify(noteDwtableItemMapper).updateNoteDwtableItem(captor.capture());
        // union 不保证顺序，验证值集合
        java.util.List<String> values = java.util.Arrays.asList(captor.getValue().getValue().split(","));
        org.junit.jupiter.api.Assertions.assertTrue(values.containsAll(java.util.Arrays.asList("苹果", "梨", "香蕉")));
        java.util.List<String> ids = java.util.Arrays.asList(captor.getValue().getLinkRecordId().split(","));
        org.junit.jupiter.api.Assertions.assertTrue(ids.containsAll(java.util.Arrays.asList("10", "20", "30")));
    }

    /**
     * 场景2（no-op）：setColumn 的 columnAId/columnBId 都不等于 lookup 列id → 不查记录不更新
     */
    @Test
    void testRecomputeSetOperationsForLookup_noSetColumnReferencesLookup_noOp()
    {
        NoteColumn lookupColumn = new NoteColumn();
        lookupColumn.setId(100L);
        lookupColumn.setType(26L);
        lookupColumn.setDwtableId(1L);
        lookupColumn.setProperty("{\"double_link_column_id\":\"200\",\"source_column_id\":\"300\"}");

        NoteColumn setColumn = new NoteColumn();
        setColumn.setId(500L);
        setColumn.setProperty("{\"columnAId\":\"600\",\"columnBId\":\"700\",\"calcType\":\"union\"}");

        when(noteColumnMapper.selectSetColumnByDwtId(1L)).thenReturn(Collections.singletonList(setColumn));

        noteRecordService.recomputeSetOperationsForLookup(lookupColumn);

        verify(noteRecordMapper, never()).selectNoteRecordList(any());
        verify(noteDwtableItemMapper, never()).updateNoteDwtableItem(any(NoteDwtableItem.class));
        verify(noteDwtableItemMapper, never()).insertNoteDwtableItem(any(NoteDwtableItem.class));
    }

    /**
     * 场景3（修复验证）：一个 setColumn property 非合法JSON，另一个正常 → 后者仍被处理
     */
    @Test
    void testRecomputeSetOperationsForLookup_malformedSetColumnProperty_continuesOthers()
    {
        NoteColumn lookupColumn = new NoteColumn();
        lookupColumn.setId(100L);
        lookupColumn.setType(26L);
        lookupColumn.setDwtableId(1L);
        JSONObject lookupProp = new JSONObject();
        lookupProp.put("double_link_column_id", "200");
        lookupProp.put("source_column_id", "300");
        lookupProp.put("dedupe", true);
        lookupColumn.setProperty(lookupProp.toJSONString());

        NoteColumn badSetColumn = new NoteColumn();
        badSetColumn.setId(501L);
        badSetColumn.setProperty("not-a-json");

        NoteColumn goodSetColumn = new NoteColumn();
        goodSetColumn.setId(500L);
        JSONObject goodProp = new JSONObject();
        goodProp.put("columnAId", "100");
        goodProp.put("columnBId", "400");
        goodProp.put("calcType", "union");
        goodSetColumn.setProperty(goodProp.toJSONString());

        NoteColumn columnB = new NoteColumn();
        columnB.setId(400L);
        columnB.setType(21L);

        NoteColumn sourceColumn = new NoteColumn();
        sourceColumn.setId(300L);
        sourceColumn.setType(1L);

        when(noteColumnMapper.selectSetColumnByDwtId(1L)).thenReturn(Arrays.asList(badSetColumn, goodSetColumn));
        when(noteColumnMapper.selectNoteColumnById(100L)).thenReturn(lookupColumn);
        when(noteColumnMapper.selectNoteColumnById(400L)).thenReturn(columnB);
        when(noteColumnMapper.selectNoteColumnById(300L)).thenReturn(sourceColumn);

        NoteRecord r1 = new NoteRecord();
        r1.setId(1L);
        when(noteRecordMapper.selectNoteRecordList(any())).thenReturn(Collections.singletonList(r1));

        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(any(NoteDwtableItem.class)))
                .thenAnswer(invocation -> {
                    NoteDwtableItem query = invocation.getArgument(0);
                    if (query.getLinkColumnId() != null && query.getLinkColumnId() == 200L) {
                        NoteDwtableItem item = new NoteDwtableItem();
                        item.setLinkRecordId("10,20");
                        return item;
                    }
                    if (query.getColumnId() != null && query.getColumnId() == 300L) {
                        NoteDwtableItem item = new NoteDwtableItem();
                        item.setValue("苹果");
                        return item;
                    }
                    if (query.getColumnId() != null && query.getColumnId() == 400L) {
                        NoteDwtableItem item = new NoteDwtableItem();
                        item.setLinkRecordId("20,30");
                        item.setValue("梨,香蕉");
                        return item;
                    }
                    if (query.getColumnId() != null && query.getColumnId() == 500L) {
                        NoteDwtableItem item = new NoteDwtableItem();
                        item.setId(900L);
                        return item;
                    }
                    return null;
                });

        noteRecordService.recomputeSetOperationsForLookup(lookupColumn);

        // badSetColumn 异常被捕获，goodSetColumn 正常处理 → update 被调用1次
        verify(noteDwtableItemMapper, times(1)).updateNoteDwtableItem(any(NoteDwtableItem.class));
    }

    /**
     * 场景4（per-record 容错）：第2条记录查询抛异常，第1条仍被处理
     */
    @Test
    void testRecomputeSetOperationsForLookup_singleRecordFailure_continues()
    {
        NoteColumn lookupColumn = new NoteColumn();
        lookupColumn.setId(100L);
        lookupColumn.setType(26L);
        lookupColumn.setDwtableId(1L);
        JSONObject lookupProp = new JSONObject();
        lookupProp.put("double_link_column_id", "200");
        lookupProp.put("source_column_id", "300");
        lookupProp.put("dedupe", true);
        lookupColumn.setProperty(lookupProp.toJSONString());

        NoteColumn setColumn = new NoteColumn();
        setColumn.setId(500L);
        JSONObject setProp = new JSONObject();
        setProp.put("columnAId", "100");
        setProp.put("columnBId", "400");
        setProp.put("calcType", "union");
        setColumn.setProperty(setProp.toJSONString());

        NoteColumn columnB = new NoteColumn();
        columnB.setId(400L);
        columnB.setType(21L);

        NoteColumn sourceColumn = new NoteColumn();
        sourceColumn.setId(300L);
        sourceColumn.setType(1L);

        when(noteColumnMapper.selectSetColumnByDwtId(1L)).thenReturn(Collections.singletonList(setColumn));
        when(noteColumnMapper.selectNoteColumnById(100L)).thenReturn(lookupColumn);
        when(noteColumnMapper.selectNoteColumnById(400L)).thenReturn(columnB);
        when(noteColumnMapper.selectNoteColumnById(300L)).thenReturn(sourceColumn);

        NoteRecord r1 = new NoteRecord(); r1.setId(1L);
        NoteRecord r2 = new NoteRecord(); r2.setId(2L);
        when(noteRecordMapper.selectNoteRecordList(any())).thenReturn(Arrays.asList(r1, r2));

        when(noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(any(NoteDwtableItem.class)))
                .thenAnswer(invocation -> {
                    NoteDwtableItem query = invocation.getArgument(0);
                    if (query.getRecordId() != null && query.getRecordId() == 2L) {
                        throw new RuntimeException("模拟第2条记录异常");
                    }
                    if (query.getLinkColumnId() != null && query.getLinkColumnId() == 200L) {
                        NoteDwtableItem item = new NoteDwtableItem();
                        item.setLinkRecordId("10,20");
                        return item;
                    }
                    if (query.getColumnId() != null && query.getColumnId() == 300L) {
                        NoteDwtableItem item = new NoteDwtableItem();
                        item.setValue("苹果");
                        return item;
                    }
                    if (query.getColumnId() != null && query.getColumnId() == 400L) {
                        NoteDwtableItem item = new NoteDwtableItem();
                        item.setLinkRecordId("20,30");
                        item.setValue("梨,香蕉");
                        return item;
                    }
                    if (query.getColumnId() != null && query.getColumnId() == 500L) {
                        NoteDwtableItem item = new NoteDwtableItem();
                        item.setId(900L);
                        return item;
                    }
                    return null;
                });

        noteRecordService.recomputeSetOperationsForLookup(lookupColumn);

        // 第1条成功 → update 被调用1次；第2条失败被吞
        verify(noteDwtableItemMapper, times(1)).updateNoteDwtableItem(any(NoteDwtableItem.class));
    }
}
