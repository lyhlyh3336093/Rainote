---
date: 2026-07-13
topic: homepage-ai-chat-dialog
---

# 首页 AI 对话功能需求

## Summary

在 notepad 侧边栏"进入聊天室"按钮左侧新增 AI 对话入口，点击后弹出 GLM 原生风格对话弹框，支持流式响应、Markdown 渲染、多轮上下文和按用户持久化历史。Java 后端代理智谱 GLM API 调用，API Key 存配置文件。

> **实现状态说明：** 本需求文档为已在 `notepad/docs/plans/2026-07-08-001-feat-deepseek-ai-chat-plan.md` 中规划并实现的功能的回溯性规格说明，用于补充安全/UX/一致性约束。实现代码已在 U1-U5 实施单元中落地（前后端代码见 `notepad/src/views/aiChat/` 与 `ruoyi-system/.../ai/`）。

## Problem Frame

notepad 应用已有 WebSocket 人工聊天室（`notepad/src/views/chat/index.vue`），但没有 AI 对话能力。用户需要 AI 辅助时必须单独打开 chatglm.cn，体验割裂。将 AI 对话嵌入侧边栏弹框，作为独立工具就近可用（v1 不与笔记/表格内容联动，工作流集成留待后续迭代评估）。

## Key Decisions

- **后端代理而非前端直调** — API Key 存储在后端配置文件中，前端不接触密钥。安全，可加鉴权和日志。
- **限制最近 N 轮上下文** — 对话越长，发送给 GLM API 的历史消息越多，可能超出 token 限制。只发送最近 N 轮（N 可配置），牺牲早期上下文换取稳定性。
- **GLM 原生风格布局** — 弹框内含对话列表侧边栏 + 消息区 + 输入框，与 chatglm.cn 一致，降低用户认知成本。

## Requirements

### 入口与容器

- R1. 在 `notepad/src/layout/sidebar/index.vue` 的"进入聊天室"按钮左侧新增 AI 对话按钮。
- R2. 点击按钮弹出对话弹框，弹框样式与现有聊天室弹框风格一致（a-modal）。
- R3. 弹框内布局为 GLM 原生风格：左侧对话列表侧边栏、右侧消息区、底部输入框。

### 对话功能

- R4. 用户可新建对话、切换历史对话、删除对话。
- R4a. 对话标题默认取首条用户消息前 20 个字符自动生成；首条消息发送前显示"新对话"。标题用户可点击编辑。
- R4b. 删除对话前必须弹出确认对话框（"确定删除此对话？删除后不可恢复"）。删除当前激活对话后，消息区显示空状态 + "新建对话"引导，不自动切换到其他对话。
- R5. 消息发送后，AI 回复以流式方式逐字显示（SSE 打字机效果）。
- R5a. 流式响应进行中时，输入框禁用，发送按钮变为"停止生成"（取消当前 SSE 流）。停止后已接收的部分回复保留并标记为 incomplete。
- R5b. 用户关闭弹框、切换页面或切换对话时，若当前有进行中的流式响应，前端立即取消 SSE 连接（`EventSource.close()` / `AbortController.abort()`），后端释放 emitter。已接收的部分回复按 R5a 的 incomplete 规则持久化。
- R6. AI 回复支持 Markdown 渲染，包括代码块、表格、列表和代码语法高亮。
- R6a. AI 回复渲染前必须经过 HTML 净化（如 DOMPurify），markdown-it 配置 `html: false`，代码高亮输出走显式 allowlist。防止存储型 XSS。
- R7. 对话保持多轮上下文，AI 能引用当前对话中之前的消息。
- R8. 发送给 GLM API 的上下文限制为最近 N 轮消息（每轮 = 一问一答 = 2 条消息，N 可在配置文件中调整），超出部分不发送。

### 持久化

- R9. 对话历史持久化到数据库，按用户隔离。每个登录用户有独立的对话列表。
- R9a. 后端在每次获取对话、获取消息列表、删除对话前必须校验 `conversation.user_id == current_user.id`，校验失败返回 403。防止 IDOR 越权访问。
- R10. 重新打开弹框时恢复用户的历史对话列表和上次选中的对话。

