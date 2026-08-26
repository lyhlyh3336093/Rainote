package com.ruoyi.system.service;

import java.util.List;

import com.ruoyi.system.domain.dto.ParsedInsert;

/**
 * 多维表格导入服务接口。
 * <p>
 * 接收 SQL 解析产物列表，在单事务内批量写入目标表的
 * {@code note_record} + {@code note_dwtable_item}（R9-R19）。
 *
 * @author ruoyi
 */
public interface INoteDwtableImportService
{
    /**
     * 导入解析后的 INSERT 数据到目标多维表格。
     * <p>
     * 列名区分大小写宽松匹配（KTD4）；排除类型列跳过（R12）；
     * NULL 转空串（R15）；link 字段不导入（R16）；
     * sort = 当前最大 sort + 1 递增（KTD2/F2）；
     * 任一步骤失败整体回滚（R19）。
     *
     * @param noteId     目标笔记 id（调用端已校验与 dwtableId 匹配）
     * @param dwtableId  目标多维表格 id
     * @param parsedList SQL 解析产物列表（保持 SQL 语句顺序）
     * @param userId     操作用户 id（仅日志审计）
     * @return 成功导入的记录数
     */
    int importData(Long noteId, Long dwtableId, List<ParsedInsert> parsedList, Long userId);
}
