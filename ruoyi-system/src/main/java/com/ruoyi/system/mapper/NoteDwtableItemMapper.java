package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
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
     * 按主键锁定查询单行数据（SELECT ... FOR UPDATE，Excel 导入 U5 入链重指向的写前锁读）。
     * <p>
     * {@link #updateLinkFieldsById} 前重读现值再合并落库（对齐
     * {@link #selectNoteDwtableItemByRecordAndColumnForUpdate} 的锁读先例）：入链捕获
     * （全删前快照）与重指向落库之间他表行可能被并发 UI 编辑，锁读取最新已提交值合并，
     * 防丢失更新。须在导入事务内调用；行已被级联删除/并发删除时返回 null，调用方自然 no-op。
     *
     * @param id 行主键
     * @return 锁定的行；不存在返回 null
     */
    public NoteDwtableItem selectNoteDwtableItemByIdForUpdate(Long id);


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

    /**
     * 清空指定列全部 items 的关联字段（linkRecordId/linkItemId/value 置空串，linkColumnId 保留）。
     * <p>
     * 列级联删除场景用（删列删配对列链路）。替换式 Excel 导入的跨表清理已由
     * 入链重指向（U5，R11-R13）取代整列清空，本方法不再用于导入路径。
     *
     * @param columnId 列主键
     * @return 受影响行数
     */
    public int clearLinkFieldsByColumnId(Long columnId);

    /**
     * 按表 id 集合全量反查 linkRecordId 非空的 items（U5/R11 入链重指向的捕获查询）。
     * <p>
     * 替换式导入全删本表前的快照读：他表指向本表旧记录的入链按 linkRecordId 数据面
     * 反查（不复用列扫描——18 列 property.table_id / 21 配对列 back_field_id 残缺的
     * 入链同样捕获，防空配漏捕）。CSV 成员级"含某旧记录 id"的精确过滤由 Java 侧
     * 拆串比对完成（查询侧同样禁 LIKE 子串匹配——id 3 会误中 13）。
     *
     * @param dwtIds 表 id 集合（调用方保证非空，通常为同笔记下本表之外的全部表）
     * @return linkRecordId 非空的 items 集合
     */
    public List<NoteDwtableItem> selectLinkItemsByDwtIds(@Param("dwtIds") List<Long> dwtIds);

    /**
     * 按主键单行更新关联三字段（U5/R11 入链重指向的唯一写路径）。
     * <p>
     * 仅 SET linkRecordId/linkItemId/value（linkColumnId 等其余字段不动；空串语义对齐
     * {@link #clearLinkFieldsByColumnId} 的字段口径）。读-改-写合并（拆 CSV 串→逐 id
     * 映射→Set 去重→join）全部在 Java 侧完成后单行落库——禁止集合式 SQL REPLACE
     * （逗号串子串误替换，如旧 id 3 被 REPLACE 误中 13）。
     *
     * @param id           行主键
     * @param linkRecordId 重写后的 linkRecordId（join 串，可为空串）
     * @param linkItemId   重写后的 linkItemId（join 串，可为空串）
     * @param value        重写后的 value（join 串，可为空串）
     * @return 受影响行数（行已被级联删除时为 0，自然 no-op）
     */
    public int updateLinkFieldsById(@Param("id") Long id, @Param("linkRecordId") String linkRecordId,
            @Param("linkItemId") String linkItemId, @Param("value") String value);
}
