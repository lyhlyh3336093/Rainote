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
import com.ruoyi.system.domain.NoteNotelink;
import com.ruoyi.system.service.INoteNotelinkService;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.core.page.TableDataInfo;

/**
 * 笔记链接Controller
 * 
 * @author liuyanghe
 * @date 2026-02-08
 */
@RestController
@RequestMapping("/system/notelink")
public class NoteNotelinkController extends BaseController
{
    @Autowired
    private INoteNotelinkService noteNotelinkService;

    /**
     * 查询笔记链接列表
     */
    @PreAuthorize("@ss.hasPermi('system:notelink:list')")
    @GetMapping("/list")
    public TableDataInfo list(NoteNotelink noteNotelink)
    {
        startPage();
        List<NoteNotelink> list = noteNotelinkService.selectNoteNotelinkList(noteNotelink);
        return getDataTable(list);
    }

    /**
     * 导出笔记链接列表
     */
    @PreAuthorize("@ss.hasPermi('system:notelink:export')")
    @Log(title = "笔记链接", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, NoteNotelink noteNotelink)
    {
        List<NoteNotelink> list = noteNotelinkService.selectNoteNotelinkList(noteNotelink);
        ExcelUtil<NoteNotelink> util = new ExcelUtil<NoteNotelink>(NoteNotelink.class);
        util.exportExcel(response, list, "笔记链接数据");
    }

    /**
     * 获取笔记链接详细信息
     */
    @PreAuthorize("@ss.hasPermi('system:notelink:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(noteNotelinkService.selectNoteNotelinkById(id));
    }

    /**
     * U2: 按 cell 聚合查询(供 R6 单元格展示)
     * 按 (linkColumnId + linkItemId) 查所有 NoteNotelink,按 id 升序返回
     */
    @PreAuthorize("@ss.hasPermi('system:notelink:query')")
    @GetMapping(value = "/cell/{linkColumnId}/{linkItemId}")
    public AjaxResult listByCell(@PathVariable("linkColumnId") Long linkColumnId,
                                 @PathVariable("linkItemId") Long linkItemId)
    {
        return success(noteNotelinkService.selectNoteNotelinkByCell(linkColumnId, linkItemId));
    }

    /**
     * U5: 按 noteId 查询引用列表(供 R9 追溯面板)
     * 查所有引用该笔记的 NoteNotelink,按 id 升序返回
     */
    @PreAuthorize("@ss.hasPermi('system:notelink:query')")
    @GetMapping(value = "/byNote/{noteId}")
    public AjaxResult listByNote(@PathVariable("noteId") Long noteId)
    {
        return success(noteNotelinkService.selectNoteNotelinkByNoteId(noteId));
    }

    /**
     * 新增笔记链接
     * U2/U3: 返回新记录 id(供反向创建流程拿 data-link-id);插入失败时返回 error
     */
    @PreAuthorize("@ss.hasPermi('system:notelink:add')")
    @Log(title = "笔记链接", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody NoteNotelink noteNotelink)
    {
        int rows = noteNotelinkService.insertNoteNotelink(noteNotelink);
        if (rows > 0) {
            // 返回新记录主键,供反向创建流程写入 data-link-id
            return success(noteNotelink.getId());
        }
        return error("新增笔记链接失败");
    }

    /**
     * 修改笔记链接
     */
    @PreAuthorize("@ss.hasPermi('system:notelink:edit')")
    @Log(title = "笔记链接", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody NoteNotelink noteNotelink)
    {
        return toAjax(noteNotelinkService.updateNoteNotelink(noteNotelink));
    }

    /**
     * 删除笔记链接
     */
    @PreAuthorize("@ss.hasPermi('system:notelink:remove')")
    @Log(title = "笔记链接", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(noteNotelinkService.deleteNoteNotelinkByIds(ids));
    }
}
