package com.ruoyi.web.controller.system;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import javax.servlet.http.HttpServletResponse;

import com.ruoyi.common.core.domain.entity.SysUser;
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
import com.ruoyi.system.domain.NoteNote;
import com.ruoyi.system.domain.vo.NoteNoteVo;
import com.ruoyi.system.service.INoteNoteService;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.utils.file.ExcelImportUtils;

/**
 * 笔记Controller
 * 
 * @author ruoyi
 * @date 2023-03-09
 */
@RestController
@RequestMapping("/system/note")
public class NoteNoteController extends BaseController
{
    @Autowired
    private INoteNoteService noteNoteService;

    /**
     * 查询所有笔记列表
     */
//    @PreAuthorize("@ss.hasPermi('note:note:query')")
    @GetMapping("/list")
    public AjaxResult list(NoteNote noteNote)
    {

        //TODO:我找到了属于我们自己的鉴权方式，抛弃注解的形式，用用户id来进行鉴权。总体分两个方面，
        // 一：用户-角色-笔记（菜单）的路子可以找出一批可以看的笔记，
        // 二：根据作者和用户id的匹配，筛选出一批笔记，
        // 二者相结合，来筛选用户拥有权限的笔记（其中第二种方式可以扩展，如：作者和责任编辑的关系，书记和秘书的关系，这种情况可以做类似于分组的方式）

        List<NoteNote> list = noteNoteService.selectNoteNoteList(noteNote,getUserId());
        return success(list);
    }


    /**
     * 搜索笔记
     */
//    @PreAuthorize("@ss.hasPermi('note:note:query')")
    @GetMapping("/searchNoteList")
    public AjaxResult searchNoteList(NoteNoteVo noteNote)
    {
        List<NoteNote> list = noteNoteService.searchNoteList(noteNote,getUserId());
        return success(list);
    }


    /**
     * 查询笔记系统菜单树
     */
    @PreAuthorize("@ss.hasPermi('note:note:query')")
    @GetMapping("/menuList")
    public AjaxResult menuList(NoteNote noteNote)
    {

        List<NoteNote> list = noteNoteService.selectNoteMenuList(noteNote,getUserId());
        return success(list);
    }


    /**
     * 查询笔记系统菜单和按钮权限
     */
    @PreAuthorize("@ss.hasPermi('note:note:query')")
    @GetMapping("/menuAuthList")
    public AjaxResult menuAuthList(NoteNote noteNote)
    {

        List<NoteNote> list = noteNoteService.selectNoteMenuAuthList(noteNote,getUserId());
        return success(list);
    }


    /**
     * 查询用户拥有的数据权限
     */
    @PreAuthorize("@ss.hasPermi('note:note:query')")
    @GetMapping("/userDataList")
    public AjaxResult userDataList(NoteNote noteNote)
    {

        List<NoteNote> list = noteNoteService.selectUserNoteList(noteNote,getUserId());
        return success(list);
    }



    /**
     * 查询笔记列表分页
     */
    @PreAuthorize("@ss.hasPermi('note:note:query')")
    @GetMapping("/pageList")
    public TableDataInfo pageList(NoteNote noteNote)
    {
        startPage();
        List<NoteNote> list = noteNoteService.selectNoteNoteList(noteNote,getUserId());
        return getDataTable(list);
    }



    /**
     * 查询笔记回收站列表
     */
    @PreAuthorize("@ss.hasPermi('note:note:query')")
    @GetMapping("/garbageList")
    public TableDataInfo garbageList(NoteNote noteNote)
    {
        startPage();
        List<NoteNote> list = noteNoteService.selectNoteGarbageList(noteNote);
        return getDataTable(list);
    }



    /**
     * 查询模板列表
     */
//    @PreAuthorize("@ss.hasPermi('note:note:query')")
    @GetMapping("/templateList")
    public TableDataInfo templateList(NoteNote noteNote)
    {
        startPage();
        List<NoteNote> list = noteNoteService.selectNoteTemplateList(noteNote);
        return getDataTable(list);
    }



