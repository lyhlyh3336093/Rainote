---
title: "agent 结果消息通道：SSE resultSummary 透出 + 审计 result 字段 + 前端渲染"
date: 2026-10-21
category: docs/solutions/design-patterns
module: ruoyi-system/agent
problem_type: design_pattern
component: service_object
severity: medium
applies_when:
  - "agent 操作的返回值需要让用户看见（统计摘要、文件名、下一步引导），而非仅步骤成败状态"
  - "SSE 事件流只携带状态字段，操作精心构建的结果消息在链路上无人消费"
  - "agent 审计需要落库操作结果以便事后追溯"
related_components:
  - background_job
tags: [agent, sse, result-summary, audit-log, frontend-rendering]
---

# agent 结果消息通道：SSE resultSummary 透出 + 审计 result 字段 + 前端渲染

## Context

项目 agent 面（26 个 `@AgentOperation` 操作）长期存在一个链路断裂：操作实现精心构建返回值（如 `note.backup` 的"备份完成：49 表/17725 行/149 文件/188MB，请在界面点击「数据备份」下载"），但该返回值在执行链路上**无人消费**——SSE `step_completed` 事件只发 status/success/errorMessage 三字段、`AgentAuditStepLog` 审计实体无结果字段、前端只渲染步骤成败状态。用户经 agent 触发操作后只看到"步骤成功"，不知道结果内容与下一步去哪。

这个断裂在单查询操作（返回 queryResult 有专用呈现通道）上不明显，但**以引导闭环为目标的操作**（备份→去界面下载；导入→查看失败清单）会把断裂放大为功能缺陷。修复将其做成全局能力：任一 agent 操作的结果摘要都能经 SSE 透出并落审计。

## Guidance

三层改动，向后兼容（resultSummary 为 null 时行为与旧版完全一致）：

**1. SSE 事件层**（AgentController）：`step_completed` 事件增发 `resultSummary` 字段——null 不携带；非 null 时 String 直接用，其他类型 fastjson2 序列化后**截断 2000 字符**（防大对象撑爆事件流与前端）。截断逻辑收敛在 `StepExecutionResult.getResultSummary()`/`summarizeResult()`，序列化失败返回 null（不因呈现层失败炸掉执行链）。

**2. 审计层**（AgentAuditStepLog）：实体加 `result` 字段，mapper XML（resultMap/列清单/update 语句）与 DDL 同步；`AgentPlanExecutor` 成功分支经 `updateStepStatus` 四参重载落库（PII 脱敏）；confirm 响应的 stepLogs 自动携带——历史详情页与实时流同源。

**3. 前端层**（stores/agent.ts + StepItem.vue）：SSE `step_completed` 拆独立 case 即时消费 resultSummary（再全量同步兜底）；StepItem 完成态渲染结果详情行；历史详情（AgentPanel）同步渲染。

```java
// StepExecutionResult.summarizeResult()：呈现层安全截断
if (result instanceof String) return (String) result;
try {
    String json = JSON.toJSONString(result);
    return json.length() > 2000 ? json.substring(0, 2000) : json;
} catch (Throwable t) { return null; }  // 呈现失败不影响执行成功
```

```typescript
// stores/agent.ts：step_completed 独立 case 即时消费
case 'step_completed': {
  const step = parseStep(data);
  if (data.resultSummary) step.result = data.resultSummary;  // 新字段
  break;
}
```

## Why This Matters

- 若无此通道：以引导闭环为目标的 agent 操作（备份/导入等）返回值全部沉没——功能等于只做了触发侧，闭环侧依赖用户自己猜"去哪拿结果"；26 个存量操作里任何返回有意义 DTO 的操作（如 `dwtable.importExcel` 的导入结果统计）同样受益。
- 审计若无 result 字段：事后追溯只有"哪步成功"没有"结果是什么"，排障时必须重放操作才能看到结果。
- 截断若不做：任一操作返回大列表（如全表记录）会把 SSE 事件撑到 MB 级，前端渲染卡死。
- 序列化失败若不吞：呈现层异常会反噬执行成功语义——操作明明成功却报步骤失败。

## When to Apply

- agent 操作返回值含用户需要看见的信息（统计/文件名/引导/失败明细）时，直接依赖该通道，无需每个操作自建呈现。
- 返回值是纯内部数据（仅 executor 判断用）时返回 null/不透出，通道自动静默。
- 大结果集（如逐行数据）不要整包透出——透出摘要（计数+引导），明细走界面查询。

## Examples

`note.backup`（整机备份的 agent 入口）是首个消费者：返回"备份完成：{表数}表/{记录数}行/{文件数}文件/{包大小}MB，文件名 {name}；界面「数据备份」按钮将基于当前数据生成最新备份并下载（覆盖槽位旧备份，仅保留最近 1 份）"——用户在 SSE 流里即时看到统计与引导，历史详情页可回溯同一段结果。部署注意：已有环境升级需执行 `ALTER TABLE note_agent_audit_step_log ADD COLUMN result text`（见部署手册 8.1）。

## Related

- [full-backup-restore-mechanism.md](../architecture-patterns/full-backup-restore-mechanism.md) — 首个消费该通道的操作（note.backup）所在特性
- [multitable-sql-import-pipeline.md](../architecture-patterns/multitable-sql-import-pipeline.md) — §6 的"agent 路径限流/操作日志对称防护"缺口由本通道的审计 result 字段部分补齐