### 后端与鉴权

- R11. Java 后端代理 GLM API 调用，前端不直接调用智谱 API。
- R12. API Key 和模型名存储在后端配置文件中（如 `application.yml`），不硬编码在代码中。
- R13. 所有登录用户均可使用 AI 对话功能，无需额外角色授权。

## Key Flows

- F1. 新建对话并发送消息
  - **Trigger:** 用户点击"新建对话"并输入消息后发送。
  - **Steps:** 创建新对话记录 → 前端发送消息到后端 → 后端组装 GLM API 请求（含空历史）→ 建立 SSE 连接 → AI 流式回复逐字返回前端 → 回复完成后持久化消息。
  - **Covers:** R4, R5, R9, R11.

- F2. 切换到历史对话
  - **Trigger:** 用户在对话列表侧边栏点击某个历史对话。
  - **Steps:** 前端请求该对话的消息列表 → 后端返回历史消息 → 前端渲染消息区 → 用户可继续发送消息，上下文从该对话的历史消息中取最近 N 轮。
  - **Covers:** R4, R8, R10.

- F3. 流式接收 AI 回复
  - **Trigger:** 后端收到 GLM API 的流式响应。
  - **Steps:** 后端通过 SSE 逐 chunk 推送到前端 → 前端追加到当前消息气泡 → 流结束后标记消息完成 → 持久化完整回复。
  - **Covers:** R5, R9.

## Acceptance Examples

- AE1. 上下文超限裁剪
  - **Given:** 一个对话已有 30 条消息（即 15 轮），配置 N=10 轮。
  - **When:** 用户发送第 31 条消息。
  - **Then:** 后端只发送最近 10 轮（20 条）历史消息给 GLM API，第 1-10 条不发送。AI 回复基于最近 10 轮上下文。

- AE2. Markdown 渲染
  - **Given:** AI 回复包含代码块和表格。
  - **When:** 回复流式输出完成。
  - **Then:** 代码块显示语法高亮，表格正确渲染为 HTML 表格，列表有正确的缩进和项目符号。

- AE3. 新用户首次打开
  - **Given:** 用户首次打开 AI 对话弹框，无任何历史对话。
  - **When:** 弹框加载完成。
  - **Then:** 对话列表为空，显示"新建对话"引导，消息区显示欢迎提示。

## Scope Boundaries

### Deferred for later

- 多模型选择 — v1 只接一个 GLM 模型，模型名可配置。未来可扩展模型选择器。
- 多模态输入（图片、文件上传）— v1 纯文本对话。
- API 调用限流和成本监控 — 部署运维关注点，不在功能需求内。

## Dependencies / Assumptions

- 已有智谱开放平台 API Key，且账户有足够的调用额度。
- GLM API 支持流式输出（SSE），智谱开放平台文档确认支持。
- notepad 已依赖 `markdown-it` ^14.3.0 与 `highlight.js` ^11.8.0（见 `notepad/package.json`），无需新增 Markdown 渲染依赖。
- RuoYi 后端 Spring Boot 支持 SSE（`SseEmitter` 或响应式方案）。

## Sources

- `notepad/src/layout/sidebar/index.vue` — 现有"进入聊天室"按钮位置（第 25 行）和弹框模式（a-modal）。
- `notepad/src/views/chat/index.vue` — 现有 WebSocket 聊天室组件，可参考布局但功能不同。

## Deferred / Open Questions

### From 2026-07-13 review

- **Existing implementation-ready plan covers same scope** — Summary / Problem Frame (P0, scope-guardian, confidence 100)

  notepad/docs/plans/2026-07-08-001-feat-deepseek-ai-chat-plan.md already specifies this same AI chat dialog feature in implementation-ready detail — sidebar button, a-modal, session list, SSE streaming, Markdown rendering, per-user persistence, DB schema (ai_chat_session/ai_chat_message), API contracts, and 7 implementation units with DoD. This requirements doc re-specifies the same scope from scratch without referencing, superseding, or diffing against the existing plan. Implementers will not know which plan governs, risking duplicate work, divergent backends, and two incompatible data models for the same feature.

