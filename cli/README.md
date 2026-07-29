# rainote —— 雨滴笔记 CLI 工具

`rainote` 是 [RuoYi-Vue](https://github.com/y-project/RuoYi-Vue) 雨滴笔记模块的命令行入口，复用后端 18 个 `Note*Controller` 的 REST 接口，**不修改后端**。

Phase 1 覆盖笔记、多维表（dwtable/column/record/block）、语义关联（notelink）三类核心业务，支持三种认证模式、JSON/CSV/table 输出与语义化退出码，面向**运维自动化、CI/CD 集成与批量数据操作**场景。

- **后端基线**：Java 1.8 / Spring Boot 2.5.14 / Maven 多模块（非 Java 17）
- **CLI 基线**：Python 3.10+ / Typer / httpx / Rich
- **测试覆盖**：418 个测试（单元测试 + 端到端业务流程测试）

---

## ⚠️ 重要安全与一致性警告

在正式使用前，请务必阅读以下警告。这些是后端现状导致的固有限制，CLI 无法在客户端单方面修复。

### 1. 并发编辑会静默覆盖（无乐观锁）

后端 6 个核心 Note 实体（`NoteNote`/`NoteRecord`/`NoteNotelink`/`NoteBlock`/`NoteColumn`/`NoteDwtable`）**没有 `@Version` 乐观锁**，Web 前端 `notepad/src/stores/editor` 仅用 sessionStorage 持久化、无冲突检测。

**后果**：CLI 与 Web 前端并发编辑同一条记录时，**最后写入者胜出**，前一次修改被静默覆盖，且无审计线索。

**`--check-revision` 当前是虚假保护**：
CLI 的 `note update` / `record update` 提供 `--check-revision <id>` 参数，调用 `GET /{id}` 取当前 `revisionId` 比对。但后端 `NoteNoteServiceImpl` 未实现 revisionId 递增逻辑，`revisionId` **硬编码为 `1L` 且永不递增**。因此当前 `--check-revision` 提供的是**虚假并发保护** —— 实际 revisionId 恒为 1。待后端修复递增逻辑后该参数才会真正生效。

**建议**：
- 高并发场景避免 CLI 与 Web 同时编辑同一记录
- 关注后端 `revisionId` 递增逻辑的修复进度，修复后 `--check-revision` 自动生效

### 2. 部分破坏性端点存在匿名执行风险

后端 `SecurityConfig.java:120` 放行 `/system`（精确路径），`L122` 对 `GET /system/note/**` **匿名放行**。叠加 `NoteNoteController` 多个 GET 端点 `@PreAuthorize` 缺失或被注释，以下破坏性操作可被匿名执行：

| 端点 | 风险 | `@PreAuthorize` |
|---|---|---|
| `GET /system/note/recoverNote/{ids}` | 恢复回收站笔记 | **缺失** |
| `GET /system/note/collectionNote/{id}` | 收藏笔记 | **缺失** |
| `GET /system/note/cancelCollection/{id}` | 取消收藏 | **缺失** |
| `GET /system/note/setTemplate/{id}` | 设为模板 | **缺失**（`@Log` 存在） |
| `GET /system/note/removeTemplate/{id}` | 取消模板 | **缺失**（`@Log` 存在） |

CLI 在这些端点的 `--help` 与代码注释中标注了风险。**根本修复需后端补全 `@PreAuthorize` 并收紧 `SecurityConfig`**，CLI 仅在文档中提示。

### 3. 审计日志盲点

后端 `@Log` 注解只记录 **who / when**，**不记录 before/after diff**。此外以下端点的 `@Log` 被注释，操作完全不入审计日志：

| 端点 | 说明 |
|---|---|
| `POST /system/record/update` (`NoteRecordController.java:157`) | 记录更新无审计 |
| `POST /system/record/updateSort` (`NoteRecordController.java:187`) | 记录排序变更无审计 |

CLI 无法弥补审计盲点。敏感场景请配合数据库层面的审计触发器或操作日志。

### 4. `--token` 参数泄露风险

`--token <jwt>` 会暴露在**进程列表、shell 历史与系统日志**中。优先级排序的凭据提供方式：

1. **`rainote login`**（推荐）—— 交互式登录，token 存入 `~/.rainote/credentials`（权限 600）
2. **`RAINOTE_TOKEN` 环境变量** —— CI/CD 场景推荐
3. **`--token-file <path>`** —— 从文件读取（文件权限自行管理）
4. `--token <jwt>` —— **仅限临时调试**，会触发 stderr 一次性警告

shell 历史防护建议：
- bash：`HISTCONTROL=ignorespace` + 命令前加空格（` rainote --token xxx ...`）
- zsh：`setopt HIST_IGNORE_SPACE`

---

## 安装

### 前置要求

- Python 3.10+（3.9 已于 2025-10 EOL）
- 可访问的 RuoYi-Vue 后端（默认 `http://localhost:8080`）

### 开发安装

```bash
cd cli
python -m pip install -e ".[dev]"
```

`[dev]` 额外安装 `pytest`、`pytest-cov`、`respx`（HTTP mock 测试）。

### 验证

```bash
rainote --version
rainote --help
```

---

## 快速开始

### 1. 登录（交互式）

```bash
rainote login -u admin
# 输入密码，按提示处理验证码
```

> **注意**：Phase 1 的 `login` 核心逻辑已实现（三种验证码模式 + 重试 + `/login` 特判），但 Typer `login` 子命令接入仍在进行中。当前可通过 `RAINOTE_TOKEN` 环境变量或 `--token-file` 提供 token。

### 2. CI/CD 场景（复用已有 token）

```bash
export RAINOTE_TOKEN="你的JWT token"
rainote note list --json
```

### 3. 查询与输出

```bash
# 默认 table 输出
rainote note list --page 1 --size 20

# JSON 输出（纯 JSON 无 ANSI，适合管道处理）
rainote note list --json | jq '.rows[0].title'

# CSV 输出
rainote note list --csv > notes.csv

# 获取详情
rainote note get 42
```

### 4. 写操作（从 JSON 文件读取 payload）

```bash
echo '{"title":"新笔记","content":"内容"}' > new_note.json
rainote note create --file new_note.json

# 更新（payload 未含 id 时自动注入）
echo '{"title":"更新标题"}' > update.json
rainote note update 42 --file update.json
```

### 5. 破坏性操作（强制预检确认）

```bash
# 交互确认
rainote note delete 1,2,3

# 跳过确认（脚本场景）
rainote note delete 1,2,3 --yes

# 回收站
rainote note garbage list
rainote note garbage clear 1,2 --yes   # 彻底清除（不可恢复）
rainote note garbage recover 3 --yes   # 恢复
```

### 6. 导出（Excel 二进制流）

```bash
rainote note export -o notes.xlsx
rainote dwtable export -o dwt.xlsx
rainote notelink export --stdout > nl.xlsx
```

---

## 命令总览

Phase 1 共 6 个命令组、覆盖 47 个后端端点：

| 命令组 | 子命令 | 说明 |
|---|---|---|
| `note` | `list` `get` `create` `update` `delete` `export` | 笔记 CRUD / 导出 |
| `note garbage` | `list` `clear` `recover` | 回收站（clear/recover 破坏性） |
| `dwtable` | `list` `page-list` `get` `get-data` `create` `edit` `delete` `export` | 多维表 |
| `column` | `list` `column-list` `double-link-list` `get` `create` `update` `update-sort` `delete` `deduplicate` `export` | 列 |
| `record` | `list` `list-all` `search-list` `data-list` `get` `get-data` `create` `update` `update-sort` `delete` `backfill-names` `export` | 记录 |
| `block` | `list` `get` `create` `update` `update-batch` `delete` `link-to-dwtable` `remove-link` `export` | 块 |
| `notelink` | `list` `get` `cell` `by-note` `create` `update` `delete` `export` | 语义关联 |

每个命令的参数详见 `rainote <命令组> <子命令> --help`。

### 后端代码事实（CLI 以代码为准，非 REST 惯例）

以下端点的实现与 REST 惯例不符，CLI 按后端代码事实调用：

- `note`/`dwtable`/`record`/`block` 的 `delete` 用 **GET** `/remove/{ids}`（非 DELETE），仅 `notelink delete` 用 DELETE
- `notelink create` 用 POST 根路径（无 `/add`），`update` 用 PUT 根路径
- `dwtable`/`record` 的 `get-data` 路径是 `/data/{id}`（方法名 `getData` 但 URL 是 `/data`）
- `column update` 路径是 `/update`（非 `/edit`），`dwtable`/`block` 的 update 路径是 `/edit`
- `column update-sort` 接收 `NoteRecordVo`（非 `NoteColumnVo`，疑似 copy-paste bug）
- `column deduplicate` 用 **GET**（与项目记忆"破坏性操作必须 POST"冲突，CLI 以代码为准）
- `note update` 路径是 `/user/update`，方法 POST（非 PUT）

---

## 认证

### 三种验证码模式

后端 `validateCaptcha` 在校验前先 `deleteObject(verifyKey)` → **验证码单次消费**，重试必须重取。

| 模式 | 说明 | 适用场景 |
|---|---|---|
| `image`（默认） | GET `/captchaImage`，若 `captchaEnabled=true` 保存图片并打开、prompt 输入验证码；错误重试（重取 uuid，最多 3 次） | 交互式 |
| `skip` | 若 `captchaEnabled=true` **立即报错退出码 6**（不静默降级）；false 则直接登录 | 后端关闭验证码时 |
| `code` | 用户自备 `--captcha-code` + `--captcha-uuid`（uuid 仍需从 `/captchaImage` 获取，CLI 不绕过验证码机制） | 自动化获取验证码后 |

### `/login` 端点特判（KTD3）

后端 `CaptchaException`/`UserPasswordNotMatchException` 落入 `GlobalExceptionHandler.handleRuntimeException` 返回 `code 500`。若按标准映射会误报为服务错误（exit 5）。

CLI 特判：`/login` 端点 `code 500` + `msg` 含 `jcaptcha|password|captcha|验证码|密码` → 退出码 **6**（ValidationError）。

### Token 活性探测（KTD2）

后端 JWT **不含 `exp` claim**，过期由 Redis TTL（`expireTime=3000` 即 50 小时）管理，CLI 无法离线判断有效期。每次命令执行前调用 `GET /getInfo` 探测 token 活性：

- **非破坏性命令**：距上次成功探测 < 5 分钟时跳过探测
- **破坏性命令**（delete/recover/garbage clear/collection/setTemplate/removeTemplate/backfill-names/remove-all/remove-all-data）：**强制预检不可跳过**
- `code 200` → token 有效；`code 401` → 标记 credentials 失效，退出码 2
- **网络失败**（超时/连接错误）→ 退出码 10，**不标记 token 失效**（避免网络抖动误判）

> 后端 `/getInfo` 仅在 token 剩余 ≤ 20 分钟时才 refresh 续签，CLI 不依赖"调用即续签"假设。

---

## 输出格式

| 选项 | 说明 |
|---|---|
| `--table`（默认） | Rich 表格，非 TTY 自动禁色 |
| `--json` | 纯 JSON 无 ANSI，直接 `json.dumps` 绕过 Rich（保证无样式字符） |
| `--csv` | Python 标准库 `csv` 模块，绕过 Rich |
| `--output <fmt>` | 等价于上面三个快捷别名 |

- **stdout 仅输出业务数据，错误一律 stderr**（R7）
- 空结果：table 模式显示灰色"无数据"提示，`--json` 模式输出 `{"rows":[],"total":0}`，退出码 0（R9）
- `--verbose` 时 `Authorization` 头自动脱敏（保留前 8 字符 + `***`）（R8）

---

## 语义化退出码

CLI 基于 `AjaxResult.code` 业务码（非 HTTP 状态码）映射退出码，便于脚本判断：

| 退出码 | 常量 | 含义 | 触发条件 |
|---|---|---|---|
| 0 | OK | 成功 | `code==200` |
| 2 | AUTH | 认证失败 | `code==401`，token 失效 |
| 3 | FORBIDDEN | 禁止访问 | `code==403` |
| 4 | NOT_FOUND | 未找到 | `code==200` 且无 `data` 键（后端 `data==null` 时不 put `data` 键） |
| 5 | SERVER | 服务错误 | `code==500`（`/login` 除外） |
| 6 | VALIDATION | 校验错误 | `code==400`，或 `/login` 的 `code 500` + 验证码/密码关键词 |
| 7 | WARN | 警告 | `code==601`，或 `--check-revision` 不匹配 |
| 10 | NETWORK | 网络错误 | 连接超时/拒绝（重试耗尽后） |

> **R10 注意**：后端 `AjaxResult(int code, String msg, Object data)` 在 `data==null` 时**不 put `data` 键**。CLI 检测"未找到"的条件是 `code==200 && 'data' not in body`，**不是** `data == null`。

---

## 配置

四层优先级（高 → 低）：

1. **CLI 全局选项**：`--base-url` `--output` `--json` `--csv` `--table` `--token` `--token-file` `--verbose` `--no-color`
2. **环境变量**：`RAINOTE_BASE_URL` `RAINOTE_OUTPUT` `RAINOTE_TOKEN` `NO_COLOR` `RAINOTE_NO_COLOR` `RAINOTE_HOME`
3. **`~/.rainote/config.toml`**：

   ```toml
   base_url = "http://localhost:8080"
   output = "table"
   no_color = false
   ```

4. **内置默认**：`base_url=http://localhost:8080`，`output=table`

运行时文件位于 `~/.rainote/`（可用 `RAINOTE_HOME` 自定义）：
- `credentials` —— JWT token（权限 600，Windows 下用 `icacls` 限制访问）
- `config.toml` —— 用户配置
- `.gitignore` —— 含 `*`，防止误提交

### 隐藏调试标志

`--allow-anonymous`（`hidden=True`，不在 `--help` 默认输出中暴露）因后端 `SecurityConfig L122` 配置缺陷而存在，允许匿名调用 GET 端点。**后端修复后将移除**，请勿在生产环境依赖。

---

## 已知限制

- **`--check-revision` 虚假保护**：后端 `revisionId` 硬编码 `1L` 不递增（见上文并发警告）
- **`login` Typer 子命令接入待完成**：核心登录逻辑已实现并测试，但 `rainote login` 命令注册仍在进行中，当前请用 `RAINOTE_TOKEN` 或 `--token-file`
- **无本地审计日志**：`~/.rainote/audit.log` 计划在 Phase 2 实现
- **无批处理事务**：`rainote batch --file ops.json` 计划在 Phase 2 实现
- **无 contract test**：CI 中检测端点漂移的契约测试计划在 Phase 2 实现

### Phase 2 展望

- 视图（noteview）、元数据（notemeta）、任务（notetask）、元组（notetuple）、`NoteDwtableItemController`（cell 级 CRUD）
- 好友（notefriend）、RBAC（noterole/noteuserrole）
- `rainote doctor` 诊断命令、`rainote batch` 批处理、本地审计日志、端点漂移契约测试

---

## 开发

### 运行测试

```bash
cd cli
python -m pytest                          # 全量测试
python -m pytest tests/test_e2e_flows.py  # 端到端业务流程测试
python -m pytest --cov=rainote            # 覆盖率
```

### 项目结构

```
cli/
├── pyproject.toml
├── README.md
├── src/rainote/
│   ├── cli.py              # Typer app 入口 + 全局选项
│   ├── config.py           # 四层配置优先级
│   ├── errors.py           # 退出码常量 + RainoteError(ClickException)
│   ├── paths.py            # ~/.rainote/ 路径与文件权限
│   ├── auth/               # 登录 + token 活性探测 + credentials
│   ├── client/             # HTTP 客户端 + 端点注册表 + 响应归一化
│   ├── commands/           # 6 个命令组（note/dwtable/column/record/block/notelink）
│   ├── models/             # ApiResponse 等数据模型（dataclass）
│   └── output/             # table/csv/json 渲染
└── tests/                  # 单元测试 + 端到端测试（respx mock HTTP）
```

### 关键技术决策

- **手写端点注册表**：18 个 `Note*Controller` 全部零 `@ApiOperation` 注解，`SwaggerConfig` 的 `withMethodAnnotation(ApiOperation.class)` 过滤器导致 `/v3/api-docs` 不含 Note 端点，OpenAPI codegen 在编码前即判死，改为手写映射并标注源文件行号便于漂移检测
- **业务码错误处理**：后端统一返回 HTTP 200（`ServletUtils.renderString` 显式 `setStatus(200)`），成败由 `AjaxResult.code` 区分
- **`RainoteError` 继承 `ClickException`**：使 Typer/Click 自动捕获并按 `exit_code` 退出，无需每个命令函数 try-except（修复了网络错误曾以 exit 1 + traceback 退出的问题）
- **JSON/CSV 绕过 Rich**：Rich 的 `NO_COLOR` 仅移除颜色不移除样式，无法保证纯文本无 ANSI，故 `--json`/`--csv` 走独立路径

---

## 许可证

MIT
