---
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: ce-plan-bootstrap
execution: code
title: "feat: DeepSeek AI Chat with Session Management"
date: 2026-07-08
sequence: 001
---

# feat: DeepSeek AI Chat with Session Management

## Summary

在首页左侧菜单列表下方新增"呼叫小AI"按钮，点击后弹出 DeepSeek AI 对话窗口。弹框采用 ChatGPT 式布局（左侧会话列表 + 右侧对话区），支持多会话管理。后端代理调用 DeepSeek API（SSE 流式响应），对话记录入库保存，按用户 ID 隔离。

## Problem Frame

当前 notepad 应用有 WebSocket 聊天室，但缺少 AI 辅助能力。用户需要一个内嵌的 AI 对话入口，能够与 DeepSeek 大模型交互，且对话上下文持久化保存，在不同会话间切换。平台统一提供 API Key，用户无需自行配置。

**主要参与者：** 已登录的 notepad 用户（通过 `userInfo.user.userId` 标识）

**核心流程：** 用户点击"呼叫小AI" → 弹框打开 → 选择/新建会话 → 输入消息 → 后端代理调用 DeepSeek（SSE 流式）→ 前端逐字显示回复 → 消息入库 → 可切换/删除历史会话

## Requirements

- **R1:** 侧边栏菜单下方新增"呼叫小AI"按钮，点击弹出对话弹框
- **R2:** 弹框内左侧显示会话列表（新建/切换/删除），右侧显示当前会话对话内容
- **R3:** 后端代理调用 DeepSeek API，API Key 保存在后端，前端不接触密钥
- **R4:** 使用 SSE 流式传输，前端逐字显示 AI 回复
- **R5:** 对话记录入库保存，包含会话表和消息表，按 userId 隔离
- **R6:** 支持创建多个独立会话，每个会话独立维护上下文
- **R7:** 打开会话时加载历史消息，AI 回复时携带会话上下文
- **R8:** AI 回复内容支持 Markdown 渲染（代码高亮、列表、表格等）

## Key Technical Decisions

### KTD1: 后端代理 DeepSeek API（非前端直连）
**决策：** 后端代理所有 DeepSeek API 调用，API Key 存储在后端环境变量中。
**理由：** 安全性——前端暴露 API Key 会被用户窃取；且平台统一密钥模式下，后端代理是唯一可行方案。
**影响：** 需要后端新增 SSE 代理接口，前端通过后端中转获取流式响应。

### KTD2: SSE 流式响应（非一次性返回）
**决策：** 后端通过 Server-Sent Events 将 DeepSeek 的流式响应转发给前端。
**理由：** 用户提供更好的交互体验（打字机效果），与 ChatGPT 体验一致。
**影响：** 前端需使用 `EventSource` 或 `fetch + ReadableStream` 处理 SSE；后端需保持 SSE 连接直到 DeepSeek 响应结束。

### KTD3: 两表数据模型（sessions + messages）
**决策：** 数据库使用两张表——`ai_chat_session`（会话）和 `ai_chat_message`（消息），通过 `session_id` 关联。
**理由：** 多会话模式需要独立管理每个会话的元数据和消息列表，两表结构清晰且查询高效。

### KTD4: Markdown 渲染 + 代码高亮
**决策：** AI 回复使用 `markdown-it` 渲染，代码块使用已有的 `highlight.js` 高亮。
**理由：** 项目已依赖 `highlight.js`，只需新增 `markdown-it`；Markdown 渲染是 AI 对话的基本期望。

### KTD5: 项目合并——后端代码并入前端项目
**决策：** 将后端代码合并到当前 notepad 项目中，形成全栈单项目开发。
**理由：** 用户明确要求合并前后端项目统一开发。
**影响：** 需要在项目中新增后端目录结构（如 `server/`），配置开发代理将 API 请求转发到后端服务。

## High-Level Technical Design

