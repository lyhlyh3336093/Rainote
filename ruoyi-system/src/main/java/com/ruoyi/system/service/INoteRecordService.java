package com.ruoyi.system.service;

import java.util.List;
import java.util.Map;

import com.ruoyi.system.domain.NoteColumn;
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
}
