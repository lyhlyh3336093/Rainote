package com.ruoyi.system.service.impl;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.NoteDwtable;
import com.ruoyi.system.domain.NoteDwtableItem;
import com.ruoyi.system.domain.NoteNote;
import com.ruoyi.system.domain.NoteNotelink;
import com.ruoyi.system.domain.NoteRecord;
import com.ruoyi.system.domain.dto.ExportMatrix;
import com.ruoyi.system.domain.vo.NoteRecordVo;
import com.ruoyi.system.mapper.NoteColumnMapper;
import com.ruoyi.system.mapper.NoteDwtableItemMapper;
import com.ruoyi.system.mapper.NoteDwtableMapper;
import com.ruoyi.system.mapper.NoteNoteMapper;
import com.ruoyi.system.mapper.NoteNotelinkMapper;
import com.ruoyi.system.mapper.NoteRecordMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NoteDwtableExportPivotServiceImpl 单元测试（U1：链接列双轨导出格式与语义修正）。
 * <p>
 * 覆盖 R1–R4：
 * <ul>
 *   <li>R1：21 列名称主列=目标记录名（多值逗号连接、CSV 风格转义），`列名_ID` 副列=linkRecordId 原样</li>
 *   <li>R2：25 列名称主列="引用笔记名（锚词）"条目串（全角括号、锚词空省略），`列名_ID` 副列=NoteNotelink 行 id（顺序一一对应）</li>
 *   <li>R4：悬空引用（目标记录已删/来源笔记已删）写空单元格，不报错不阻断</li>
 *   <li>性能：25 列笔记标题/表级归属笔记解析走导出全程共享缓存（noteTitleCache，
 *       对齐 recordNameCache 先例），times() 断言锁定 mapper 调用次数下降</li>
 *   <li>回归红线：18 列导出输出与现状逐字节一致（含 `列名_ID` 副列）</li>
 * </ul>
 * 行结构约定：每行 = [record_id, 每个双列类型的 (ID, 文本) 相邻两格, ...]，与列 sort 顺序对应。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class NoteDwtableExportPivotServiceImplTest
{
    /** 测试用固定坐标 */
    private static final Long NOTE_ID = 1L;
    private static final Long TABLE_ID = 100L;

    @Mock
    private NoteDwtableMapper noteDwtableMapper;

    @Mock
    private NoteColumnMapper noteColumnMapper;

    @Mock
    private NoteRecordMapper noteRecordMapper;

    @Mock
    private NoteDwtableItemMapper noteDwtableItemMapper;

    @Mock
    private NoteNotelinkMapper noteNotelinkMapper;

    @Mock
    private NoteNoteMapper noteNoteMapper;

    @InjectMocks
    private NoteDwtableExportPivotServiceImpl service;

    // ---------- 构造工具 ----------

    private NoteColumn column(long id, String name, long type, long sort)
    {
        NoteColumn col = new NoteColumn();
        col.setId(id);
        col.setName(name);
        col.setType(type);
        col.setDwtableId(TABLE_ID);
        col.setSort(sort);
        col.setIsShow(0L);
        return col;
    }

    private NoteRecord record(long id, String name)
    {
        NoteRecord r = new NoteRecord();
        r.setId(id);
        r.setName(name);
        return r;
    }

    private NoteDwtableItem item(long id, long recordId, long columnId, String value, String linkRecordId)
    {
        NoteDwtableItem i = new NoteDwtableItem();
        i.setId(id);
        i.setDwtId(TABLE_ID);
        i.setRecordId(recordId);
        i.setColumnId(columnId);
        i.setValue(value);
        i.setLinkRecordId(linkRecordId);
        return i;
    }

    private NoteNotelink notelink(long id, Long noteId, String contextText, String itemValue,
            Long linkNoteId, Long linkDwTableId)
    {
        NoteNotelink l = new NoteNotelink();
        l.setId(id);
        l.setNoteId(noteId);
        l.setContextText(contextText);
        l.setItemValue(itemValue);
        l.setLinkNoteId(linkNoteId);
        l.setLinkDwTableId(linkDwTableId);
        return l;
    }

    private NoteNote note(long id, String title)
    {
        NoteNote n = new NoteNote();
        n.setId(id);
        n.setTitle(title);
        return n;
    }

    /**
     * 搭建单表 pivot 环境（noteId=1 下仅一张表）并执行 pivot。
     *
     * @param refRecords 21/18 列引用的目标记录（selectNoteRecordByIds 返回值，null 表示不打桩）
     */
    private ExportMatrix pivotSingleTable(List<NoteColumn> columns, List<NoteRecord> records,
            List<NoteDwtableItem> items, List<NoteRecord> refRecords)
    {
        NoteDwtable table = new NoteDwtable();
        table.setId(TABLE_ID);
        table.setNoteId(NOTE_ID);
        table.setName("测试表");
        when(noteDwtableMapper.selectDWTableList(NOTE_ID)).thenReturn(Collections.singletonList(table));
        when(noteColumnMapper.selectNoteColumnList(any(NoteColumn.class))).thenReturn(columns);
        when(noteRecordMapper.selectNoteRecordList(any(NoteRecordVo.class))).thenReturn(records);
        when(noteDwtableItemMapper.selectNoteDwtableItemList(any(NoteDwtableItem.class))).thenReturn(items);
        if (refRecords != null)
        {
            when(noteRecordMapper.selectNoteRecordByIds(any(String[].class))).thenReturn(refRecords);
        }
        List<ExportMatrix> matrices = service.pivot(NOTE_ID);
        assertEquals(1, matrices.size(), "应仅有一张表的导出矩阵");
        return matrices.get(0);
    }

    // ---------- 21 列（双向关联）----------

    /**
     * 场景1：21 列单目标——名称主列=记录名、`_ID` 副列=该记录 id（R1）。
     */
    @Test
    public void testDoubleLinkSingleTarget()
    {
        NoteColumn col = column(202L, "双向关联", 21L, 1L);
        List<String> row = pivotSingleTable(
                Collections.singletonList(col),
                Collections.singletonList(record(301L, "行一")),
                Collections.singletonList(item(501L, 301L, 202L, "满攻", "401")),
                Collections.singletonList(record(401L, "满攻")))
                .getRows().get(0);

        // 行结构：[record_id, 21列_ID, 21列_文本]
        assertEquals(Arrays.asList("301", "401", "满攻"), row, "21 列单目标应输出记录名与对应 id");
    }

    /**
     * 场景2：21 列多目标——名称逗号连接，顺序与 `_ID` 串的 id 顺序一致（R1）。
     */
    @Test
    public void testDoubleLinkMultiTargetOrderConsistent()
    {
        NoteColumn col = column(202L, "双向关联", 21L, 1L);
        List<String> row = pivotSingleTable(
                Collections.singletonList(col),
                Collections.singletonList(record(301L, "行一")),
                Collections.singletonList(item(501L, 301L, 202L, "", "401,402")),
                Arrays.asList(record(401L, "满攻"), record(402L, "速推")))
                .getRows().get(0);

        assertEquals("401,402", row.get(1), "`_ID` 副列应为 linkRecordId 原样");
        assertEquals("满攻,速推", row.get(2), "名称条目顺序应与 id 顺序一一对应");
    }

    /**
     * 场景3：记录名含逗号/引号——名称整名双引号包裹、内层双引号翻倍（R1 CSV 风格转义）；
     * 普通名称不包裹；`_ID` 副列仍为 linkRecordId 原样。
     */
    @Test
    public void testDoubleLinkNameWithCommaOrQuoteEscaped()
    {
        NoteColumn col = column(202L, "双向关联", 21L, 1L);
        List<String> row = pivotSingleTable(
                Collections.singletonList(col),
                Collections.singletonList(record(301L, "行一")),
                Collections.singletonList(item(501L, 301L, 202L, "", "403,404,405")),
                Arrays.asList(record(403L, "平凡名"), record(404L, "a,b"), record(405L, "他说\"x\"")))
                .getRows().get(0);

        assertEquals("403,404,405", row.get(1), "`_ID` 副列应保持 linkRecordId 原样");
        assertEquals("平凡名,\"a,b\",\"他说\"\"x\"\"\"", row.get(2),
                "含逗号/引号的名称应整名包裹并翻倍内层引号，普通名称不包裹");
    }

    /**
     * 场景4：21 列悬空引用（目标记录已删）——名称主列写空、不报错（R4）；
     * `_ID` 副列按现状保留 linkRecordId 原样。
     */
    @Test
    public void testDoubleLinkDanglingTargetEmptyName()
    {
        NoteColumn col = column(202L, "双向关联", 21L, 1L);
        List<String> row = pivotSingleTable(
                Collections.singletonList(col),
                Collections.singletonList(record(301L, "行一")),
                Collections.singletonList(item(501L, 301L, 202L, "", "999")),
                Collections.emptyList())
                .getRows().get(0);

        assertEquals("999", row.get(1), "21 列 `_ID` 副列保留现状（linkRecordId 原样）");
        assertEquals("", row.get(2), "悬空引用的名称主列应写空");
    }

    // ---------- 25 列（语义关联）----------

    /**
     * 场景5：25 列多行 notelink——条目数与 `_ID` 串的 id 数一致、顺序一一对应（R2）；
     * 条目="引用笔记名（锚词）"，引用来源=NoteNotelink 的 noteId 侧笔记。
     */
    @Test
    public void testSemanticLinkMultipleRowsEntriesMatchIds()
    {
        NoteColumn col = column(203L, "语义关联", 25L, 1L);
        when(noteNotelinkMapper.selectNoteNotelinkByCell(203L, 501L)).thenReturn(Arrays.asList(
                notelink(601L, 701L, "满攻", "满攻", null, null),
                notelink(602L, 702L, "速推", "速推", null, null)));
        when(noteNoteMapper.selectNoteNoteById(701L)).thenReturn(note(701L, "笔记甲"));
        when(noteNoteMapper.selectNoteNoteById(702L)).thenReturn(note(702L, "笔记乙"));

        List<String> row = pivotSingleTable(
                Collections.singletonList(col),
                Collections.singletonList(record(301L, "行一")),
                Collections.singletonList(item(501L, 301L, 203L, "", null)),
                null)
                .getRows().get(0);

        assertEquals("601,602", row.get(1), "`_ID` 副列应为 NoteNotelink 行 id 串");
        assertEquals("笔记甲（满攻）,笔记乙（速推）", row.get(2),
                "名称条目应为'引用笔记名（锚词）'且与 id 顺序对应");
        // 条目数与 id 数一致（本场景名称不含逗号，可直接按逗号计数）
        assertEquals(row.get(1).split(",").length, row.get(2).split(",").length,
                "名称条目数应与 id 数一致");
    }

    /**
     * 场景6：25 列历史行 linkNoteId=null——引用笔记名经 noteId 侧（归属兜底链）产出、
     * `_ID` 为 NoteNotelink 行 id（R2）。
     */
    @Test
    public void testSemanticLinkHistoricalNullLinkNoteId()
    {
        NoteColumn col = column(203L, "语义关联", 25L, 1L);
        when(noteNotelinkMapper.selectNoteNotelinkByCell(203L, 501L)).thenReturn(Collections.singletonList(
                notelink(603L, 703L, "锚词甲", "锚词甲", null, 800L)));
        when(noteNoteMapper.selectNoteNoteById(703L)).thenReturn(note(703L, "历史笔记"));

        List<String> row = pivotSingleTable(
                Collections.singletonList(col),
                Collections.singletonList(record(301L, "行一")),
                Collections.singletonList(item(501L, 301L, 203L, "", null)),
                null)
                .getRows().get(0);

        assertEquals("603", row.get(1), "历史行 `_ID` 应为 NoteNotelink 行 id");
        assertEquals("历史笔记（锚词甲）", row.get(2),
                "linkNoteId=null 的历史行文本仍应产出（引用笔记名+锚词）");
    }

    /**
     * 场景7：25 列 noteId=null 的防御场景——经 COALESCE 式兜底链取引用笔记名：
     * 先 linkNoteId；linkNoteId 也为 null 时从 linkDwTableId→NoteDwtable.noteId 取。
     */
    @Test
    public void testSemanticLinkNoteIdNullFallbackChain()
    {
        NoteColumn col = column(203L, "语义关联", 25L, 1L);
        when(noteNotelinkMapper.selectNoteNotelinkByCell(203L, 501L)).thenReturn(Arrays.asList(
                notelink(605L, null, "词一", "词一", 950L, null),
                notelink(606L, null, "词二", "词二", null, 800L)));
        when(noteNoteMapper.selectNoteNoteById(950L)).thenReturn(note(950L, "链上笔记"));
        // linkDwTableId=800 → NoteDwtable.noteId=900 兜底
        NoteDwtable linkedTable = new NoteDwtable();
        linkedTable.setId(800L);
        linkedTable.setNoteId(900L);
        when(noteDwtableMapper.selectNoteDwtableById(800L)).thenReturn(linkedTable);
        when(noteNoteMapper.selectNoteNoteById(900L)).thenReturn(note(900L, "兜底笔记"));

        List<String> row = pivotSingleTable(
                Collections.singletonList(col),
                Collections.singletonList(record(301L, "行一")),
                Collections.singletonList(item(501L, 301L, 203L, "", null)),
                null)
                .getRows().get(0);

        assertEquals("605,606", row.get(1), "两行 notelink 的 `_ID` 应为行 id 串");
        assertEquals("链上笔记（词一）,兜底笔记（词二）", row.get(2),
                "noteId=null 时应经 linkNoteId/归属表兜底取引用笔记名");
    }

    /**
     * 场景8：25 列锚词为空——contextText 空回退 itemValue；两者都空省略全角括号（R2）。
     */
    @Test
    public void testSemanticLinkAnchorEmptyOmitsParentheses()
    {
        NoteColumn col = column(203L, "语义关联", 25L, 1L);
        when(noteNotelinkMapper.selectNoteNotelinkByCell(203L, 501L)).thenReturn(Arrays.asList(
                notelink(601L, 701L, null, "回退词", null, null),
                notelink(602L, 702L, "", "", null, null)));
        when(noteNoteMapper.selectNoteNoteById(701L)).thenReturn(note(701L, "笔记甲"));
        when(noteNoteMapper.selectNoteNoteById(702L)).thenReturn(note(702L, "笔记乙"));

        List<String> row = pivotSingleTable(
                Collections.singletonList(col),
                Collections.singletonList(record(301L, "行一")),
                Collections.singletonList(item(501L, 301L, 203L, "", null)),
                null)
                .getRows().get(0);

        assertEquals("笔记甲（回退词）,笔记乙", row.get(2),
                "contextText 空应回退 itemValue，两者都空应省略括号");
    }

    /**
     * 场景9：25 列悬空引用（来源笔记已删）——该条目整体跳过，名称与 `_ID` 均为空（R4）。
     */
    @Test
    public void testSemanticLinkDanglingSourceNoteEmpty()
    {
        NoteColumn col = column(203L, "语义关联", 25L, 1L);
        when(noteNotelinkMapper.selectNoteNotelinkByCell(203L, 501L)).thenReturn(Collections.singletonList(
                notelink(604L, 704L, "词", "词", null, null)));
        // selectNoteNoteById(704L) 未打桩，默认返回 null——模拟来源笔记已删

        List<String> row = pivotSingleTable(
                Collections.singletonList(col),
                Collections.singletonList(record(301L, "行一")),
                Collections.singletonList(item(501L, 301L, 203L, "", null)),
                null)
                .getRows().get(0);

        assertEquals("", row.get(1), "悬空引用的 `_ID` 应为空");
        assertEquals("", row.get(2), "悬空引用的名称主列应为空");
    }

    /**
     * 场景10：25 列单元格无 item（或 notelink 行不存在）——双列均写空（R4）。
     */
    @Test
    public void testSemanticLinkNoItemEmpty()
    {
        NoteColumn col = column(203L, "语义关联", 25L, 1L);

        List<String> row = pivotSingleTable(
                Collections.singletonList(col),
                Collections.singletonList(record(301L, "行一")),
                Collections.emptyList(),
                null)
                .getRows().get(0);

        assertEquals(Arrays.asList("301", "", ""), row, "无 item 的语义关联列应双列写空");
    }

    /**
     * 场景11：25 列悬空与有效条目混合——仅悬空条目跳过，有效条目名称与 id 保持一一对应（R4）。
     */
    @Test
    public void testSemanticLinkDanglingMixedWithValid()
    {
        NoteColumn col = column(203L, "语义关联", 25L, 1L);
        when(noteNotelinkMapper.selectNoteNotelinkByCell(203L, 501L)).thenReturn(Arrays.asList(
                notelink(601L, 701L, "词甲", "词甲", null, null),
                notelink(602L, 704L, "词乙", "词乙", null, null)));
        when(noteNoteMapper.selectNoteNoteById(701L)).thenReturn(note(701L, "笔记甲"));
        // 704 笔记已删（未打桩返回 null）

        List<String> row = pivotSingleTable(
                Collections.singletonList(col),
                Collections.singletonList(record(301L, "行一")),
                Collections.singletonList(item(501L, 301L, 203L, "", null)),
                null)
                .getRows().get(0);

        assertEquals("601", row.get(1), "悬空条目跳过后 `_ID` 仅含有效行 id");
        assertEquals("笔记甲（词甲）", row.get(2), "悬空条目跳过后名称仅含有效条目");
    }

    /**
     * 场景14：25 列缓存命中断言——多行 notelink 指向同一来源笔记/同一目标表时，
     * 标题查询（selectNoteNoteById）与表级归属查询（selectNoteDwtableById）均仅落库一次
     * （noteTitleCache 导出全程共享、miss 才落库，对齐 recordNameCache 先例）。
     */
    @Test
    public void testSemanticLinkTitleCacheSingleQueryPerNote()
    {
        NoteColumn col = column(203L, "语义关联", 25L, 1L);
        // 3 行历史 notelink：noteId/linkNoteId 均 null，同 linkDwTableId=800 → 表级归属兜底
        when(noteNotelinkMapper.selectNoteNotelinkByCell(203L, 501L)).thenReturn(Arrays.asList(
                notelink(605L, null, "词一", "词一", null, 800L),
                notelink(606L, null, "词二", "词二", null, 800L),
                notelink(607L, null, "词三", "词三", null, 800L)));
        NoteDwtable linkedTable = new NoteDwtable();
        linkedTable.setId(800L);
        linkedTable.setNoteId(900L);
        when(noteDwtableMapper.selectNoteDwtableById(800L)).thenReturn(linkedTable);
        when(noteNoteMapper.selectNoteNoteById(900L)).thenReturn(note(900L, "兜底笔记"));

        List<String> row = pivotSingleTable(
                Collections.singletonList(col),
                Collections.singletonList(record(301L, "行一")),
                Collections.singletonList(item(501L, 301L, 203L, "", null)),
                null)
                .getRows().get(0);

        assertEquals("605,606,607", row.get(1), "3 行 notelink 的 `_ID` 应为行 id 串");
        assertEquals("兜底笔记（词一）,兜底笔记（词二）,兜底笔记（词三）", row.get(2),
                "3 行共享同一归属笔记，名称条目仍逐行产出");
        // 缓存锁定：3 次引用/兜底仅 1 次落库查询（改造前为各查一次共 3 次）
        verify(noteDwtableMapper, times(1)).selectNoteDwtableById(800L);
        verify(noteNoteMapper, times(1)).selectNoteNoteById(900L);
    }

    // ---------- 18 列（单向关联）回归红线 ----------

    /**
     * 场景12：18 列回归——导出输出与现状逐字节一致：
     * 现状算法=ID 列 linkRecordId 原样；文本列逐 id 解析记录名（悬空/非法 id 空占位、不转义）逗号 join。
     * 覆盖多值、悬空 id（999）、名称含逗号（"a,b" 不转义——18/21 行为分叉点）。
     */
    @Test
    public void testSingleLinkRegressionByteIdentical()
    {
        NoteColumn col = column(201L, "单向关联", 18L, 1L);
        List<String> row = pivotSingleTable(
                Collections.singletonList(col),
                Collections.singletonList(record(301L, "行一")),
                Collections.singletonList(item(501L, 301L, 201L, "", "401,999,404")),
                Arrays.asList(record(401L, "满攻"), record(404L, "a,b")))
                .getRows().get(0);

        // 现状输出推导：ID=linkRecordId 原样；文本=满攻 + 空占位(999 悬空) + a,b（含逗号不转义）
        assertEquals("401,999,404", row.get(1), "18 列 `_ID` 副列应与现状一致（linkRecordId 原样，含悬空 id）");
        assertEquals("满攻,,a,b", row.get(2), "18 列文本应与现状逐字节一致（悬空空占位、含逗号名称不转义）");
    }

    /**
     * 场景13：18 列与 21 列同表并存——18 列不转义、21 列转义，两列行为互不影响；
     * 同时验证 18 列在 21 列改造后的输出仍与现状一致。
     */
    @Test
    public void testSingleLinkAndDoubleLinkCoexist()
    {
        NoteColumn col18 = column(201L, "单向关联", 18L, 1L);
        NoteColumn col21 = column(202L, "双向关联", 21L, 2L);
        List<String> row = pivotSingleTable(
                Arrays.asList(col18, col21),
                Collections.singletonList(record(301L, "行一")),
                Arrays.asList(
                        item(501L, 301L, 201L, "", "404"),
                        item(502L, 301L, 202L, "", "404")),
                Collections.singletonList(record(404L, "a,b")))
                .getRows().get(0);

        // 行结构：[record_id, 18列_ID, 18列_文本, 21列_ID, 21列_文本]
        assertEquals("a,b", row.get(2), "18 列名称含逗号应保持现状不转义");
        assertEquals("\"a,b\"", row.get(4), "21 列名称含逗号应 CSV 转义包裹");
        assertEquals("404", row.get(3), "21 列 `_ID` 副列应为 linkRecordId 原样");
    }
}
