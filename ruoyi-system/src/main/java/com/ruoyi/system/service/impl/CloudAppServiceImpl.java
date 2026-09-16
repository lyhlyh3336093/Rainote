package com.ruoyi.system.service.impl;

import java.util.ArrayList;
import java.util.List;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.system.mapper.CloudAppMapper;
import com.ruoyi.system.domain.CloudApp;
import com.ruoyi.system.service.ICloudAppService;

/**
 * 应用Service业务层处理
 * 
 * @author liuyanghe
 * @date 2025-03-09
 */
@Service
public class CloudAppServiceImpl implements ICloudAppService 
{
    @Autowired
    private CloudAppMapper cloudAppMapper;

    /**
     * 查询应用
     * 
     * @param id 应用主键
     * @return 应用
     */
    @Override
    public CloudApp selectCloudAppById(Long id)
    {
        return cloudAppMapper.selectCloudAppById(id);
    }

    /**
     * 查询应用列表
     * 
     * @param cloudApp 应用
     * @return 应用
     */
    @Override
    public List<CloudApp> selectCloudAppList(CloudApp cloudApp)
    {
        //列表分两部分，默认的应用列表和个性化应用列表（包括我创建的，我收藏的等）
        List<CloudApp> unionList  = new ArrayList<CloudApp>();
        CloudApp defaultCloudApp = new CloudApp();
        defaultCloudApp.setCreater("1");
        List<CloudApp> defaultList =  cloudAppMapper.selectCloudAppList(defaultCloudApp);
        unionList.addAll(defaultList);
//        unionList.addAll(cloudAppMapper.selectCloudAppList(cloudApp));
        return unionList;
    }

    /**
     * 新增应用
     * 
     * @param cloudApp 应用
     * @return 结果
     */
    @Override
    public int insertCloudApp(CloudApp cloudApp)
    {
        cloudApp.setCreateTime(DateUtils.getNowDate());
        return cloudAppMapper.insertCloudApp(cloudApp);
    }

    /**
     * 修改应用
     * 
     * @param cloudApp 应用
     * @return 结果
     */
    @Override
    public int updateCloudApp(CloudApp cloudApp)
    {
        return cloudAppMapper.updateCloudApp(cloudApp);
    }

    /**
     * 批量删除应用
     * 
     * @param ids 需要删除的应用主键
     * @return 结果
     */
    @Override
    public int deleteCloudAppByIds(String[] ids)
    {
        return cloudAppMapper.deleteCloudAppByIds(ids);
    }

    /**
     * 删除应用信息
     *
     * @param id 应用主键
     * @return 结果
     */
    @Override
    public int deleteCloudAppById(Long id)
    {
        return cloudAppMapper.deleteCloudAppById(id);
    }

    /**
     * 删除前公共校验:存在性(R4)、默认应用仅管理员可删(R5)
     * 任一 id 校验失败即整体中止(R7,由异常机制天然保证)
     *
     * 注意:R6 创建者权限校验已放开,所有拥有 system:app:remove 权限的用户
     * 均可删除普通应用;系统默认应用(creater="1")仍仅管理员可删(R5)
     *
     * @param ids 待删除的应用主键数组
     * @throws ServiceException 校验失败时抛出
     */
    @Override
    public void validateBeforeDelete(Long[] ids)
    {
        Long currentUserId = SecurityUtils.getUserId();
        boolean isAdmin = SecurityUtils.isAdmin(currentUserId);

        for (Long id : ids)
        {
            CloudApp app = cloudAppMapper.selectCloudAppById(id);
            // R4: 存在性校验(selectCloudAppById 已过滤 delFlag=1,返回 null 即不存在或已删除)
            if (app == null)
            {
                throw new ServiceException("应用不存在或已删除");
            }
            // R5: 默认应用仅管理员可删(creater="1" 视为系统默认应用)
            if ("1".equals(app.getCreater()) && !isAdmin)
            {
                throw new ServiceException("系统默认应用仅管理员可删除");
            }
            // R6 已放开:普通应用删除不再校验创建者,所有有权限用户均可删除
        }
    }
}
