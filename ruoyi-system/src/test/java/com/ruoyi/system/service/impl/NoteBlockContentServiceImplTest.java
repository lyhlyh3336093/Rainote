package com.ruoyi.system.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.service.NoteBlockContentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NoteBlockContentServiceImpl 单元测试（U1）。
 * <p>
 * 验证语义锚点文本恢复的匹配谓词（R7）、strip 规则（R8）、
 * Jsoup round-trip 保真度，以及历史锚点降级匹配。
 * <p>
 * 该服务无 Spring 依赖，直接 new 实例化测试。
 */
class NoteBlockContentServiceImplTest {

    private NoteBlockContentService service;

    @BeforeEach
    void setUp() {
        service = new NoteBlockContentServiceImpl();
    }

    /** 从 property JSON 中提取 data.text 字段值 */
    private String extractText(String propertyJson) {
        JSONObject obj = JSON.parseObject(propertyJson);
        JSONObject data = obj.getJSONObject("data");
        return data != null ? data.getString("text") : null;
    }

    /** 构造一个 paragraph block property，text 中嵌入给定 HTML */
    private String paragraphBlock(String html) {
        JSONObject block = new JSONObject();
        block.put("type", "paragraph");
        JSONObject data = new JSONObject();
        data.put("text", html);
        block.put("data", data);
        return block.toJSONString();
    }

    // ============ R7 REVERSE 锚点匹配 ============

    /**
     * 场景1：REVERSE 锚点（data-link-id 命中 deleted set）→ 替换为纯文本，strip [红门] → 红门
     */
    @Test
    void testRestoreAnchors_reverseAnchorMatch_strippedAndReplaced() {
        String html = "前缀<a data-type=\"semantic\" data-link-id=\"100\">[红门]</a>后缀";
        String property = paragraphBlock(html);

        Set<Long> reverseLinkIds = new HashSet<>();
        reverseLinkIds.add(100L);

        String result = service.restoreAnchors(property, 50L, reverseLinkIds, Collections.emptySet());

        String text = extractText(result);
        assertEquals("前缀红门后缀", text, "REVERSE 锚点应被替换为纯文本，且 strip 首尾中括号");
    }

    // ============ R7 FORWARD 锚点匹配（新，data-column-id）============

    /**
     * 场景2：FORWARD 锚点（data-column-id 命中被删列 id）→ 替换为纯文本
     */
    @Test
    void testRestoreAnchors_forwardAnchorMatch_strippedAndReplaced() {
        String html = "前缀<a data-type=\"semantic\" data-link-id=\"\" data-column-id=\"50\">[标题]</a>后缀";
        String property = paragraphBlock(html);

        String result = service.restoreAnchors(property, 50L, Collections.emptySet(), Collections.emptySet());

        String text = extractText(result);
        assertEquals("前缀标题后缀", text, "FORWARD 锚点（data-column-id 匹配）应被替换为纯文本");
    }

    // ============ R7 非命中锚点保持不变 ============

    /**
     * 场景3a：REVERSE 锚点 data-link-id 不在 deleted set → 保持不变
     */
    @Test
    void testRestoreAnchors_reverseAnchorNotInSet_unchanged() {
        String html = "前缀<a data-type=\"semantic\" data-link-id=\"999\">[其他]</a>后缀";
        String property = paragraphBlock(html);

        Set<Long> reverseLinkIds = new HashSet<>();
        reverseLinkIds.add(100L); // 不含 999

        String result = service.restoreAnchors(property, 50L, reverseLinkIds, Collections.emptySet());

        // 无匹配锚点，返回原字符串（同引用）
        assertSame(property, result, "无匹配锚点时应返回原字符串引用");
    }

    /**
     * 场景3b：FORWARD 锚点 data-column-id 不匹配被删列 → 保持不变
     */
    @Test
    void testRestoreAnchors_forwardAnchorColumnMismatch_unchanged() {
        String html = "前缀<a data-type=\"semantic\" data-link-id=\"\" data-column-id=\"999\">[其他列]</a>后缀";
        String property = paragraphBlock(html);

        String result = service.restoreAnchors(property, 50L, Collections.emptySet(), Collections.emptySet());

        assertSame(property, result, "FORWARD 锚点 data-column-id 不匹配时应返回原字符串");
    }

    // ============ 多锚点同一 block ============

