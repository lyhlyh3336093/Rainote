package com.ruoyi.system.service.impl;

import java.util.List;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.system.domain.TableRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.system.mapper.SysViewManagementMapper;
import com.ruoyi.system.domain.SysViewManagement;
import com.ruoyi.system.service.ISysViewManagementService;

/**
 * 视图管理Service业务层处理
 * 
 * @author liuyanghe
 * @date 2025-08-21
 */
@Service
public class SysViewManagementServiceImpl implements ISysViewManagementService 
{
    @Autowired
    private SysViewManagementMapper sysViewManagementMapper;

    /**
     * 查询视图管理
     * 
     * @param id 视图管理主键
     * @return 视图管理
     */
    @Override
    public SysViewManagement selectSysViewManagementById(Long id)
    {
        return sysViewManagementMapper.selectSysViewManagementById(id);
    }

    /**
     * 查询视图管理列表
     * 
     * @param sysViewManagement 视图管理
     * @return 视图管理
     */
    @Override
    public List<SysViewManagement> selectSysViewManagementList(SysViewManagement sysViewManagement)
    {
        return sysViewManagementMapper.selectSysViewManagementList(sysViewManagement);
    }


    /**
     * 查询视图管理列表
     *
     * @param sysViewManagement 视图管理
     * @return 视图管理
     */
    @Override
    public List<SysViewManagement> selectTableList(SysViewManagement sysViewManagement)
    {

        //todo:实际上用户能够查看的表信息只有相关的业务表,这个并没有对应的固定表只能是先写死,后续有新的业务表再在这里增加
        //以后多了可以弄成配置项或者常量表,但是现在先固定的吧
        //目前的表内容有:笔记表note_note,
        return null;
    }

    /**
     * 新增视图管理
     * 
     * @param sysViewManagement 视图管理
     * @return 结果
     */
    @Override
    public int insertSysViewManagement(SysViewManagement sysViewManagement)
    {
        sysViewManagement.setCreateTime(DateUtils.getNowDate());
        return sysViewManagementMapper.insertSysViewManagement(sysViewManagement);
    }

    /**
     * 修改视图管理
     * 
     * @param sysViewManagement 视图管理
     * @return 结果
     */
    @Override
    public int updateSysViewManagement(SysViewManagement sysViewManagement)
    {
        sysViewManagement.setUpdateTime(DateUtils.getNowDate());
        return sysViewManagementMapper.updateSysViewManagement(sysViewManagement);
    }

    /**
     * 批量删除视图管理
     * 
     * @param ids 需要删除的视图管理主键
     * @return 结果
     */
    @Override
    public int deleteSysViewManagementByIds(Long[] ids)
    {
        return sysViewManagementMapper.deleteSysViewManagementByIds(ids);
    }

    /**
     * 删除视图管理信息
     * 
     * @param id 视图管理主键
     * @return 结果
     */
    @Override
    public int deleteSysViewManagementById(Long id)
    {
        return sysViewManagementMapper.deleteSysViewManagementById(id);
    }

    @Override
    public int insertTableRow(TableRow tableRow) {
        //todo:这里要琢磨一下怎么给表新增一列
        //初步设想是直接用表名+列名+列属性新增一个,但是加之前要做校验,不能有同名列数据

        return 0;
    }
}
