package com.ruoyi.web.controller.system;

import java.util.List;
import javax.servlet.http.HttpServletResponse;

import com.ruoyi.system.domain.TableRow;
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
import com.ruoyi.system.domain.SysViewManagement;
import com.ruoyi.system.service.ISysViewManagementService;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.core.page.TableDataInfo;

/**
 * 视图管理Controller
 * 
 * @author liuyanghe
 * @date 2025-08-21
 */
@RestController
@RequestMapping("/system/management")
public class SysViewManagementController extends BaseController
{
    @Autowired
    private ISysViewManagementService sysViewManagementService;

    /**
     * 查询视图管理列表
     */
    @PreAuthorize("@ss.hasPermi('system:management:list')")
    @GetMapping("/list")
    public TableDataInfo list(SysViewManagement sysViewManagement)
    {
        startPage();
        List<SysViewManagement> list = sysViewManagementService.selectSysViewManagementList(sysViewManagement);
        return getDataTable(list);
    }



    /**
     * 查询所有业务表列表
     */
    @PreAuthorize("@ss.hasPermi('system:management:list')")
    @GetMapping("/tableList")
    public TableDataInfo tableList(SysViewManagement sysViewManagement)
    {
        startPage();
        List<SysViewManagement> list = sysViewManagementService.selectTableList(sysViewManagement);
        return getDataTable(list);
    }

    /**
     * 导出视图管理列表
     */
    @PreAuthorize("@ss.hasPermi('system:management:export')")
    @Log(title = "视图管理", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, SysViewManagement sysViewManagement)
    {
        List<SysViewManagement> list = sysViewManagementService.selectSysViewManagementList(sysViewManagement);
        ExcelUtil<SysViewManagement> util = new ExcelUtil<SysViewManagement>(SysViewManagement.class);
        util.exportExcel(response, list, "视图管理数据");
    }

    /**
     * 获取视图管理详细信息
     */
    @PreAuthorize("@ss.hasPermi('system:management:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(sysViewManagementService.selectSysViewManagementById(id));
    }

    /**
     * 向指定数据库表添加列数据
     */
    @PreAuthorize("@ss.hasPermi('system:management:add')")
    @Log(title = "视图管理", businessType = BusinessType.INSERT)
    @PostMapping(value = "/addTableRow")
    public AjaxResult addTableRow(@Validated @RequestBody TableRow tableRow)
    {
        return toAjax(sysViewManagementService.insertTableRow(tableRow));
    }


    /**
     * 数据表添加某一列
     */
    @PreAuthorize("@ss.hasPermi('system:management:add')")
    @Log(title = "数据表添加某一列", businessType = BusinessType.INSERT)
    @PostMapping(value = "/table/addRow")
    public AjaxResult addRow(@Validated @RequestBody SysViewManagement sysViewManagement)
    {
        return toAjax(sysViewManagementService.insertSysViewManagement(sysViewManagement));
    }


    /**
     * 修改视图管理
     */
    @PreAuthorize("@ss.hasPermi('system:management:edit')")
    @Log(title = "视图管理", businessType = BusinessType.UPDATE)
    @PostMapping(value = "/edit")
    public AjaxResult edit(@Validated @RequestBody SysViewManagement sysViewManagement)
    {
        return toAjax(sysViewManagementService.updateSysViewManagement(sysViewManagement));
    }

    /**
     * 删除视图管理
     */
    @PreAuthorize("@ss.hasPermi('system:management:remove')")
    @Log(title = "视图管理", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(sysViewManagementService.deleteSysViewManagementByIds(ids));
    }
}
