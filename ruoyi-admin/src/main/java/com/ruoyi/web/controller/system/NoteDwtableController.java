package com.ruoyi.web.controller.system;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.annotation.RateLimiter;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.enums.LimitType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.agent.security.AgentOwnershipChecker;
import com.ruoyi.system.domain.NoteDwtable;
import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult;
import com.ruoyi.system.domain.dto.ParsedInsert;
import com.ruoyi.system.service.INoteDwtableService;
import com.ruoyi.system.service.INoteDwtableExportService;
import com.ruoyi.system.service.INoteDwtableExcelImportService;
import com.ruoyi.system.service.INoteDwtableImportService;
import com.ruoyi.system.service.impl.SqlInsertParser;
import com.ruoyi.system.service.impl.ZipImportExtractor;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.core.page.TableDataInfo;

/**
 * 多维表格数据表Controller
 * 
 * @author liuyanghe
 * @date 2023-04-03
 */
@RestController
@RequestMapping("/system/dwtable")
public class NoteDwtableController extends BaseController
{
    @Autowired
    private INoteDwtableService noteDwtableService;

    @Autowired
    private INoteDwtableExportService noteDwtableExportService;

    @Autowired
    private INoteDwtableImportService noteDwtableImportService;

    @Autowired
    private INoteDwtableExcelImportService noteDwtableExcelImportService;

    @Autowired
    private AgentOwnershipChecker agentOwnershipChecker;

    /**
     * 查询多维表格数据表列表分页
     */
//    @PreAuthorize("@ss.hasPermi('system:dwtable:list')")
    @GetMapping("/pageList")
    public TableDataInfo pageList(NoteDwtable noteDwtable)
    {
        startPage();
        List<NoteDwtable> list = noteDwtableService.selectNoteDwtableList(noteDwtable);
        return getDataTable(list);
    }


    /**
     * 查询多维表格数据表列表
     */
//    @PreAuthorize("@ss.hasPermi('system:dwtable:list')")
    @GetMapping("/list")
    public AjaxResult list(NoteDwtable noteDwtable)
    {
        List<NoteDwtable> list = noteDwtableService.selectNoteDwtableList(noteDwtable);
        return success(list);
    }

    /**
     * 导出多维表格数据表列表
     */
//    @PreAuthorize("@ss.hasPermi('system:dwtable:export')")
    @Log(title = "多维表格数据表", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, NoteDwtable noteDwtable)
    {
        List<NoteDwtable> list = noteDwtableService.selectNoteDwtableList(noteDwtable);
        ExcelUtil<NoteDwtable> util = new ExcelUtil<NoteDwtable>(NoteDwtable.class);
        util.exportExcel(response, list, "多维表格数据表数据");
    }

