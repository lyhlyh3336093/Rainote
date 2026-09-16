package com.ruoyi.web.controller.system;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;

import com.ruoyi.system.agent.security.AgentOwnershipChecker;
import com.ruoyi.system.domain.NoteRecord;
import com.ruoyi.system.domain.vo.NoteColumnVo;
import com.ruoyi.system.domain.vo.NoteRecordVo;
import com.ruoyi.system.service.impl.ColumnDefaultValueSupport;
import com.ruoyi.common.utils.SecurityUtils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @Autowired
    private AgentOwnershipChecker agentOwnershipChecker;

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
     * <p>
     * 端点入口统一归属校验（审查修复，admin 通行语义不变）：在路由判定之前对目标列执行
     * {@link AgentOwnershipChecker#checkColumnOwnership}——default 直更路径与
     * updateNoteColumn 原路径两分支均有归属校验，消除"附带 name 变更零成本绕过"的
     * 防护不对称（原路径无校验为 pre-existing 面，一并修复）；updateColumnDefault
     * service 内的同名校验保留为防御性二次调用（幂等无害）。
     * <p>
     * U2/KTD6 路由不变量：后端在保存入口对原列（查库）与提交列做字段级 diff——
     * 仅当 diff 只含 property.default 键变更时走 mapper 直更（updateColumnDefault，
     * 零 service 副作用）；携带任何其他字段变更（name/isShow/sort/type 等）仍走
     * NoteColumnServiceImpl.updateNoteColumn 原路径，
     * 保住列改名后的 recomputeRecordNamesForTable 等合法副作用。
     */
//    @PreAuthorize("@ss.hasPermi('system:column:edit')")
    @Log(title = "列信息", businessType = BusinessType.UPDATE)
    @RequestMapping(value = "/update",method = org.springframework.web.bind.annotation.RequestMethod.POST)
    public AjaxResult edit(@Validated @RequestBody  NoteColumnVo noteColumnvo)
    {
        agentOwnershipChecker.checkColumnOwnership(noteColumnvo.getId(), SecurityUtils.getUserId());
        NoteColumn originColumn = noteColumnService.selectNoteColumnById(noteColumnvo.getId());
        if (ColumnDefaultValueSupport.isDefaultOnlyChange(originColumn, noteColumnvo))
        {
            String defaultValue = ColumnDefaultValueSupport.readSubmittedDefault(
                    noteColumnvo.getProperty());
            return toAjax(noteColumnService.updateColumnDefault(
                    noteColumnvo.getId(), defaultValue, SecurityUtils.getUserId()));
        }
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
    @PreAuthorize("@ss.hasPermi('system:column:remove')")
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
    @GetMapping("/deduplicate")
    public AjaxResult deduplicate(@RequestParam("columnId") Long columnId)
    {
        return toAjax(noteColumnService.deduplicate(columnId));
    }
}
