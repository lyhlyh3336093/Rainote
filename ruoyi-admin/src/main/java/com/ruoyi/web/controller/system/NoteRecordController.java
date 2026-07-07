package com.ruoyi.web.controller.system;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;


import com.ruoyi.system.domain.vo.NoteRecordVo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
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
import com.ruoyi.system.domain.NoteRecord;
import com.ruoyi.system.service.INoteRecordService;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.core.page.TableDataInfo;

/**
 * 记录Controller
 * 
 * @author liuyanghe
 * @date 2023-04-12
 */
@RestController
@RequestMapping("/system/record")
public class NoteRecordController extends BaseController
{
    @Autowired
    private INoteRecordService noteRecordService;

    /**
     * 查询记录分页
     */
//    @PreAuthorize("@ss.hasPermi('system:record:list')")
    @GetMapping("/pageList")
    public TableDataInfo pageList(NoteRecordVo noteRecord)
    {
        startPage();
        List<NoteRecord> list = noteRecordService.selectNoteRecordList(noteRecord);
        return getDataTable(list);
    }

    /**
     * 查询记录列表
     */
//    @PreAuthorize("@ss.hasPermi('system:record:list')")
    @GetMapping("/list")
    public AjaxResult list(NoteRecordVo noteRecord)
    {
        List<NoteRecord> list = noteRecordService.selectNoteRecordList(noteRecord);
        return success(list);
    }


    /**
     * 查询数据表中所有记录内的数据列表
     */
//    @PreAuthorize("@ss.hasPermi('system:record:list')")
    @GetMapping("/searchList")
    public AjaxResult searchList(NoteRecordVo noteRecordVo)
    {
        List<Map<String,Object>> list = noteRecordService.selectNoteRecordDataList(noteRecordVo);
        return success(list);
    }


    /**
     * 查询数据表中所有记录内的数据列表
     */
//    @PreAuthorize("@ss.hasPermi('system:record:list')")
    @GetMapping("/dataList")
    public AjaxResult dataList(Long dwtableId)
    {
        List<Map<String,Object>> list = noteRecordService.selectDataListByDwtableId(dwtableId);
        return success(list);
    }


    /**
     * 导出记录列表
     */
//    @PreAuthorize("@ss.hasPermi('system:record:export')")
    @Log(title = "记录", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, NoteRecordVo noteRecord)
    {
        List<NoteRecord> list = noteRecordService.selectNoteRecordList(noteRecord);
        ExcelUtil<NoteRecord> util = new ExcelUtil<NoteRecord>(NoteRecord.class);
        util.exportExcel(response, list, "记录数据");
    }

    /**
     * 获取记录详细信息
     */
//    @PreAuthorize("@ss.hasPermi('system:record:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(noteRecordService.selectNoteRecordById(id));
    }

    /**
     * 获取记录内的表格数据
     */
//    @PreAuthorize("@ss.hasPermi('system:record:query')")
    @GetMapping(value = "/data/{id}")
    public AjaxResult getData(@PathVariable("id") Long id)
    {
        return success(noteRecordService.selectNoteRecordData(id));
    }



    /**
     * 新增记录
     */
    @Log(title = "记录", businessType = BusinessType.INSERT)
    @PostMapping(value = "/add")
    public AjaxResult add(@Validated @RequestBody NoteRecordVo noteRecordvo)
    {
        NoteRecord noteRecord = new NoteRecord();
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
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
        if(noteRecordvo.getItems()!=null){
            items = noteRecordvo.getItems();
        }
        return success(noteRecordService.insertNoteRecord(noteRecord,items));
    }

    /**
     * 修改记录
     */
    //    @PreAuthorize("@ss.hasPermi('system:note:edit')")
//    @Log(title = "记录", businessType = BusinessType.UPDATE)
    @RequestMapping(value = "/update",method = org.springframework.web.bind.annotation.RequestMethod.POST)
    public AjaxResult update(@Validated @RequestBody NoteRecordVo noteRecordvo)
    {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
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
        if(noteRecordvo.getItems()!=null){
            items = noteRecordvo.getItems();
        }
        return toAjax(noteRecordService.updateNoteRecord(noteRecord,items));
    }



    /**
     * 修改记录排序
     */
    //    @PreAuthorize("@ss.hasPermi('system:note:edit')")
//    @Log(title = "记录", businessType = BusinessType.UPDATE)
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
        return toAjax(noteRecordService.updateSort(noteRecord,sorts));
    }




    /**
     * 删除记录
     */
    @Log(title = "笔记", businessType = BusinessType.DELETE)
    @RequestMapping(value = "/remove/{ids}",method = org.springframework.web.bind.annotation.RequestMethod.GET)
    public AjaxResult remove(@PathVariable String ids)
    {
        String[] idarr = ids.split(",");
        return toAjax(noteRecordService.deleteNoteRecordByIds(idarr));
    }

    /**
     * 一次性回填所有数据表所有记录的 name（管理端点，无前端改动）
     * 调用 recomputeAllRecordNames，逐表独立事务重算行名称派生投影。
     */
    @PreAuthorize("@ss.hasPermi('system:record:backfill')")
    @Log(title = "行名称回填", businessType = BusinessType.UPDATE)
    @PostMapping("/backfillNames")
    public AjaxResult backfillNames()
    {
        int updated = noteRecordService.recomputeAllRecordNames();
        return success(updated);
    }
}
