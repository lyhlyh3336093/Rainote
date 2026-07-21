package com.ruoyi.web.controller.system;

import java.util.List;
import javax.servlet.http.HttpServletResponse;

import com.ruoyi.system.domain.vo.NoteViewVo;
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
import com.ruoyi.system.domain.NoteView;
import com.ruoyi.system.service.INoteViewService;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.core.page.TableDataInfo;

/**
 * 视图Controller
 * 
 * @author liuyanghe
 * @date 2023-04-10
 */
@RestController
@RequestMapping("/system/view")
public class NoteViewController extends BaseController
{
    @Autowired
    private INoteViewService noteViewService;

    /**
     * 查询视图列表
     */
//    @PreAuthorize("@ss.hasPermi('system:view:list')")
    @GetMapping("/list")
    public TableDataInfo list(NoteView noteView)
    {
        startPage();
        List<NoteView> list = noteViewService.selectNoteViewList(noteView);
        return getDataTable(list);
    }

    /**
     * 查询数据表列表
     */
//    @GetMapping("/tableList")
//    public TableDataInfo tableList(Long noteId)
//    {
//        startPage();
//        List<NoteView> list = noteViewService.selectNoteTableList(noteView);
//        return getDataTable(list);
//    }



    /**
     * 导出视图列表
     */
//    @PreAuthorize("@ss.hasPermi('system:view:export')")
    @Log(title = "视图", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, NoteView noteView)
    {
        List<NoteView> list = noteViewService.selectNoteViewList(noteView);
        ExcelUtil<NoteView> util = new ExcelUtil<NoteView>(NoteView.class);
        util.exportExcel(response, list, "视图数据");
    }

    /**
     * 获取视图详细信息
     */
//    @PreAuthorize("@ss.hasPermi('system:view:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(noteViewService.selectNoteViewById(id));
    }

    /**
     * 新增视图
     */
//    @PreAuthorize("@ss.hasPermi('system:view:add')")
    @Log(title = "视图", businessType = BusinessType.INSERT)
    @PostMapping(value = "/add")
    public AjaxResult add(@Validated @RequestBody NoteViewVo noteViewvo)
    {
        NoteView noteView = new NoteView();
        noteView.setName(noteViewvo.getName());
        noteView.setType(noteViewvo.getType());
        noteView.setDwtableId(noteViewvo.getDwtableId());
        if(noteViewvo.getHiddenFields()!=null){
            noteView.setHiddenFields(noteViewvo.getHiddenFields());
        }
        if(noteViewvo.getProperty()!=null){
            noteView.setProperty(noteViewvo.getProperty());
        }
        return success(noteViewService.insertNoteView(noteView));
    }

    /**
     * 修改视图
     */
//    @PreAuthorize("@ss.hasPermi('system:view:edit')")
    @Log(title = "视图", businessType = BusinessType.UPDATE)
    @RequestMapping(value = "/edit",method = org.springframework.web.bind.annotation.RequestMethod.POST)
    public AjaxResult edit(@Validated @RequestBody NoteViewVo noteViewvo)
    {
        NoteView noteView = new NoteView();
        noteView.setId(noteViewvo.getId());
        noteView.setName(noteViewvo.getName());
        noteView.setType(noteViewvo.getType());
        noteView.setDwtableId(noteViewvo.getDwtableId());
        if(noteViewvo.getHiddenFields()!=null){
            noteView.setHiddenFields(noteViewvo.getHiddenFields());
        }
        if(noteViewvo.getProperty()!=null){
            noteView.setProperty(noteViewvo.getProperty());
        }
        return toAjax(noteViewService.updateNoteView(noteView));
    }

    /**
     * 删除视图
     */
//    @PreAuthorize("@ss.hasPermi('system:column:remove')")
    @Log(title = "视图", businessType = BusinessType.DELETE)
    @RequestMapping(value = "/remove/{ids}",method = org.springframework.web.bind.annotation.RequestMethod.GET)
    public AjaxResult remove(@PathVariable String ids)
    {
        String[] idarr = ids.split(",");
        return toAjax(noteViewService.deleteNoteViewByIds(idarr));
    }
}
