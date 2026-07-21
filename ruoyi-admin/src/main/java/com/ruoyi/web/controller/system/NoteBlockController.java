package com.ruoyi.web.controller.system;

import java.util.List;
import javax.servlet.http.HttpServletResponse;


import com.ruoyi.system.domain.vo.NoteBlockVo;
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
import com.ruoyi.system.domain.NoteBlock;
import com.ruoyi.system.service.INoteBlockService;
import com.ruoyi.common.utils.poi.ExcelUtil;

/**
 * 块元素Controller
 * 
 * @author liuyanghe
 * @date 2023-05-17
 */
@RestController
@RequestMapping("/system/block")
public class NoteBlockController extends BaseController
{
    @Autowired
    private INoteBlockService noteBlockService;

    /**
     * 查询块元素列表
     */
//    @PreAuthorize("@ss.hasPermi('system:block:list')")
    @GetMapping("/list")
    public AjaxResult list(NoteBlock noteBlock)
    {

        List<NoteBlock> list = noteBlockService.selectNoteBlockList(noteBlock);
        return success(list);
    }

    /**
     * 导出块元素列表
     */
//    @PreAuthorize("@ss.hasPermi('system:block:export')")
    @Log(title = "块元素", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, NoteBlock noteBlock)
    {
        List<NoteBlock> list = noteBlockService.selectNoteBlockList(noteBlock);
        ExcelUtil<NoteBlock> util = new ExcelUtil<NoteBlock>(NoteBlock.class);
        util.exportExcel(response, list, "块元素数据");
    }

    /**
     * 获取块元素详细信息
     */
//    @PreAuthorize("@ss.hasPermi('system:block:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(noteBlockService.selectNoteBlockById(id));
    }

    /**
     * 新增块元素
     */
//    @PreAuthorize("@ss.hasPermi('system:block:add')")
//    @Log(title = "块元素", businessType = BusinessType.INSERT)
    @PostMapping(value = "/add")
    public AjaxResult add(@Validated @RequestBody NoteBlock noteBlock)
    {
        return success(noteBlockService.insertNoteBlock(noteBlock));
    }

    /**
     * 修改块元素
     */
//    @PreAuthorize("@ss.hasPermi('system:block:edit')")
    @Log(title = "块元素", businessType = BusinessType.UPDATE)
    @RequestMapping(value = "/update",method = org.springframework.web.bind.annotation.RequestMethod.POST)
    public AjaxResult edit(@Validated @RequestBody NoteBlock noteBlock)
    {
        return toAjax(noteBlockService.updateNoteBlock(noteBlock));
    }


    /**
     * 对多维表格进行双向关联
     */
//    @PreAuthorize("@ss.hasPermi('system:block:edit')")
//    @Log(title = "块元素", businessType = BusinessType.UPDATE)
    @RequestMapping(value = "/linkToDwtable",method = org.springframework.web.bind.annotation.RequestMethod.POST)
    public AjaxResult linkToDwtable(@Validated @RequestBody NoteBlockVo noteBlockVo)
    {
        return toAjax(noteBlockService.linkToDwtable(noteBlockVo));
    }


    /**
     * 解除对多维表格的双向关联
     */
//    @PreAuthorize("@ss.hasPermi('system:block:edit')")
//    @Log(title = "块元素", businessType = BusinessType.UPDATE)
    @RequestMapping(value = "/removeLink",method = org.springframework.web.bind.annotation.RequestMethod.POST)
    public AjaxResult removeLink(@Validated @RequestBody NoteBlockVo noteBlockVo)
    {
        return toAjax(noteBlockService.removeLink(noteBlockVo));
    }



    /**
     * 批量修改块元素
     */
//    @PreAuthorize("@ss.hasPermi('system:block:edit')")
    @Log(title = "块元素", businessType = BusinessType.UPDATE)
    @RequestMapping(value = "/updateBatch",method = org.springframework.web.bind.annotation.RequestMethod.POST)
    public AjaxResult updateBatch(@Validated @RequestBody List<NoteBlock> noteBlocks)
    {
        int result=0;
        for(NoteBlock noteBlock:noteBlocks){
            int num = noteBlockService.updateNoteBlock(noteBlock);
            result=result+num;
        }
        return toAjax(result);
    }


    /**
     * 删除块元素
     */
//    @PreAuthorize("@ss.hasPermi('system:block:remove')")
    @Log(title = "块元素", businessType = BusinessType.DELETE)
    @RequestMapping(value = "/remove/{ids}",method = org.springframework.web.bind.annotation.RequestMethod.GET)
    public AjaxResult remove(@PathVariable String ids)
    {
        String[] idarr = ids.split(",");
        return toAjax(noteBlockService.deleteNoteBlockByIds(idarr));
    }
}
