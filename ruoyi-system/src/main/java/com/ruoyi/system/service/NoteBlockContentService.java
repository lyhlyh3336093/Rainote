package com.ruoyi.system.service;

import java.util.Set;

/**
 * NoteBlock 内容解析与语义锚点文本恢复服务。
 * <p>
 * 用于 type=25 列级联删除时，重写受影响 NoteBlock 的 property 内容，
 * 将匹配的 {@code <a data-type="semantic">} 锚点替换为纯文本。
 * <p>
 * 解析策略：递归遍历 property JSON 的所有字符串值（覆盖 data.text、
 * data.items[]、data.items[].text、data.content[][] 等字段路径），
 * 仅对含 {@code data-type="semantic"} 的字符串做 Jsoup 解析与替换，
 * 避免对无关字符串的 round-trip 损坏。
 */
public interface NoteBlockContentService {

    /**
     * 恢复 NoteBlock 内容中的语义锚点文本。
     * <p>
     * 匹配谓词（R7）：
     * <ul>
     *   <li>REVERSE 锚点：data-link-id 非空且 ∈ reverseLinkIds</li>
     *   <li>FORWARD 锚点（新，有 data-column-id）：data-column-id == columnId</li>
     *   <li>FORWARD 锚点（历史，无 data-column-id）：data-link-id 为空 且
     *       "data-table-id:data-record-id" ∈ forwardKeys（降级匹配，log.warn 标注歧义）</li>
     * </ul>
     * 文本恢复（R8）：strip 锚点 innerText 首尾的 [ 和 ]（正则 {@code ^\[|\]$}）。
     *
     * @param propertyJson  NoteBlock.property 的 JSON 字符串
     * @param columnId      被删列的 id（用于 FORWARD 锚点匹配 data-column-id）
     * @param reverseLinkIds 已删除的 NoteNotelink id 集合（用于 REVERSE 锚点匹配 data-link-id）
     * @param forwardKeys   FORWARD 锚点降级匹配键集合，格式 "tableId:recordId"
     *                       （来自 NoteDwtableItem 的 dwtId + ":" + recordId）
     * @return 更新后的 property JSON 字符串；若无匹配锚点返回原字符串；
     *         propertyJson 为 null/空时原样返回；
     *         JSON 解析失败时抛出 IllegalArgumentException（由上层 per-block catch 处理）
     */
    String restoreAnchors(String propertyJson, Long columnId,
                          Set<Long> reverseLinkIds, Set<String> forwardKeys);
}
