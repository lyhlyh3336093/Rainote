package com.ruoyi.web.controller.system;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;

import com.ruoyi.system.domain.NoteRecord;
import com.ruoyi.system.domain.vo.NoteColumnVo;
import com.ruoyi.system.domain.vo.NoteRecordVo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.service.INoteColumnService;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.core.page.TableDataInfo;

/**
 * 列信息Controller
 * 
 * @author liuyanghe
 * @date 2023-04-03
 */
@RestController
@RequestMapping("/system/column")
public class NoteColumnController extends BaseController
{
    @Autowired
    private INoteColumnService noteColumnService;

    /**
     * 查询列信息列表
     */
//    @PreAuthorize("@ss.hasPermi('system:column:list')")
    @GetMapping("/list")
    public TableDataInfo list(NoteColumn noteColumn)
    {
        startPage();
        List<NoteColumn> list = noteColumnService.selectNoteColumnList(noteColumn);
        return getDataTable(list);
    }

    /**
     * 查询多维笔记中的列信息列表
     */
//    @PreAuthorize("@ss.hasPermi('system:column:list')")
    @GetMapping("/columnList")
    public AjaxResult columnList(NoteColumn noteColumn)
    {
        List<NoteColumn> list = noteColumnService.selectNoteColumnList(noteColumn);
        return success(list);
    }


    /**
     * 根据数据表id查询数据表下的所有双向链接列
     */
//    @PreAuthorize("@ss.hasPermi('system:column:list')")
    @GetMapping("/selectNoteDoubleLinkColumnList")
    public AjaxResult selectNoteDoubleLinkColumnList(Long dwtableId)
    {
        List<NoteColumn> list = noteColumnService.selectNoteDoubleLinkColumnList(dwtableId);
        return success(list);
    }


    /**
     * 导出列信息列表
     */
//    @PreAuthorize("@ss.hasPermi('system:column:export')")
    @Log(title = "列信息", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, NoteColumn noteColumn)
    {
        List<NoteColumn> list = noteColumnService.selectNoteColumnList(noteColumn);
        ExcelUtil<NoteColumn> util = new ExcelUtil<NoteColumn>(NoteColumn.class);
        util.exportExcel(response, list, "列信息数据");
    }

    /**
     * 获取列信息详细信息
     */
//    @PreAuthorize("@ss.hasPermi('system:column:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(noteColumnService.selectNoteColumnById(id));
    }

    /**
     * 新增列信息
     */
//    @PreAuthorize("@ss.hasPermi('system:column:add')")
    @Log(title = "列信息", businessType = BusinessType.INSERT)
    @PostMapping(value = "/add")
    public AjaxResult add(@Validated @RequestBody NoteColumnVo noteColumnvo)
    {
//        NoteColumn noteColumn = new NoteColumn();
//
//        if(noteColumnvo.getIsShow()==null){
//            noteColumn.setIsShow(0L);
//        }
//        noteColumn.setProperty(noteColumnvo.getProperty().toString());
//        noteColumn.setName(noteColumnvo.getName());
//        noteColumn.setType(noteColumnvo.getType());
//        noteColumn.setDwtableId(noteColumnvo.getDwtableId());
        return toAjax(noteColumnService.insertNoteColumn(noteColumnvo));
    }

    /**
     * 修改列信息
     */
//    @PreAuthorize("@ss.hasPermi('system:column:edit')")
    @Log(title = "列信息", businessType = BusinessType.UPDATE)
    @RequestMapping(value = "/update",method = org.springframework.web.bind.annotation.RequestMethod.POST)
    public AjaxResult edit(@Validated @RequestBody  NoteColumnVo noteColumnvo)
    {
        return toAjax(noteColumnService.updateNoteColumn(noteColumnvo));
    }


    /**
     * 修改列排序
     */
//    @PreAuthorize("@ss.hasPermi('system:column:edit')")
    @Log(title = "列信息", businessType = BusinessType.UPDATE)
    @RequestMapping(value = "/updateSort",method = org.springframework.web.bind.annotation.RequestMethod.POST)
    public AjaxResult updateSort(@Validated @RequestBody NoteRecordVo noteRecordvo)
    {
        List<Map<String, Object>> sorts = new ArrayList<Map<String, Object>>();
        NoteRecord noteRecord = new NoteRecord();
        noteRecord.setId(noteRecordvo.getId());
        noteRecord.setViewId(noteRecordvo.getViewId());
        if(noteRecordvo.getLinkRecordId()!=null&&noteRecordvo.getLinkName()!=null){
            noteRecord.setLinkName(noteRecordvo.getLinkName());
            noteRecord.setLinkRecordId(noteRecordvo.getLinkRecordId());
        }
        if(noteRecordvo.getName()!=null){
            noteRecord.setName(noteRecordvo.getName());
        }
        if(noteRecordvo.getProperty()!=null){
            noteRecord.setProperty(noteRecordvo.getProperty());
        }
        if(noteRecordvo.getSorts()!=null){
            sorts = noteRecordvo.getSorts();
        }
        return toAjax(noteColumnService.updateSort(sorts));
    }

    /**
     * 删除列信息
     */
//    @PreAuthorize("@ss.hasPermi('system:column:remove')")
    @Log(title = "列信息", businessType = BusinessType.DELETE)
    @RequestMapping(value = "/remove/{ids}",method = org.springframework.web.bind.annotation.RequestMethod.GET)
    public AjaxResult remove(@PathVariable String ids)
    {
        String[] idarr = ids.split(",");
        return toAjax(noteColumnService.deleteNoteColumnByIds(idarr));
    }

    /**
     * 切换lookup列去重开关，并重算该列存储值及关联的集合运算列
     */
    @Log(title = "列信息", businessType = BusinessType.UPDATE)
    @GetMapping("/deduplicate/{columnId}")
    public AjaxResult deduplicate(@PathVariable("columnId") Long columnId)
    {
        return toAjax(noteColumnService.deduplicate(columnId));
    }
}
