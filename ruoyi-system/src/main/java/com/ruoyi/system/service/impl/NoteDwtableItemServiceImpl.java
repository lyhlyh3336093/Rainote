package com.ruoyi.system.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.system.mapper.NoteDwtableItemMapper;
import com.ruoyi.system.domain.NoteDwtableItem;
import com.ruoyi.system.service.INoteDwtableItemService;

/**
 * 多维表格数据表内容Service业务层处理
 * 
 * @author liuyanghe
 * @date 2023-04-03
 */
@Service
public class NoteDwtableItemServiceImpl implements INoteDwtableItemService 
{
    @Autowired
    private NoteDwtableItemMapper noteDwtableItemMapper;

    /**
     * 查询多维表格数据表内容
     * 
     * @param id 多维表格数据表内容主键
     * @return 多维表格数据表内容
     */
    @Override
    public NoteDwtableItem selectNoteDwtableItemById(Long id)
    {
        return noteDwtableItemMapper.selectNoteDwtableItemById(id);
    }

    /**
     * 查询多维表格数据表内容列表
     * 
     * @param noteDwtableItem 多维表格数据表内容
     * @return 多维表格数据表内容
     */
    @Override
    public List<NoteDwtableItem> selectNoteDwtableItemList(NoteDwtableItem noteDwtableItem)
    {
        return noteDwtableItemMapper.selectNoteDwtableItemList(noteDwtableItem);
    }

    /**
     * 新增多维表格数据表内容
     * 
     * @param noteDwtableItem 多维表格数据表内容
     * @return 结果
     */
    @Override
    public int insertNoteDwtableItem(NoteDwtableItem noteDwtableItem)
    {
        return noteDwtableItemMapper.insertNoteDwtableItem(noteDwtableItem);
    }

    /**
     * 修改多维表格数据表内容
     * 
     * @param noteDwtableItem 多维表格数据表内容
     * @return 结果
     */
    @Override
    public int updateNoteDwtableItem(NoteDwtableItem noteDwtableItem)
    {
        if (noteDwtableItem == null || noteDwtableItem.getId() == null)
        {
            return 0;
        }
        return noteDwtableItemMapper.updateNoteDwtableItem(noteDwtableItem);
    }

    /**
     * 批量删除多维表格数据表内容
     * 
     * @param ids 需要删除的多维表格数据表内容主键
     * @return 结果
     */
    @Override
    public int deleteNoteDwtableItemByIds(Long[] ids)
    {
        return noteDwtableItemMapper.deleteNoteDwtableItemByIds(ids);
    }

    /**
     * 删除多维表格数据表内容信息
     * 
     * @param id 多维表格数据表内容主键
     * @return 结果
     */
    @Override
    public int deleteNoteDwtableItemById(Long id)
    {
        return noteDwtableItemMapper.deleteNoteDwtableItemById(id);
    }
}
