package com.ruoyi.web.controller.system;


import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;

import javax.servlet.http.HttpServletResponse;



import com.ruoyi.system.domain.vo.NoteFriendVo;

import com.ruoyi.system.service.WebSocketServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.domain.NoteFriend;
import com.ruoyi.system.service.INoteFriendService;
import com.ruoyi.common.utils.poi.ExcelUtil;



/**
 * friendController
 * 
 * @author liuyanghe
 * @date 2024-08-17
 */
@RestController
@RequestMapping("/system/friend")
public class NoteFriendController extends BaseController
{
    @Autowired
    private INoteFriendService noteFriendService;


    /**
     * 查询friend列表
     */
//    @PreAuthorize("@ss.hasPermi('system:friend:list')")
    @GetMapping("/list")
    public AjaxResult list(NoteFriend noteFriend)
    {

        List<NoteFriendVo> list = noteFriendService.selectNoteFriendList(noteFriend);
        //查询好友是否在线
        CopyOnWriteArraySet<WebSocketServer> webSocketSet = WebSocketServer.getWebSocketSet();
        List<String> onlineIds =new ArrayList<String>();
        for (WebSocketServer item : webSocketSet) {
            onlineIds.add(item.getSid());
            for(NoteFriendVo noteFriendVo:list){
                if(noteFriendVo.getFriendId().equals(item.getSid())){
                    noteFriendVo.setIfOnline("0");
                }
            }
        }
        return success(list);
    }

    /**
     * 查询黑名单
     */
//    @PreAuthorize("@ss.hasPermi('system:friend:list')")
    @GetMapping("/selectFriendBlockList")
    public AjaxResult selectFriendBlockList(NoteFriend noteFriend)
    {
        List<NoteFriendVo> list = noteFriendService.selectFriendBlockList(noteFriend);
        return success(list);
    }


    /**
     * 导出friend列表
     */
//    @PreAuthorize("@ss.hasPermi('system:friend:export')")
    @Log(title = "friend", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, NoteFriend noteFriend)
    {
        List<NoteFriendVo> list = noteFriendService.selectNoteFriendList(noteFriend);
        ExcelUtil<NoteFriendVo> util = new ExcelUtil<NoteFriendVo>(NoteFriendVo.class);
        util.exportExcel(response, list, "friend数据");
    }

    /**
     * 获取friend详细信息
     */
//    @PreAuthorize("@ss.hasPermi('system:friend:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") String id)
    {
        return success(noteFriendService.selectNoteFriendById(id));
    }

    /**
     * 新增friend
     */
//    @PreAuthorize("@ss.hasPermi('system:friend:add')")
    @Log(title = "friend", businessType = BusinessType.INSERT)
    @PostMapping(value = "/add")
    public AjaxResult add(@Validated @RequestBody  NoteFriend noteFriend)
    {
        if(-1==noteFriendService.insertNoteFriend(noteFriend)){
            return error("该用户没有申请好友！");
        }else{
            return toAjax(1);
        }
    }

    /**
     * 修改friend
     */
//    @PreAuthorize("@ss.hasPermi('system:friend:edit')")
    @Log(title = "friend", businessType = BusinessType.UPDATE)
    @RequestMapping(value = "/edit",method = org.springframework.web.bind.annotation.RequestMethod.POST)
    public AjaxResult edit(@Validated @RequestBody NoteFriend noteFriend)
    {
        if(-1==noteFriendService.updateNoteFriend(noteFriend)){
            return error("和该用户不是好友关系！");
        }else{
            return toAjax(1);
        }
    }

    /**
     * 拉黑好友
     */
//    @PreAuthorize("@ss.hasPermi('system:friend:edit')")
//    @Log(title = "friend", businessType = BusinessType.UPDATE)
    @RequestMapping(value = "/blockFriend",method = org.springframework.web.bind.annotation.RequestMethod.POST)
    public AjaxResult blockFriend(@RequestBody NoteFriend noteFriend)
    {
        return toAjax(noteFriendService.blockNoteFriend(noteFriend));
    }


    /**
     * 私聊发消息
     */
    // @RequestMapping(value = "/sendMessageToSomeone",method = org.springframework.web.bind.annotation.RequestMethod.POST)
    // public AjaxResult sendMessageToSomeone(@RequestBody NoteFriendVo noteFriendVo)
    // {

    //     boolean ifOnline = false;
    //     CopyOnWriteArraySet<WebSocketServer> webSocketSet = WebSocketServer.getWebSocketSet();
    //     try{
    //         for (WebSocketServer item : webSocketSet) {
    //             String sid = item.getSid();
    //             String friendId = noteFriendVo.getFriendId().toString();
    //             if (sid.equals(friendId)) {
    //                 ifOnline = true;
    //                 item.singleTalk(noteFriendVo.getMessage(),noteFriendVo.getFriendId().toString(),noteFriendVo.getUserId().toString(),noteFriendVo.getFriendId().toString());
    //             }
    //         }
    //     } catch (IOException e) {
    //         e.printStackTrace();
    //     }
    //     if(ifOnline){
    //         return success();
    //     }else{
    //         return error("好友不在线，消息未发送成功");
    //     }
    // }

    /**
     * 删除friend
     */
//    @PreAuthorize("@ss.hasPermi('system:friend:remove')")
    @Log(title = "friend", businessType = BusinessType.DELETE)
    @RequestMapping(value = "/remove",method = org.springframework.web.bind.annotation.RequestMethod.POST)
    public AjaxResult remove(@Validated @RequestBody NoteFriend noteFriend)
    {

        return toAjax(noteFriendService.deleteNoteFriend(noteFriend));
    }
}
