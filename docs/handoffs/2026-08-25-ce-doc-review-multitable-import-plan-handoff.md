# ce-doc-review 接力提示词 — 多维表格导入功能计划文档评审

> 用于在新任务窗口接力执行 ce-doc-review interactive 评审。本文件包含：任务意图、已完成阶段、剩余步骤、必要文件路径、persona 选择理由、模板填充规则、综合管线、交互路由选项。
> **执行者**：在新窗口中直接按本文"Next — 执行步骤"节执行即可，无需重新探索。

---

## 1. Intent & Corrections（意图与约束）

- **当前任务**：对计划文档 `d:\WorkSpace\RuoYi-Vue\docs\plans\2026-08-25-001-feat-multitable-import-plan.md` 运行 ce-doc-review **interactive 模式**（无 `mode:headless` flag）。
- **触发链路**：ce-plan 生成该计划后，post-generation 菜单中用户选了 option 2（Run deeper doc review），随即触发 ce-doc-review interactive。
- **完成标志**：所有 Phase 跑完，用户在 terminal question 后明确选择下一步动作（重新菜单 / 跳到下一个工作阶段 / 结束）。
- **无用户修正**：执行至今未收到用户纠正。
- **语言**：用户面向的输出全部用中文（team 公告、findings 呈现、routing question、per-finding walk-through、terminal question）；finding 内容可中英混合。

---

## 2. Context（关键上下文与已读文件）

### 2.1 待评审文档

- **计划路径**：`d:\WorkSpace\RuoYi-Vue\docs\plans\2026-08-25-001-feat-multitable-import-plan.md`
- **文档类型分类**（Phase 1 已决）：`plan`（frontmatter 含 `type: feat`、`origin:` 路径，含 Implementation Units U1-U5、Key Technical Decisions KTD1-KTD6）
- **origin 路径**：`d:\WorkSpace\RuoYi-Vue\docs\brainstorms\2026-08-25-multitable-import-requirements.md`（frontmatter `origin:` 字段值，personas 用此 slot 决定技术抑制）
- **计划主题**：多维表格 SQL 导入功能（对称导出能力），接受 .sql/.zip，按列名宽松匹配，排除类型 18/20/21/23/24/25/26，单事务批量写入 note_record + note_dwtable_item，失败整体回滚。

### 2.2 ce-doc-review skill 文件（全部位于 `d:\WorkSpace\RuoYi-Vue\.trae\skills\ce-doc-review\`）

| 文件 | 用途 | 何时读 |
|------|------|--------|
| `SKILL.md` | Phase 0-2 + interactive 规则 | 已读，含 Phase 3-5 总览 |
| `references/subagent-template.md` | subagent prompt 模板，slot 填充规则 | 已读 |
| `references/findings-schema.json` | 输出 JSON schema | 已读 |
| `references/personas/coherence-reviewer.md` | persona prompt（always-on） | 已读 |
| `references/personas/feasibility-reviewer.md` | persona prompt（always-on） | 已读 |
| `references/personas/design-lens-reviewer.md` | persona prompt（条件激活） | 已读 |
| `references/personas/security-lens-reviewer.md` | persona prompt（条件激活） | 已读 |
| `references/personas/scope-guardian-reviewer.md` | persona prompt（条件激活） | 已读 |
| `references/personas/adversarial-document-reviewer.md` | persona prompt（条件激活） | 已读 |
| `references/synthesis-and-presentation.md` | Phase 3 综合管线 + Phase 4 呈现 | **dispatch 完成后再读**（SKILL.md 明示） |
| `references/walkthrough.md` | routing question + per-finding walk-through | **Phase 5 前读** |
| `references/bulk-preview.md` | best-judgment / Append / walk-through 余下 — 预览 | **routing 选 B/C/D 时读** |
| `references/open-questions-defer.md` | Defer append mechanic | **routing 选 C 时读** |
| `references/review-output-template.md` | Phase 4 interactive 呈现格式 | **Phase 4 前读** |

### 2.3 跨会话注意

- **ToolSearch 在本 harness 不可用**。AskUserQuestion 直接可用——用 `AskUserQuestion` 工具发起 routing question / per-finding question / bulk-preview Proceed&Cancel / terminal question。**不要**调用 ToolSearch；**不要**用 numbered-list fallback（除非 AskUserQuestion 工具调用本身失败）。
- **并行上限**：单次响应最多 5 个并行 tool 调用。6 个 reviewer 分 **2 批 × 3 个** dispatch。

---

## 3. Completed Work（已完成阶段）

### Phase 0：Mode 检测 ✅
- 无 `mode:headless` flag → **interactive mode**。

