package com.ruoyi.web.controller.system;

import java.util.Arrays;
import java.util.List;
import javax.servlet.http.HttpServletResponse;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import com.ruoyi.system.domain.CloudApp;
import com.ruoyi.system.service.ICloudAppService;
import com.ruoyi.common.utils.poi.ExcelUtil;

/**
 * 应用Controller
 * 
 * @author liuyanghe
 * @date 2025-03-09
 */
@RestController
@RequestMapping("/system/app")
public class CloudAppController extends BaseController
{
    @Autowired
    private ICloudAppService cloudAppService;

    /**
     * 查询应用列表
     */
//    @PreAuthorize("@ss.hasPermi('system:app:list')")
    @GetMapping("/list")
    public AjaxResult list(CloudApp cloudApp)
    {
//        startPage();
//        List<CloudApp> list = cloudAppService.selectCloudAppList(cloudApp);
//        return getDataTable(list);

        List<CloudApp> list = cloudAppService.selectCloudAppList(cloudApp);
        return success(list);
    }

    /**
     * 导出应用列表
     */
    @PreAuthorize("@ss.hasPermi('system:app:export')")
    @Log(title = "应用", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, CloudApp cloudApp)
    {
        List<CloudApp> list = cloudAppService.selectCloudAppList(cloudApp);
        ExcelUtil<CloudApp> util = new ExcelUtil<CloudApp>(CloudApp.class);
        util.exportExcel(response, list, "应用数据");
    }

    /**
     * 获取应用详细信息
     */
    @PreAuthorize("@ss.hasPermi('system:app:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(cloudAppService.selectCloudAppById(id));
    }

    /**
     * 新增应用
     */
    @PreAuthorize("@ss.hasPermi('system:app:add')")
    @Log(title = "应用", businessType = BusinessType.INSERT)
    @PostMapping(value = "/add")
    public AjaxResult add(@Validated @RequestBody CloudApp cloudApp)
    {
        return toAjax(cloudAppService.insertCloudApp(cloudApp));
    }

    /**
     * 修改应用
     */
    @PreAuthorize("@ss.hasPermi('system:app:edit')")
    @Log(title = "应用", businessType = BusinessType.UPDATE)
    @PostMapping(value = "/edit")
    public AjaxResult edit(@Validated @RequestBody CloudApp cloudApp)
    {
        return toAjax(cloudAppService.updateCloudApp(cloudApp));
    }

    /**
     * 删除应用
     */
    @PreAuthorize("@ss.hasPermi('system:app:remove')")
    @Log(title = "应用", businessType = BusinessType.DELETE)
    @RequestMapping(value = "/remove/{ids}",method = org.springframework.web.bind.annotation.RequestMethod.GET)
    public AjaxResult remove(@PathVariable String ids)
    {
        String[] idarr = ids.split(",");
        Long[] longIds = Arrays.stream(idarr).map(Long::valueOf).toArray(Long[]::new);
        cloudAppService.validateBeforeDelete(longIds);
        return toAjax(cloudAppService.deleteCloudAppByIds(idarr));
    }

    /**
     * 删除单个应用
     */
    @PreAuthorize("@ss.hasPermi('system:app:remove')")
    @Log(title = "应用", businessType = BusinessType.DELETE)
    @GetMapping("/delete")
    public AjaxResult delete(Long id)
    {
        cloudAppService.validateBeforeDelete(new Long[]{id});
        return toAjax(cloudAppService.deleteCloudAppById(id));
    }

    /**
     * 删除应用(标准 DELETE 接口,对齐前端 delApp)
     * 支持批量删除:DELETE /system/app/1,2,3
     * Spring MVC 自动将逗号分隔的路径变量转换为 Long[]
     */
    @PreAuthorize("@ss.hasPermi('system:app:remove')")
    @Log(title = "应用", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult removeByIds(@PathVariable Long[] ids)
    {
        cloudAppService.validateBeforeDelete(ids);
        String[] idarr = Arrays.stream(ids).map(String::valueOf).toArray(String[]::new);
        return toAjax(cloudAppService.deleteCloudAppByIds(idarr));
    }

}