```
┌─────────────────────────────────────────────────────────┐
│                    浏览器 (前端 Vue 3)                     │
│                                                         │
│  ┌─────────────┐    ┌──────────────────────────────┐   │
│  │  Sidebar    │    │     AI Chat Modal             │   │
│  │             │    │ ┌────────┬─────────────────┐  │   │
│  │  菜单列表    │    │ │会话列表 │   对话区域       │  │   │
│  │             │    │ │        │                 │  │   │
│  │ 进入聊天室   │    │ │ 新建   │  消息气泡(MD渲染)│  │   │
│  │ 呼叫小AI ←──┼────┼─┤ 会话1  │                 │  │   │
│  │             │    │ │ 会话2  │  输入框 + 发送   │  │   │
│  └─────────────┘    │ │ 会话3  │                 │  │   │
│                     │ └────────┴─────────────────┘  │   │
│                     └──────────────────────────────┘   │
│                                                         │
│  Pinia Store (aiChatStore)                              │
│    ├── sessions[]     会话列表                          │
│    ├── currentSession 当前会话                          │
│    └── messages[]     当前会话消息                      │
└─────────────────────────┬───────────────────────────────┘
                          │ HTTP (useFetch) + SSE (EventSource)
                          │ Authorization: token (cookie)
                          ▼
┌─────────────────────────────────────────────────────────┐
│                  后端 (server/)                          │
│                                                         │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────┐  │
│  │ Session API │  │ Message API  │  │ DeepSeek Proxy│  │
│  │             │  │              │  │   (SSE)       │  │
│  │ POST /create│  │ GET /list    │  │ POST /chat    │  │
│  │ GET  /list  │  │ POST /save   │  │ → DeepSeek API│  │
│  │ DELETE /:id │  │              │  │ ← SSE stream  │  │
│  └──────┬──────┘  └──────┬───────┘  └───────┬───────┘  │
│         │                │                  │           │
│         ▼                ▼                  │           │
│  ┌─────────────────────────────────────┐    │           │
│  │          数据库 (MySQL)              │    │           │
│  │                                     │    │           │
│  │  ai_chat_session                    │    │           │
│  │   - id, user_id, title, created_at  │    │           │
│  │                                     │    │           │
│  │  ai_chat_message                    │    │           │
│  │   - id, session_id, role, content,  │    │           │
│  │     created_at                      │    │           │
│  └─────────────────────────────────────┘    │           │
│                                             │           │
│  API Key: 环境变量 DEEPSEEK_API_KEY ◄───────┘           │
└─────────────────────────────────────────────────────────┘
```

## Implementation Units

### U1. 后端-数据库表设计与建表

**Goal:** 创建 AI 对话所需的数据库表结构

**Requirements:** R5, R6

**Dependencies:** 无

**Files:**
- `server/sql/ai_chat_init.sql` — 建表脚本

**Approach:**

创建两张表：

```
ai_chat_session:
  - id           BIGINT PRIMARY KEY AUTO_INCREMENT
  - user_id      BIGINT NOT NULL          -- 用户ID (关联现有用户表)
  - title        VARCHAR(100)             -- 会话标题 (默认取首条消息前20字)
  - created_at   DATETIME DEFAULT NOW()
  - updated_at   DATETIME DEFAULT NOW()
  - INDEX idx_user_id (user_id)

ai_chat_message:
  - id           BIGINT PRIMARY KEY AUTO_INCREMENT
  - session_id   BIGINT NOT NULL          -- 会话ID
  - role         VARCHAR(20) NOT NULL     -- 'user' | 'assistant'
  - content      TEXT NOT NULL            -- 消息内容
  - created_at   DATETIME DEFAULT NOW()
  - INDEX idx_session_id (session_id)
```

**Test scenarios:**
- 建表脚本执行无报错，两张表创建成功
- 插入测试数据验证字段约束（user_id 非空、role 枚举值）

**Verification:** 数据库中存在 `ai_chat_session` 和 `ai_chat_message` 两张表，字段类型和索引正确

---

### U2. 后端-DeepSeek API 代理接口（SSE 流式）

**Goal:** 实现后端代理接口，接收前端消息，调用 DeepSeek API，通过 SSE 转发流式响应

**Requirements:** R3, R4, R7

**Dependencies:** U1

**Files:**
- `server/controller/aiChatController.js` (或对应后端语言的控制器) — SSE 代理接口
- `server/service/deepseekService.js` — DeepSeek API 调用封装
- `server/.env` — `DEEPSEEK_API_KEY` 环境变量

**Approach:**

**接口定义：** `POST /ai/chat`

**请求：**
```json
{
  "sessionId": 123,
  "message": "用户输入的消息"
}
```

**响应：** `text/event-stream` (SSE)
```
data: {"content": "你", "done": false}

data: {"content": "好", "done": false}

data: {"content": "！", "done": false}

data: {"content": "", "done": true, "messageId": 456}
```

**处理流程：**
1. 从 token 鉴权获取 userId
2. 根据 sessionId 查询历史消息（构建上下文）
3. 调用 DeepSeek API（`deepseek-chat` 模型），开启 `stream: true`
4. 将用户消息入库（`role: user`）
5. 逐 chunk 转发 SSE 给前端
6. 流结束后，将完整 AI 回复入库（`role: assistant`）
7. 如果是会话首条消息，更新会话标题

