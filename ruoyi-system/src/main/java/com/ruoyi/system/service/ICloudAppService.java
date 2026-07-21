package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.CloudApp;

/**
 * 应用Service接口
 * 
 * @author liuyanghe
 * @date 2025-03-09
 */
public interface ICloudAppService 
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
     * 批量删除应用
     * 
     * @param ids 需要删除的应用主键集合
     * @return 结果
     */
    public int deleteCloudAppByIds(String[] ids);

    /**
     * 删除应用信息
     *
     * @param id 应用主键
     * @return 结果
     */
    public int deleteCloudAppById(Long id);

    /**
     * 删除前公共校验(存在性、默认应用保护、创建者权限)
     * 三入口(DELETE /{ids}、GET /remove/{ids}、GET /delete)统一调用
     *
     * @param ids 待删除的应用主键数组
     * @throws com.ruoyi.common.exception.ServiceException 校验失败时抛出,由全局异常处理转换为 AjaxResult.error
     */
    public void validateBeforeDelete(Long[] ids);
}