    /**
     * 导出笔记列表
     */
    @PreAuthorize("@ss.hasPermi('note:note:export')")
    @Log(title = "笔记", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, NoteNote noteNote)
    {
        List<NoteNote> list = noteNoteService.selectNoteNoteList(noteNote,getUserId());
        ExcelUtil<NoteNote> util = new ExcelUtil<NoteNote>(NoteNote.class);
        util.exportExcel(response, list, "笔记数据");
    }


    /**
     * 导入数据
     */
    @PreAuthorize("@ss.hasPermi('note:note:export')")
    @Log(title = "笔记", businessType = BusinessType.EXPORT)
    @PostMapping("/import")
    public void importNote(InputStream is, NoteNote noteNote,String filePath)
    {
//        List<NoteNote> list = noteNoteService.selectNoteNoteList(noteNote,getUserId());
        ExcelUtil<NoteNote> util = new ExcelUtil<NoteNote>(NoteNote.class);
        //目前先只做导入本身.
        noteNoteService.importNote(is,filePath);
        System.out.println("导入内容为:"+is.toString());

    }



    /**
     * 获取笔记详细信息
     */
    @PreAuthorize("@ss.hasPermi('note:note:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(noteNoteService.selectNoteNoteById(id));
    }


    /**
     * 获取笔记展示信息
     */
    @PreAuthorize("@ss.hasPermi('note:note:query')")
    @GetMapping(value = "/data/{id}")
    public AjaxResult getNoteData(@PathVariable("id") Long id)
    {
        return success(noteNoteService.selectNoteDataById(id));
    }


    /**
     * 获取多维表格数据表下的数据表列表
     */
    @PreAuthorize("@ss.hasPermi('note:note:query')")
    @GetMapping(value = "/getDWTables/{id}")
    public AjaxResult getDWTables(@PathVariable("id") Long id)
    {
        return success(noteNoteService.selectNoteNoteById(id));
    }

    /**
     * 新增笔记
     */
//    @PreAuthorize("@ss.hasPermi('note:note:add')")
    @Log(title = "笔记", businessType = BusinessType.INSERT)
    @PostMapping(value = "/add")
    public AjaxResult add(@Validated @RequestBody NoteNote noteNote)
    {
        noteNote.setAuth(getUserId());
        return success(noteNoteService.insertNoteNote(noteNote));
    }

    /**
     * 修改笔记
     */
    @PreAuthorize("@ss.hasPermi('note:note:edit')")
    @Log(title = "笔记", businessType = BusinessType.UPDATE)
    @RequestMapping(value = "/user/update",method = org.springframework.web.bind.annotation.RequestMethod.POST)
    public AjaxResult edit(@Validated @RequestBody NoteNote noteNote)
    {
        return toAjax(noteNoteService.updateNoteNote(noteNote));
    }



    /**
     * 另存为笔记
     */
    @PreAuthorize("@ss.hasPermi('note:note:edit')")
    @Log(title = "笔记", businessType = BusinessType.UPDATE)
    @RequestMapping(value = "/saveAsNewNote",method = org.springframework.web.bind.annotation.RequestMethod.POST)
    public AjaxResult saveAsNewNote(@Validated @RequestBody NoteNote noteNote)
    {
        return success(noteNoteService.saveAsNewNote(noteNote));
    }


    /**
     * 更新用户首次登录标识
     */
    @Log(title = "更新用户首次登录标识", businessType = BusinessType.UPDATE)
    @GetMapping(value = "/updateFirstLogin/{userId}")
    public AjaxResult updateFirstLogin(@PathVariable("userId") Long userId)
    {
        return success(noteNoteService.updateFirstLogin(userId));
    }


    /**
     * 删除笔记
     */
    @PreAuthorize("@ss.hasPermi('note:note:delete')")
    @Log(title = "笔记", businessType = BusinessType.DELETE)
    @RequestMapping(value = "/remove/{ids}",method = org.springframework.web.bind.annotation.RequestMethod.GET)
    public AjaxResult remove(@PathVariable String ids)
    {
        String[] idarr = ids.split(",");
        return toAjax(noteNoteService.deleteNoteNoteByIds(idarr));
    }


