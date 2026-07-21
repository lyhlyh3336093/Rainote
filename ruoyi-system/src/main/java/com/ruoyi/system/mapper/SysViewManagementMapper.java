package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.SysViewManagement;

/**
 * 视图管理Mapper接口
 * 
 * @author liuyanghe
 * @date 2025-08-21
 */
public interface SysViewManagementMapper 
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
     * 删除视图管理
     * 
     * @param id 视图管理主键
     * @return 结果
     */
    public int deleteSysViewManagementById(Long id);

    /**
     * 批量删除视图管理
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteSysViewManagementByIds(Long[] ids);


}
