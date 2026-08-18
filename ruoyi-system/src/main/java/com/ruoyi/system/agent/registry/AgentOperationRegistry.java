package com.ruoyi.system.agent.registry;

import com.ruoyi.system.agent.annotation.AgentOperation;
import com.ruoyi.system.agent.annotation.AgentParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 操作清单注册中心。
 * <p>
 * 应用启动时扫描所有 Spring Bean 中标注 {@link AgentOperation} 的方法，
 * 构建操作清单 {@code Map<String, AgentOperationSpec>}。LLM 仅能从注册的操作清单中
 * 选择调用，防止编造不存在的操作。
 * <p>
 * 安全约束：
 * <ul>
 *   <li>C3：清库类方法（方法名匹配 removeAll*）强制拒绝注册</li>
 *   <li>F2：allowedFields 含敏感字段时启动报错拒绝注册</li>
 *   <li>操作名重复时启动报错</li>
 * </ul>
 *
 * @see AgentOperation
 * @see AgentParam
 */
@Component
public class AgentOperationRegistry implements ApplicationContextAware, InitializingBean
{
    private static final Logger log = LoggerFactory.getLogger(AgentOperationRegistry.class);

    /**
     * F2: 敏感字段集合，不允许出现在任何操作的 allowedFields 中。
     * 包含归属、状态、审计字段——LLM 不得通过 mass-assignment 篡改。
     */
    private static final Set<String> SENSITIVE_FIELDS;
    static
    {
        Set<String> s = new HashSet<>(Arrays.asList(
                "auth", "createBy", "creater", "delFlag", "revisionId",
                "isDeleted", "noteType", "templateFlag", "collectionFlag",
                "perms", "updateBy", "createTime", "updateTime", "searchValue",
                "params", "createBy"
        ));
        SENSITIVE_FIELDS = Collections.unmodifiableSet(s);
    }

