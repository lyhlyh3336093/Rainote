package com.ruoyi.system.agent.registry;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * Agent 操作规格，启动时由 {@link AgentOperationRegistry} 扫描 {@code @AgentOperation} 构建。
 * <p>
 * 运行时 AgentPlanExecutor 通过此规格反射调用 Service 方法。
 */
public class AgentOperationSpec
{
    /** 操作唯一标识 */
    private final String name;
    /** 操作描述（供 LLM 理解） */
    private final String description;
    /** 是否破坏性操作 */
    private final boolean destructive;
    /** 权限键 */
    private final String permissionKey;
    /** Service Bean 实例 */
    private final Object bean;
    /** 反射调用方法 */
    private final Method method;
    /** 参数规格列表 */
    private final List<AgentParamSpec> params;

    public AgentOperationSpec(String name, String description, boolean destructive,
                              String permissionKey, Object bean, Method method,
                              List<AgentParamSpec> params)
    {
        this.name = name;
        this.description = description;
        this.destructive = destructive;
        this.permissionKey = permissionKey;
        this.bean = bean;
        this.method = method;
        this.params = Collections.unmodifiableList(params);
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isDestructive() { return destructive; }
    public String getPermissionKey() { return permissionKey; }
    public Object getBean() { return bean; }
    public Method getMethod() { return method; }
    public List<AgentParamSpec> getParams() { return params; }
}
