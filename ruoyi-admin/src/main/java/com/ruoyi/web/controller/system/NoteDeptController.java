package com.ruoyi.web.controller.system;

import java.util.List;
import javax.servlet.http.HttpServletResponse;

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
import com.ruoyi.system.domain.NoteDept;
import com.ruoyi.system.service.INoteDeptService;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.core.page.TableDataInfo;

/**
 * 笔记部门Controller
 * 
 * @author liuyanghe
 * @date 2023-09-15
 */
@RestController
@RequestMapping("/system/noteDept")
public class NoteDeptController extends BaseController
{
    @Autowired
    private INoteDeptService noteDeptService;

    /**
     * 查询笔记部门列表
     */
//    @PreAuthorize("@ss.hasPermi('system:dept:list')")
    @GetMapping("/list")
    public TableDataInfo list(NoteDept noteDept)
    {
        startPage();
        List<NoteDept> list = noteDeptService.selectNoteDeptList(noteDept);
        return getDataTable(list);
    }

    /**
     * 导出笔记部门列表
     */
//    @PreAuthorize("@ss.hasPermi('system:dept:export')")
    @Log(title = "笔记部门", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, NoteDept noteDept)
    {
        List<NoteDept> list = noteDeptService.selectNoteDeptList(noteDept);
        ExcelUtil<NoteDept> util = new ExcelUtil<NoteDept>(NoteDept.class);
        util.exportExcel(response, list, "笔记部门数据");
    }

    /**
     * 获取笔记部门详细信息
     */
//    @PreAuthorize("@ss.hasPermi('system:dept:query')")
    @GetMapping(value = "/{deptId}")
    public AjaxResult getInfo(@PathVariable("deptId") Long deptId)
    {
        return success(noteDeptService.selectNoteDeptByDeptId(deptId));
    }

    /**
     * 新增笔记部门
     */
//    @PreAuthorize("@ss.hasPermi('system:dept:add')")
    @Log(title = "笔记部门", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody NoteDept noteDept)
    {
        return toAjax(noteDeptService.insertNoteDept(noteDept));
    }

    /**
     * 修改笔记部门
     */
//    @PreAuthorize("@ss.hasPermi('system:dept:edit')")
    @Log(title = "笔记部门", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody NoteDept noteDept)
    {
        return toAjax(noteDeptService.updateNoteDept(noteDept));
    }

    /**
     * 删除笔记部门
     */
//    @PreAuthorize("@ss.hasPermi('system:dept:remove')")
    @Log(title = "笔记部门", businessType = BusinessType.DELETE)
	@DeleteMapping("/{deptIds}")
    public AjaxResult remove(@PathVariable Long[] deptIds)
    {
        return toAjax(noteDeptService.deleteNoteDeptByDeptIds(deptIds));
    }
}