    /**
     * 从回收站删除
     */
    @PreAuthorize("@ss.hasPermi('note:note:delete')")
    @Log(title = "笔记", businessType = BusinessType.DELETE)
    @RequestMapping(value = "/clearGarbage/{ids}",method = org.springframework.web.bind.annotation.RequestMethod.GET)
    public AjaxResult clearGarbage(@PathVariable String ids)
    {
        String[] idarr = ids.split(",");
        return toAjax(noteNoteService.deleteNoteFromGarbageByIds(idarr));
    }

    /**
     * 重置系统
     */
    @PreAuthorize("@ss.hasPermi('note:note:delete')")
    @Log(title = "笔记", businessType = BusinessType.DELETE)
    @RequestMapping(value = "/removeAll",method = org.springframework.web.bind.annotation.RequestMethod.GET)
    public AjaxResult removeAll()
    {
        //只有管理员有权限
        if(getUserId()==1){
            return toAjax(noteNoteService.removeAll());
        }else {
            return error("只有管理员才可执行此操作");
        }
    }


    /**
     * 清空所有数据
     */
    @PreAuthorize("@ss.hasPermi('note:note:delete')")
    @Log(title = "笔记", businessType = BusinessType.DELETE)
    @RequestMapping(value = "/removeAllData",method = org.springframework.web.bind.annotation.RequestMethod.GET)
    public AjaxResult removeAllData()
    {
        //只有管理员有权限
        if(getUserId()==1){
            return toAjax(noteNoteService.removeAll());
        }else {
            return error("只有管理员才可执行此操作");
        }


    }


    /**
     * 恢复笔记
     */
    @RequestMapping(value = "/recoverNote/{ids}",method = org.springframework.web.bind.annotation.RequestMethod.GET)
    public AjaxResult recoverNote(@PathVariable String ids)
    {
        String[] idarr = ids.split(",");
        return toAjax(noteNoteService.recoverNoteNoteByIds(idarr));
    }

    /**
     * 查询收藏列表
     */
    @GetMapping("/collectionList")
    public TableDataInfo collectionList(NoteNote noteNote)
    {
        startPage();
        List<NoteNote> list = noteNoteService.selectCollectionList(noteNote);
        return getDataTable(list);
    }

    /**
     * 取消收藏
     */
    @Log(title = "取消收藏", businessType = BusinessType.DELETE)
    @RequestMapping(value = "/cancelCollection/{id}",method = org.springframework.web.bind.annotation.RequestMethod.GET)
    public AjaxResult cancelCollection(@PathVariable String id)
    {
        return toAjax(noteNoteService.cancelCollectionByIds(id));
    }

    /**
     * 收藏笔记
     */
    @RequestMapping(value = "/collectionNote/{id}",method = org.springframework.web.bind.annotation.RequestMethod.GET)
    public AjaxResult collectionNote(@PathVariable String id)
    {
        return toAjax(noteNoteService.collectionNoteByIds(id));
    }


    /**
     * 设为模板
     */
//    @PreAuthorize("@ss.hasPermi('note:note:edit')")
//    @Log(title = "笔记", businessType = BusinessType.UPDATE)
    @RequestMapping(value = "/setTemplate/{id}",method = org.springframework.web.bind.annotation.RequestMethod.GET)
    public AjaxResult setTemplate(@PathVariable String id)
    {
        return toAjax(noteNoteService.setTemplate(id));
    }


    /**
     * 撤销模板
     */
//    @PreAuthorize("@ss.hasPermi('note:note:edit')")
//    @Log(title = "笔记", businessType = BusinessType.UPDATE)
    @RequestMapping(value = "/removeTemplate/{id}",method = org.springframework.web.bind.annotation.RequestMethod.GET)
    public AjaxResult removeTemplate(@PathVariable String id)
    {
        return toAjax(noteNoteService.removeTemplate(id));
    }


}
