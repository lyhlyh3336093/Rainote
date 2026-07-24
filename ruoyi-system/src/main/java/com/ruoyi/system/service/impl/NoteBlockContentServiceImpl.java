package com.ruoyi.system.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.service.NoteBlockContentService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Set;

/**
 * NoteBlock 内容解析与语义锚点文本恢复实现。
 * <p>
 * 使用 Jsoup 解析 HTML，FastJSON 解析 property JSON。
 * 递归遍历 JSON 的所有字符串值，仅对含 {@code data-type="semantic"} 的字符串做替换，
 * 避免对无关字符串的 round-trip 损坏。
 */
@Service
public class NoteBlockContentServiceImpl implements NoteBlockContentService {

    private static final Logger log = LoggerFactory.getLogger(NoteBlockContentServiceImpl.class);

    /** Jsoup CSS 选择器：匹配 a[data-type="semantic"] 锚点 */
    private static final String SEMANTIC_ANCHOR_SELECTOR = "a[data-type=semantic]";

    /** 快速判断字符串是否可能含语义锚点（避免对无关字符串做 Jsoup 解析） */
    private static final String ANCHOR_MARKER = "data-type=\"semantic\"";

    @Override
    public String restoreAnchors(String propertyJson, Long columnId,
                                  Set<Long> reverseLinkIds, Set<String> forwardKeys) {
        if (propertyJson == null || propertyJson.isEmpty()) {
            return propertyJson;
        }
        Object parsed;
        try {
            parsed = JSON.parse(propertyJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse NoteBlock.property JSON", e);
        }
        if (parsed == null) {
            return propertyJson;
        }
        boolean changed;
        if (parsed instanceof JSONObject) {
            changed = processObject((JSONObject) parsed, columnId, reverseLinkIds, forwardKeys);
        } else if (parsed instanceof JSONArray) {
            changed = processArray((JSONArray) parsed, columnId, reverseLinkIds, forwardKeys);
        } else {
            // 顶层是基本类型（字符串/数字/布尔），不是 JSON 对象或数组，不处理
            return propertyJson;
        }
        if (changed) {
            return JSON.toJSONString(parsed);
        }
        return propertyJson;
    }

    /**
     * 递归处理 JSONObject 的所有字段值。
     *
     * @return 是否有字段被修改
     */
    private boolean processObject(JSONObject obj, Long columnId,
                                  Set<Long> reverseLinkIds, Set<String> forwardKeys) {
        boolean changed = false;
        // 复制 keySet 避免遍历时 put 触发 ConcurrentModificationException
        for (String key : new ArrayList<>(obj.keySet())) {
            Object value = obj.get(key);
            Object newValue = processElement(value, columnId, reverseLinkIds, forwardKeys);
            if (newValue != null) {
                obj.put(key, newValue);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 递归处理 JSONArray 的所有元素。
     *
     * @return 是否有元素被修改
     */
    private boolean processArray(JSONArray arr, Long columnId,
                                 Set<Long> reverseLinkIds, Set<String> forwardKeys) {
        boolean changed = false;
        for (int i = 0; i < arr.size(); i++) {
            Object value = arr.get(i);
            Object newValue = processElement(value, columnId, reverseLinkIds, forwardKeys);
            if (newValue != null) {
                arr.set(i, newValue);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 递归处理单个值。
     * <p>
     * 返回值语义：
     * <ul>
     *   <li>非 null：值已被修改，这是新值（调用方需写回）</li>
     *   <li>null：值未修改（调用方保持原值）</li>
     * </ul>
     * 对于 JSONObject/JSONArray，原地修改后返回自身（通知调用方重新序列化）。
     */
    private Object processElement(Object value, Long columnId,
                                  Set<Long> reverseLinkIds, Set<String> forwardKeys) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            String str = (String) value;
            // 快速过滤：不含锚点标记的字符串直接跳过，避免不必要的 Jsoup round-trip
            if (!str.contains(ANCHOR_MARKER)) {
                return null;
            }
            String restored = restoreHtmlAnchors(str, columnId, reverseLinkIds, forwardKeys);
            return (restored != null && !restored.equals(str)) ? restored : null;
        }
        if (value instanceof JSONObject) {
            JSONObject child = (JSONObject) value;
            boolean changed = processObject(child, columnId, reverseLinkIds, forwardKeys);
            return changed ? child : null;
        }
        if (value instanceof JSONArray) {
            JSONArray child = (JSONArray) value;
            boolean changed = processArray(child, columnId, reverseLinkIds, forwardKeys);
            return changed ? child : null;
        }
        // 数字、布尔等基本类型不处理
        return null;
    }

    /**
     * 用 Jsoup 解析 HTML 字符串，替换匹配的语义锚点为纯文本。
     * <p>
     * 设置 prettyPrint(false) 避免 Jsoup 对无关 HTML 添加换行/缩进。
     *
     * @param html HTML 片段字符串
     * @return 修改后的 HTML 字符串；null 表示无锚点被替换
     */
    private String restoreHtmlAnchors(String html, Long columnId,
                                     Set<Long> reverseLinkIds, Set<String> forwardKeys) {
        Document doc = Jsoup.parse(html);
        doc.outputSettings().prettyPrint(false);
        boolean changed = false;
        for (Element anchor : doc.select(SEMANTIC_ANCHOR_SELECTOR)) {
            if (shouldRestore(anchor, columnId, reverseLinkIds, forwardKeys)) {
                String text = anchor.text();
                // R8: strip 首尾的 [ 和 ]，与前端 executeUnlink 的 replace(/^\[|\]$/g, '') 一致
                text = text.replaceAll("^\\[|\\]$", "");
                // 用 TextNode 替换锚点，保留纯文本（Jsoup 序列化时会正确转义特殊字符）
                anchor.replaceWith(new TextNode(text));
                changed = true;
            }
        }
        if (!changed) {
            return null;
        }
        // Jsoup.parse 会包装 <html><head></head><body>...</body></html>，取 body 内容
        return doc.body().html();
    }

    /**
     * 判断锚点是否应被恢复（R7 匹配谓词）。
     * <p>
     * 匹配优先级：
     * <ol>
     *   <li>REVERSE：data-link-id 非空且 ∈ reverseLinkIds</li>
     *   <li>FORWARD（新）：data-column-id 非空且 == columnId</li>
     *   <li>FORWARD（历史）：data-link-id 为空、无 data-column-id、
     *       "data-table-id:data-record-id" ∈ forwardKeys（降级匹配，log.warn 标注）</li>
     * </ol>
     */
    private boolean shouldRestore(Element anchor, Long columnId,
                                  Set<Long> reverseLinkIds, Set<String> forwardKeys) {
        String linkId = anchor.attr("data-link-id");
        String columnIdAttr = anchor.attr("data-column-id");

        // REVERSE 锚点：data-link-id 非空
        if (linkId != null && !linkId.isEmpty()) {
            try {
                Long id = Long.parseLong(linkId);
                if (reverseLinkIds != null && reverseLinkIds.contains(id)) {
                    return true;
                }
            } catch (NumberFormatException e) {
                // data-link-id 不是合法数字，不匹配 REVERSE
            }
            // REVERSE 锚点但 id 不在删除集合，不处理（属于其他列的 REVERSE 锚点）
            return false;
        }

        // FORWARD 锚点（data-link-id 为空）
        // 新锚点：data-column-id 非空且 == columnId
        if (columnIdAttr != null && !columnIdAttr.isEmpty()) {
            try {
                Long attrColumnId = Long.parseLong(columnIdAttr);
                return columnId != null && columnId.equals(attrColumnId);
            } catch (NumberFormatException e) {
                return false;
            }
        }

        // 历史锚点（无 data-column-id）：降级匹配 data-table-id:data-record-id ∈ forwardKeys
        if (forwardKeys != null && !forwardKeys.isEmpty()) {
            String tableId = anchor.attr("data-table-id");
            String recordId = anchor.attr("data-record-id");
            String key = tableId + ":" + recordId;
            if (forwardKeys.contains(key)) {
                log.warn("[CASCADE-DELETE] legacy FORWARD anchor without data-column-id, "
                        + "potential column ambiguity, key={}", key);
                return true;
            }
        }
        return false;
    }
}
