package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.NoteFriend;
import com.ruoyi.system.domain.vo.NoteFriendVo;

/**
 * friendService接口
 * 
 * @author liuyanghe
 * @date 2024-08-17
 */
public interface INoteFriendService 
{
    /**
     * 查询friend
     * 
     * @param id friend主键
     * @return friend
     */
    public NoteFriend selectNoteFriendById(String id);

    /**
     * 查询friend列表
     * 
     * @param noteFriend friend
     * @return friend集合
     */
    public List<NoteFriendVo> selectNoteFriendList(NoteFriend noteFriend);

    /**
     * 新增friend
     * 
     * @param noteFriend friend
     * @return 结果
     */
    public int insertNoteFriend(NoteFriend noteFriend);

    /**
     * 修改friend
     * 
     * @param noteFriend friend
     * @return 结果
     */
    public int updateNoteFriend(NoteFriend noteFriend);


    /**
     * 删除friend
     * 
     * @param noteFriend 需要删除的friend对象
     * @return 结果
     */
    public int deleteNoteFriend(NoteFriend noteFriend);

    /**
     * 删除friend信息
     * 
     * @param id friend主键
     * @return 结果
     */
    public int deleteNoteFriendById(String id);


    /**
     * 拉黑好友
     *
     * @param noteFriend friend
     * @return 结果
     */
    public int blockNoteFriend(NoteFriend noteFriend);

    /**
     * 查看黑名单
     *
     * @param noteFriend friend
     * @return 结果
     */
    List<NoteFriendVo> selectFriendBlockList(NoteFriend noteFriend);
}
