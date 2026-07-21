package com.ruoyi.web.controller.system;

import java.util.List;
import javax.servlet.http.HttpServletResponse;

import com.ruoyi.common.core.domain.entity.SysUser;
import org.springframework.security.access.prepost.PreAuthorize;
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
import com.ruoyi.system.domain.NoteTask;
import com.ruoyi.system.service.INoteTaskService;
import com.ruoyi.common.utils.poi.ExcelUtil;

/**
 * 待办事项Controller
 * 
 * @author liuyanghe
 * @date 2024-06-27
 */
@RestController
@RequestMapping("/system/task")
public class NoteTaskController extends BaseController
{
    @Autowired
    private INoteTaskService noteTaskService;

    /**
     * 查询待办事项列表
     */
//    @PreAuthorize("@ss.hasPermi('note:task:list')")
    @GetMapping("/list")
    public AjaxResult list(NoteTask noteTask)
    {
        List<NoteTask> list = noteTaskService.selectNoteTaskList(noteTask);
        return success(list);
    }


    /**
     * 查询接收任务列表
     */
//    @PreAuthorize("@ss.hasPermi('note:task:list')")
    @GetMapping("/receiveList")
    public AjaxResult receiveList(NoteTask noteTask)
    {
        List<NoteTask> list = noteTaskService.selectReceiveTaskList(noteTask);
        return success(list);
    }



    /**
     * 查询任务栏
     */
//    @PreAuthorize("@ss.hasPermi('note:task:list')")
    @GetMapping("/taskbar")
    public AjaxResult taskbar(NoteTask noteTask)
    {
        List<NoteTask> list = noteTaskService.selectTaskbar(noteTask);
        return success(list);
    }



    /**
     * 查询小组成员
     */
//    @PreAuthorize("@ss.hasPermi('note:task:list')")
    @GetMapping("/teamMembers")
    public AjaxResult teamMembers(NoteTask noteTask)
    {
        List<SysUser> list = noteTaskService.selectTeamMembers(noteTask);
        return success(list);
    }

    /**
     * 导出待办事项列表
     */
    @PreAuthorize("@ss.hasPermi('system:task:export')")
    @Log(title = "待办事项", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, NoteTask noteTask)
    {
        List<NoteTask> list = noteTaskService.selectNoteTaskList(noteTask);
        ExcelUtil<NoteTask> util = new ExcelUtil<NoteTask>(NoteTask.class);
        util.exportExcel(response, list, "待办事项数据");
    }

    /**
     * 获取待办事项详细信息
     */
//    @PreAuthorize("@ss.hasPermi('system:task:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(noteTaskService.selectNoteTaskById(id));
    }

    /**
     * 新增待办事项
     */
//    @PreAuthorize("@ss.hasPermi('system:task:add')")
    @Log(title = "待办事项", businessType = BusinessType.INSERT)
    @PostMapping(value = "/add")
    public AjaxResult add(@Validated @RequestBody NoteTask noteTask)
    {
        return toAjax(noteTaskService.insertNoteTask(noteTask));
    }

    /**
     * 修改待办事项
     */
//    @PreAuthorize("@ss.hasPermi('system:task:edit')")
    @Log(title = "待办事项", businessType = BusinessType.UPDATE)
    @PostMapping(value = "/edit")
    public AjaxResult edit(@RequestBody NoteTask noteTask)
    {
        return toAjax(noteTaskService.updateNoteTask(noteTask));
    }



    /**
     * 接收任务
     */
//    @PreAuthorize("@ss.hasPermi('system:task:edit')")
    @Log(title = "待办事项", businessType = BusinessType.UPDATE)
    @PostMapping(value = "/receiveTask")
    public AjaxResult receiveTask(@RequestBody NoteTask noteTask)
    {
        return toAjax(noteTaskService.receiveNoteTask(noteTask));
    }

    /**
     * 删除待办事项
     */
//    @PreAuthorize("@ss.hasPermi('system:task:remove')")
    @Log(title = "待办事项", businessType = BusinessType.DELETE)
    @RequestMapping(value = "/remove/{ids}",method = org.springframework.web.bind.annotation.RequestMethod.GET)
    public AjaxResult remove(@PathVariable String ids)
    {
//        String[] idarr = ids.split(",");
//        return toAjax(noteTaskService.deleteNoteTaskByIds(idarr));
        return toAjax(noteTaskService.deleteNoteTaskById(Long.valueOf(ids)));
    }
}