- **Error states for API/SSE failures unspecified** — Requirements / Key Flows (P0, design-lens, confidence 100)

  When GLM API calls fail, SSE connections drop mid-stream, the network is lost, or the API key is invalid, users will see a frozen UI with no feedback and no recovery path. Implementers will either build inconsistent error handling or block waiting for spec. Flows F1-F3 describe only the happy path with zero error branches.

- **Concurrent actions during streaming undefined** — Requirements / Key Flows (P1, design-lens, confidence 100)

  Flows F1-F3 do not specify what happens when a user takes a concurrent action during an active stream — sending a new message while the previous AI reply is still streaming, switching to a different conversation mid-stream, closing the modal, or deleting the active conversation. Without these branches, implementers will either block the UI entirely (poor UX) or allow races that corrupt the SSE pipeline, mismatch messages to sessions, or leak dangling emitters on the backend. The requirements need to state the intended UX (disable input while streaming? allow cancellation? what happens to the in-flight response?).

- **Loading states not specified** — Requirements / Key Flows (P1, design-lens, confidence 100)

  The requirements do not specify loading states for: initial dialog open (fetching the conversation list), switching to a historical conversation (fetching the message list), or sending a message and waiting for the first SSE token (time-to-first-byte). Without these, implementers will either omit feedback entirely (poor UX, users perceive the app as frozen) or build inconsistent ad-hoc spinners across the three transitions. The requirements need to state the expected loading UX for each async transition — skeleton, spinner, disabled state, placeholder text.

- **No authz check on conversation access/deletion (IDOR)** — Requirements / Key Flows (P1, security-lens, confidence 100)

  R9 states conversations are "per-user isolated" but F2 (switch conversation) and R4 (delete conversation) do not specify that the backend must verify the requesting user owns the target conversation ID. Without this check, an authenticated user can pass another user's conversation ID and read or delete their data — an Insecure Direct Object Reference (IDOR) vulnerability. The requirements need an explicit authorization clause: every conversation fetch, message list fetch, and delete must verify `conversation.user_id == current_user.id` before proceeding.

- **Markdown render enables stored XSS** — Requirements (P1, security-lens, confidence 100)

  R6 requires Markdown rendering of AI replies, but the requirements do not specify HTML sanitization. GLM output is not trusted — it can be manipulated via prompt injection or produce raw HTML by design. Rendering AI output as HTML without sanitization enables stored XSS: malicious markup renders in the victim's browser, exfiltrating the user's session token, conversation history, or executing actions under their credentials (especially severe because R9 persists the unsanitized output to the database, re-infecting every future view). The requirements need an explicit sanitization clause — render Markdown through a sanitizer (e.g., DOMPurify) with `html: false` in markdown-it config and explicit allowlist for code highlighting output.

- **N-round limit ignores token-based overflow** — Requirements (P1, adversarial, confidence 100)

  R8 caps context at "the last N rounds" but rounds have wildly different token costs — a round with a 5000-token code dump counts the same as a "yes/no" round. A conversation of 20 short rounds fits comfortably under GLM's token limit, while 5 long rounds can blow past it. The round-count limit either over-truncates (wasteful) or under-truncates (request fails). The requirements need a token-aware truncation strategy — count tokens (e.g., via tiktoken or GLM's tokenizer) and truncate from the oldest message until under the budget, with N as a secondary cap.

- **Unrestricted access + no rate limiting** — Requirements (P1, product-lens + security-lens + adversarial, confidence 100)

  R13 grants AI chat access to every logged-in user with no usage cap, no rate limit, and no per-user quota. Combined with the backend-proxied API key (R11), any authenticated user can issue unlimited GLM calls at the operator's expense — accidental loops, scripted abuse, or a shared account all produce runaway API bills. The "Deferred for later" section explicitly defers rate limiting to "ops", but this is a product-level abuse vector that affects cost, fairness, and availability for all users, not just the abuser. The requirements need at minimum a per-user rate limit (e.g., X messages/minute) and a daily quota even if the exact numbers are deferred to config.

