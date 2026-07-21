package com.ruoyi.system.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

import java.util.Set;

/**
 * 笔记角色对象 note_role
 * 
 * @author liuyanghe
 * @date 2023-08-26
 */
public class NoteRole extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 角色ID */
    private Long id;

    /** 角色名称 */
    @Excel(name = "角色名称")
    private String roleName;

    /** 角色权限字符串 */
    @Excel(name = "角色权限字符串")
    private String roleKey;

    /** 显示顺序 */
    @Excel(name = "显示顺序")
    private Long roleSort;

    /** 数据范围（1：全部数据权限 2：自定数据权限 3：本部门数据权限 4：本部门及以下数据权限） */
    @Excel(name = "数据范围", readConverterExp = "1=：全部数据权限,2=：自定数据权限,3=：本部门数据权限,4=：本部门及以下数据权限")
    private String dataScope;

    /** 菜单树选择项是否关联显示 （0：父子不互相关联显示 1：父子互相关联显示 ） */
    @Excel(name = "菜单树选择项是否关联显示 （0：父子不互相关联显示 1：父子互相关联显示 ）")
    private Integer menuCheckStrictly;

    /** 部门树选择项是否关联显示 （0：父子不互相关联显示 1：父子互相关联显示 ） */
    @Excel(name = "部门树选择项是否关联显示 （0：父子不互相关联显示 1：父子互相关联显示 ）")
    private Integer deptCheckStrictly;

    /** 角色状态（0正常 1停用） */
    @Excel(name = "角色状态", readConverterExp = "0=正常,1=停用")
    private String status;


    /** 菜单组 */
    private Long[] menuIds;

    /** 删除标志（0代表存在 2代表删除） */
    private String delFlag;

    /** 部门组（数据权限） */
    private Long[] deptIds;

    /** 角色菜单权限 */
    private Set<String> permissions;

    /** 用户是否存在此角色标识 默认为1表示没有，检测到有该角色标记为0 */
    private Long flag = 1L;


    public NoteRole() {
    }

    public NoteRole(Long id) {
        this.id = id;
    }

    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }
    public void setRoleName(String roleName) 
    {
        this.roleName = roleName;
    }

    public String getRoleName() 
    {
        return roleName;
    }
    public void setRoleKey(String roleKey) 
    {
        this.roleKey = roleKey;
    }

    public String getRoleKey() 
    {
        return roleKey;
    }
    public void setRoleSort(Long roleSort) 
    {
        this.roleSort = roleSort;
    }

    public Long getRoleSort() 
    {
        return roleSort;
    }
    public void setDataScope(String dataScope) 
    {
        this.dataScope = dataScope;
    }

    public String getDataScope() 
    {
        return dataScope;
    }
    public void setMenuCheckStrictly(Integer menuCheckStrictly) 
    {
        this.menuCheckStrictly = menuCheckStrictly;
    }


    public Integer getMenuCheckStrictly() 
    {
        return menuCheckStrictly;
    }
    public void setDeptCheckStrictly(Integer deptCheckStrictly) 
    {
        this.deptCheckStrictly = deptCheckStrictly;
    }

    public Integer getDeptCheckStrictly() 
    {
        return deptCheckStrictly;
    }
    public void setStatus(String status) 
    {
        this.status = status;
    }

    public String getStatus() 
    {
        return status;
    }
    public void setDelFlag(String delFlag) 
    {
        this.delFlag = delFlag;
    }

    public String getDelFlag() 
    {
        return delFlag;
    }


    public Long getFlag()
    {
        return flag;
    }

    public void setFlag(Long flag)
    {
        this.flag = flag;
    }

    public boolean isAdmin()
    {
        return isAdmin(this.id);
    }

    public static boolean isAdmin(Long roleId)
    {
        return roleId != null && 1L == roleId;
    }


    public Long[] getMenuIds() {
        return menuIds;
    }

    public void setMenuIds(Long[] menuIds) {
        this.menuIds = menuIds;
    }


    public Long[] getDeptIds() {
        return deptIds;
    }

    public void setDeptIds(Long[] deptIds) {
        this.deptIds = deptIds;
    }

    public Set<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(Set<String> permissions) {
        this.permissions = permissions;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("roleName", getRoleName())
            .append("roleKey", getRoleKey())
            .append("roleSort", getRoleSort())
            .append("dataScope", getDataScope())
            .append("menuCheckStrictly", getMenuCheckStrictly())
            .append("deptCheckStrictly", getDeptCheckStrictly())
            .append("status", getStatus())
            .append("delFlag", getDelFlag())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}
