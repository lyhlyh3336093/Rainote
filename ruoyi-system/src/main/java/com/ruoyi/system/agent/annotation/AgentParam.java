package com.ruoyi.system.agent.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Agent 操作参数注解。
 * <p>
 * 标注在 {@link AgentOperation} 方法的参数上，声明参数 schema 供 LLM 理解与 AgentParamBinder 绑定。
 * <p>
 * 未标注此注解的参数视为框架注入参数（如 userId 由 AgentExecutionContext 提供，不暴露给 LLM）。
 *
 * @see AgentOperation
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentParam
{
    /**
     * 参数名，与 LLM 生成的 JSON 参数键对应。
     */
    String value() default "";

    /**
     * 参数类型：string / long / boolean / list / object。
     * object 类型须配合 {@link #objectType()} 指定类名。
     */
    String type() default "object";

    /**
     * 是否必填。
     */
    boolean required() default true;

    /**
     * 供 LLM 理解的参数描述。
     */
    String description() default "";

    /**
     * 复杂参数结构的类名（如 "NoteColumnVo"、"NoteRecord"）。
     * AgentParamBinder 据此反射实例化对象并按 {@link #allowedFields()} 注入字段。
     * 仅对 type=object 生效。
     */
    String objectType() default "";

    /**
     * 可写字段白名单（mass-assignment 防护，F2）。
     * <p>
     * AgentParamBinder 反射注入时仅设置白名单内字段，忽略其他字段
     * （尤其 auth、createBy、creater、delFlag、revisionId、isDeleted、noteType、
     * templateFlag、collectionFlag 等敏感/归属/状态字段）并记录告警日志。
     * <p>
     * 启动时 Registry 校验无敏感字段出现在任何操作的 allowedFields 中，违反则拒绝注册。
     * 仅对 type=object 生效。
     */
    String[] allowedFields() default {};
}
