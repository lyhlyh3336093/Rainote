package com.ruoyi.system.service;

import java.util.List;
import java.util.Map;

import com.ruoyi.system.domain.NoteDwtable;

/**
 * 多维表格数据表Service接口
 * 
 * @author liuyanghe
 * @date 2023-04-03
 */
public interface INoteDwtableService 
{
    /**
     * 查询多维表格数据表
     * 
     * @param id 多维表格数据表主键
     * @return 多维表格数据表
     */
    public NoteDwtable selectNoteDwtableById(Long id);


    /**
     * 获取多维表格数据表表内数据
     *
     * @param id 多维表格数据表主键
     * @return 多维表格数据表
     */
    public Map<String,Object> selectNoteDwtableDataById(Long id);

    /**
     * 查询多维表格数据表列表
     * 
     * @param noteDwtable 多维表格数据表
     * @return 多维表格数据表集合
     */
    public List<NoteDwtable> selectNoteDwtableList(NoteDwtable noteDwtable);

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
     * 批量删除多维表格数据表
     * 
     * @param ids 需要删除的多维表格数据表主键集合
     * @return 结果
     */
    public int deleteNoteDwtableByIds(String[] ids);

    /**
     * 删除多维表格数据表信息
     * 
     * @param id 多维表格数据表主键
     * @return 结果
     */
    public int deleteNoteDwtableById(Long id);
}
