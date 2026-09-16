package com.ruoyi.system.agent;

import com.ruoyi.system.agent.annotation.AgentOperation;
import com.ruoyi.system.agent.annotation.AgentParam;
import com.ruoyi.system.agent.registry.AgentOperationRegistry;
import com.ruoyi.system.agent.registry.AgentOperationSpec;
import com.ruoyi.system.agent.registry.AgentParamBinder;
import com.ruoyi.system.agent.registry.AgentParamSpec;
import com.ruoyi.system.agent.registry.AgentExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentOperationRegistry 与 AgentParamBinder 单元测试。
 * <p>
 * 覆盖 11 个测试场景：注册数量、操作查询、破坏性标记、重名检测、参数 schema、
 * 未标注方法排除、C3 removeAll 排除、F2 mass-assignment 防护、F2 allowlist 启动校验、
 * userId 注入。
 */
class AgentOperationRegistryTest
{
    // ===== 测试用 Service =====

    /**
     * 合法测试服务：包含查询、创建、删除、带 userId 的查询，以及一个未标注的方法。
     */
    @Component
    static class TestService
    {
        @AgentOperation(name = "test.getById", description = "测试查询")
        public String getById(@AgentParam(value = "id", type = "long", description = "ID") Long id)
        {
            return "result:" + id;
        }

        @AgentOperation(name = "test.create", description = "测试创建")
        public int create(@AgentParam(value = "data", type = "object", objectType = "TestEntity",
                allowedFields = {"name", "value"}, description = "数据") TestEntity data)
        {
            return 1;
        }

        @AgentOperation(name = "test.delete", destructive = true, permissionKey = "test:delete",
                description = "测试删除")
        public int delete(@AgentParam(value = "id", type = "long", description = "ID") Long id)
        {
            return 1;
        }

        @AgentOperation(name = "test.list", description = "测试带userId查询")
        public List<String> list(@AgentParam(value = "filter", type = "object", objectType = "TestEntity",
                allowedFields = {"name"}, description = "筛选") TestEntity filter, Long userId)
        {
            return Collections.emptyList();
        }

        /** 未标注 @AgentOperation 的方法，不应被注册 */
        public String unannotatedMethod()
        {
            return "ignored";
        }
    }

    /** 测试用实体类（模拟 domain 对象） */
    public static class TestEntity
    {
        private String name;
        private String value;
        private Long auth;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public Long getAuth() { return auth; }
        public void setAuth(Long auth) { this.auth = auth; }
    }

    /** C3 测试服务：包含 removeAll 方法 */
    @Component
    static class TestServiceWithRemoveAll
    {
        @AgentOperation(name = "test.removeAll", description = "不应注册的清库操作")
        public int removeAll()
        {
            return 0;
        }
    }

    /** F2 测试服务：allowedFields 含敏感字段 */
    @Component
    static class TestServiceWithSensitiveField
    {
        @AgentOperation(name = "test.sensitive", description = "不应注册的敏感字段操作")
        public int create(@AgentParam(value = "data", type = "object", objectType = "TestEntity",
                allowedFields = {"name", "auth"}, description = "数据") TestEntity data)
        {
            return 1;
        }
    }

    /** 重名测试服务：两个方法注册同一操作名 */
    @Component
    static class TestServiceWithDuplicate
    {
        @AgentOperation(name = "test.dup", description = "第一个")
        public int methodA(@AgentParam(value = "id", type = "long") Long id) { return 1; }

        @AgentOperation(name = "test.dup", description = "第二个")
        public int methodB(@AgentParam(value = "id", type = "long") Long id) { return 1; }
    }

    // ===== Registry 测试 =====