### Phase 1：文档分类 + persona 选择 ✅
- 分类：`plan`（依据 frontmatter `type: feat` + `origin:` + Implementation Units + KTD）。
- 选择的 6 个 persona 及理由：
  1. **coherence-reviewer**（always-on，必选）
  2. **feasibility-reviewer**（always-on，必选）
  3. **design-lens-reviewer** — U5 含 UI（按钮位置、modal、文件选择器、loading 三态）
  4. **security-lens-reviewer** — U3/U4 有 SQL 注入面（解析用户上传 SQL）、PII 单元格值、auth/ownership、API 端点
  5. **scope-guardian-reviewer** — >8 个需求（R1-R27 + AE1-AE8），多 Deferred 层（Deferred for later / Outside / Deferred to Follow-Up）
  6. **adversarial-document-reviewer** — 高风险域（数据迁移 + PII）；`Document type: plan` AND `Origin:` 是路径 → 仅运行 Section 2（技术假设）、Section 3（KTD 决策压测）、Section 5（架构替代方案）；**抑制** Section 1（premise）和 Section 4（simplification — scope-guardian 负责）。
- **product-lens-reviewer DEFERRED** — premise 已在 origin 上游校验；brainstorm 轮已跑过 product-lens 无 actionable premise findings；计划不超出 origin scope。

### Decision primer（Round 1）
```
<prior-decisions>
Round 1 — no prior decisions.
</prior-decisions>
```

**注意**：先前一次 headless pass（不同会话，context switch 前）应用过 1 个 safe_auto 修复（DUAL_TYPES 澄清，现已可见于 plan U3 Patterns L211）+ **SURFACED 但未 action**（因无用户决策）1 个 proposed fix（MyBatis #{} 参数化防 SQL 注入，KTD5/U3）+ 2 个 FYI。按 SKILL "cross-session persistence out of scope"，**本次 interactive 视为 Round 1，primer 为空**。surfaced-but-unactioned 的 proposed fix 可能作为新 finding 重新出现并需 routing——这是正确行为，**不是**要抑制的 re-raise。

---

## 4. Active Work（当前进度 — Phase 2 即将开始）

Todo 状态：
- ✅ Phase 0-1：Mode + classify + select personas
- ⏳ **Phase 2：Announce team + dispatch 6 reviewers in parallel**（当前）
- ⏸ Phase 3：Synthesize
- ⏸ Phase 4：Apply safe_auto + present
- ⏸ Phase 5：Routing question + per-finding walkthrough + terminal question

---

## 5. Next — 执行步骤

### Step 1：Announce the Review Team（中文公告）

用中文向用户公告 6-persona 团队及理由（理由见第 3 节 Phase 1 persona 选择）。格式参考：

```
Reviewing with:
- coherence-reviewer（always-on）
- feasibility-reviewer（always-on）
- design-lens-reviewer — U5 含 UI 实现（按钮位置、modal、文件选择器、loading 三态）
- security-lens-reviewer — U3/U4 含 SQL 注入面、PII 单元格值、auth/ownership、API 端点
- scope-guardian-reviewer — 27 个 R + 8 个 AE，多 Deferred 层
- adversarial-document-reviewer — 高风险域（数据迁移 + PII）；plan-with-origin → 仅 Section 2/3/5
```

### Step 2：Dispatch 6 reviewers（2 批 × 3，并行 ≤5）

每个 reviewer 用 **Task 工具** dispatch，`subagent_type=general_purpose_task`。

**批次 1（并行 3 个）**：coherence-reviewer、feasibility-reviewer、design-lens-reviewer
**批次 2（批次 1 返回后并行 3 个）**：security-lens-reviewer、scope-guardian-reviewer、adversarial-document-reviewer

#### 每个 subagent 的 prompt（按 subagent-template.md 填充）

模板路径：`d:\WorkSpace\RuoYi-Vue\.trae\skills\ce-doc-review\references\subagent-template.md`

**Slot 填充值**：

| Slot | 值 |
|------|-----|
| `{persona_file}` | 对应 persona 文件的**完整内容**（路径见第 2.2 节） |
| `{schema}` | `references/findings-schema.json` 的完整内容 |
| `{document_type}` | `plan` |
| `{document_path}` | `d:\WorkSpace\RuoYi-Vue\docs\plans\2026-08-25-001-feat-multitable-import-plan.md` |
| `{origin_path}` | `docs/brainstorms/2026-08-25-multitable-import-requirements.md`（frontmatter `origin:` 原值，**不**用 `none`） |
| `{document_content}` | **让 subagent 自己 Read 计划文件获取全文**——避免我在 prompt 里塞超长字符串。在 subagent prompt 末尾加：`请用 Read 工具读取路径 d:\WorkSpace\RuoYi-Vue\docs\plans\2026-08-25-001-feat-multitable-import-plan.md 获取 {document_content} 全文。` |
| `{decision_primer}` | 见第 3 节 Decision primer 块（Round 1 空 primer） |