    /**
     * 场景4：同一 block 多个锚点，一次 parse 替换全部命中锚点，一次 serialize 回写。
     * 非命中锚点保持为完整 &lt;a&gt; 标签（不被 strip）。
     */
    @Test
    void testRestoreAnchors_multipleAnchorsSameBlock_allReplacedInOnePass() {
        String html = "<a data-type=\"semantic\" data-link-id=\"100\">[甲]</a>"
                + "中间文本"
                + "<a data-type=\"semantic\" data-link-id=\"101\">[乙]</a>"
                + "<a data-type=\"semantic\" data-link-id=\"999\">[不删]</a>";
        String property = paragraphBlock(html);

        Set<Long> reverseLinkIds = new HashSet<>();
        reverseLinkIds.add(100L);
        reverseLinkIds.add(101L);

        String result = service.restoreAnchors(property, 50L, reverseLinkIds, Collections.emptySet());

        String text = extractText(result);
        // 命中锚点（100、101）被替换为纯文本
        assertTrue(text.contains("甲"), "命中锚点 100 应被替换为纯文本");
        assertTrue(text.contains("乙"), "命中锚点 101 应被替换为纯文本");
        assertTrue(text.contains("中间文本"), "非锚点文本应保留");
        // 非命中锚点（999）保持为完整 <a> 标签
        assertTrue(text.contains("data-link-id=\"999\""), "非命中锚点应保留完整标签");
        assertTrue(text.contains("[不删]"), "非命中锚点的括号文本应保留");
        // 已删除锚点的 data-link-id 不应残留
        assertFalse(text.contains("data-link-id=\"100\""), "命中锚点 100 的标签应被移除");
        assertFalse(text.contains("data-link-id=\"101\""), "命中锚点 101 的标签应被移除");
    }

    // ============ 畸形 JSON ============

    /**
     * 场景5：畸形 JSON 解析抛 IllegalArgumentException（由上层 U3 per-block catch 处理）
     */
    @Test
    void testRestoreAnchors_malformedJson_throwsIllegalArgument() {
        String badJson = "not-a-json";

        assertThrows(IllegalArgumentException.class, () ->
                service.restoreAnchors(badJson, 50L, Collections.emptySet(), Collections.emptySet()),
                "畸形 JSON 应抛 IllegalArgumentException 供上层 catch");
    }

    // ============ 畸形 HTML（Jsoup 容错）============

    /**
     * 场景6：畸形 HTML（未闭合标签）Jsoup 容错解析，不抛异常
     */
    @Test
    void testRestoreAnchors_malformedHtml_jsoupToleratesNoException() {
        // 未闭合的 anchor，Jsoup 会自动闭合
        String html = "<a data-type=\"semantic\" data-link-id=\"100\">[未闭合";
        String property = paragraphBlock(html);

        Set<Long> reverseLinkIds = new HashSet<>();
        reverseLinkIds.add(100L);

        // 不应抛异常（Jsoup 容错解析）
        assertDoesNotThrow(() -> {
            String result = service.restoreAnchors(property, 50L, reverseLinkIds, Collections.emptySet());
            assertNotNull(result);
        }, "畸形 HTML 应由 Jsoup 容错处理，不抛异常");
    }

    // ============ R8 strip 规则 ============

    /**
     * 场景7：strip [复杂[文本] → 复杂[文本（只 strip 首尾第一个，正则 ^\[|\]$）
     */
    @Test
    void testRestoreAnchors_stripOnlyFirstAndLastBracket() {
        String html = "<a data-type=\"semantic\" data-link-id=\"100\">[复杂[文本]</a>";
        String property = paragraphBlock(html);

        Set<Long> reverseLinkIds = new HashSet<>();
        reverseLinkIds.add(100L);

        String result = service.restoreAnchors(property, 50L, reverseLinkIds, Collections.emptySet());

        String text = extractText(result);
        assertEquals("复杂[文本", text, "strip 正则 ^\\[|\\]$ 只移除首尾第一个中括号");
    }

    // ============ Jsoup round-trip 保真 ============

    /**
     * 场景8：含中文、&nbsp;、嵌套 <strong> 的 HTML 解析后序列化不损坏
     */
    @Test
    void testRestoreAnchors_roundTripPreservesChineseEntitiesAndNestedTags() {
        // 非 semantic 锚点，验证 round-trip 不损坏无关 HTML
        String html = "中文测试<strong>加粗</strong>&nbsp;空格<em>斜体</em>";
        String property = paragraphBlock(html);

        String result = service.restoreAnchors(property, 50L, Collections.emptySet(), Collections.emptySet());

        // 无 semantic 锚点，应返回原字符串
        assertSame(property, result, "无 semantic 锚点的 HTML 应原样返回");
    }

    /**
     * 场景8b：含 semantic 锚点替换后，周围中文与嵌套标签保持完好
     */
    @Test
    void testRestoreAnchors_preservesSurroundingHtmlAfterReplacement() {
        String html = "<strong>标题</strong>：<a data-type=\"semantic\" data-link-id=\"100\">[链接]</a>&nbsp;尾部";
        String property = paragraphBlock(html);

        Set<Long> reverseLinkIds = new HashSet<>();
        reverseLinkIds.add(100L);

        String result = service.restoreAnchors(property, 50L, reverseLinkIds, Collections.emptySet());

        String text = extractText(result);
        // &nbsp; 在 Jsoup body().html() 中会被保留为 &nbsp;
        assertTrue(text.contains("<strong>标题</strong>"), "周围嵌套标签应保持完好");
        assertTrue(text.contains("链接"), "锚点文本应被恢复");
        assertFalse(text.contains("data-type=\"semantic\""), "semantic 锚点应被移除");
    }

