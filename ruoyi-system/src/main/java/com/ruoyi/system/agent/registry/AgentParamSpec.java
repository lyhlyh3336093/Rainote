package com.ruoyi.system.agent.registry;

/**
 * Agent 操作参数规格，启动时由 Registry 从 {@code @AgentParam} 注解提取构建。
 *
 * @see com.ruoyi.system.agent.annotation.AgentParam
 */
public class AgentParamSpec
{
    /** 参数名 */
    private final String name;
    /** 参数类型：string/long/boolean/list/object */
    private final String type;
    /** 是否必填 */
    private final boolean required;
    /** 参数描述 */
    private final String description;
    /** 复杂参数类名（如 NoteColumnVo），仅 type=object 时非空 */
    private final String objectType;
    /** 可写字段白名单，仅 type=object 时非空 */
    private final String[] allowedFields;
    /** 方法参数位置索引 */
    private final int paramIndex;
    /** true=框架注入参数（如 userId），false=LLM 提供参数 */
    private final boolean frameworkInjected;

    public AgentParamSpec(String name, String type, boolean required, String description,
                          String objectType, String[] allowedFields, int paramIndex,
                          boolean frameworkInjected)
    {
        this.name = name;
        this.type = type;
        this.required = required;
        this.description = description;
        this.objectType = objectType;
        this.allowedFields = allowedFields != null ? allowedFields.clone() : new String[0];
        this.paramIndex = paramIndex;
        this.frameworkInjected = frameworkInjected;
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public boolean isRequired() { return required; }
    public String getDescription() { return description; }
    public String getObjectType() { return objectType; }
    public String[] getAllowedFields() { return allowedFields.clone(); }
    public int getParamIndex() { return paramIndex; }
    public boolean isFrameworkInjected() { return frameworkInjected; }
}