**输出契约**：subagent 仅返回 valid JSON（符合 findings-schema.json），无 prose/markdown/explanation。返回字段：`reviewer`、`findings[]`、`residual_risks[]`、`deferred_questions[]`。每个 finding 必含 `title`、`severity`(P0-P3)、`section`、`why_it_matters`、`finding_type`(error/omission)、`autofix_class`(safe_auto/gated_auto/manual)、`confidence`(0/25/50/75/100)、`evidence[]`，`suggested_fix` 对 safe_auto/gated_auto 必填。

**错误处理**：若某 subagent 失败/超时，用其他完成的 findings 继续，在 Coverage 节注明失败 reviewer，**不要**因单 reviewer 失败阻塞整次评审。

### Step 3：Phase 3 综合管线

dispatch 全部返回后，读 `d:\WorkSpace\RuoYi-Vue\.trae\skills\ce-doc-review\references\synthesis-and-presentation.md`，按其流程跑：

1. **validate** — 每条 finding 检查 schema 合规、confidence 合法 anchor、evidence 非空。
2. **anchor gate** — 丢 anchor 0/25（silent drop）；anchor 50 → FYI 子节；anchor 75/100 → actionable tier。
3. **dedup** — 同 persona 内重复 finding 合并（保留更高 anchor/severity）。
4. **3.3b same-persona collapse** — 同 persona 同主题多个 finding 折叠。
5. **3.4 cross-persona boost** — 多 persona 指向同一问题 → confidence/severity 提升。
6. **3.5 contradictions** — 不同 persona 结论冲突时按规则消解。
7. **3.5b recommended_action tie-break** — 多 finding 路由冲突时按 Skip > Defer > Apply 优先级。
8. **3.5c chain linking** — 关联 findings 链式呈现。
9. **3.6 promote** — 满足 auto-promotion 模式（factually incorrect behavior / missing standard control / codebase-pattern-resolved fix / framework-native API substitution / mechanically-implied completeness）的 finding 自动提升 autofix_class。
10. **3.7 route** — 按 anchor + autofix_class 路由到：actionable（walk-through）/ FYI 子节 / suppressed。
11. **3.8 sort** — actionable tier 按 severity P0>P1>P2>P3 再按 anchor 100>75。
12. **3.9 suppress restatements** — R29：re-raise 检测（与 prior-decisions 中 Skipped/Deferred 项 evidence overlap >50% → suppress）；R30：fix-landed 检测（prior Applied finding 当前文档已修 → 不再 raise）。

### Step 4：Phase 4 应用 safe_auto + 呈现

- 读 `d:\WorkSpace\RuoYi-Vue\.trae\skills\ce-doc-review\references\review-output-template.md`。
- 对所有 `autofix_class=safe_auto` 且 `confidence=100` 的 finding，**静默应用** suggested_fix 到计划文件（用 Edit 工具改 `d:\WorkSpace\RuoYi-Vue\docs\plans\2026-08-25-001-feat-multitable-import-plan.md`）。记录已应用的 fix 列表。
- 按 review-output-template.md 格式呈现剩余 findings（actionable tier 逐条 + FYI 子节 + Coverage 表）。

### Step 5：Phase 5 交互路由

读 `d:\WorkSpace\RuoYi-Vue\.trae\skills\ce-doc-review\references\walkthrough.md`。

#### 5.1 Routing question（用 AskUserQuestion 工具）

向用户提问："对剩余 actionable findings，希望如何处理？" 4 选项：

- **A. Per-finding walk-through** — 逐条 walk-through：对每条 finding 问 Apply / Defer / Skip（safe_auto 已静默应用，不在此列）。
- **B. Auto-resolve with best judgment** — 全部按 suggested_fix 应用（gated_auto + manual 中有 suggested_fix 的）。读 `references/bulk-preview.md`，先 preview 全部将应用的 fix，问 Proceed/Cancel。
- **C. Append-to-Open-Questions** — 全部 append 到计划文件的 `## Deferred / Open Questions / ### From YYYY-MM-DD review` 子节。读 `references/open-questions-defer.md` 按 mechanic 写入。先 preview。
- **D. Report-only** — 不动文档，仅输出 findings 报告供用户离线决策。

#### 5.2 按 user 选择执行

- 选 A：逐条 finding 用 AskUserQuestion 问 Apply / Defer / Skip（3 选项 + 自定义 Other）。Apply → Edit 应用 suggested_fix；Defer → 写入 Open Questions；Skip → 记录理由。
- 选 B/C：先 preview（读 `references/bulk-preview.md`），用 AskUserQuestion 问 Proceed/Cancel。Proceed → 批量应用或 append；Cancel → 退回 routing question。
- 选 D：直接输出报告，跳到 5.3。

