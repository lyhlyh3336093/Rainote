package com.ruoyi.system.service.impl;

import java.util.List;

import com.ruoyi.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.system.mapper.NoteFriendRequestMapper;
import com.ruoyi.system.domain.NoteFriendRequest;
import com.ruoyi.system.service.INoteFriendRequestService;

/**
 * friendrequestService业务层处理
 * 
 * @author liuyanghe
 * @date 2024-08-17
 */
@Service
public class NoteFriendRequestServiceImpl implements INoteFriendRequestService 
{
    @Autowired
    private NoteFriendRequestMapper noteFriendRequestMapper;

    /**
     * 查询friendrequest
     * 
     * @param id friendrequest主键
     * @return friendrequest
     */
    @Override
    public NoteFriendRequest selectNoteFriendRequestById(Long id)
    {
        return noteFriendRequestMapper.selectNoteFriendRequestById(id);
    }

    /**
     * 查询friendrequest列表
     * 
     * @param noteFriendRequest friendrequest
     * @return friendrequest
     */
    @Override
    public List<NoteFriendRequest> selectNoteFriendRequestList(NoteFriendRequest noteFriendRequest)
    {
        return noteFriendRequestMapper.selectNoteFriendRequestList(noteFriendRequest);
    }

    /**
     * 新增friendrequest
     * 
     * @param noteFriendRequest friendrequest
     * @return 结果
     */
    @Override
    public int insertNoteFriendRequest(NoteFriendRequest noteFriendRequest)
    {
        //补充信息
        if(noteFriendRequest.getRequestTime()==null){
            noteFriendRequest.setRequestTime(DateUtils.getNowDate());
        }

        return noteFriendRequestMapper.insertNoteFriendRequest(noteFriendRequest);
    }

    /**
     * 修改friendrequest
     * 
     * @param noteFriendRequest friendrequest
     * @return 结果
     */
    @Override
    public int updateNoteFriendRequest(NoteFriendRequest noteFriendRequest)
    {
        return noteFriendRequestMapper.updateNoteFriendRequest(noteFriendRequest);
    }

    /**
     * 批量删除friendrequest
     * 
     * @param ids 需要删除的friendrequest主键
     * @return 结果
     */
    @Override
    public int deleteNoteFriendRequestByIds(Long[] ids)
    {
        return noteFriendRequestMapper.deleteNoteFriendRequestByIds(ids);
    }

    /**
     * 删除friendrequest信息
     * 
     * @param id friendrequest主键
     * @return 结果
     */
    @Override
    public int deleteNoteFriendRequestById(Long id)
    {
        return noteFriendRequestMapper.deleteNoteFriendRequestById(id);
    }

    @Override
    public int refuseRequest(NoteFriendRequest noteFriendRequest) {
        return noteFriendRequestMapper.refuseRequest(noteFriendRequest);
    }
}
