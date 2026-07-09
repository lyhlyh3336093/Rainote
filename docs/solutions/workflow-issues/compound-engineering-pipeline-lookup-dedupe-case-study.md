---
title: "复合工程流水线案例：Lookup列去重特性的端到端沉淀旅程"
date: 2026-07-09
category: docs/solutions/workflow-issues/
module: compound-engineering-pipeline
problem_type: workflow_issue
component: development_workflow
severity: medium
applies_when:
  - "需要在RuoYi项目中走完整的复合工程流水线（brainstorm→plan→实现→code-review→compound→doc-review）"
  - "想要理解ce-compound与ce-doc-review如何协作产出可沉淀的团队知识"
  - "评估是否值得为某个特性投入全流程，还是应精简"
  - "需要参照一个真实案例来规划自己的端到端工作流"
related_components:
  - documentation
  - service_object
tags: [compound-engineering, ce-compound, ce-code-review, ce-doc-review, workflow, knowledge-sedimentation, lookup-column, case-study]
---

# 复合工程流水线案例：Lookup列去重特性的端到端沉淀旅程

## 背景

本案例记录 RuoYi-Vue 项目中"Lookup列去重开关级联重算"特性从需求到知识沉淀的完整旅程，跨越 7 个阶段、3 次 git 提交，产出 5 份文档与多项代码修复。它不是技术模式文档（那部分见 [架构模式文档](file:///d:/WorkSpace/RuoYi-Vue/docs/solutions/architecture-patterns/lookup-column-dedupe-cascade-recompute.md)），而是一份**工作流案例研究**——回答"复合工程流水线如何端到端运作、每个阶段产出什么、哪里值得投入、哪里可以精简"。

## 指导

### 流水线全景：7 阶段衔接

```
brainstorm → plan → implementation → ce-code-review → ce-compound → 中文化 → ce-doc-review
  需求R1-R9    计划U1-U3    代码+端点      6缺陷修复      架构模式文档   产物中文化   文档审查+已知局限
```

每个阶段的产出与衔接逻辑：

| 阶段 | 产出 | 衔接到下一阶段的方式 |
|------|------|---------------------|
| brainstorm | 需求文档 R1-R9 | 需求被 plan 拆解为 U1/U2/U3 |
| plan | 计划文档 U1-U3 | 计划单元指导 implementation |
| implementation | 代码 + `deduplicate`端点 | 代码变更触发 ce-code-review |
| ce-code-review | 6缺陷修复（P0-P3） | 修复后的代码成为 ce-compound 的素材 |
| ce-compound | 架构模式文档 + CONCEPTS + AGENTS | 文档成为中文化转换对象 |
| 中文化 | 中文产物 | 中文文档进入 ce-doc-review |
| ce-doc-review | 3处修复 + 6项已知局限 | 已知局限成为后续改进路标 |

### 关键决策：何时走全流程

**值得全流程**的场景：
- 触碰计算列重算逻辑（静默且具破坏性的缺陷模式）
- 涉及数据完整性（序列化、缓存一致性）
- 跨多个依赖组件的级联变更
- 首次在某个领域建立模式（后续特性可复用）

**可精简**的场景：
- 单文件小修复（跳过 brainstorm/plan，直接实现 + lightweight compound）
- 纯展示层调整（跳过 code-review 的 adversarial reviewer）
- 已有成熟模式的同类变更（复用现有 solution doc，不重新 compound）

### ce-code-review 的价值密度

6 个缺陷体现了"重算/序列化逻辑"中极易复发的失败模式：

| 缺陷 | 严重级 | 失败模式 | 复发性 |
|------|--------|----------|--------|
| `replaceAll`剥离空格 | P0 | 静默数据损坏 | 极高 |
| 缺失级联调用 | P1 | 缓存不一致 | 高 |
| 静默吞异常 | P1 | 不可观测失败 | 高 |
| NPE风险 | P2 | 并行字段独立null | 中 |
| 冗余查询 | P3 | 循环内重复DB调用 | 中 |
| 默认分支空结果 | P2 | 隐式空值传播 | 中 |

**教训**：P0 缺陷（`replaceAll(" ","")`）在每次重算时都静默篡改用户数据，却不会触发任何报错。这类缺陷只能靠代码审查发现——测试很难覆盖"空格被剥离"这种语义损坏，因为断言通常只检查非空与长度，不检查值内容是否被篡改。

### ce-doc-review 的增量价值

ce-doc-review 在已沉淀文档上发现了 4 类 reviewer 视角的 13 条问题，合并为 7 条可操作 + 4 条 FYI。它在 ce-compound 产出之后提供了**第二层审查**，捕获的不是代码缺陷，而是文档缺陷：

**关键发现（已应用修复）**：

1. **严重级别表述矛盾**——修复6 标为 P2 却称"最危险的一类缺陷"，与修复1 P0"最高严重级别"冲突。澄清：P0按修复紧迫性排序，"容易复发"按模式危险性排序，是不同维度。
2. **触发逻辑与模式宣称不符**——示例代码只 diff `dedupe` 字段，但模式宣称适用任意派生影响开关。补充指引：引入新派生开关时必须扩展 diff 谓词并加回归测试。
3. **缺少已知局限章节**——新增 6 项局限作为后续改进路标，使文档从"如何做"扩展到"边界在哪"。

**已知局限（后续改进路标）**：

| # | 局限 | 风险 |
|---|------|------|
| 1 | 级联仅一层 | transitive 依赖陈旧 |
| 2 | 无 `@Transactional` | 部分失败致缓存不一致 |
| 3 | 逐行 catch | 跨行半迁移状态 |
| 4 | 端点未声明授权 | 越权全表重算 |
| 5 | GET 端点变更状态 | CSRF 友好 |
| 6 | 同步重算无规模约束 | 大表阻塞 |

### 流水线产物的交叉引用

```
docs/brainstorms/2026-07-06-...-requirements.md   ← 需求源头（R1-R9）
    ↓
docs/plans/2026-07-06-001-...-plan.md              ← 决策产物（U1/U2/U3）
    ↓
代码实现（NoteColumnServiceImpl / NoteRecordServiceImpl / NoteColumnController）
    ↓
docs/solutions/architecture-patterns/lookup-column-dedupe-cascade-recompute.md  ← 技术模式
    ↓ (ce-doc-review 修补)
CONCEPTS.md  ← 领域词汇（Lookup Column + dedupe行为规则）
AGENTS.md   ← 发现入口
```

## 为什么这很重要

复合工程流水线的价值在于**知识复利**：第一次解决某类问题需要研究、试错、审查；沉淀后，下一次同类问题只需检索文档。但流水线本身有成本——7 阶段、多次子代理派发、3 次提交。本案例证明三点：

1. **ce-code-review 在重算逻辑上的 ROI 极高**：6 个缺陷中 3 个是 P0/P1，会直接导致数据损坏或缓存不一致。这类静默缺陷无法靠测试覆盖，只能靠多视角审查发现。71 个测试通过仍不能保证没有 `replaceAll` 剥离空格这类语义损坏。

2. **ce-doc-review 提供"路标价值"**：已知局限 6 项不是缺陷，而是后续改进的优先级清单。没有这步，这些局限会散落在对话历史中，无法被未来 agent 检索。文档审查把"隐式知识"转为"显式路标"。

3. **中文化降低团队认知成本**：技术术语保留英文（`dedupe`、`cascade`、`recompute`），叙述用中文。这让非英语母语开发者能更快理解，同时不损失精确性。YAML enum 值、代码块、文件路径保持英文以确保工具兼容。

## 适用场景

- 你正在规划一个涉及计算列重算或数据完整性的特性，需要决定是否走全流程
- 你想理解 ce-compound 与 ce-doc-review 如何协作（compound产出 → doc-review修补）
- 你需要向团队论证"为什么值得为这个特性投入完整流水线"
- 你在编写自己的端到端工作流，想参照一个真实案例的阶段衔接与产物组织

## 示例

### 产物演进时间线

```
2026-07-06  brainstorm 产出需求文档（R1-R9, U1/U2/U3）
2026-07-06  plan 产出计划文档
2026-07-0X  implementation 完成代码 + deduplicate端点
2026-07-08  ce-code-review 应用6缺陷修复（71测试通过）
2026-07-08  ce-compound 产出架构模式文档 + CONCEPTS + AGENTS
            （commit 533b1543, 12 files +1178/-12）
2026-07-08  中文化转换（commit d5c90c0b, 3 files +78/-78）
2026-07-08  ce-doc-review 应用3处修复 + 6项已知局限
            （commit 4202225f, 1 file +19/-1）
2026-07-09  本案例研究（工作流视角沉淀）
```

### 遗留待办（来自已知局限）

以下待办已记录在架构模式文档的"已知局限"章节，等待后续处理：

1. `deduplicate` 方法添加 `@Transactional`
2. `deduplicate` 端点 GET → POST
3. 端点添加 `@PreAuthorize` 角色声明
4. 两步重算包裹事务边界
5. 级联支持多跳（transitive 依赖）
6. 大表同步重算增加规模守卫

### 全流程 vs 精简流程决策树

```
变更是否触碰计算列重算/序列化逻辑？
├─ 否 → 是否跨多组件？
│       ├─ 否 → 单文件修复：直接实现 + lightweight compound
│       └─ 是 → 实现后走 ce-code-review（跳过 brainstorm/plan）
└─ 是 → 是否首次在此领域建立模式？
        ├─ 否 → 复用现有 solution doc，实现 + code-review
        └─ 是 → 走全流程：
                brainstorm → plan → 实现 → code-review → compound → doc-review
```

## 相关文档

- [Lookup列去重开关级联重算模式](file:///d:/WorkSpace/RuoYi-Vue/docs/solutions/architecture-patterns/lookup-column-dedupe-cascade-recompute.md) —— 技术模式文档（架构模式视角，含6个代码审查修复与已知局限）
- [需求文档](file:///d:/WorkSpace/RuoYi-Vue/docs/brainstorms/2026-07-06-lookup-column-value-dedupe-requirements.md) —— 需求 R1-R9 源头
- [计划文档](file:///d:/WorkSpace/RuoYi-Vue/docs/plans/2026-07-06-001-feat-lookup-column-value-dedupe-plan.md) —— 实现单元 U1/U2/U3
- [CONCEPTS.md](file:///d:/WorkSpace/RuoYi-Vue/CONCEPTS.md) —— 领域词汇表（Lookup Column + dedupe 行为规则）
- [AGENTS.md](file:///d:/WorkSpace/RuoYi-Vue/AGENTS.md) —— AI代理发现入口
