---
date: 2026-07-21
topic: app-delete
---

# 应用管理模块删除功能

## Summary

在 [CloudAppController](ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/CloudAppController.java) 新增符合 RuoYi 标准的 `@DeleteMapping("/{ids}")` 接口,修复前端 [delApp](ruoyi-ui/src/api/system/app.js) 调用 `DELETE /system/app/{id}` 与后端现有 `GET /remove/{ids}`、`GET /delete` 路径不匹配导致删除功能不可用的问题。同时增加删除前校验(存在性、默认应用保护、创建者权限),保留现有两个 GET 接口向后兼容。软删除复用现有 `delFlag=1` 逻辑。

---

## Problem Frame

前端 [delApp](ruoyi-ui/src/api/system/app.js) 发送 `DELETE /system/app/{id}`,但后端 [CloudAppController](ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/CloudAppController.java) 只提供 `GET /system/app/remove/{ids}` 和 `GET /system/app/delete?id=xxx` 两个非标准接口,路径与方法均不匹配,前端删除按钮点击后必然失败(404/405)。此外现有删除接口无任何前置校验,存在删除不存在的应用、误删系统默认应用(`creater="1"`)、越权删除他人应用的风险。两个 GET 删除接口本身也违反 REST 安全语义(见 project_memory 中 `deduplicate` 端点的同类教训),但本次为向后兼容予以保留。

---

## Key Decisions

**新增标准 DELETE 接口而非改前端调用**:在 [CloudAppController](ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/CloudAppController.java) 新增 `@DeleteMapping("/{ids}")`,与前端 [delApp](ruoyi-ui/src/api/system/app.js) 已有的标准调用对齐。符合 RuoYi 框架 CRUD 风格,符合 REST 语义,前端零改动。

**保留现有两个 GET 删除接口路由但统一校验**:为避免未知调用方受影响,保留 `GET /remove/{ids}` 和 `GET /delete` 路由,但将其校验逻辑统一到 Service 层公共方法,与新增 DELETE 接口共用同一套校验(存在性、默认应用保护、创建者权限),确保三入口安全姿态一致。后续可由调用方逐步迁移到 DELETE 接口后再废弃旧路由。

**软删除**:复用现有 `delFlag=1` 逻辑,不改为物理删除。查询接口已带 `delFlag=0` 过滤,软删除后记录自动从列表消失。

**三维度删除前校验**:存在性校验(应用存在且未软删除)、默认应用保护(`creater="1"` 禁删)、创建者权限校验(仅创建者本人或管理员可删)。

**校验放在 Service 层**:Controller 仅做路由与权限注解,具体校验逻辑在 [CloudAppServiceImpl](ruoyi-system/src/main/java/com/ruoyi/system/service/impl/CloudAppServiceImpl.java) 中实现,复用 `selectCloudAppById`(已带 `delFlag=0` 过滤)做存在性查询。

---

## Requirements

**标准 DELETE 接口**

- R1. 在 [CloudAppController](ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/CloudAppController.java) 新增 `@DeleteMapping("/{ids}")` 方法,接收逗号分隔的 ids 字符串路径变量。
- R2. 方法上加 `@PreAuthorize("@ss.hasPermi('system:app:remove')")` 与 `@Log(title = "应用", businessType = BusinessType.DELETE)` 注解,与现有删除接口保持一致。
- R3. 保留现有 `GET /remove/{ids}` 与 `GET /delete` 接口路由,但其删除逻辑必须复用与 DELETE 接口相同的 Service 层公共校验方法(存在性、默认应用保护、创建者权限),三入口统一受控,关闭绕过路径。

**删除前校验**

- R4. **存在性校验**:对每个待删除 id,调用 `selectCloudAppById` 查询,返回 null 则说明应用不存在或已软删除,整体中止并返回错误提示(不执行任何删除)。
- R5. **默认应用保护(绝对规则,优先于 R6 管理员旁路)**:若查询到的应用 `creater` 字段值为 `"1"`,视为系统默认应用,任何人(含管理员)不得删除,返回错误提示。R5 命中即整体中止,不进入 R6 校验。
- R6. **创建者权限校验**:若应用 `creater` 字段值不等于当前登录用户 ID(字符串比较),且当前用户非管理员(定义为 `SecurityUtils.getUserId() == 1L`,即 super admin),禁止删除,返回权限错误提示。
- R7. 任一 id 校验失败时,整体中止(不部分删除),返回首个失败原因对应的友好错误信息。

**执行删除**

- R8. 全部校验通过后,调用现有 `cloudAppService.deleteCloudAppByIds(idArr)` 执行软删除(`delFlag=1`)。
- R9. 返回 `toAjax(受影响行数)` 作为标准响应。

---

## Scope Boundaries

**Deferred for later**