    @Test
    void test_registered_count_matches_annotated_methods()
    {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(TestService.class, AgentOperationRegistry.class))
        {
            AgentOperationRegistry registry = ctx.getBean(AgentOperationRegistry.class);
            // TestService 有 4 个 @AgentOperation 方法
            assertEquals(4, registry.getOperations().size(),
                    "注册操作数应与标注方法数一致");
        }
    }

    @Test
    void test_get_operation_by_name_returns_complete_spec()
    {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(TestService.class, AgentOperationRegistry.class))
        {
            AgentOperationRegistry registry = ctx.getBean(AgentOperationRegistry.class);
            AgentOperationSpec spec = registry.getOperation("test.delete");

            assertNotNull(spec, "操作 test.delete 应存在");
            assertEquals("test.delete", spec.getName());
            assertTrue(spec.isDestructive(), "delete 应为破坏性操作");
            assertEquals("test:delete", spec.getPermissionKey());
            assertNotNull(spec.getMethod(), "应持有 Method 引用");
            assertNotNull(spec.getBean(), "应持有 Bean 引用");
        }
    }

    @Test
    void test_destructive_operations_marked_correctly()
    {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(TestService.class, AgentOperationRegistry.class))
        {
            AgentOperationRegistry registry = ctx.getBean(AgentOperationRegistry.class);
            assertTrue(registry.getOperation("test.delete").isDestructive(),
                    "delete 应 destructive=true");
        }
    }

    @Test
    void test_non_destructive_operations_marked_correctly()
    {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(TestService.class, AgentOperationRegistry.class))
        {
            AgentOperationRegistry registry = ctx.getBean(AgentOperationRegistry.class);
            assertFalse(registry.getOperation("test.getById").isDestructive(),
                    "getById 应 destructive=false");
            assertFalse(registry.getOperation("test.create").isDestructive(),
                    "create 应 destructive=false");
            assertFalse(registry.getOperation("test.list").isDestructive(),
                    "list 应 destructive=false");
        }
    }

    @Test
    void test_duplicate_operation_name_causes_startup_error()
    {
        assertThrows(BeanCreationException.class, () ->
        {
            try (AnnotationConfigApplicationContext ctx =
                         new AnnotationConfigApplicationContext(TestServiceWithDuplicate.class,
                                 AgentOperationRegistry.class))
            {
                ctx.getBean(AgentOperationRegistry.class);
            }
        }, "操作名重复应启动报错");
    }

    @Test
    void test_param_schema_reflects_annotation()
    {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(TestService.class, AgentOperationRegistry.class))
        {
            AgentOperationRegistry registry = ctx.getBean(AgentOperationRegistry.class);
            AgentOperationSpec spec = registry.getOperation("test.create");
            List<AgentParamSpec> params = spec.getParams();

            assertEquals(1, params.size(), "create 应有 1 个 LLM 参数");
            AgentParamSpec param = params.get(0);
            assertEquals("data", param.getName());
            assertEquals("object", param.getType());
            assertTrue(param.isRequired());
            assertFalse(param.isFrameworkInjected(), "data 不是框架注入参数");
            assertArrayEquals(new String[]{"name", "value"}, param.getAllowedFields());
        }
    }

    @Test
    void test_unannotated_methods_not_registered()
    {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(TestService.class, AgentOperationRegistry.class))
        {
            AgentOperationRegistry registry = ctx.getBean(AgentOperationRegistry.class);
            assertFalse(registry.hasOperation("unannotatedMethod"),
                    "未标注 @AgentOperation 的方法不应被注册");
        }
    }

    @Test
    void test_removeAll_excluded_c3()
    {
        assertThrows(BeanCreationException.class, () ->
        {
            try (AnnotationConfigApplicationContext ctx =
                         new AnnotationConfigApplicationContext(TestServiceWithRemoveAll.class,
                                 AgentOperationRegistry.class))
            {
                ctx.getBean(AgentOperationRegistry.class);
            }
        }, "removeAll 方法应被 C3 规则拒绝注册");
    }

    @Test
    void test_sensitive_field_in_allowlist_rejected_f2()
    {
        assertThrows(BeanCreationException.class, () ->
        {
            try (AnnotationConfigApplicationContext ctx =
                         new AnnotationConfigApplicationContext(TestServiceWithSensitiveField.class,
                                 AgentOperationRegistry.class))
            {
                ctx.getBean(AgentOperationRegistry.class);
            }
        }, "allowedFields 含敏感字段 auth 应被 F2 规则拒绝注册");
    }

    // ===== AgentParamBinder 测试 =====

    @Test
    void test_mass_assignment_protection_auth_ignored_f2()
    {
        // 直接测试 AgentParamBinder：LLM 参数含 auth=1，应被忽略
        AgentParamBinder binder = new AgentParamBinder();

        // 从 TestService.create 方法构建 spec
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(TestService.class, AgentOperationRegistry.class))
        {
            AgentOperationRegistry registry = ctx.getBean(AgentOperationRegistry.class);
            AgentOperationSpec spec = registry.getOperation("test.create");

            // LLM 参数：data 对象含 name + auth（auth 不在 allowlist 中）
            Map<String, Object> dataMap = new HashMap<>();
            dataMap.put("name", "测试实体");
            dataMap.put("value", "值");
            dataMap.put("auth", 1L);  // 敏感字段，应被忽略
            Map<String, Object> llmParams = new HashMap<>();
            llmParams.put("data", dataMap);

            AgentExecutionContext context = new AgentExecutionContext(99L, Collections.<String>emptySet());
            Object[] args = binder.bind(spec, llmParams, context);

            TestEntity entity = (TestEntity) args[0];
            assertEquals("测试实体", entity.getName(), "name 应被设置");
            assertEquals("值", entity.getValue(), "value 应被设置");
            assertNull(entity.getAuth(), "auth 应被忽略（不在 allowlist 中），不被篡改");
        }
    }

    @Test
    void test_userId_injected_from_context()
    {
        AgentParamBinder binder = new AgentParamBinder();

        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(TestService.class, AgentOperationRegistry.class))
        {
            AgentOperationRegistry registry = ctx.getBean(AgentOperationRegistry.class);
            AgentOperationSpec spec = registry.getOperation("test.list");

            // LLM 参数：filter
            Map<String, Object> llmParams = new HashMap<>();
            Map<String, Object> filterMap = new HashMap<>();
            filterMap.put("name", "筛选条件");
            llmParams.put("filter", filterMap);

            // context 中的 userId = 42L
            AgentExecutionContext context = new AgentExecutionContext(42L, Collections.<String>emptySet());
            Object[] args = binder.bind(spec, llmParams, context);

            // 参数 0: TestEntity filter
            TestEntity filter = (TestEntity) args[0];
            assertEquals("筛选条件", filter.getName());

            // 参数 1: Long userId（从 context 注入）
            assertEquals(42L, args[1], "userId 应从 AgentExecutionContext 注入");
        }
    }
}
