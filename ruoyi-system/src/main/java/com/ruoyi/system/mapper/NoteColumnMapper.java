package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.NoteColumn;

/**
 * 列信息Mapper接口
 * 
 * @author liuyanghe
 * @date 2023-04-03
 */
public interface NoteColumnMapper 
{
    /**
     * 查询列信息
     *
     * @param id 列信息主键
     * @return 列信息
     */
    public NoteColumn selectNoteColumnById(Long id);

    /**
     * 按主键锁定查询列信息（SELECT ... FOR UPDATE，Excel 导入 U5）。
     * <p>
     * 单选/多选未知选项追加的读-改-写前置锁定：须在导入事务内调用，
     * 防并发 UI 编辑列属性（select 逗号字符串）造成丢失更新。
     *
     * @param id 列信息主键
     * @return 锁定的列信息；不存在返回 null
     */
    public NoteColumn selectNoteColumnByIdForUpdate(Long id);

    /**
     * 清空所有
     *
     */
    public int removeAll();

    /**
     * 查询列信息列表
     * 
     * @param noteColumn 列信息
     * @return 列信息集合
     */
    public List<NoteColumn> selectNoteColumnList(NoteColumn noteColumn);


    /**
     * 根据数据表id查询数据表下的所有双向链接列
     *
     * @param tableId 数据表id
     * @return 列信息集合
     */
    public List<NoteColumn> selectNoteDoubleLinkColumnList(Long tableId);


    /**
     * 查询某数据表中有多少数学公式类型的列
     *
     * @param dwtableId 数据表id
     * @return 列信息集合
     */
    public List<NoteColumn> selectCalcColumnByDwtId(Long dwtableId);

    /**
     * 查询某数据表中有多少集合运算类型的列
     *
     * @param dwtableId 数据表id
     * @return 列信息集合
     */
    public List<NoteColumn> selectSetColumnByDwtId(Long dwtableId);


    /**
     * 新增列信息
     * 
     * @param noteColumn 列信息
     * @return 结果
     */
    public int insertNoteColumn(NoteColumn noteColumn);

    /**
     * 修改列信息
     * 
     * @param noteColumn 列信息
     * @return 结果
     */
    public int updateNoteColumn(NoteColumn noteColumn);

    /**
     * 删除列信息
     * 
     * @param id 列信息主键
     * @return 结果
     */
    public int deleteNoteColumnById(Long id);

    /**
     * 根据数据库表id删除列信息
     *
     * @param dwtableId 数据表主键
     * @return 结果
     */
    public int deleteNoteColumnByDwtableId(Long dwtableId);

    /**
     * 批量删除列信息
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteNoteColumnByIds(String[] ids);


    /**
     * 批量查找列信息
     *
     * @param ids 需要查找的数据主键集合
     * @return 结果
     */
    public List<NoteColumn> selectNoteColumnByIds(String[] ids);
}