- 前端 `creater` 字段自动填充:当前 [index.vue](ruoyi-ui/src/views/system/app/index.vue) 表单让用户手动输入"创建者",应改为提交时自动取当前登录用户 ID。本次不动前端。
- 现有 `GET /remove/{ids}` 与 `GET /delete` 接口路由的废弃与移除:本次保留路由但统一校验(见 R3),待调用方迁移后再清理旧路由。
- 删除接口的物理删除选项:本次仅软删除。

**需求分期交付**

- 第一期(最小可用修复):R1-R3 + R8-R9,修复删除按钮 404/405 问题。即使 Q1(creater 语义)未决也可先行上线。
- 第二期(安全加固):R4-R7,以 Q1 解决为前置条件。Q1 确认前 R6 不得上线,但 R4(存在性校验)、R5(默认应用保护)可先行。

**Outside this product's identity**

- 应用与笔记/多维表格的关联校验:经全量搜索,`cloud_app` 表当前无任何业务模块通过外键或服务调用关联(`ICloudAppService` 仅在自身 CRUD 链路中被引用),无需级联校验。
- 删除审计日志(记录"谁在何时删除了哪个应用")。
- 应用图标的文件清理(logo 字段存的 URL,删除应用时是否清理上传文件)。

---

## Key Flows

- F1. 单条删除
  - **Trigger:** 用户在前端应用列表点击某行"删除"按钮,二次确认后调用 `delApp(id)`。
  - **Steps:** 前端发 `DELETE /system/app/{id}` → Controller 取 `@PathVariable("ids") String ids` → Service 对该 id 逐项校验(存在性 → 默认应用[命中即中止] → 创建者权限) → 全部通过则 `update cloud_app set delFlag=1 where id=#{id}`。
  - **Outcome:** 应用 `delFlag=1`,列表刷新后不再显示。
  - **Covered by:** R1, R2, R4, R5, R6, R7, R8, R9.

- F2. 批量删除
  - **Trigger:** 用户在应用列表勾选多条,点击顶部"删除"按钮,二次确认后调用 `delApp(ids)`(ids 为数组,toString 成 "1,2,3")。
  - **Steps:** 前端发 `DELETE /system/app/1,2,3` → Controller 取 ids 字符串 → split(",") → Service 对每个 id 逐项校验 → 全部通过则批量 `update cloud_app set delFlag=1 where id in (...)`。
  - **Outcome:** 所有选中应用 `delFlag=1`。
  - **Covered by:** R1, R2, R4, R5, R6, R7, R8, R9.

---

## Success Criteria

- S1. 前端点击删除按钮后,应用从列表消失,数据库对应行 `delFlag=1`。
- S2. 删除不存在的 id 或已软删除的 id,返回友好错误提示,不执行删除。
- S3. 删除 `creater="1"` 的系统默认应用,返回友好错误提示,数据库行 `delFlag` 不变。
- S4. 非创建者且非管理员的用户删除他人应用,返回权限错误提示,数据库行 `delFlag` 不变。
- S5. 批量删除中任一 id 校验失败,整体中止,所有选中应用 `delFlag` 不变(不部分删除)。

---

## Dependencies / Assumptions

- `creater` 字段语义(用户 ID vs 用户名)是 R6 创建者权限校验的前置条件,必须在实现前通过查库或查前端 payload 确认,不能停留在"假设"。若 Q1 未确认,R6 不得上线,但 R1-R3 + R8-R9(按钮修复)可先行交付。
- "管理员"已在 R6 中明确定义为 `SecurityUtils.getUserId() == 1L`(super admin),与 RuoYi 框架既有口径一致(Q2 已关闭)。
- 假设现有 `GET /remove/{ids}` 与 `GET /delete` 接口有未知调用方,因此保留路由但统一校验逻辑(见 R3);若无调用方,后续可单独清理旧路由。
- 假设 `cloud_app` 表当前确无业务关联(基于全量搜索 `ICloudAppService`、`cloud_app`、`app_id` 的结果),无需级联删除。

---

## Outstanding Questions

- Q1. `creater` 字段实际存储的是用户 ID 还是用户名?这决定 R6 创建者权限校验时与 `SecurityUtils.getUserId()` 比较还是与 `SecurityUtils.getUsername()` 比较。需实现时查看前端新增应用提交的 payload 或数据库实际数据确认。
- Q2. "管理员"的口径:仅 super admin(user_id=1),还是所有拥有 `system:app:remove` 权限的角色?前者更严格,后者依赖 `@PreAuthorize` 已有的权限注解。建议实现时默认用 super admin 判断,若需放宽再调整。 **[已在 R6 中明确为 super admin(`SecurityUtils.getUserId() == 1L`),Q2 关闭]**
- Q3. 批量删除中如果部分 id 校验失败,是整体中止(当前 R7 设计)还是跳过失败项继续删除成功项?当前选择整体中止以保证原子性,若业务需要"尽力删除"可调整。
