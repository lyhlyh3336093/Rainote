package com.ruoyi.system.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.NoteDwtableItem;
import com.ruoyi.system.mapper.NoteColumnMapper;
import com.ruoyi.system.mapper.NoteDwtableItemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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
}
