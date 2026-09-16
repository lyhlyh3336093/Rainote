package com.ruoyi.system.agent.registry;

import com.ruoyi.common.utils.myHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 参数绑定器。
 * <p>
 * 将 LLM 生成的扁平 JSON 参数（{@code Map<String, Object>}）绑定到 Service 方法的实际签名，
 * 产出可直接用于 {@code Method.invoke(bean, args)} 的参数数组。
 * <p>
 * 核心能力：
 * <ul>
 *   <li>对象反射实例化：根据 Method 实际参数类型实例化 domain/VO 对象</li>
 *   <li>字段 allowlist 防护（F2）：仅设置 @AgentParam.allowedFields 声明的字段，忽略敏感字段</li>
 *   <li>userId 按位置注入：未标注 @AgentParam 的 Long 参数从 AgentExecutionContext 取 userId</li>
 *   <li>复杂签名适配：双参数（NoteRecord + List&lt;Map&gt; items）、Vo 参数、String[] 参数</li>
 * </ul>
 */
@Component
public class AgentParamBinder
{
    private static final Logger log = LoggerFactory.getLogger(AgentParamBinder.class);

    /**
     * 将 LLM 参数绑定到 Service 方法签名。
     *
     * @param spec      操作规格（含 Method 引用与参数规格）
     * @param llmParams LLM 生成的参数 Map（键为参数名，值为 JSON 反序列化对象）
     * @param context   执行上下文（提供 userId 等框架注入参数）
     * @return 方法参数数组，可直接用于 Method.invoke
     * @throws IllegalArgumentException 必填参数缺失或类型不匹配
     */
    public Object[] bind(AgentOperationSpec spec, Map<String, Object> llmParams,
                         AgentExecutionContext context)
    {
        Method method = spec.getMethod();
        List<AgentParamSpec> paramSpecs = spec.getParams();
        Class<?>[] paramTypes = method.getParameterTypes();
        Object[] args = new Object[paramSpecs.size()];

        for (AgentParamSpec paramSpec : paramSpecs)
        {
            int index = paramSpec.getParamIndex();
            Class<?> targetType = paramTypes[index];

            if (paramSpec.isFrameworkInjected())
            {
                args[index] = bindFrameworkParam(paramSpec, context);
            }
            else
            {
                Object value = (llmParams != null) ? llmParams.get(paramSpec.getName()) : null;
                if (value == null && paramSpec.isRequired())
                {
                    throw new IllegalArgumentException(
                            "必填参数缺失: " + paramSpec.getName() + " (操作: " + spec.getName() + ")");
                }
                args[index] = convertValue(value, paramSpec, targetType, spec.getName());
            }
        }
        return args;
    }

    /**
     * 绑定框架注入参数（当前仅 userId）。
     */
    private Object bindFrameworkParam(AgentParamSpec paramSpec, AgentExecutionContext context)
    {
        if ("userId".equals(paramSpec.getName()))
        {
            if (context == null || context.getUserId() == null)
            {
                throw new IllegalStateException("框架注入参数 userId 不可用：AgentExecutionContext 未提供");
            }
            return context.getUserId();
        }
        throw new IllegalStateException("未知的框架注入参数: " + paramSpec.getName());
    }

    /**
     * 按实际 Java 参数类型转换 LLM 值。
     */
    @SuppressWarnings("unchecked")
    private Object convertValue(Object value, AgentParamSpec paramSpec, Class<?> targetType,
                                String opName)
    {
        if (value == null)
        {
            return null;
        }

        // String[]
        if (targetType == String[].class)
        {
            return toStringArray(value, paramSpec.getName(), opName);
        }
        // Long / long
        if (targetType == Long.class || targetType == long.class)
        {
            return toLong(value);
        }
        // Integer / int
        if (targetType == Integer.class || targetType == int.class)
        {
            return toInteger(value);
        }
        // Boolean / boolean
        if (targetType == Boolean.class || targetType == boolean.class)
        {
            return toBoolean(value);
        }
        // String
        if (targetType == String.class)
        {
            return value.toString();
        }
        // List / Collection
        if (List.class.isAssignableFrom(targetType))
        {
            return toList(value);
        }
        // Map（直接传递，如 List<Map<String,Object>> 中的元素）
        if (Map.class.isAssignableFrom(targetType))
        {
            return value;
        }
        // 自定义对象（domain/VO）：反射实例化 + allowlist 字段注入
        return toObject(value, paramSpec, targetType, opName);
    }