**DeepSeek API 调用参数：**
- Model: `deepseek-chat`
- Messages: 历史消息 + 当前用户消息
- Stream: true
- API Key: 从环境变量 `DEEPSEEK_API_KEY` 读取

**Patterns to follow:** 后端现有的鉴权中间件（从 token 获取 userId）、现有的数据库操作模式

**Test scenarios:**
- **Happy path:** 发送消息 → 收到 SSE 流式响应 → 完整回复入库
- **上下文:** 多轮对话时，DeepSeek 收到正确的历史消息上下文
- **Auth:** 未登录请求返回 401
- **DeepSeek 错误:** API Key 无效或额度不足时，返回错误 SSE 事件
- **首条消息:** 会话首条消息触发标题更新
- **网络中断:** DeepSeek 连接中断时，已有部分回复正常入库

**Verification:** 前端能通过 SSE 接收到逐字回复，回复结束后数据库中有完整的 user + assistant 消息记录

---

### U3. 后端-会话管理 CRUD 接口

**Goal:** 实现会话列表查询、创建、删除接口

**Requirements:** R2, R5, R6

**Dependencies:** U1

**Files:**
- `server/controller/aiChatController.js` — 会话 CRUD 接口（与 U2 同文件）

**Approach:**

**接口定义：**

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/ai/session/create` | 创建新会话，返回 `{ sessionId, title, createdAt }` |
| GET | `/ai/session/list` | 获取当前用户所有会话列表，按 `updated_at` 降序 |
| DELETE | `/ai/session/:id` | 删除会话及其所有消息（级联删除） |
| GET | `/ai/message/list?sessionId=xx` | 获取指定会话的所有消息 |

**响应格式（遵循项目现有 `{ code, msg, data }` 格式）：**
```json
{
  "code": 200,
  "msg": "success",
  "data": { ... }
}
```

**鉴权：** 所有接口通过现有 token 鉴权中间件获取 userId，只能操作当前用户的会话

**Test scenarios:**
- **创建会话:** POST create → 返回新 sessionId
- **会话列表:** 用户 A 创建 3 个会话 → GET list 返回 3 条，按时间降序
- **用户隔离:** 用户 A 看不到用户 B 的会话
- **删除会话:** DELETE 后 → 会话和关联消息均被删除
- **消息列表:** GET messages by sessionId → 返回按时间升序的消息
- **越权访问:** 用户 A 删除用户 B 的会话 → 返回错误

**Verification:** 所有 CRUD 接口功能正常，用户数据隔离正确

---

### U4. 前端-"呼叫小AI"按钮与弹框框架

**Goal:** 在侧边栏添加"呼叫小AI"按钮，点击弹出 AI 对话弹框框架

**Requirements:** R1

**Dependencies:** 无（可与 U2/U3 并行开发，使用 mock 数据）

**Files:**
- `src/layout/sidebar/index.vue` — 修改：新增按钮和弹框
- `src/views/aiChat/index.vue` — 新建：AI 对话主组件
- `src/stores/aiChat/index.ts` — 新建：AI 对话 Pinia store

**Approach:**

**侧边栏修改（`src/layout/sidebar/index.vue`）：**
- 在"进入聊天室"按钮下方新增"呼叫小AI"按钮
- 新增 `isAiChatVisible` 状态控制弹框
- 新增 `<a-modal>` 弹框，宽 `1200px`，`:footer="null"`，`destroy-on-close`
- 弹框内渲染 `<ai-chat />` 组件

**AI 对话 Store（`src/stores/aiChat/index.ts`）：**
```
state:
  - sessions: Session[]         // 会话列表
  - currentSessionId: number    // 当前选中会话
  - messages: Message[]         // 当前会话消息
  - isStreaming: boolean        // 是否正在接收流式响应

actions:
  - fetchSessions()             // GET /ai/session/list
  - createSession()             // POST /ai/session/create
  - deleteSession(id)           // DELETE /ai/session/:id
  - selectSession(id)           // 切换会话 + GET /ai/message/list
  - sendMessage(text)           // POST /ai/chat (SSE) + 追加消息
