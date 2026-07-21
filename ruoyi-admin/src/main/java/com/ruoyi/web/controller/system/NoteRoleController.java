package com.ruoyi.web.controller.system;

import java.util.List;
import javax.servlet.http.HttpServletResponse;

import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.service.INoteRoleDeptService;
import com.ruoyi.system.service.ISysDeptService;
import com.ruoyi.system.service.ISysUserService;
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
import com.ruoyi.system.domain.NoteRole;
import com.ruoyi.system.service.INoteRoleService;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.core.page.TableDataInfo;

/**
 * 笔记角色Controller
 *
 * @author liuyanghe
 * @date 2023-08-26
 */
@RestController
@RequestMapping("/system/noteRole")
public class NoteRoleController extends BaseController
{
    @Autowired
    private INoteRoleService noteRoleService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private SysPermissionService permissionService;

    @Autowired
    private ISysUserService userService;

    @Autowired
    private INoteRoleDeptService noteRoleDeptService;


    @Autowired
    private ISysDeptService deptService;

    /**
     * 查询笔记角色列表
     */
    @PreAuthorize("@ss.hasPermi('note:role:list')")
    @GetMapping("/list")
    public TableDataInfo list(NoteRole noteRole)
    {
        startPage();
        //因为换成了登录用户来获取权限，所以这里要用userid
        List<NoteRole> list = noteRoleService.selectNoteRoleListByUserId(noteRole,getUserId());
        return getDataTable(list);
    }

    /**
     * 导出笔记角色列表
     */
    @PreAuthorize("@ss.hasPermi('note:role:export')")
    @Log(title = "笔记角色", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, NoteRole noteRole)
    {
        List<NoteRole> list = noteRoleService.selectNoteRoleList(noteRole);
        ExcelUtil<NoteRole> util = new ExcelUtil<NoteRole>(NoteRole.class);
        util.exportExcel(response, list, "笔记角色数据");
    }

    /**
     * 获取笔记角色详细信息
     */
    @PreAuthorize("@ss.hasPermi('note:role:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(noteRoleService.selectNoteRoleById(id));
    }



    /**
     * 新增笔记角色
     */
//    @PreAuthorize("@ss.hasPermi('note:role:add')")
    @Log(title = "笔记角色", businessType = BusinessType.INSERT)
    @PostMapping(value = "/add")
    public AjaxResult add(@Validated @RequestBody NoteRole noteRole)
    {

        if (UserConstants.NOT_UNIQUE.equals(noteRoleService.checkNoteRoleNameUnique(noteRole)))
        {
            return error("新增角色'" + noteRole.getRoleName() + "'失败，角色名称已存在");
        }
        else if (UserConstants.NOT_UNIQUE.equals(noteRoleService.checkNoteRoleKeyUnique(noteRole)))
        {
            return error("新增角色'" + noteRole.getRoleName() + "'失败，角色权限已存在");
        }
        noteRole.setCreateBy(getUsername());
        return success(noteRoleService.insertNoteRole(noteRole));
    }

    /**
     * 修改笔记角色
     */
//    @PreAuthorize("@ss.hasPermi('note:note:edit')")
    @Log(title = "笔记角色", businessType = BusinessType.UPDATE)
    @PostMapping(value = "/edit")
    public AjaxResult edit(@RequestBody NoteRole noteRole)
    {

        noteRoleService.checkRoleAllowed(noteRole);
        noteRoleService.checkRoleDataScope(noteRole.getId());
        if (UserConstants.NOT_UNIQUE.equals(noteRoleService.checkNoteRoleNameUnique(noteRole)))
        {
            return error("修改角色'" + noteRole.getRoleName() + "'失败，角色名称已存在");
        }
        else if (UserConstants.NOT_UNIQUE.equals(noteRoleService.checkNoteRoleKeyUnique(noteRole)))
        {
            return error("修改角色'" + noteRole.getRoleName() + "'失败，角色权限已存在");
        }
        noteRole.setUpdateBy(getUsername());

        if (noteRoleService.updateNoteRole(noteRole) > 0)
        {
            // 更新缓存用户权限
            LoginUser loginUser = getLoginUser();
            if (StringUtils.isNotNull(loginUser.getUser()) && !loginUser.getUser().isAdmin())
            {
                loginUser.setPermissions(permissionService.getMenuPermission(loginUser.getUser()));
                loginUser.setUser(userService.selectUserByUserName(loginUser.getUser().getUserName()));
                tokenService.setLoginUser(loginUser);
            }
            return success();
        }
        return error("修改角色'" + noteRole.getRoleName() + "'失败，请联系管理员");
//        return toAjax(noteRoleService.updateNoteRole(noteRole));
    }


    /**
     * 修改保存数据权限
     */
//    @PreAuthorize("@ss.hasPermi('note:role:edit')")
    @Log(title = "角色管理", businessType = BusinessType.UPDATE)
    @PostMapping(value="/dataScope")
    public AjaxResult dataScope(@RequestBody NoteRole noteRole)
    {
        noteRoleService.checkRoleAllowed(noteRole);
        noteRoleService.checkRoleDataScope(noteRole.getId());
        return toAjax(noteRoleService.authDataScope(noteRole));
    }


    /**
     * 状态修改
     */
//    @PreAuthorize("@ss.hasPermi('note:role:edit')")
    @Log(title = "角色管理", businessType = BusinessType.UPDATE)
    @PostMapping(value ="/changeStatus")
    public AjaxResult changeStatus(@RequestBody NoteRole role)
    {
        noteRoleService.checkRoleAllowed(role);
        noteRoleService.checkRoleDataScope(role.getId());
        role.setUpdateBy(getUsername());
        return toAjax(noteRoleService.updateRoleStatus(role));
    }


    /**
     * 获取角色选择框列表
     */
//    @PreAuthorize("@ss.hasPermi('note:role:query')")
    @GetMapping("/optionselect")
    public AjaxResult optionselect()
    {
        return success(noteRoleService.selectNoteRoleAll());
    }



    /**
     * 获取对应角色部门树列表
     */
//    @PreAuthorize("@ss.hasPermi('note:role:query')")
    @GetMapping(value = "/deptTree/{roleId}")
    public AjaxResult deptTree(@PathVariable("roleId") Long roleId)
    {
        AjaxResult ajax = AjaxResult.success();
        ajax.put("checkedKeys", noteRoleDeptService.selectNoteRoleDeptByRoleId(roleId));
        ajax.put("depts", deptService.selectDeptTreeList(new SysDept()));
        return ajax;
    }


    /**
     * 查询已分配用户角色列表
     */
//    @PreAuthorize("@ss.hasPermi('note:role:list')")
    @GetMapping(value = "/allocatedList")
    public AjaxResult allocatedList(NoteRole role)
    {
        List<SysUser> list = userService.selectAllocatedNoteList(role);
        return success(list);
    }

    /**
     * 删除笔记角色
     */
//    @PreAuthorize("@ss.hasPermi('note:role:remove')")
    @Log(title = "删除笔记角色", businessType = BusinessType.DELETE)
    @RequestMapping(value = "/remove/{ids}",method = org.springframework.web.bind.annotation.RequestMethod.GET)
    public AjaxResult remove(@PathVariable String ids)
    {
        String[] idarr = ids.split(",");
        return toAjax(noteRoleService.deleteNoteRoleByIds(idarr));
    }
}
