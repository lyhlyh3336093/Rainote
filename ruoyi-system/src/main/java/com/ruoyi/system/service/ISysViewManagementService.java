package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.SysViewManagement;
import com.ruoyi.system.domain.TableRow;

/**
 * 视图管理Service接口
 * 
 * @author liuyanghe
 * @date 2025-08-21
 */
public interface ISysViewManagementService 
{
    /**
     * 查询视图管理
     * 
     * @param id 视图管理主键
     * @return 视图管理
     */
    public SysViewManagement selectSysViewManagementById(Long id);

    /**
     * 查询视图管理列表
     * 
     * @param sysViewManagement 视图管理
     * @return 视图管理集合
     */
    public List<SysViewManagement> selectSysViewManagementList(SysViewManagement sysViewManagement);

    /**
     * 查询业务表列表
     *
     * @param sysViewManagement 视图管理
     * @return 业务表信息集合
     */
    List<SysViewManagement> selectTableList(SysViewManagement sysViewManagement);
    /**
     * 新增视图管理
     * 
     * @param sysViewManagement 视图管理
     * @return 结果
     */
    public int insertSysViewManagement(SysViewManagement sysViewManagement);

    /**
     * 修改视图管理
     * 
     * @param sysViewManagement 视图管理
     * @return 结果
     */
    public int updateSysViewManagement(SysViewManagement sysViewManagement);

    /**
     * 批量删除视图管理
     * 
     * @param ids 需要删除的视图管理主键集合
     * @return 结果
     */
    public int deleteSysViewManagementByIds(Long[] ids);

    /**
     * 删除视图管理信息
     * 
     * @param id 视图管理主键
     * @return 结果
     */
    public int deleteSysViewManagementById(Long id);

    /**
     * 向指定数据库表添加列数据
     *
     * @param tableRow 列属性数据
     * @return 结果
     */
    public int insertTableRow(TableRow tableRow);
}