#### 5.3 Terminal question（用 AskUserQuestion 工具）

全部 findings 处理完后，问用户下一步：
- 重新跑 ce-doc-review Round 2（更新 primer，包含本轮决策）
- 返回 ce-plan post-generation 菜单（按 `d:\WorkSpace\RuoYi-Vue\.trae\skills\ce-plan\references\plan-handoff.md` 刷新计数重渲染）
- 跳到下一个工作阶段（如 ce-work 直接进入实现）
- 结束本次 review

按用户选择路由。

---

## 6. 关键约束与陷阱

1. **不要**在 dispatch 前 Read synthesis/walkthrough/bulk-preview/open-questions-defer/review-output-template（SKILL.md 明示"agent dispatch 完成前不加载"）。
2. **不要**让 subagent 调用 ce-* skill 或派生 agent——subagent 是 leaf reviewer，直接返回 JSON。
3. **不要**让 subagent 把 `## Deferred / Open Questions` 节内容当新 finding——那是 prior-round review 输出，不是文档本体。
4. **不要**在 Round 1 primer 里塞 headless pass 的 surfaced-but-unactioned fix——cross-session不持久化，本次是 Round 1。
5. **AskUserQuestion 必须真用工具**——不能因为"不方便"就改成 narrative 文本。ToolSearch 不可用不构成 fallback 触发条件（直接用 AskUserQuestion 即可）。
6. **subagent prompt 要明确告诉它 Read 计划文件获取 {document_content}**——不要试图把全文塞进 prompt 字符串。
7. **并行上限 5**——6 reviewers 必须分 2 批。
8. **safe_auto 静默应用**——不问用户；gated_auto/manual/FYI 才进 routing。
9. **R29/R30 检测依赖 evidence snippet overlap**——synthesis 阶段必须用 finding 的第一个 evidence quote 前 ~120 字符做匹配。
10. **语言**：用户面向输出全部中文；finding 内容可中英混合。

---

## 7. 文件清单（新窗口需 Read 的文件）

按顺序：

1. `d:\WorkSpace\RuoYi-Vue\.trae\skills\ce-doc-review\SKILL.md`（已含 Phase 0-2 全文 + Phase 3-5 总览）
2. `d:\WorkSpace\RuoYi-Vue\.trae\skills\ce-doc-review\references\subagent-template.md`
3. `d:\WorkSpace\RuoYi-Vue\.trae\skills\ce-doc-review\references\findings-schema.json`
4. 6 个 persona 文件（路径见第 2.2 节）
5. `d:\WorkSpace\RuoYi-Vue\docs\plans\2026-08-25-001-feat-multitable-import-plan.md`（待评审文档）
6. `d:\WorkSpace\RuoYi-Vue\docs\brainstorms\2026-08-25-multitable-import-requirements.md`（origin，cross-ref）
7. **dispatch 完成后**：`references/synthesis-and-presentation.md`
8. **Phase 4 前**：`references/review-output-template.md`
9. **Phase 5 前**：`references/walkthrough.md`
10. **routing 选 B/C 时**：`references/bulk-preview.md`、`references/open-questions-defer.md`（C 时）

---

## 8. 简化版接力指令（贴到新任务窗口即可启动）

> 你正在接力执行 ce-doc-review interactive 评审。完整工作流和上下文见 `d:\WorkSpace\RuoYi-Vue\docs\handoffs\2026-08-25-ce-doc-review-multitable-import-plan-handoff.md`——先 Read 该文件获取全貌。
>
> 当前状态：Phase 0-1 已完成，Phase 2 即将开始（公告团队 + dispatch 6 个 reviewer）。
>
> 请按该文件第 5 节"Next — 执行步骤"顺序执行：
> 1. 用中文公告 6-persona 团队（理由见第 3 节）
> 2. 用 Task 工具（subagent_type=general_purpose_task）分 2 批 × 3 dispatch reviewers；每个 subagent 按 subagent-template.md 填充 slot，让其自行 Read 计划文件获取 {document_content}，仅返回 JSON findings
> 3. dispatch 全部返回后读 synthesis-and-presentation.md 跑综合管线
> 4. 应用 safe_auto 静默 fix + 按 review-output-template.md 呈现
> 5. 用 AskUserQuestion 工具发起 routing question（4 选项 A/B/C/D），按选择执行 walk-through 或 bulk-preview
> 6. 用 AskUserQuestion 发起 terminal question
>
> 关键约束见第 6 节。语言用中文。

---

**生成时间**：2026-08-25
**当前会话 ID**：本次 ce-doc-review interactive
**前置阶段**：ce-brainstorm → ce-doc-review（origin）→ ce-plan → ce-doc-review（plan，本次）