    private final Map<String, AgentOperationSpec> operations = new LinkedHashMap<>();
    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext)
    {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterPropertiesSet()
    {
        scanAndRegister();
    }

    /**
     * 扫描所有 Bean，注册标注 @AgentOperation 的方法。
     */
    private void scanAndRegister()
    {
        String[] beanNames = applicationContext.getBeanNamesForType(Object.class);
        for (String beanName : beanNames)
        {
            Object bean;
            try
            {
                bean = applicationContext.getBean(beanName);
            }
            catch (Exception e)
            {
                // 跳过无法实例化的 Bean（如作用域代理）
                continue;
            }
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            Method[] methods;
            try
            {
                methods = targetClass.getMethods();
            }
            catch (SecurityException e)
            {
                continue;
            }
            for (Method method : methods)
            {
                AgentOperation op = AnnotationUtils.findAnnotation(method, AgentOperation.class);
                if (op != null)
                {
                    registerOperation(bean, targetClass, method, op);
                }
            }
        }
        log.info("AgentOperationRegistry: 已注册 {} 个 agent 操作", operations.size());
    }

    /**
     * 注册单个操作。
     *
     * @throws IllegalStateException C3 排除、F2 校验失败、操作名重复
     */
    private void registerOperation(Object bean, Class<?> targetClass, Method method, AgentOperation op)
    {
        String opName = op.name();

        // C3: 排除清库类方法
        if (method.getName().startsWith("removeAll"))
        {
            throw new IllegalStateException(String.format(
                    "C3安全约束: 清库类方法 %s.%s 不得注册为 agent 操作",
                    targetClass.getSimpleName(), method.getName()));
        }

        // 操作名重复检测
        if (operations.containsKey(opName))
        {
            AgentOperationSpec existing = operations.get(opName);
            throw new IllegalStateException(String.format(
                    "操作名重复: '%s' 已被 %s.%s 注册，%s.%s 冲突",
                    opName,
                    existing.getBean().getClass().getSimpleName(), existing.getMethod().getName(),
                    targetClass.getSimpleName(), method.getName()));
        }

        // 构建参数规格
        List<AgentParamSpec> params = buildParamSpecs(method);

        // F2: 校验 allowedFields 无敏感字段
        for (AgentParamSpec param : params)
        {
            for (String field : param.getAllowedFields())
            {
                if (SENSITIVE_FIELDS.contains(field))
                {
                    throw new IllegalStateException(String.format(
                            "F2安全约束: 操作 '%s' 的参数 '%s' allowedFields 含敏感字段 '%s'，拒绝注册",
                            opName, param.getName(), field));
                }
            }
        }

        AgentOperationSpec spec = new AgentOperationSpec(
                opName, op.description(), op.destructive(), op.permissionKey(),
                bean, method, params);
        operations.put(opName, spec);
        log.debug("注册操作: {} -> {}.{}", opName, targetClass.getSimpleName(), method.getName());
    }

    /**
     * 从方法参数提取参数规格。
     * <p>
     * 标注了 @AgentParam 的参数为 LLM 提供参数；未标注的 Long 类型参数视为框架注入的 userId。
     */
    private List<AgentParamSpec> buildParamSpecs(Method method)
    {
        Parameter[] parameters = method.getParameters();
        Annotation[][] paramAnnotations = method.getParameterAnnotations();
        List<AgentParamSpec> specs = new ArrayList<>();

        for (int i = 0; i < parameters.length; i++)
        {
            Parameter param = parameters[i];
            AgentParam agentParam = null;
            for (Annotation ann : paramAnnotations[i])
            {
                if (ann instanceof AgentParam)
                {
                    agentParam = (AgentParam) ann;
                    break;
                }
            }

            if (agentParam != null)
            {
                // LLM 提供的参数
                String name = (agentParam.value() == null || agentParam.value().isEmpty())
                        ? param.getName() : agentParam.value();
                specs.add(new AgentParamSpec(
                        name, agentParam.type(), agentParam.required(),
                        agentParam.description(), agentParam.objectType(),
                        agentParam.allowedFields(), i, false));
            }
            else
            {
                // 框架注入参数：Long 类型视为 userId，其他类型标记为未知
                Class<?> paramType = param.getType();
                boolean isUserId = (paramType == Long.class || paramType == long.class);
                String inferredName = isUserId ? "userId" : param.getName();
                String inferredType = isUserId ? "long" : paramType.getSimpleName();
                specs.add(new AgentParamSpec(
                        inferredName, inferredType, false, "框架注入参数",
                        "", new String[0], i, true));
            }
        }
        return specs;
    }

    /**
     * 获取操作规格，不存在返回 null。
     */
    public AgentOperationSpec getOperation(String name)
    {
        return operations.get(name);
    }

    /**
     * 获取全部已注册操作（不可变视图）。
     */
    public Map<String, AgentOperationSpec> getOperations()
    {
        return Collections.unmodifiableMap(operations);
    }

    /**
     * 是否存在指定操作。
     */
    public boolean hasOperation(String name)
    {
        return operations.containsKey(name);
    }

    /**
     * 导出操作清单为 LLM 可读的结构化描述，注入 system prompt。
     * <p>
     * 每个操作包含 name、description、destructive、params 列表。
     * 不暴露 Method/Bean 引用等运行时信息。
     *
     * @return 不可变操作描述列表
     */
    public List<Map<String, Object>> exportSchemaForLlm()
    {
        List<Map<String, Object>> schema = new ArrayList<>();
        for (AgentOperationSpec spec : operations.values())
        {
            Map<String, Object> opMap = new LinkedHashMap<>();
            opMap.put("name", spec.getName());
            opMap.put("description", spec.getDescription());
            opMap.put("destructive", spec.isDestructive());

            List<Map<String, Object>> paramList = new ArrayList<>();
            for (AgentParamSpec param : spec.getParams())
            {
                if (param.isFrameworkInjected())
                {
                    // 框架注入参数不暴露给 LLM
                    continue;
                }
                Map<String, Object> paramMap = new LinkedHashMap<>();
                paramMap.put("name", param.getName());
                paramMap.put("type", param.getType());
                paramMap.put("required", param.isRequired());
                paramMap.put("description", param.getDescription());
                // type=object 时输出 objectType 和 allowedFields，让 LLM 知道嵌套对象内部字段名
                if ("object".equals(param.getType()))
                {
                    paramMap.put("objectType", param.getObjectType());
                    String[] fields = param.getAllowedFields();
                    if (fields.length > 0)
                    {
                        paramMap.put("allowedFields", java.util.Arrays.asList(fields));
                    }
                }
                paramList.add(paramMap);
            }
            opMap.put("params", paramList);
            schema.add(opMap);
        }
        return Collections.unmodifiableList(schema);
    }
}
