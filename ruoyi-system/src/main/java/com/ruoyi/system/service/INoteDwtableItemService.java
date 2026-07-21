package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.NoteDwtableItem;

/**
 * 多维表格数据表内容Service接口
 * 
 * @author liuyanghe
 * @date 2023-04-03
 */
public interface INoteDwtableItemService 
{
    /**
     * 查询多维表格数据表内容
     * 
     * @param id 多维表格数据表内容主键
     * @return 多维表格数据表内容
     */
    public NoteDwtableItem selectNoteDwtableItemById(Long id);

    /**
     * 查询多维表格数据表内容列表
     * 
     * @param noteDwtableItem 多维表格数据表内容
     * @return 多维表格数据表内容集合
     */
    public List<NoteDwtableItem> selectNoteDwtableItemList(NoteDwtableItem noteDwtableItem);

    /**
     * 新增多维表格数据表内容
     * 
     * @param noteDwtableItem 多维表格数据表内容
     * @return 结果
     */
    public int insertNoteDwtableItem(NoteDwtableItem noteDwtableItem);

    /**
     * 修改多维表格数据表内容
     * 
     * @param noteDwtableItem 多维表格数据表内容
     * @return 结果
     */
    public int updateNoteDwtableItem(NoteDwtableItem noteDwtableItem);

    /**
     * 批量删除多维表格数据表内容
     * 
     * @param ids 需要删除的多维表格数据表内容主键集合
     * @return 结果
     */
    public int deleteNoteDwtableItemByIds(Long[] ids);

    /**
     * 删除多维表格数据表内容信息
     * 
     * @param id 多维表格数据表内容主键
     * @return 结果
     */
    public int deleteNoteDwtableItemById(Long id);
}
