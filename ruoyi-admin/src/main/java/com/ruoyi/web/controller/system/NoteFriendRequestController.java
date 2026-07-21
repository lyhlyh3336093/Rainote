package com.ruoyi.web.controller.system;

import java.util.List;
import javax.servlet.http.HttpServletResponse;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.domain.NoteFriendRequest;
import com.ruoyi.system.service.INoteFriendRequestService;
import com.ruoyi.common.utils.poi.ExcelUtil;

/**
 * friendrequestController
 * 
 * @author liuyanghe
 * @date 2024-08-17
 */
@RestController
@RequestMapping("/system/request")
public class NoteFriendRequestController extends BaseController
{
    @Autowired
    private INoteFriendRequestService noteFriendRequestService;

    /**
     * 查询好友请求列表
     */
//    @PreAuthorize("@ss.hasPermi('system:request:list')")
    @GetMapping("/list")
    public AjaxResult list(NoteFriendRequest noteFriendRequest)
    {

        List<NoteFriendRequest> list = noteFriendRequestService.selectNoteFriendRequestList(noteFriendRequest);
        return success(list);
    }

    /**
     * 导出好友请求列表
     */
//    @PreAuthorize("@ss.hasPermi('system:request:export')")
    @Log(title = "friendrequest", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, NoteFriendRequest noteFriendRequest)
    {
        List<NoteFriendRequest> list = noteFriendRequestService.selectNoteFriendRequestList(noteFriendRequest);
        ExcelUtil<NoteFriendRequest> util = new ExcelUtil<NoteFriendRequest>(NoteFriendRequest.class);
        util.exportExcel(response, list, "friendrequest数据");
    }

    /**
     * 获取好友请求详细信息
     */
//    @PreAuthorize("@ss.hasPermi('system:request:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(noteFriendRequestService.selectNoteFriendRequestById(id));
    }

    /**
     * 新增好友请求
     */
//    @PreAuthorize("@ss.hasPermi('system:request:add')")
    @Log(title = "friendrequest", businessType = BusinessType.INSERT)
    @PostMapping(value = "/add")
    public AjaxResult add(@Validated @RequestBody NoteFriendRequest noteFriendRequest)
    {
        return toAjax(noteFriendRequestService.insertNoteFriendRequest(noteFriendRequest));
    }

    /**
     * 修改好友请求
     */
//    @PreAuthorize("@ss.hasPermi('system:request:edit')")
    @Log(title = "friendrequest", businessType = BusinessType.UPDATE)
    @PostMapping(value = "/edit")
    public AjaxResult edit(@Validated @RequestBody NoteFriendRequest noteFriendRequest)
    {
        return toAjax(noteFriendRequestService.updateNoteFriendRequest(noteFriendRequest));
    }

    /**
     * 删除好友请求
     */
//    @PreAuthorize("@ss.hasPermi('system:request:remove')")
    @Log(title = "好友请求", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(noteFriendRequestService.deleteNoteFriendRequestByIds(ids));
    }


    /**
     * 拒绝好友请求
     */
//    @PreAuthorize("@ss.hasPermi('system:request:remove')")
    @Log(title = "好友请求", businessType = BusinessType.DELETE)
    @PostMapping(value = "/refuse")
    public AjaxResult refuseRequest(@Validated @RequestBody NoteFriendRequest noteFriendRequest)
    {
        return toAjax(noteFriendRequestService.refuseRequest(noteFriendRequest));
    }

}