```

**Message 类型：**
```ts
interface Message {
  id?: number
  role: 'user' | 'assistant'
  content: string
  createdAt?: string
}
```

**Patterns to follow:**
- 弹框模式参考现有聊天室弹框（`sidebar/index.vue` L31-L42）
- Store 模式参考 `src/stores/user/index.ts` 和 `src/stores/menu/index.ts`
- API 调用使用 `useFetch` hook

**Test scenarios:**
- **按钮显示:** 侧边栏菜单下方显示"呼叫小AI"按钮，位于"进入聊天室"下方
- **弹框打开:** 点击按钮 → 弹框打开，标题"呼叫小AI"
- **弹框关闭:** 关闭弹框 → 状态重置（destroy-on-close）

**Verification:** 按钮可见，点击后弹框正常打开和关闭

---

### U5. 前端-会话列表侧边栏组件

**Goal:** 实现弹框左侧的会话列表，支持新建/切换/删除会话

**Requirements:** R2, R6

**Dependencies:** U4

**Files:**
- `src/views/aiChat/components/SessionList.vue` — 新建：会话列表组件
- `src/views/aiChat/index.vue` — 修改：引入 SessionList 组件

**Approach:**

**布局：** 弹框内使用 flex 布局，左侧 `SessionList` 宽 `240px`，右侧对话区 `flex: 1`

**SessionList 组件功能：**
- 顶部"新建对话"按钮（`<a-button type="dashed" block>`）
- 会话列表（`<a-list>`），每项显示标题和时间
- 点击会话项切换当前会话（调用 `selectSession`）
- 每项右侧删除按钮（`<a-popconfirm>` 确认后删除）
- 当前选中会话高亮
- 空状态提示（"暂无会话，点击新建开始对话"）

**新建会话流程：**
1. 调用 `createSession()` → 后端创建 → 返回 sessionId
2. 自动选中新会话 → 清空消息列表
3. 对话区显示空状态 + 输入框

**Test scenarios:**
- **新建会话:** 点击"新建对话" → 列表新增一项，自动选中
- **切换会话:** 点击不同会话项 → 右侧对话区切换内容
- **删除会话:** 点击删除 → 确认后从列表移除
- **当前选中高亮:** 选中项有视觉区分
- **空状态:** 无会话时显示提示文案

**Verification:** 会话列表的增删切换功能完整可用

---

### U6. 前端-对话区域与消息渲染

**Goal:** 实现右侧对话区——消息列表（含 Markdown 渲染）、输入框、SSE 流式接收

**Requirements:** R4, R7, R8

**Dependencies:** U4, U5

**Files:**
- `src/views/aiChat/components/ChatArea.vue` — 新建：对话区域主组件
- `src/views/aiChat/components/MessageBubble.vue` — 新建：单条消息气泡（Markdown 渲染）
- `src/utils/markdown.ts` — 新建：markdown-it 实例配置

**新增依赖：** `markdown-it`（需 `npm install markdown-it`）

**Approach:**

**ChatArea 组件结构：**
```
<div class="chat-area">
  <div class="messages" ref="messagesContainer">  <!-- 滚动区域 -->
    <MessageBubble v-for="msg in messages" :key="msg" :message="msg" />
  </div>
  <div class="input-area">
    <a-textarea v-model:value="inputText" @pressEnter="send" :auto-size="{ minRows: 1, maxRows: 4 }" />
    <a-button type="primary" @click="send" :loading="isStreaming">发送</a-button>
  </div>
