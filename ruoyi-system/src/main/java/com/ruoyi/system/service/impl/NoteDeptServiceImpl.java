package com.ruoyi.system.service.impl;

import java.util.List;
import com.ruoyi.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.system.mapper.NoteDeptMapper;
import com.ruoyi.system.domain.NoteDept;
import com.ruoyi.system.service.INoteDeptService;

/**
 * 笔记部门Service业务层处理
 * 
 * @author liuyanghe
 * @date 2023-09-15
 */
@Service
public class NoteDeptServiceImpl implements INoteDeptService 
{
    @Autowired
    private NoteDeptMapper noteDeptMapper;

    /**
     * 查询笔记部门
     * 
     * @param deptId 笔记部门主键
     * @return 笔记部门
     */
    @Override
    public NoteDept selectNoteDeptByDeptId(Long deptId)
    {
        return noteDeptMapper.selectNoteDeptByDeptId(deptId);
    }

    /**
     * 查询笔记部门列表
     * 
     * @param noteDept 笔记部门
     * @return 笔记部门
     */
    @Override
    public List<NoteDept> selectNoteDeptList(NoteDept noteDept)
    {
        return noteDeptMapper.selectNoteDeptList(noteDept);
    }

    /**
     * 新增笔记部门
     * 
     * @param noteDept 笔记部门
     * @return 结果
     */
    @Override
    public int insertNoteDept(NoteDept noteDept)
    {
        noteDept.setCreateTime(DateUtils.getNowDate());
        return noteDeptMapper.insertNoteDept(noteDept);
    }

    /**
     * 修改笔记部门
     * 
     * @param noteDept 笔记部门
     * @return 结果
     */
    @Override
    public int updateNoteDept(NoteDept noteDept)
    {
        noteDept.setUpdateTime(DateUtils.getNowDate());
        return noteDeptMapper.updateNoteDept(noteDept);
    }

    /**
     * 批量删除笔记部门
     * 
     * @param deptIds 需要删除的笔记部门主键
     * @return 结果
     */
    @Override
    public int deleteNoteDeptByDeptIds(Long[] deptIds)
    {
        return noteDeptMapper.deleteNoteDeptByDeptIds(deptIds);
    }

    /**
     * 删除笔记部门信息
     * 
     * @param deptId 笔记部门主键
     * @return 结果
     */
    @Override
    public int deleteNoteDeptByDeptId(Long deptId)
    {
        return noteDeptMapper.deleteNoteDeptByDeptId(deptId);
    }
}
