package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.NoteTuple;

/**
 * 元组Service接口
 * 
 * @author liuyanghe
 * @date 2023-05-07
 */
public interface INoteTupleService 
{
    /**
     * 查询元组
     * 
     * @param id 元组主键
     * @return 元组
     */
    public NoteTuple selectNoteTupleById(Long id);

    /**
     * 查询元组列表
     * 
     * @param noteTuple 元组
     * @return 元组集合
     */
    public List<NoteTuple> selectNoteTupleList(NoteTuple noteTuple);

    /**
     * 新增元组
     * 
     * @param noteTuple 元组
     * @return 结果
     */
    public int insertNoteTuple(NoteTuple noteTuple);

    /**
     * 修改元组
     * 
     * @param noteTuple 元组
     * @return 结果
     */
    public int updateNoteTuple(NoteTuple noteTuple);

    /**
     * 批量删除元组
     * 
     * @param ids 需要删除的元组主键集合
     * @return 结果
     */
    public int deleteNoteTupleByIds(Long[] ids);

    /**
     * 删除元组信息
     * 
     * @param id 元组主键
     * @return 结果
     */
    public int deleteNoteTupleById(Long id);
}
