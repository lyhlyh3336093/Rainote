package com.ruoyi.system.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.NoteDwtableItem;
import com.ruoyi.system.domain.NoteRecord;
import com.ruoyi.system.mapper.NoteColumnMapper;
import com.ruoyi.system.mapper.NoteDwtableItemMapper;
import com.ruoyi.system.mapper.NoteRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.Collections;

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
}