    // ============ 历史锚点降级匹配 ============

    /**
     * 场景9：历史锚点（无 data-column-id）降级匹配 data-table-id:data-record-id ∈ forwardKeys
     */
    @Test
    void testRestoreAnchors_legacyForwardAnchor_degradedMatch() {
        // 历史锚点：无 data-column-id，data-link-id 为空，有 data-table-id 和 data-record-id
        String html = "<a data-type=\"semantic\" data-link-id=\"\" data-table-id=\"1\" data-record-id=\"2\">[历史]</a>";
        String property = paragraphBlock(html);

        Set<String> forwardKeys = new HashSet<>();
        forwardKeys.add("1:2");

        String result = service.restoreAnchors(property, 50L, Collections.emptySet(), forwardKeys);

        String text = extractText(result);
        assertEquals("历史", text, "历史锚点应通过 data-table-id:data-record-id 降级匹配并恢复");
    }

    /**
     * 场景9b：历史锚点 data-table-id:data-record-id 不在 forwardKeys → 保持不变
     */
    @Test
    void testRestoreAnchors_legacyForwardAnchor_keyNotInSet_unchanged() {
        String html = "<a data-type=\"semantic\" data-link-id=\"\" data-table-id=\"9\" data-record-id=\"9\">[其他]</a>";
        String property = paragraphBlock(html);

        Set<String> forwardKeys = new HashSet<>();
        forwardKeys.add("1:2");

        String result = service.restoreAnchors(property, 50L, Collections.emptySet(), forwardKeys);

        assertSame(property, result, "历史锚点 key 不匹配时应保持不变");
    }

    // ============ 边界场景 ============

    /**
     * 场景10：null/空 propertyJson 原样返回
     */
    @Test
    void testRestoreAnchors_nullOrEmptyProperty_returnedAsIs() {
        assertNull(service.restoreAnchors(null, 50L, Collections.emptySet(), Collections.emptySet()));
        assertEquals("", service.restoreAnchors("", 50L, Collections.emptySet(), Collections.emptySet()));
    }

    /**
     * 场景11：递归遍历嵌套结构（list block 的 data.items[].text 含锚点）
     */
    @Test
    void testRestoreAnchors_nestedItemsArray_anchorReplaced() {
        // 模拟 list block：data.items[] 数组，每个 item 有 text 字段
        JSONObject block = new JSONObject();
        block.put("type", "list");
        JSONObject data = new JSONObject();
        com.alibaba.fastjson2.JSONArray items = new com.alibaba.fastjson2.JSONArray();
        JSONObject item1 = new JSONObject();
        item1.put("text", "<a data-type=\"semantic\" data-link-id=\"100\">[项一]</a>");
        JSONObject item2 = new JSONObject();
        item2.put("text", "普通文本");
        items.add(item1);
        items.add(item2);
        data.put("items", items);
        block.put("data", data);
        String property = block.toJSONString();

        Set<Long> reverseLinkIds = new HashSet<>();
        reverseLinkIds.add(100L);

        String result = service.restoreAnchors(property, 50L, reverseLinkIds, Collections.emptySet());

        JSONObject resultObj = JSON.parseObject(result);
        String item1Text = resultObj.getJSONObject("data").getJSONArray("items").getJSONObject(0).getString("text");
        assertEquals("项一", item1Text, "嵌套 data.items[].text 中的锚点应被递归处理");
    }

    /**
     * 场景12：sibling columns — 两个 FORWARD 锚点分属不同列，删除一列只命中本列锚点。
     * 存活列锚点保持为完整 &lt;a&gt; 标签（不被 strip）。
     */
    @Test
    void testRestoreAnchors_siblingColumns_onlyMatchingColumnRestored() {
        // 两个 FORWARD 锚点：column-id=50（被删）和 column-id=60（存活）
        String html = "<a data-type=\"semantic\" data-link-id=\"\" data-column-id=\"50\">[本列]</a>"
                + "<a data-type=\"semantic\" data-link-id=\"\" data-column-id=\"60\">[他列]</a>";
        String property = paragraphBlock(html);

        String result = service.restoreAnchors(property, 50L, Collections.emptySet(), Collections.emptySet());

        String text = extractText(result);
        // 被删列(column-id=50)锚点被替换为纯文本
        assertTrue(text.contains("本列"), "被删列锚点应被替换为纯文本");
        assertFalse(text.contains("data-column-id=\"50\""), "被删列锚点标签应被移除");
        // 存活列(column-id=60)锚点保持完整 <a> 标签
        assertTrue(text.contains("data-column-id=\"60\""), "存活列锚点应保留完整标签");
        assertTrue(text.contains("[他列]"), "存活列锚点括号文本应保留");
    }
}
