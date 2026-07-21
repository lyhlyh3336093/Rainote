package com.ruoyi.web.controller.system;

import java.util.List;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.domain.NoteUserRole;
import com.ruoyi.system.service.INoteUserRoleService;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.core.page.TableDataInfo;

/**
 * 用户和笔记系统角色关联Controller
 * 
 * @author liuyanghe
 * @date 2023-08-29
 */
@RestController
@RequestMapping("/system/note/userRole")
public class NoteUserRoleController extends BaseController
{
    @Autowired
    private INoteUserRoleService noteUserRoleService;

    /**
     * 查询用户和笔记系统角色关联列表
     */
//    @PreAuthorize("@ss.hasPermi('system:role:list')")
    @GetMapping("/list")
    public TableDataInfo list(NoteUserRole noteUserRole)
    {
        startPage();
        List<NoteUserRole> list = noteUserRoleService.selectNoteUserRoleList(noteUserRole);
        return getDataTable(list);
    }

    /**
     * 导出用户和笔记系统角色关联列表
     */
//    @PreAuthorize("@ss.hasPermi('system:role:export')")
    @Log(title = "用户和笔记系统角色关联", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, NoteUserRole noteUserRole)
    {
        List<NoteUserRole> list = noteUserRoleService.selectNoteUserRoleList(noteUserRole);
        ExcelUtil<NoteUserRole> util = new ExcelUtil<NoteUserRole>(NoteUserRole.class);
        util.exportExcel(response, list, "用户和笔记系统角色关联数据");
    }

    /**
     * 获取用户和笔记系统角色关联详细信息
     */
//    @PreAuthorize("@ss.hasPermi('system:role:query')")
    @GetMapping(value = "/{userId}")
    public AjaxResult getInfo(@PathVariable("userId") Long userId)
    {
        return success(noteUserRoleService.selectNoteUserRoleByUserId(userId));
    }

    /**
     * 新增用户和笔记系统角色关联
     */
//    @PreAuthorize("@ss.hasPermi('system:role:add')")
    @Log(title = "用户和笔记系统角色关联", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody NoteUserRole noteUserRole)
    {
        return toAjax(noteUserRoleService.insertNoteUserRole(noteUserRole));
    }

    /**
     * 修改用户和笔记系统角色关联
     */
//    @PreAuthorize("@ss.hasPermi('system:role:edit')")
    @Log(title = "用户和笔记系统角色关联", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody NoteUserRole noteUserRole)
    {
        return toAjax(noteUserRoleService.updateNoteUserRole(noteUserRole));
    }

    /**
     * 用户授权角色
     */
//    @PreAuthorize("@ss.hasPermi('system:user:edit')")
    @Log(title = "用户授权角色", businessType = BusinessType.GRANT)
    @PostMapping("/noteAuthRole")
    public AjaxResult inserNotetAuthRole(Long userId, Long[] roleIds)
    {
        //TODO：这里是数据权限的筛选，有aspectj等相关技术的应用，目前正在学习中，但是优先发布和上线项目
        // 后续需要数据权限的时候再来完善吧。
        //好了现在我看懂了，数据权限就是dataScope的数据筛选，
        // 本质是若依自己用注解的方式在sql语句上用$拼接了一些筛选条件，
        // (我逐渐开始理解一切.jpg)，
        //哦对了，这里跟aspectj没啥关系，底层和框架的事，若依去干；配置和参数的事，我来干
//        noteUserRoleService.checkUserDataScope(userId);

        noteUserRoleService.insertNoteUserAuth(userId, roleIds);
        return success();
    }



    /**
     * 删除用户和笔记系统角色关联
     */
//    @PreAuthorize("@ss.hasPermi('system:role:remove')")
    @Log(title = "用户和笔记系统角色关联", businessType = BusinessType.DELETE)
    @RequestMapping(value = "/cancel/{ids}",method = org.springframework.web.bind.annotation.RequestMethod.GET)
    public AjaxResult cancel(@PathVariable String ids)
    {
        String[] idarr = ids.split(",");
        return toAjax(noteUserRoleService.deleteNoteUserRoleByUserIds(idarr));
    }
}
