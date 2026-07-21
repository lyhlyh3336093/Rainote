package com.ruoyi.web.controller.system;

import java.util.List;
import javax.servlet.http.HttpServletResponse;

import com.ruoyi.system.domain.NoteNote;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.domain.UserInfo;
import com.ruoyi.system.service.IUserInfoService;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.core.page.TableDataInfo;

/**
 * userInfoController
 * 
 * @author liuyanghe
 * @date 2026-01-13
 */
@RestController
@RequestMapping("/system/userinfo")
public class UserInfoController extends BaseController
{
    @Autowired
    private IUserInfoService userInfoService;

    /**
     * 查询userInfo列表
     */
    @PreAuthorize("@ss.hasPermi('system:userinfo:list')")
    @GetMapping("/list")
    public TableDataInfo list(UserInfo userInfo)
    {
        startPage();
        List<UserInfo> list = userInfoService.selectUserInfoList(userInfo);
        return getDataTable(list);
    }

    /**
     * 导出userInfo列表
     */
    @PreAuthorize("@ss.hasPermi('system:userinfo:export')")
    @Log(title = "userInfo", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, UserInfo userInfo)
    {
        List<UserInfo> list = userInfoService.selectUserInfoList(userInfo);
        ExcelUtil<UserInfo> util = new ExcelUtil<UserInfo>(UserInfo.class);
        util.exportExcel(response, list, "userInfo数据");
    }

    /**
     * 获取userInfo详细信息
     */
    @PreAuthorize("@ss.hasPermi('system:userinfo:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(userInfoService.selectUserInfoById(id));
    }

    /**
     * 新增userInfo
     */
    @PreAuthorize("@ss.hasPermi('system:userinfo:add')")
    @Log(title = "userInfo", businessType = BusinessType.INSERT)
    @PostMapping(value = "/add")
    public AjaxResult add(@Validated @RequestBody UserInfo userInfo)
    {
        return toAjax(userInfoService.insertUserInfo(userInfo));
    }

    /**
     * 修改userInfo
     */
    @PreAuthorize("@ss.hasPermi('system:userinfo:edit')")
    @Log(title = "userInfo", businessType = BusinessType.UPDATE)
    @RequestMapping(value = "/edit",method = org.springframework.web.bind.annotation.RequestMethod.POST)
    public AjaxResult edit(@Validated @RequestBody UserInfo userInfo)
    {
        return toAjax(userInfoService.updateUserInfo(userInfo));
    }

    /**
     * 删除userInfo
     */
    @PreAuthorize("@ss.hasPermi('system:userinfo:remove')")
    @Log(title = "userInfo", businessType = BusinessType.DELETE)
//    @RequestMapping(value = "/remove/{id}",method = org.springframework.web.bind.annotation.RequestMethod.GET)
    @GetMapping("/remove")
    public AjaxResult remove(Long id)
    {
        return toAjax(userInfoService.deleteUserInfoById(id));
    }

    /**
     * 删除userInfo
     */
    @PreAuthorize("@ss.hasPermi('system:userinfo:remove')")
    @Log(title = "userInfo", businessType = BusinessType.DELETE)
    @RequestMapping(value = "/batchRemove/{ids}",method = org.springframework.web.bind.annotation.RequestMethod.GET)
    public AjaxResult batchRemove(@PathVariable String ids)
    {
        String[] idarr = ids.split(",");
        return toAjax(userInfoService.deleteUserInfoByIds(idarr));
    }
}
