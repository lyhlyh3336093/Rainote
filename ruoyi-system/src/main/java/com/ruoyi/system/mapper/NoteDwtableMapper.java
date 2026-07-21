package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.NoteDwtable;

/**
 * 多维表格数据表Mapper接口
 * 
 * @author liuyanghe
 * @date 2023-04-03
 */
public interface NoteDwtableMapper 
{
    /**
     * 查询多维表格数据表
     * 
     * @param id 多维表格数据表主键
     * @return 多维表格数据表
     */
    public NoteDwtable selectNoteDwtableById(Long id);

    /**
     * 查询多维表格数据表列表
     * 
     * @param noteDwtable 多维表格数据表
     * @return 多维表格数据表集合
     */
    public List<NoteDwtable> selectNoteDwtableList(NoteDwtable noteDwtable);

    /**
     * 查询多维表格数据表列表
     *
     * @param id 多维表格id
     * @return 多维表格数据表集合
     */
    public List<NoteDwtable> selectDWTableList(Long id);

    /**
     * 清空所有
     *
     */
    public int removeAll();


    /**
     * 新增多维表格数据表
     * 
     * @param noteDwtable 多维表格数据表
     * @return 结果
     */
    public int insertNoteDwtable(NoteDwtable noteDwtable);

    /**
     * 修改多维表格数据表
     * 
     * @param noteDwtable 多维表格数据表
     * @return 结果
     */
    public int updateNoteDwtable(NoteDwtable noteDwtable);

    /**
     * 删除多维表格数据表
     * 
     * @param id 多维表格数据表主键
     * @return 结果
     */
    public int deleteNoteDwtableById(Long id);


    /**
     * 根据笔记id删除多维表格数据表
     *
     * @param id 笔记id
     * @return 结果
     */
    public int deleteNoteDwtableByNoteId(Long id);


    /**
     * 根据笔记id恢复多维表格数据表
     *
     * @param id 笔记id
     * @return 结果
     */
    public int recoverNoteDwtableByNoteId(Long id);

    /**
     * 批量删除多维表格数据表
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteNoteDwtableByIds(String[] ids);
}
