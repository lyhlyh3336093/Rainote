package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.NoteFriend;
import com.ruoyi.system.domain.vo.NoteFriendVo;

/**
 * friendMapper接口
 * 
 * @author liuyanghe
 * @date 2024-08-17
 */
public interface NoteFriendMapper 
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
     * 根据用户id查询friend列表
     *
     * @param userId 用户id
     * @return friend集合
     */
    public List<NoteFriendVo> selectNoteFriendListByUserId(String userId);


    /**
     * 根据friendid查询friend列表
     *
     * @param friendId friendId
     * @return friend集合
     */
    public List<NoteFriendVo> selectNoteFriendListByFriendId(String friendId);

    /**
     * 查看黑名单
     *
     * @param noteFriend friend
     * @return friend集合
     */
    public List<NoteFriendVo> selectFriendBlockList(NoteFriend noteFriend);

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
     * @param id friend主键
     * @return 结果
     */
    public int deleteNoteFriendById(String id);

    /**
     * 删除friend
     * 
     * @param noteFriend 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteNoteFriend(NoteFriend noteFriend);

    /**
     * 拉黑好友
     *
     * @param noteFriend friend
     * @return 结果
     */
    int blockNoteFriend(NoteFriend noteFriend);
}
