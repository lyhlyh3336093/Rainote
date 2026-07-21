package com.ruoyi.web.controller.system;

import java.util.List;
import javax.servlet.http.HttpServletResponse;
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
import com.ruoyi.system.domain.NoteRoleDept;
import com.ruoyi.system.service.INoteRoleDeptService;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.core.page.TableDataInfo;

/**
 * 笔记角色和部门关联Controller
 * 
 * @author liuyanghe
 * @date 2023-09-15
 */
@RestController
@RequestMapping("/system/noteRoleDept")
public class NoteRoleDeptController extends BaseController
{
    @Autowired
    private INoteRoleDeptService noteRoleDeptService;

    /**
     * 查询笔记角色和部门关联列表
     */
//    @PreAuthorize("@ss.hasPermi('note:dept:list')")
    @GetMapping("/list")
    public TableDataInfo list(NoteRoleDept noteRoleDept)
    {
        startPage();
        List<NoteRoleDept> list = noteRoleDeptService.selectNoteRoleDeptList(noteRoleDept);
        return getDataTable(list);
    }

    /**
     * 导出笔记角色和部门关联列表
     */
//    @PreAuthorize("@ss.hasPermi('note:dept:export')")
    @Log(title = "笔记角色和部门关联", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, NoteRoleDept noteRoleDept)
    {
        List<NoteRoleDept> list = noteRoleDeptService.selectNoteRoleDeptList(noteRoleDept);
        ExcelUtil<NoteRoleDept> util = new ExcelUtil<NoteRoleDept>(NoteRoleDept.class);
        util.exportExcel(response, list, "笔记角色和部门关联数据");
    }

    /**
     * 获取笔记角色和部门关联详细信息
     */
//    @PreAuthorize("@ss.hasPermi('note:dept:query')")
    @GetMapping(value = "/{roleId}")
    public AjaxResult getInfo(@PathVariable("roleId") Long roleId)
    {
        return success(noteRoleDeptService.selectNoteRoleDeptByRoleId(roleId));
    }

    /**
     * 新增笔记角色和部门关联
     */
//    @PreAuthorize("@ss.hasPermi('note:dept:add')")
    @Log(title = "笔记角色和部门关联", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody NoteRoleDept noteRoleDept)
    {
        return toAjax(noteRoleDeptService.insertNoteRoleDept(noteRoleDept));
    }

    /**
     * 修改笔记角色和部门关联
     */
//    @PreAuthorize("@ss.hasPermi('note:dept:edit')")
    @Log(title = "笔记角色和部门关联", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody NoteRoleDept noteRoleDept)
    {
        return toAjax(noteRoleDeptService.updateNoteRoleDept(noteRoleDept));
    }

    /**
     * 删除笔记角色和部门关联
     */
//    @PreAuthorize("@ss.hasPermi('note:dept:remove')")
    @Log(title = "笔记角色和部门关联", businessType = BusinessType.DELETE)
	@DeleteMapping("/{roleIds}")
    public AjaxResult remove(@PathVariable Long[] roleIds)
    {
        return toAjax(noteRoleDeptService.deleteNoteRoleDeptByRoleIds(roleIds));
    }
}