- **R10 restore-on-reopen has no delivering flow** — Requirements / Key Flows (P1, scope-guardian + adversarial, confidence 100)

  R10 says "重新打开弹框时恢复用户的历史对话列表和上次选中的对话" but no Key Flow delivers this requirement — F1 covers new conversation, F2 covers switching, F3 covers streaming. There is no F4 describing: (a) what triggers the restore (dialog open event), (b) what the backend returns (list + last-selected conversation ID + its messages), (c) the order of fetching (list first, then messages of last-selected? or both in parallel?), (d) what happens if the last-selected conversation was deleted in another session. Without an F4, R10 is an unimplemented requirement hiding in plain sight. The suggested fix is to add F4 covering the restore flow.

- **Workflow integration goal unsupported** — Problem Frame (P1, product-lens, confidence 75)

  The Problem Frame claims the goal is to keep users "in their note/table workflow" without switching pages, but no requirement delivers workflow integration. The AI chat is a standalone modal with no link to the surrounding note or table context — the user can't ask the AI about the current note, reference the active row, or paste a selection into the prompt. The "workflow integration" premise is decorative. Either remove the framing from the Problem Frame (honest about what's being built: a standalone AI chat in a modal) or add a requirement like "send current note/row context to AI on demand" to deliver the stated goal.

- **No stop/cancel for streaming** — Requirements / Key Flows (P1, design-lens, confidence 75)

  Once an AI stream starts, there is no way for the user to abort it. Long responses, wrong turns, or accidental sends force the user to wait for stream completion before they can continue. The requirements need a stop button that aborts the SSE connection on the backend and either discards or partial-saves the in-flight response on the frontend, plus a clear rule for what gets persisted when a stream is cancelled mid-flight.

- **Responsive/accessibility strategy absent** — Requirements (P1, design-lens, confidence 75)

  The GLM-native style layout (conversation sidebar + message area + input) does not specify responsive behavior on small screens (does the sidebar collapse? does the modal go full-screen on mobile?) or accessibility (ARIA roles for the dialog, keyboard navigation between sidebar items and input, focus trap, screen reader announcements for streaming tokens). Without these, the modal may be unusable on mobile or for assistive-tech users, and RuoYi's existing accessibility baseline is unknown.

- **Conversation naming/identification unspecified** — Requirements (P1, design-lens, confidence 75)

  R4 allows new/switch/delete conversations but does not specify how conversations are named or identified in the sidebar list. Are titles auto-generated (first N chars of the first user message? a timestamp? "New conversation 1, 2, 3..."?) Are they user-editable? What's the empty-state label before the first message is sent? Without this, the sidebar will either show raw IDs, empty entries, or whatever the implementer improvises — inconsistent UX. Suggested fix: auto-title from the first user message (truncated to N chars), user-editable on click.

- **Backend host conflicts with existing plan** — Requirements (P1, scope-guardian, confidence 75)

  This doc specifies the backend proxy as RuoYi Java (`application.yml`, R11/R12), but `notepad/docs/plans/2026-07-08-001-feat-deepseek-ai-chat-plan.md` specifies Node.js (`notepad/server/`). Two different backends for the same AI chat feature means divergent infrastructure, duplicated maintenance, and no shared API contract. This requirement needs to either explicitly supersede the existing plan (and state why the Java backend wins) or reconcile with it.

- **Persistence assumes SSE stream completes** — Requirements / Key Flows (P1, adversarial, confidence 75)

  F3 says "流结束后...持久化完整回复" — but streams can be interrupted (network drop, server crash, user closes modal, user cancels). What gets persisted on interruption? Partial reply marked incomplete? Not persisted at all? Persistenced as complete with truncation? The requirement needs an explicit branch for interrupted streams, otherwise the database will hold inconsistent state and F2 (switch conversation) will render corrupted partial replies.

