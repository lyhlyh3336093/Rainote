package com.ruoyi.web.controller.system;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;
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
import com.ruoyi.system.domain.NoteDwtable;
import com.ruoyi.system.service.INoteDwtableService;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.core.page.TableDataInfo;

/**
 * 多维表格数据表Controller
 * 
 * @author liuyanghe
 * @date 2023-04-03
 */
@RestController
@RequestMapping("/system/dwtable")
public class NoteDwtableController extends BaseController
{
    @Autowired
    private INoteDwtableService noteDwtableService;

    /**
     * 查询多维表格数据表列表分页
     */
//    @PreAuthorize("@ss.hasPermi('system:dwtable:list')")
    @GetMapping("/pageList")
    public TableDataInfo pageList(NoteDwtable noteDwtable)
    {
        startPage();
        List<NoteDwtable> list = noteDwtableService.selectNoteDwtableList(noteDwtable);
        return getDataTable(list);
    }


    /**
     * 查询多维表格数据表列表
     */
//    @PreAuthorize("@ss.hasPermi('system:dwtable:list')")
    @GetMapping("/list")
    public AjaxResult list(NoteDwtable noteDwtable)
    {
        List<NoteDwtable> list = noteDwtableService.selectNoteDwtableList(noteDwtable);
        return success(list);
    }

    /**
     * 导出多维表格数据表列表
     */
//    @PreAuthorize("@ss.hasPermi('system:dwtable:export')")
    @Log(title = "多维表格数据表", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, NoteDwtable noteDwtable)
    {
        List<NoteDwtable> list = noteDwtableService.selectNoteDwtableList(noteDwtable);
        ExcelUtil<NoteDwtable> util = new ExcelUtil<NoteDwtable>(NoteDwtable.class);
        util.exportExcel(response, list, "多维表格数据表数据");
    }

    /**
     * 获取多维表格数据表详细信息
     */
//    @PreAuthorize("@ss.hasPermi('system:dwtable:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(noteDwtableService.selectNoteDwtableById(id));
    }


    /**
     * 获取多维表格数据表表内数据
     */
//    @PreAuthorize("@ss.hasPermi('system:dwtable:query')")
    @GetMapping(value = "/data/{id}")
    public AjaxResult getData(@PathVariable("id") Long id)
    {
        Map<String,Object> result = new HashMap<>();
        result = noteDwtableService.selectNoteDwtableDataById(id);
        return success(result);
    }


    /**
     * 新增多维表格数据表
     */
//    @PreAuthorize("@ss.hasPermi('system:dwtable:add')")
    @Log(title = "多维表格数据表", businessType = BusinessType.INSERT)
    @PostMapping(value = "/add")
    public AjaxResult add(@Validated @RequestBody  NoteDwtable noteDwtable)
    {
        return success(noteDwtableService.insertNoteDwtable(noteDwtable));
    }

    /**
     * 修改多维表格数据表
     */
    @PreAuthorize("@ss.hasPermi('system:dwtable:edit')")
    @Log(title = "多维表格数据表", businessType = BusinessType.UPDATE)
    @PostMapping(value = "/edit")
    public AjaxResult edit(@Validated @RequestBody NoteDwtable noteDwtable)
    {
        return toAjax(noteDwtableService.updateNoteDwtable(noteDwtable));
    }

    /**
     * 删除多维表格数据表
     */
//    @PreAuthorize("@ss.hasPermi('system:dwtable:remove')")
    @Log(title = "多维表格数据表", businessType = BusinessType.DELETE)
    @RequestMapping(value = "/remove/{ids}",method = org.springframework.web.bind.annotation.RequestMethod.GET)
    public AjaxResult remove(@PathVariable String ids)
    {
        String[] idarr = ids.split(",");
        return toAjax(noteDwtableService.deleteNoteDwtableByIds(idarr));
    }
}