    /**
     * 导出多维表格数据（Excel/SQL zip 下载）
     * <p>
     * 把 noteId 下全部数据表 pivot 成行×列矩阵，按列类型渲染规则输出
     * （关联/派生类列双列、记录 id 列必出、超限表按行分片），打包为 zip 下载。
     * <p>
     * 安全（KTD8-KTD11）：归属校验、按用户限流、PII 审计、SQL 转义、zip slip 防护。
     *
     * @param response HTTP 响应（写入 zip 字节）
     * @param noteId   多维表格（笔记）ID
     * @param format   导出格式：excel / sql
     */
    @Log(title = "多维表格导出", businessType = BusinessType.EXPORT)
    @RateLimiter(time = 60, count = 10, limitType = LimitType.USER)
    @PostMapping("/exportData")
    public void exportData(HttpServletResponse response,
            @RequestParam("noteId") Long noteId,
            @RequestParam(value = "format", defaultValue = "excel") String format)
    {
        Long userId = SecurityUtils.getUserId();
        String userName = SecurityUtils.getUsername();
        byte[] zipBytes = noteDwtableExportService.export(noteId, format, userId, userName);
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=\"multitable-export.zip\"");
        response.setContentLength(zipBytes.length);
        try
        {
            response.getOutputStream().write(zipBytes);
            response.getOutputStream().flush();
        }
        catch (Exception e)
        {
            logger.error("导出下载失败 noteId={}, error={}", noteId, e.getMessage(), e);
        }
    }

    /**
     * 导入多维表格数据（.sql / .zip，对称导出能力）
     * <p>
     * 安全（KTD6）：归属校验复用 {@link AgentOwnershipChecker}（admin 通行）、
     * 额外断言 dwtable.noteId 与 noteId 匹配、按用户限流（对称导出 60s/10 次）。
     * .sql 直接解析；.zip 内存解压后按文件名升序逐个解析（R5/R8）；
     * 解析产物交由导入服务在单事务内写入（R19）；
     * 失败抛 {@link ServiceException} 由全局异常处理返回 HTTP 200 + JSON（R20）。
     *
     * @param noteId    多维表格（笔记）ID
     * @param dwtableId 目标数据表ID
     * @param file      上传的 .sql 或 .zip 文件
     * @return 成功导入的记录数
     */
    @Log(title = "多维表格导入", businessType = BusinessType.IMPORT)
    @RateLimiter(time = 60, count = 10, limitType = LimitType.USER)
    @PostMapping("/importData")
    public AjaxResult importData(@RequestParam("noteId") Long noteId,
            @RequestParam("dwtableId") Long dwtableId,
            @RequestParam("file") MultipartFile file) throws IOException
    {
        Long userId = SecurityUtils.getUserId();
        // KTD6：归属校验（admin 通行）+ noteId↔dwtableId 匹配断言
        agentOwnershipChecker.checkDwtableOwnership(dwtableId, userId);
        NoteDwtable dwtable = noteDwtableService.selectNoteDwtableById(dwtableId);
        if (dwtable == null || !noteId.equals(dwtable.getNoteId()))
        {
            throw new ServiceException("导入失败：多维表与笔记不匹配");
        }
        if (file == null || file.isEmpty())
        {
            throw new ServiceException("导入失败：上传文件为空");
        }
        byte[] bytes = file.getBytes();
        String filename = file.getOriginalFilename();
        List<ParsedInsert> parsedList;
        if (filename != null && filename.toLowerCase().endsWith(".sql"))
        {
            parsedList = SqlInsertParser.parse(bytes);
        }
        else if (filename != null && filename.toLowerCase().endsWith(".zip"))
        {
            List<byte[]> sqlFiles = ZipImportExtractor.extract(bytes);
            parsedList = new ArrayList<>();
            for (byte[] sqlBytes : sqlFiles)
            {
                parsedList.addAll(SqlInsertParser.parse(sqlBytes));
            }
        }
        else
        {
            throw new ServiceException("不支持的文件类型，仅支持 .sql / .zip");
        }
        int recordCount = noteDwtableImportService.importData(noteId, dwtableId, parsedList, userId);
        Map<String, Object> data = new HashMap<>();
        data.put("recordCount", recordCount);
        return AjaxResult.success().put("data", data);
    }

    /**
     * 多维表格 Excel 导入预检（阶段一，不写库）
     * <p>
     * 解析 .xlsx + 列映射 + 关联文本匹配，返回缺参与影响面清单
     * （缺参/歧义/默认值填充/新选项/对称写入跨表影响/忽略提示/文件指纹），
     * 由前端分流：有缺参或歧义打开补参弹框，仅影响面信息轻量确认，完全干净直接导入（R13）。
     * <p>
     * 安全（R23-R25）：归属校验复用 {@link AgentOwnershipChecker}（admin 通行）、
     * 额外断言 dwtable.noteId 与 noteId 匹配、按用户限流（对称导入 60s/10 次）；
     * 预检清单含单元格派生值，禁入 sys_oper_log（isSaveRequestData/isSaveResponseData 均关闭）。
     *
     * @param noteId    多维表格（笔记）ID
     * @param dwtableId 目标数据表ID
     * @param file      上传的 .xlsx 文件
     * @return 预检清单（data 字段，含文件指纹供阶段二比对）
     */
    @Log(title = "多维表格Excel导入预检", businessType = BusinessType.IMPORT,
            isSaveRequestData = false, isSaveResponseData = false)
    @RateLimiter(time = 60, count = 10, limitType = LimitType.USER)
    @PostMapping("/precheckExcelImport")
    public AjaxResult precheckExcelImport(@RequestParam("noteId") Long noteId,
            @RequestParam("dwtableId") Long dwtableId,
            @RequestParam("file") MultipartFile file)
    {
        Long userId = SecurityUtils.getUserId();
        // R23：归属校验（admin 通行）+ noteId↔dwtableId 匹配断言（照 importData 模式）
        agentOwnershipChecker.checkDwtableOwnership(dwtableId, userId);
        NoteDwtable dwtable = noteDwtableService.selectNoteDwtableById(dwtableId);
        if (dwtable == null || !noteId.equals(dwtable.getNoteId()))
        {
            throw new ServiceException("预检失败：多维表与笔记不匹配");
        }
        if (file == null || file.isEmpty())
        {
            throw new ServiceException("预检失败：上传文件为空");
        }
        ExcelImportPrecheckResult result =
                noteDwtableExcelImportService.precheck(noteId, dwtableId, file, userId);
        return AjaxResult.success().put("data", result);
    }

    /**
     * 获取多维表格数据表详细信息
     */
//    @PreAuthorize("@ss.hasPermi('system:dwtable:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(noteDwtableService.selectNoteDwtableById(id));
    }


    /**
     * 获取多维表格数据表表内数据
     */
//    @PreAuthorize("@ss.hasPermi('system:dwtable:query')")
    @GetMapping(value = "/data/{id}")
    public AjaxResult getData(@PathVariable("id") Long id)
    {
        Map<String,Object> result = new HashMap<>();
        result = noteDwtableService.selectNoteDwtableDataById(id);
        return success(result);
    }


    /**
     * 新增多维表格数据表
     */
//    @PreAuthorize("@ss.hasPermi('system:dwtable:add')")
    @Log(title = "多维表格数据表", businessType = BusinessType.INSERT)
    @PostMapping(value = "/add")
    public AjaxResult add(@Validated @RequestBody  NoteDwtable noteDwtable)
    {
        return success(noteDwtableService.insertNoteDwtable(noteDwtable));
    }

    /**
     * 修改多维表格数据表
     */
    @PreAuthorize("@ss.hasPermi('system:dwtable:edit')")
    @Log(title = "多维表格数据表", businessType = BusinessType.UPDATE)
    @PostMapping(value = "/edit")
    public AjaxResult edit(@Validated @RequestBody NoteDwtable noteDwtable)
    {
        return toAjax(noteDwtableService.updateNoteDwtable(noteDwtable));
    }

    /**
     * 删除多维表格数据表
     */
    @PreAuthorize("@ss.hasPermi('system:dwtable:remove')")
    @Log(title = "多维表格数据表", businessType = BusinessType.DELETE)
    @RequestMapping(value = "/remove/{ids}",method = org.springframework.web.bind.annotation.RequestMethod.GET)
    public AjaxResult remove(@PathVariable String ids)
    {
        String[] idarr = ids.split(",");
        return toAjax(noteDwtableService.deleteNoteDwtableByIds(idarr));
    }
}