- **Round vs message unit drift in AE1** — Acceptance Examples (P2, coherence, confidence 75)

  AE1 says "一个对话已有 30 轮消息，配置 N=20" — but "rounds" and "messages" are different units. A round is a (user + assistant) pair = 2 messages. "30 轮消息" reads ambiguously as "30 messages" or "30 rounds of messages". R8 also says "最近 N 轮消息". Either standardize on messages (and convert N to message count) or standardize on rounds (and clarify N counts round pairs). Suggested fix: change AE1 to "已有 30 条消息（15 轮），配置 N=10 轮" to disambiguate.

- **R3 GLM-native style scope-exceeds-goal** — Requirements (P2, scope-guardian, confidence 75)

  R3 requires "GLM 原生风格布局" with a conversation sidebar — but the stated goal in the Problem Frame is just "AI 对话能力". A sidebar with multiple conversations, switching, and deletion is significantly more UX surface than "an AI chat" requires. This is scope inflation against the stated goal. Consider whether v1 needs the sidebar at all, or whether a single conversation with history would suffice for the MVP. Suggested fix: defer the sidebar to v2, ship single-conversation mode for v1.

- **R8 configurable N is config-ahead-of-need** — Requirements (P2, scope-guardian, confidence 75)

  R8 says N is "可在配置文件中调整" but there's no evidence anyone will actually need to tune N per-deployment. This is speculative flexibility (YAGNI). Consider hardcoding N to a sensible default (e.g., 20) until a real tuning need emerges from production. Suggested fix: drop the "可配置" qualifier from R8, ship N=20 as a constant, revisit if a tuning need surfaces.

- **PII flowing to third-party GLM** — Requirements (P2, security-lens, confidence 75)

  User prompts may contain PII (names, emails, internal business data, source code with secrets). The requirements do not mention a data classification boundary, PII scrubbing, or a warning to users that their input is sent to a third-party LLM provider (智谱). Depending on the deployment context, this may violate data protection obligations or internal compliance. At minimum, the UI should disclose third-party transmission; ideally, the backend should redact obvious PII patterns before forwarding to GLM.

- **API Key rotation/environment separation unspecified** — Requirements (P2, security-lens, confidence 75)

  R12 says the API key is stored in `application.yml` but does not specify how the key is rotated, how different environments (dev/staging/prod) use different keys, or whether the key is loaded from a secret manager vs. committed to the repo. A single hardcoded key in config is a key-management anti-pattern that complicates rotation and leaks across environments. The requirement should specify environment-specific key sourcing (e.g., `${GLM_API_KEY:#{'dev-key'}}` with env var override).

- **WebSocket reuse not considered** — Requirements (P2, adversarial, confidence 75)

  The notepad already has a WebSocket connection for the human chat room (`notepad/src/views/chat/index.vue`). The new AI chat introduces SSE as a separate transport. Two chat-like transports in the same app means duplicated connection management, duplicated reconnect logic, and divergent backend handlers. The requirements should either document why SSE is necessary (e.g., GLM API is SSE-only) or consider whether the existing WebSocket can serve both human and AI chat to reduce transport complexity.

- **Delete active conversation behavior undefined** — Requirements / Key Flows (P2, design-lens, confidence 75)

  R4 allows delete but does not specify what happens when the user deletes the currently-active conversation (the one whose messages are rendered in the message area). Does the UI show an empty state? Auto-switch to the next-most-recent conversation? Show a "create new conversation" prompt? Without this, the implementer will improvise and the user may see stale messages from a deleted conversation. Suggested fix: after delete, if the deleted conversation was active, show the empty state with a "new conversation" CTA.

- **Rounds-based truncation (R8) does not solve the token-limit problem it cites** — R8 / Key Decisions (P1, adversarial, scope-guardian, confidence 100)

  R8 justifies truncation by "may exceed token limits," but the chosen unit is conversation rounds, not tokens. A single round containing a long code answer can be 4,000+ tokens; 20 such rounds (AE1's N=20) could exceed GLM-4's context window, causing API 400 errors mid-conversation. Conversely, 20 short rounds waste available context, degrading answer quality. The decision-scope mismatch means the stated problem (token overflow) is not actually solved by the chosen mechanism (round counting).
