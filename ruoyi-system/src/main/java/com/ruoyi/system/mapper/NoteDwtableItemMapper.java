package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.NoteDwtableItem;

/**
 * 多维表格数据表内容Mapper接口
 * 
 * @author liuyanghe
 * @date 2023-04-03
 */
public interface NoteDwtableItemMapper 
{
    /**
     * 查询多维表格数据表内容
     * 
     * @param id 多维表格数据表内容主键
     * @return 多维表格数据表内容
     */
    public NoteDwtableItem selectNoteDwtableItemById(Long id);

    /**
     * 清空所有
     *
     */
    public int removeAll();

    /**
     * 查询多维表格数据表内容列表
     * 
     * @param noteDwtableItem 多维表格数据表内容
     * @return 多维表格数据表内容集合
     */
    public List<NoteDwtableItem> selectNoteDwtableItemList(NoteDwtableItem noteDwtableItem);


    /**
     * 根据行列id查询数据
     *
     * @param noteDwtableItem 多维表格数据表内容
     * @return 多维表格数据表内容
     */
    public NoteDwtableItem selectNoteDwtableItemByRecordAndColumn(NoteDwtableItem noteDwtableItem);

    /**
     * 根据行id和双向链接列id查询数据
     *
     * @param noteDwtableItem 多维表格数据表内容
     * @return 多维表格数据表内容集合
     */
    public NoteDwtableItem selectNoteDwtableItemByRecordAndLinkColumn(NoteDwtableItem noteDwtableItem);

    /**
     * 按记录 id + 列 id 锁定查询数据（SELECT ... FOR UPDATE，Excel 导入 U5/KTD3）。
     * <p>
     * 双链对称写入的聚合读-改-写前置锁定：现有 {@link #selectNoteDwtableItemByRecordAndColumn}
     * 为快照读，无法防并发 UI 编辑丢失更新。须在导入事务内调用；
     * 行不存在时仅加间隙锁（(recordId, columnId) 无唯一约束），
     * 锁内复核行存在性，不存在则由调用方 upsert 创建。
     *
     * @param noteDwtableItem 查询条件（recordId 与 columnId 必填）
     * @return 锁定的行；不存在返回 null
     */
    public NoteDwtableItem selectNoteDwtableItemByRecordAndColumnForUpdate(NoteDwtableItem noteDwtableItem);


    /**
     * 新增多维表格数据表内容
     *
     * @param noteDwtableItem 多维表格数据表内容
     * @return 结果
     */
    public int insertNoteDwtableItem(NoteDwtableItem noteDwtableItem);

    /**
     * 批量新增多维表格数据表内容（多维表格导入，KTD5 foreach 批量写入）。
     * <p>
     * U5 扩展：列集含 link 字段（linkRecordId/linkItemId/linkColumnId，非关联 item 传 null），
     * 单向(18)/双向(21)关联列的 link 数据经批量 insert 落库不被丢弃；
     * useGeneratedKeys 回填自增 id（零缓冲不变量：对称写入的 linkItemId 须引用已落库的本表 item id）。
     *
     * @param items 数据表内容列表（dwtId/columnId/recordId/value 必填，link 字段可空）
     * @return 结果
     */
    public int insertNoteDwtableItems(List<NoteDwtableItem> items);

    /**
     * 修改多维表格数据表内容
     * 
     * @param noteDwtableItem 多维表格数据表内容
     * @return 结果
     */
    public int updateNoteDwtableItem(NoteDwtableItem noteDwtableItem);

    /**
     * 删除多维表格数据表内容
     * 
     * @param id 多维表格数据表内容主键
     * @return 结果
     */
    public int deleteNoteDwtableItemById(Long id);


    /**
     * 通过列id删除多维表格数据表内容
     *
     * @param columnId 列主键
     * @return 结果
     */
    public int deleteNoteDwtableItemByColumnId(Long columnId);

    /**
     * 查询指定列下 linkBlockId 非空的 NoteDwtableItem（FORWARD 方向锚点信息）。
     * <p>
     * 用于 type=25 列级联删除时 R6 收集 FORWARD 锚点元数据。
     * 解耦 R6 收集条件：基于 linkBlockId 存在性而非 cell value 非空，
     * 修复 value-clear 路径下 linkBlockId 保留导致 F2 遗漏 FORWARD 文本恢复的问题。
     *
     * @param columnId 列主键
     * @return linkBlockId 非空的 NoteDwtableItem 集合
     */
    public List<NoteDwtableItem> selectItemsByColumnIdWithLinkBlockId(Long columnId);


    /**
     * 根据数据表id删除多维表格数据表内容
     *
     * @param dwtId 多维表格数据表主键
     * @return 结果
     */
    public int deleteNoteDwtableItemByDwtId(Long dwtId);

    /**
     * 根据记录id删除多维表格数据表内容
     *
     * @param recordId 多维表格数据表主键
     * @return 结果
     */
    public int deleteNoteDwtableItemByRecordId(Long recordId);

    /**
     * 批量删除多维表格数据表内容
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteNoteDwtableItemByIds(Long[] ids);
}
