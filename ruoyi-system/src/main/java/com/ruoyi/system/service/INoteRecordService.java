package com.ruoyi.system.service;

import java.util.List;
import java.util.Map;

import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.NoteDwtableItem;
import com.ruoyi.system.domain.NoteRecord;
import com.ruoyi.system.domain.vo.NoteRecordVo;

/**
 * 记录Service接口
 * 
 * @author liuyanghe
 * @date 2023-04-12
 */
public interface INoteRecordService 
{
    /**
     * 查询记录
     * 
     * @param id 记录主键
     * @return 记录
     */
    public NoteRecord selectNoteRecordById(Long id);

    /**
     * 查询记录列表
     * 
     * @param noteRecord 记录
     * @return 记录集合
     */
    public List<NoteRecord> selectNoteRecordList(NoteRecordVo noteRecord);

    /**
     * 新增记录
     * 
     * @param noteRecord 记录
     * @return 结果
     */
    public int insertNoteRecord(NoteRecord noteRecord,List<Map<String, Object>> items);

    /**
     * 修改记录
     * 
     * @param noteRecord 记录
     * @return 结果
     */
    public int updateNoteRecord(NoteRecord noteRecord,List<Map<String, Object>> items);

    /**
     * 修改记录排序
     *
     * @param noteRecord 记录
     * @return 结果
     */
    public int updateSort(NoteRecord noteRecord,List<Map<String, Object>> sorts);


    /**
     * 批量删除记录
     * 
     * @param ids 需要删除的记录主键集合
     * @return 结果
     */
    public int deleteNoteRecordByIds(String[] ids);

    /**
     * 删除记录信息
     * 
     * @param id 记录主键
     * @return 结果
     */
    public int deleteNoteRecordById(Long id);

    /**
     * 查询记录内的表格数据
     *
     * @param id 记录主键
     * @return 记录
     */
    Map<String,Object> selectNoteRecordData(Long id);

    /**
     * 查询记录内的数据列表
     *
     * @param noteRecordVo 记录
     * @return 记录数据集合
     */
    List<Map<String, Object>> selectNoteRecordDataList(NoteRecordVo noteRecordVo);


    /**
     * 根据数据表id查询记录内的数据列表
     *
     * @param dwtableId 记录
     * @return 记录数据集合
     */
    List<Map<String, Object>> selectDataListByDwtableId(Long dwtableId);

    /**
     * 全量重算指定lookup列(type=26)所有记录的存储值。
     * 在updateNoteColumn检测到dedupe开关变化时触发，遍历该列所属表的所有记录，
     * 重新调用resolveLookupValue并更新对应NoteDwtableItem的value。
     * 单条记录失败不中断整体流程，记录错误日志后继续。
     *
     * @param lookupColumn 需要重算的lookup列
     */
    void recomputeLookupColumnValues(NoteColumn lookupColumn);

    /**
     * 全量重算引用了指定lookup列(type=26)的所有集合运算列。
     * 遍历表中所有集合运算列，筛选出A列或B列引用了该lookup列的，
     * 对每条记录从DB重新读取A/B数据并执行集合运算，更新结果item。
     * 单条记录失败不中断整体流程，记录错误日志后继续。
     *
     * @param lookupColumn dedupe开关已切换的lookup列
     */
    void recomputeSetOperationsForLookup(NoteColumn lookupColumn);

    /**
     * 派生记录名称：取该表最左侧 type=1（多行文本）列的值。
     * 取值优先级（KTD-3）：先 incomingItems（本次写入新值），再 existingItems（DB 当前值），仍无则返回 ""。
     * 无 type=1 列时返回 ""（R6）。
     *
     * @param dwtableId      数据表ID（必传，由调用方负责按 KTD-2 解析）
     * @param incomingItems  本次写入的 items（Map 列表，可为 null）
     * @param existingItems  DB 当前 items（可为 null）
     * @return 派生的行名称；无 type=1 列或值为空则返回 ""
     */
    String deriveRecordName(Long dwtableId,
                            List<Map<String, Object>> incomingItems,
                            List<NoteDwtableItem> existingItems);

    /**
     * 按表重算所有记录的 name 字段（KTD-6 无条件重算，幂等）。
     * 对该表每条记录调用 deriveRecordName 重算 name，updateNoteRecord 落盘。
     * 单条记录失败不中断整体流程，记录错误日志后继续。
     *
     * @param dwtableId 数据表ID
     * @return 成功重算的记录数
     */
    int recomputeRecordNamesForTable(Long dwtableId);

    /**
     * 一次性回填所有数据表所有记录的 name（R5）。
     * 遍历全部数据表，逐表调用 recomputeRecordNamesForTable（每表各自 @Transactional，KTD-5）。
     * 单表失败不中断整体流程，记录错误日志后继续。
     *
     * @return 所有表累计成功重算的记录数
     */
    int recomputeAllRecordNames();
}
