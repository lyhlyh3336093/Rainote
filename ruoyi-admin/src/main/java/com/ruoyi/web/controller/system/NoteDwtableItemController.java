package com.ruoyi.web.controller.system;

import java.util.List;
import javax.servlet.http.HttpServletResponse;
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
import com.ruoyi.system.domain.NoteDwtableItem;
import com.ruoyi.system.service.INoteDwtableItemService;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.core.page.TableDataInfo;

/**
 * 多维表格数据表内容Controller
 * 
 * @author liuyanghe
 * @date 2023-04-03
 */
@RestController
@RequestMapping("/system/item")
public class NoteDwtableItemController extends BaseController
{
    @Autowired
    private INoteDwtableItemService noteDwtableItemService;

    /**
     * 多维表格数据表内容分页
     */
//    @PreAuthorize("@ss.hasPermi('system:item:list')")
    @GetMapping("/pageList")
    public TableDataInfo pageList(NoteDwtableItem noteDwtableItem)
    {
        startPage();
        List<NoteDwtableItem> list = noteDwtableItemService.selectNoteDwtableItemList(noteDwtableItem);
        return getDataTable(list);
    }

    /**
     * 查询多维表格数据表内容
     */
//    @PreAuthorize("@ss.hasPermi('system:item:list')")
    @GetMapping("/list")
    public AjaxResult list(NoteDwtableItem noteDwtableItem)
    {
        List<NoteDwtableItem> list = noteDwtableItemService.selectNoteDwtableItemList(noteDwtableItem);
        return success(list);
    }


    /**
     * 导出多维表格数据表内容列表
     */
//    @PreAuthorize("@ss.hasPermi('system:item:export')")
    @Log(title = "多维表格数据表内容", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, NoteDwtableItem noteDwtableItem)
    {
        List<NoteDwtableItem> list = noteDwtableItemService.selectNoteDwtableItemList(noteDwtableItem);
        ExcelUtil<NoteDwtableItem> util = new ExcelUtil<NoteDwtableItem>(NoteDwtableItem.class);
        util.exportExcel(response, list, "多维表格数据表内容数据");
    }

    /**
     * 获取多维表格数据表内容详细信息
     */
//    @PreAuthorize("@ss.hasPermi('system:item:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(noteDwtableItemService.selectNoteDwtableItemById(id));
    }

    /**
     * 新增多维表格数据表内容
     */
//    @PreAuthorize("@ss.hasPermi('system:item:add')")
    @Log(title = "多维表格数据表内容", businessType = BusinessType.INSERT)
    @PostMapping(value = "/add")
    public AjaxResult add(@Validated @RequestBody NoteDwtableItem noteDwtableItem)
    {
        return toAjax(noteDwtableItemService.insertNoteDwtableItem(noteDwtableItem));
    }

    /**
     * 修改多维表格数据表内容
     */
//    @PreAuthorize("@ss.hasPermi('system:item:edit')")
    @Log(title = "多维表格数据表内容", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody NoteDwtableItem noteDwtableItem)
    {
        return toAjax(noteDwtableItemService.updateNoteDwtableItem(noteDwtableItem));
    }

    /**
     * 删除多维表格数据表内容
     */
//    @PreAuthorize("@ss.hasPermi('system:item:remove')")
    @Log(title = "多维表格数据表内容", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(noteDwtableItemService.deleteNoteDwtableItemByIds(ids));
    }
}
