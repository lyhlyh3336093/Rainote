package com.ruoyi.web.controller.system;

import java.util.ArrayList;
import java.util.List;
import javax.servlet.http.HttpServletResponse;

import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.system.domain.NoteNote;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
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
import com.ruoyi.system.domain.NoteRoleMenu;
import com.ruoyi.system.service.INoteRoleMenuService;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.core.page.TableDataInfo;

/**
 * 笔记系统角色和菜单关联Controller
 * 
 * @author liuyanghe
 * @date 2023-08-28
 */
@RestController
@RequestMapping("/system/noteMenu")
public class NoteRoleMenuController extends BaseController
{
    @Autowired
    private INoteRoleMenuService noteRoleMenuService;

    /**
     * 查询笔记系统角色和菜单关联列表
     */
//    @PreAuthorize("@ss.hasPermi('system:menu:list')")
    @GetMapping("/list")
    public AjaxResult list(NoteRoleMenu noteRoleMenu)
    {
        List<NoteRoleMenu> list = noteRoleMenuService.selectNoteRoleMenuList(noteRoleMenu);
        return success(list);
    }

    /**
     * 导出笔记系统角色和菜单关联列表
     */
//    @PreAuthorize("@ss.hasPermi('system:menu:export')")
    @Log(title = "笔记系统角色和菜单关联", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, NoteRoleMenu noteRoleMenu)
    {
        List<NoteRoleMenu> list = noteRoleMenuService.selectNoteRoleMenuList(noteRoleMenu);
        ExcelUtil<NoteRoleMenu> util = new ExcelUtil<NoteRoleMenu>(NoteRoleMenu.class);
        util.exportExcel(response, list, "笔记系统角色和菜单关联数据");
    }

    /**
     * 获取笔记系统角色和菜单关联详细信息
     */
//    @PreAuthorize("@ss.hasPermi('system:menu:query')")
    @GetMapping(value = "/{roleId}")
    public AjaxResult getInfo(@PathVariable("roleId") Long roleId)
    {
        return success(noteRoleMenuService.selectNoteRoleMenuByRoleId(roleId));
    }


    /**
     * 加载笔记系统中对应角色菜单列表树
     */
        @GetMapping(value = "/noteRoleMenuTreeselect/{roleId}")
    public AjaxResult noteRoleMenuTreeselect(@PathVariable("roleId") Long roleId)
    {
//        List<NoteRoleMenu> menus = noteRoleMenuService.selectNoteRoleMenuList(getUserId());
        List<NoteNote> notes = noteRoleMenuService.selectNoteMenuAuthList();
        AjaxResult ajax = AjaxResult.success();
        List<Long> checkedKeys = new ArrayList<Long>();
        checkedKeys.addAll(noteRoleMenuService.selectNoteRoleMenuListByRoleId(roleId));
//        checkedKeys.addAll(noteRoleMenuService.selectNoteMenuListByUserId(getUserId()));
        ajax.put("checkedKeys", checkedKeys);
        ajax.put("menus", noteRoleMenuService.buildNoteRoleMenuTreeSelect(notes));
        return ajax;
    }


    /**
     * 获取笔记系统菜单下拉树列表
     */
    @GetMapping("/noteTreeselect")
    public AjaxResult noteTreeselect()
    {
        List<NoteNote> menus = noteRoleMenuService.selectNoteMenuList(getUserId());
        return success(noteRoleMenuService.buildNoteRoleMenuTreeSelect(menus));
    }

    /**
     * 查询笔记系统中未分配用户角色列表
     */
//    @PreAuthorize("@ss.hasPermi('system:role:list')")
    @GetMapping("/noteUnallocatedList")
    public TableDataInfo unallocatedList(SysUser user)
    {
        startPage();
        List<SysUser> list = noteRoleMenuService.selectNoteUnallocatedList(user);
        return getDataTable(list);
    }

    /**
     * 新增笔记系统角色和菜单关联
     */
//    @PreAuthorize("@ss.hasPermi('system:menu:add')")
    @Log(title = "笔记系统角色和菜单关联", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody NoteRoleMenu noteRoleMenu)
    {
        return toAjax(noteRoleMenuService.insertNoteRoleMenu(noteRoleMenu));
    }

    /**
     * 修改笔记系统角色和菜单关联
     */
//    @PreAuthorize("@ss.hasPermi('system:menu:edit')")
    @Log(title = "笔记系统角色和菜单关联", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody NoteRoleMenu noteRoleMenu)
    {
        return toAjax(noteRoleMenuService.updateNoteRoleMenu(noteRoleMenu));
    }

    /**
     * 删除笔记系统角色和菜单关联
     */
//    @PreAuthorize("@ss.hasPermi('system:menu:remove')")
    @Log(title = "笔记系统角色和菜单关联", businessType = BusinessType.DELETE)
	@DeleteMapping("/{roleIds}")
    public AjaxResult remove(@PathVariable String roleIds)
    {
        String[] idarr = roleIds.split(",");
        return toAjax(noteRoleMenuService.deleteNoteRoleMenuByRoleIds(idarr));
    }
}
