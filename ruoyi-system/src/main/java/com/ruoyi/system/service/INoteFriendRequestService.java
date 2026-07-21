package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.NoteFriendRequest;

/**
 * friendrequestService接口
 * 
 * @author liuyanghe
 * @date 2024-08-17
 */
public interface INoteFriendRequestService 
{
    /**
     * 查询friendrequest
     * 
     * @param id friendrequest主键
     * @return friendrequest
     */
    public NoteFriendRequest selectNoteFriendRequestById(Long id);

    /**
     * 查询friendrequest列表
     * 
     * @param noteFriendRequest friendrequest
     * @return friendrequest集合
     */
    public List<NoteFriendRequest> selectNoteFriendRequestList(NoteFriendRequest noteFriendRequest);

    /**
     * 新增friendrequest
     * 
     * @param noteFriendRequest friendrequest
     * @return 结果
     */
    public int insertNoteFriendRequest(NoteFriendRequest noteFriendRequest);

    /**
     * 修改friendrequest
     * 
     * @param noteFriendRequest friendrequest
     * @return 结果
     */
    public int updateNoteFriendRequest(NoteFriendRequest noteFriendRequest);

    /**
     * 批量删除friendrequest
     * 
     * @param ids 需要删除的friendrequest主键集合
     * @return 结果
     */
    public int deleteNoteFriendRequestByIds(Long[] ids);

    /**
     * 删除friendrequest信息
     * 
     * @param id friendrequest主键
     * @return 结果
     */
    public int deleteNoteFriendRequestById(Long id);


    /**
     * 拒绝好友请求
     *
     * @param noteFriendRequest friendrequest
     * @return 结果
     */
    public int refuseRequest(NoteFriendRequest noteFriendRequest);
}
