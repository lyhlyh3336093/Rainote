package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.UserInfo;

/**
 * userInfoMapper接口
 * 
 * @author liuyanghe
 * @date 2026-01-13
 */
public interface UserInfoMapper 
{
    /**
     * 查询userInfo
     * 
     * @param id userInfo主键
     * @return userInfo
     */
    public UserInfo selectUserInfoById(Long id);

    /**
     * 查询userInfo列表
     * 
     * @param userInfo userInfo
     * @return userInfo集合
     */
    public List<UserInfo> selectUserInfoList(UserInfo userInfo);

    /**
     * 新增userInfo
     * 
     * @param userInfo userInfo
     * @return 结果
     */
    public int insertUserInfo(UserInfo userInfo);

    /**
     * 修改userInfo
     * 
     * @param userInfo userInfo
     * @return 结果
     */
    public int updateUserInfo(UserInfo userInfo);

    /**
     * 删除userInfo
     * 
     * @param id userInfo主键
     * @return 结果
     */
    public int deleteUserInfoById(Long id);

    /**
     * 批量删除userInfo
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteUserInfoByIds(String[] ids);
}
