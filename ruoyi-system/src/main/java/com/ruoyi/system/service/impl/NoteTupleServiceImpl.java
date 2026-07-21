package com.ruoyi.system.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.system.mapper.NoteTupleMapper;
import com.ruoyi.system.domain.NoteTuple;
import com.ruoyi.system.service.INoteTupleService;

/**
 * 元组Service业务层处理
 * 
 * @author liuyanghe
 * @date 2023-05-07
 */
@Service
public class NoteTupleServiceImpl implements INoteTupleService 
{
    @Autowired
    private NoteTupleMapper noteTupleMapper;

    /**
     * 查询元组
     * 
     * @param id 元组主键
     * @return 元组
     */
    @Override
    public NoteTuple selectNoteTupleById(Long id)
    {
        return noteTupleMapper.selectNoteTupleById(id);
    }

    /**
     * 查询元组列表
     * 
     * @param noteTuple 元组
     * @return 元组
     */
    @Override
    public List<NoteTuple> selectNoteTupleList(NoteTuple noteTuple)
    {
        return noteTupleMapper.selectNoteTupleList(noteTuple);
    }

    /**
     * 新增元组
     * 
     * @param noteTuple 元组
     * @return 结果
     */
    @Override
    public int insertNoteTuple(NoteTuple noteTuple)
    {
        return noteTupleMapper.insertNoteTuple(noteTuple);
    }

    /**
     * 修改元组
     * 
     * @param noteTuple 元组
     * @return 结果
     */
    @Override
    public int updateNoteTuple(NoteTuple noteTuple)
    {
        return noteTupleMapper.updateNoteTuple(noteTuple);
    }

    /**
     * 批量删除元组
     * 
     * @param ids 需要删除的元组主键
     * @return 结果
     */
    @Override
    public int deleteNoteTupleByIds(Long[] ids)
    {
        return noteTupleMapper.deleteNoteTupleByIds(ids);
    }

    /**
     * 删除元组信息
     * 
     * @param id 元组主键
     * @return 结果
     */
    @Override
    public int deleteNoteTupleById(Long id)
    {
        return noteTupleMapper.deleteNoteTupleById(id);
    }
}
