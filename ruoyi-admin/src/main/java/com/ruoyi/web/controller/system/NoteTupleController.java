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
import com.ruoyi.system.domain.NoteTuple;
import com.ruoyi.system.service.INoteTupleService;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.core.page.TableDataInfo;

/**
 * 元组Controller
 * 
 * @author liuyanghe
 * @date 2023-05-07
 */
@RestController
@RequestMapping("/system/tuple")
public class NoteTupleController extends BaseController
{
    @Autowired
    private INoteTupleService noteTupleService;

    /**
     * 查询元组列表
     */
//    @PreAuthorize("@ss.hasPermi('system:tuple:list')")
    @GetMapping("/list")
    public TableDataInfo list(NoteTuple noteTuple)
    {
        startPage();
        List<NoteTuple> list = noteTupleService.selectNoteTupleList(noteTuple);
        return getDataTable(list);
    }

    /**
     * 导出元组列表
     */
//    @PreAuthorize("@ss.hasPermi('system:tuple:export')")
    @Log(title = "元组", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, NoteTuple noteTuple)
    {
        List<NoteTuple> list = noteTupleService.selectNoteTupleList(noteTuple);
        ExcelUtil<NoteTuple> util = new ExcelUtil<NoteTuple>(NoteTuple.class);
        util.exportExcel(response, list, "元组数据");
    }

    /**
     * 获取元组详细信息
     */
//    @PreAuthorize("@ss.hasPermi('system:tuple:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(noteTupleService.selectNoteTupleById(id));
    }

    /**
     * 新增元组
     */
//    @PreAuthorize("@ss.hasPermi('system:tuple:add')")
    @Log(title = "元组", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody NoteTuple noteTuple)
    {
        return toAjax(noteTupleService.insertNoteTuple(noteTuple));
    }

    /**
     * 修改元组
     */
//    @PreAuthorize("@ss.hasPermi('system:tuple:edit')")
    @Log(title = "元组", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody NoteTuple noteTuple)
    {
        return toAjax(noteTupleService.updateNoteTuple(noteTuple));
    }

    /**
     * 删除元组
     */
//    @PreAuthorize("@ss.hasPermi('system:tuple:remove')")
    @Log(title = "元组", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(noteTupleService.deleteNoteTupleByIds(ids));
    }
}