</div>
```

**消息发送流程（SSE 流式）：**
1. 用户输入消息 → 按 Enter 或点击发送
2. 立即在消息列表追加 `{ role: 'user', content: inputText }`（乐观更新）
3. 创建 `EventSource` 或使用 `fetch` + `ReadableStream` 连接 `/ai/chat`
4. 收到 SSE `data` 事件 → 追加内容到最后一条 assistant 消息
5. 收到 `done: true` → 结束流，滚动到底部
6. 发送期间禁用输入框，显示 loading 状态

**SSE 接收方案：**
由于 `useFetch` 不支持 SSE，需要使用原生 `fetch` + `ReadableStream` 处理：
```ts
// 方向性伪代码，非实现规范
const response = await fetch('/api/ai/chat', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'Authorization': token },
  body: JSON.stringify({ sessionId, message })
})
const reader = response.body.getReader()
// 循环读取 chunk，解析 SSE data: 行，追加到 assistant 消息
```

**MessageBubble 组件：**
- 用户消息：右对齐，蓝色背景
- AI 消息：左对齐，灰色背景，使用 `markdown-it` 渲染 `content`
- 代码块使用已有的 `highlight.js` 高亮
- 流式消息：显示光标闪烁动画

**Markdown 配置（`src/utils/markdown.ts`）：**
- 使用 `markdown-it` 实例
- 配置 `highlight.js` 作为代码高亮
- 允许代码块、列表、表格、链接等基础语法

**滚动行为：** 新消息出现时自动滚动到底部；流式响应时持续跟随滚动

**Test scenarios:**
- **发送消息:** 输入文本 → Enter → 用户消息出现在右侧 → AI 流式回复出现在左侧
- **流式效果:** AI 回复逐字显示，有光标动画
- **Markdown 渲染:** AI 回复含代码块 → 代码高亮显示；含列表/表格 → 正确渲染
- **Enter 发送:** 按 Enter 发送，Shift+Enter 换行
- **发送中禁用:** 流式响应期间输入框禁用，发送按钮 loading
- **自动滚动:** 新消息出现时自动滚动到底部
- **历史加载:** 切换到有历史消息的会话 → 消息列表正确显示

**Verification:** 对话功能完整，流式响应体验流畅，Markdown 正确渲染

---

### U7. 前端-项目配置与开发环境整合

**Goal:** 配置 Vite 代理、安装依赖、整合前后端开发环境

**Requirements:** KTD5

**Dependencies:** U4

**Files:**
- `vite.config.ts` — 修改：确保 `/api` 代理指向后端开发服务
- `package.json` — 修改：新增 `markdown-it` 依赖
- `public/config.js` — 修改：新增 AI 相关配置项

**Approach:**

**Vite 代理配置：**
确保 `vite.config.ts` 中 `/api` 代理指向后端开发服务器地址。如果后端已合并到本项目中，代理到本地后端端口。

**新增依赖：**
- `markdown-it` — Markdown 渲染

**public/config.js 新增配置：**
```js
window.config = {
  deduplicatePath: '/system/column/deduplicate',
  // AI 对话 SSE 接口地址（需要单独配置因为 useFetch 不支持 SSE）
  aiChatStreamPath: '/api/ai/chat',
}
```

**Test scenarios:**
- **代理生效:** 开发环境下 `/api/ai/*` 请求正确转发到后端
- **依赖安装:** `markdown-it` 正常安装，import 无报错

**Verification:** `npm install` 无报错，`npm run dev` 正常启动，API 请求代理正确

---

## Scope Boundaries

### In Scope
- "呼叫小AI"按钮 + AI 对话弹框（多会话 + SSE 流式）
- 后端 DeepSeek 代理 + 会话/消息 CRUD + 数据库表
- Markdown 渲染 + 代码高亮
- 前后端项目合并开发环境配置

### Deferred to Follow-Up Work
- 后端代码从另一个 Trae 窗口物理合并到本仓库的具体操作（需要后端代码路径信息）
- 用户用量配额 / 限流
- 对话导出功能
- 会话重命名功能
- 移动端响应式适配

### Non-Goals
- 文件上传 / 语音输入 / 多模态功能
- AI 模型选择器（仅使用 DeepSeek）
- 与现有 WebSocket 聊天室集成
- 用户自带 API Key 模式

## Risks & Dependencies

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| 后端技术栈未知 | U1-U3 实现细节无法精确到代码 | 计划以 API 契约 + 方向性指导为主，后端按现有模式实现 |
| SSE 在某些代理/防火墙下可能被缓冲 | 流式效果失效 | 后端设置正确的 `X-Accel-Buffering: no` 头；前端有超时重试 |
| DeepSeek API 额度耗尽 | 所有用户无法使用 | 后端捕获额度错误，前端显示友好提示 |
| 前后端合并的路径冲突 | 静态资源与 API 路由冲突 | 后端路由统一 `/api` 前缀，Vite 代理处理开发环境 |
| `markdown-it` XSS 风险 | AI 回复含恶意脚本 | 配置 `markdown-it` HTML 转义；不渲染内联 HTML |

**外部依赖：**
- DeepSeek API（需要有效的 API Key）
- `markdown-it` npm 包
- 现有后端鉴权中间件

## Definition of Done

- [ ] 侧边栏"呼叫小AI"按钮可见，点击弹出对话弹框
- [ ] 弹框左侧会话列表：新建/切换/删除功能正常
- [ ] 右侧对话区：发送消息 → AI 流式回复 → 逐字显示
- [ ] AI 回复 Markdown 正确渲染（代码高亮、列表、表格）
- [ ] 对话记录入库，刷新页面后重新打开弹框可看到历史会话
- [ ] 不同用户的会话互相隔离
- [ ] 后端 API Key 不暴露给前端
