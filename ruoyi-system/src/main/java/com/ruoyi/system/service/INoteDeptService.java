package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.NoteDept;

/**
 * 笔记部门Service接口
 * 
 * @author liuyanghe
 * @date 2023-09-15
 */
public interface INoteDeptService 
{
    /**
     * 查询笔记部门
     * 
     * @param deptId 笔记部门主键
     * @return 笔记部门
     */
    public NoteDept selectNoteDeptByDeptId(Long deptId);

    /**
     * 查询笔记部门列表
     * 
     * @param noteDept 笔记部门
     * @return 笔记部门集合
     */
    public List<NoteDept> selectNoteDeptList(NoteDept noteDept);

    /**
     * 新增笔记部门
     * 
     * @param noteDept 笔记部门
     * @return 结果
     */
    public int insertNoteDept(NoteDept noteDept);

    /**
     * 修改笔记部门
     * 
     * @param noteDept 笔记部门
     * @return 结果
     */
    public int updateNoteDept(NoteDept noteDept);

    /**
     * 批量删除笔记部门
     * 
     * @param deptIds 需要删除的笔记部门主键集合
     * @return 结果
     */
    public int deleteNoteDeptByDeptIds(Long[] deptIds);

    /**
     * 删除笔记部门信息
     * 
     * @param deptId 笔记部门主键
     * @return 结果
     */
    public int deleteNoteDeptByDeptId(Long deptId);
}
