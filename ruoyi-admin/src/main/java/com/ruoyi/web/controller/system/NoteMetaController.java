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
import com.ruoyi.system.domain.NoteMeta;
import com.ruoyi.system.service.INoteMetaService;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.core.page.TableDataInfo;

/**
 * 元数据Controller
 * 
 * @author liuyanghe
 * @date 2023-05-13
 */
@RestController
@RequestMapping("/system/meta")
public class NoteMetaController extends BaseController
{
    @Autowired
    private INoteMetaService noteMetaService;

    /**
     * 查询元数据列表
     */
//    @PreAuthorize("@ss.hasPermi('system:meta:list')")
    @GetMapping("/list")
    public TableDataInfo list(NoteMeta noteMeta)
    {
        startPage();
        List<NoteMeta> list = noteMetaService.selectNoteMetaList(noteMeta);
        return getDataTable(list);
    }

    /**
     * 导出元数据列表
     */
//    @PreAuthorize("@ss.hasPermi('system:meta:export')")
    @Log(title = "元数据", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, NoteMeta noteMeta)
    {
        List<NoteMeta> list = noteMetaService.selectNoteMetaList(noteMeta);
        ExcelUtil<NoteMeta> util = new ExcelUtil<NoteMeta>(NoteMeta.class);
        util.exportExcel(response, list, "元数据数据");
    }

    /**
     * 获取元数据详细信息
     */
//    @PreAuthorize("@ss.hasPermi('system:meta:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(noteMetaService.selectNoteMetaById(id));
    }


    /**
     * 获取笔记元数据详细信息
     */
//    @PreAuthorize("@ss.hasPermi('system:meta:query')")
    @GetMapping(value = "/info/{id}")
    public AjaxResult getNoteInfo(@PathVariable("id") Long id)
    {
        return success(noteMetaService.selectNoteMetaByNoteId(id));
    }

    /**
     * 新增元数据
     */
//    @PreAuthorize("@ss.hasPermi('system:meta:add')")
    @Log(title = "元数据", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody NoteMeta noteMeta)
    {
        return toAjax(noteMetaService.insertNoteMeta(noteMeta));
    }

    /**
     * 修改元数据
     */
//    @PreAuthorize("@ss.hasPermi('system:meta:edit')")
    @Log(title = "元数据", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody NoteMeta noteMeta)
    {
        return toAjax(noteMetaService.updateNoteMeta(noteMeta));
    }

    /**
     * 删除元数据
     */
//    @PreAuthorize("@ss.hasPermi('system:meta:remove')")
    @Log(title = "元数据", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(noteMetaService.deleteNoteMetaByIds(ids));
    }
}