    /**
     * 反射实例化对象并按 allowlist 注入字段（F2 mass-assignment 防护核心）。
     */
    @SuppressWarnings("unchecked")
    private Object toObject(Object value, AgentParamSpec paramSpec, Class<?> targetType,
                            String opName)
    {
        if (!(value instanceof Map))
        {
            throw new IllegalArgumentException(String.format(
                    "操作 '%s' 参数 '%s' 期望 object(Map)，实际 %s",
                    opName, paramSpec.getName(), value.getClass().getName()));
        }
        Map<String, Object> map = (Map<String, Object>) value;

        try
        {
            java.lang.reflect.Constructor<?> constructor = targetType.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object instance = constructor.newInstance();
            Set<String> allowedFields = new HashSet<>(Arrays.asList(paramSpec.getAllowedFields()));

            for (Map.Entry<String, Object> entry : map.entrySet())
            {
                String fieldName = entry.getKey();
                if (!allowedFields.contains(fieldName))
                {
                    log.warn("F2 mass-assignment 防护: 操作 '{}' 参数 '{}' 字段 '{}' 不在 allowlist 中，已忽略",
                            opName, paramSpec.getName(), fieldName);
                    continue;
                }
                setField(targetType, instance, fieldName, entry.getValue(), opName, paramSpec.getName());
            }
            return instance;
        }
        catch (Exception e)
        {
            throw new RuntimeException(String.format(
                    "参数绑定失败: 操作 '%s' 参数 '%s' 类型 %s",
                    opName, paramSpec.getName(), targetType.getName()), e);
        }
    }

    /**
     * 反射设置字段值，支持父类字段查找与类型转换。
     */
    private void setField(Class<?> clazz, Object instance, String fieldName, Object value,
                          String opName, String paramName)
    {
        Field field = findField(clazz, fieldName);
        if (field == null)
        {
            log.warn("操作 '{}' 参数 '{}': 字段 '{}' 在 {} 继承链中不存在，跳过",
                    opName, paramName, fieldName, clazz.getSimpleName());
            return;
        }
        try
        {
            field.setAccessible(true);
            Object converted = convertFieldValue(field.getType(), value);
            field.set(instance, converted);
        }
        catch (Exception e)
        {
            log.warn("操作 '{}' 参数 '{}': 设置字段 '{}' 失败: {}",
                    opName, paramName, fieldName, e.getMessage());
        }
    }

    /**
     * 在类继承链中查找字段（含父类，如 BaseEntity 的字段）。
     */
    private Field findField(Class<?> clazz, String fieldName)
    {
        Class<?> current = clazz;
        while (current != null && current != Object.class)
        {
            try
            {
                return current.getDeclaredField(fieldName);
            }
            catch (NoSuchFieldException e)
            {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 字段值类型转换（myHashMap、Long、String[] 等）。
     */
    @SuppressWarnings("unchecked")
    private Object convertFieldValue(Class<?> fieldType, Object value)
    {
        if (value == null)
        {
            return null;
        }
        // myHashMap: 项目自定义 Map 子类，需转为 myHashMap 实例
        if (myHashMap.class.isAssignableFrom(fieldType) && value instanceof Map)
        {
            myHashMap<String, Object> hm = new myHashMap<>();
            hm.putAll((Map<String, Object>) value);
            return hm;
        }
        // Long / long
        if (fieldType == Long.class || fieldType == long.class)
        {
            return toLong(value);
        }
        // Integer / int
        if (fieldType == Integer.class || fieldType == int.class)
        {
            return toInteger(value);
        }
        // String[]
        if (fieldType == String[].class)
        {
            return toStringArray(value, "field", "convertFieldValue");
        }
        // 默认直接赋值（String、List、Boolean 等类型兼容的场景）
        return value;
    }

    // ===== 基础类型转换工具 =====

    private String[] toStringArray(Object value, String paramName, String opName)
    {
        if (value instanceof String[])
        {
            return (String[]) value;
        }
        if (value instanceof Collection)
        {
            Collection<?> coll = (Collection<?>) value;
            String[] arr = new String[coll.size()];
            int i = 0;
            for (Object item : coll)
            {
                arr[i++] = item == null ? null : item.toString();
            }
            return arr;
        }
        // 单值包装为单元素数组
        return new String[]{value.toString()};
    }

    private Long toLong(Object value)
    {
        if (value instanceof Number)
        {
            return ((Number) value).longValue();
        }
        return Long.valueOf(value.toString());
    }

    private Integer toInteger(Object value)
    {
        if (value instanceof Number)
        {
            return ((Number) value).intValue();
        }
        return Integer.valueOf(value.toString());
    }

    private Boolean toBoolean(Object value)
    {
        if (value instanceof Boolean)
        {
            return (Boolean) value;
        }
        return Boolean.valueOf(value.toString());
    }

    @SuppressWarnings("unchecked")
    private List<Object> toList(Object value)
    {
        if (value instanceof List)
        {
            return (List<Object>) value;
        }
        if (value.getClass().isArray())
        {
            return Arrays.asList((Object[]) value);
        }
        return Collections.singletonList(value);
    }
}
