package com.ruoyi.system.service.impl;
import java.util.Date;
import java.util.List;

import com.ruoyi.system.domain.NoteFriendRequest;
import com.ruoyi.system.domain.vo.NoteFriendVo;
import com.ruoyi.system.mapper.NoteFriendRequestMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.system.mapper.NoteFriendMapper;
import com.ruoyi.system.domain.NoteFriend;
import com.ruoyi.system.service.INoteFriendService;

/**
 * friendService业务层处理
 * 
 * @author liuyanghe
 * @date 2024-08-17
 */
@Service
public class NoteFriendServiceImpl implements INoteFriendService 
{
    @Autowired
    private NoteFriendMapper noteFriendMapper;
    @Autowired
    private NoteFriendRequestMapper noteFriendRequestMapper;


    /**
     * 查询friend
     * 
     * @param id friend主键
     * @return friend
     */
    @Override
    public NoteFriend selectNoteFriendById(String id)
    {
        return noteFriendMapper.selectNoteFriendById(id);
    }

    /**
     * 查询friend列表
     * 
     * @param noteFriend friend
     * @return friend
     */
    @Override
    public List<NoteFriendVo> selectNoteFriendList(NoteFriend noteFriend)
    {
        List<NoteFriendVo> list = noteFriendMapper.selectNoteFriendListByUserId(noteFriend.getUserId().toString());
        for(NoteFriendVo item:list){
            //默认用户不在线，在查询是否在线之后如果在线则改为在线
            item.setIfOnline("1");
        }
        return noteFriendMapper.selectNoteFriendListByUserId(noteFriend.getUserId().toString());
    }

    /**
     * 新增friend
     * 
     * @param noteFriend friend
     * @return 结果
     */
    @Override
    public int insertNoteFriend(NoteFriend noteFriend)
    {
        //增加亿点点细节
        noteFriend.setCreatTime(new Date());
        noteFriend.setStatus("0");//0表示是好友关系


        //好友申请里面要有这个人的申请记录，如果没有那就没办法直接添加好友
        NoteFriendRequest query = new NoteFriendRequest();
        query.setRequestId(noteFriend.getFriendId());
        query.setUserId(noteFriend.getUserId());
        List<NoteFriendRequest> requests = noteFriendRequestMapper.selectNoteFriendRequestList(query);
        if(requests.size()>0){
            //为了保证用户在查询的时候能直接用userid就能查出所有好友，所以在新增好友的时候，选择双向增加发法
            //也就是在增加一条userid是自己，friendid是好友的对象同时再加一条friendid是自己，userid是好友的记录
            noteFriendMapper.insertNoteFriend(noteFriend);
            NoteFriend friendEntry = new NoteFriend();
            friendEntry.setCreatTime(new Date());
            friendEntry.setStatus("0");
            friendEntry.setFriendId(noteFriend.getUserId());
            friendEntry.setUserId(noteFriend.getFriendId());
            noteFriendMapper.insertNoteFriend(friendEntry);
            return noteFriendRequestMapper.deleteNoteFriendRequestById(requests.get(0).getId());
        }else{
            return -1;
        }
    }

    /**
     * 修改friend
     * 
     * @param noteFriend friend
     * @return 结果
     */
    @Override
    public int updateNoteFriend(NoteFriend noteFriend)
    {
        //修改之前先确认一下是否是好友关系
        List<NoteFriendVo> results = noteFriendMapper.selectNoteFriendList(noteFriend);
        if(results.size()>0){
            return noteFriendMapper.updateNoteFriend(noteFriend);
        }else{
            return -1;
        }
    }


    /**
     * 批量删除friend
     * 
     * @param noteFriend 需要删除的friend对象
     * @return 结果
     */
    @Override
    public int deleteNoteFriend(NoteFriend noteFriend)
    {
        return noteFriendMapper.deleteNoteFriend(noteFriend);
    }

    /**
     * 删除friend信息
     * 
     * @param id friend主键
     * @return 结果
     */
    @Override
    public int deleteNoteFriendById(String id)
    {
        return noteFriendMapper.deleteNoteFriendById(id);
    }

    /**
     * 拉黑好友
     *
     * @param noteFriend friend
     * @return 结果
     */
    @Override
    public int blockNoteFriend(NoteFriend noteFriend) {
        return noteFriendMapper.blockNoteFriend(noteFriend);
    }

    @Override
    public List<NoteFriendVo> selectFriendBlockList(NoteFriend noteFriend) {
        return noteFriendMapper.selectFriendBlockList(noteFriend);
    }
}
