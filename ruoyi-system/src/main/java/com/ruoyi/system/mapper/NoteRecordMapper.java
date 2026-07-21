package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.NoteRecord;
import com.ruoyi.system.domain.vo.NoteRecordVo;

/**
 * 记录Mapper接口
 * 
 * @author liuyanghe
 * @date 2023-04-12
 */
public interface NoteRecordMapper 
{
    /**
     * 查询记录
     * 
     * @param id 记录主键
     * @return 记录
     */
    public NoteRecord selectNoteRecordById(Long id);


    /**
     * 清空所有
     *
     */
    public int removeAll();


    /**
     * 查询记录
     *
     * @param ids 记录主键
     * @return 记录
     */
    public List<NoteRecord> selectNoteRecordByIds(String[] ids);


    /**
     * 查询记录列表
     * 
     * @param noteRecordVo 记录
     * @return 记录集合
     */
    public List<NoteRecord> selectNoteRecordList(NoteRecordVo noteRecordVo);

    /**
     * 新增记录
     * 
     * @param noteRecord 记录
     * @return 结果
     */
    public int insertNoteRecord(NoteRecord noteRecord);

    /**
     * 修改记录
     * 
     * @param noteRecord 记录
     * @return 结果
     */
    public int updateNoteRecord(NoteRecord noteRecord);

    /**
     * 删除记录
     * 
     * @param id 记录主键
     * @return 结果
     */
    public int deleteNoteRecordById(Long id);

    /**
     * 根据视图id删除记录
     *
     * @param viewId 视图主键
     * @return 结果
     */
    public int deleteNoteRecordByViewId(Long viewId);



    /**
     * 根据数据表id删除记录
     *
     * @param dwtableId 数据表数据表主键
     * @return 结果
     */
    public int deleteNoteRecordByDwtableId(Long dwtableId);


    /**
     * 批量删除记录
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteNoteRecordByIds(String[] ids);
}
