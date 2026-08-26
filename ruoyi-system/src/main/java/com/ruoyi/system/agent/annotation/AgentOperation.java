package com.ruoyi.system.agent.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Agent 可执行操作注解。
 * <p>
 * 标注在 ServiceImpl 方法上，应用启动时由 {@code AgentOperationRegistry} 扫描注册为操作清单。
 * LLM 仅能从注册的操作清单中选择调用，防止编造不存在的操作。
 * <p>
 * 安全约束：
 * <ul>
 *   <li>清库类方法（方法名匹配 removeAll* 或无参数全表删除）强制拒绝注册（C3）</li>
 *   <li>每个操作的参数须通过 {@link AgentParam#allowedFields()} 声明字段白名单，防止 mass-assignment（F2）</li>
 * </ul>
 *
 * @see AgentParam
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentOperation
{
    /**
     * 操作唯一标识，如 "note.create"、"record.batchDelete"。
     * 重复时启动报错。
     */
    String name();

    /**
     * 是否破坏性操作（delete/batchDelete 等）。
     * 破坏性操作执行前须用户二次确认。
     */
    boolean destructive() default false;

    /**
     * 权限键，如 "note:note:delete"、"system:column:remove"。
     * 运行时由 AgentExecutionContext 携带的 permissionKeys 校验。
     */
    String permissionKey() default "";

    /**
     * 供 LLM 理解的操作描述，注入 system prompt。
     */
    String description() default "";
}
