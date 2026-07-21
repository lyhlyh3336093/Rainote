package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.CloudApp;

/**
 * 应用Mapper接口
 * 
 * @author liuyanghe
 * @date 2025-03-09
 */
public interface CloudAppMapper 
{
    /**
     * 查询应用
     * 
     * @param id 应用主键
     * @return 应用
     */
    public CloudApp selectCloudAppById(Long id);

    /**
     * 查询应用列表
     * 
     * @param cloudApp 应用
     * @return 应用集合
     */
    public List<CloudApp> selectCloudAppList(CloudApp cloudApp);

    /**
     * 新增应用
     * 
     * @param cloudApp 应用
     * @return 结果
     */
    public int insertCloudApp(CloudApp cloudApp);

    /**
     * 修改应用
     * 
     * @param cloudApp 应用
     * @return 结果
     */
    public int updateCloudApp(CloudApp cloudApp);

    /**
     * 删除应用
     * 
     * @param id 应用主键
     * @return 结果
     */
    public int deleteCloudAppById(Long id);

    /**
     * 批量删除应用
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteCloudAppByIds(String[] ids);
}
