---
date: 2026-10-08
topic: standalone-windows-deploy
---

# 单机便携部署包需求（Windows）

## Summary

在开发机上提供一个一键打包脚本，产出单个 ZIP 部署包：内含后端 fat jar、notepad 前端生产构建产物、便携 JDK 8 / MySQL（打包时预灌当前全部数据）/ Redis、上传文件与本地配置。目标 Windows 电脑只需解压并双击启动脚本，即可在本机浏览器使用 Rainote，数据开箱即用。

## Problem Frame

Rainote 目前只能在开发机上以开发形态运行（本机安装的 MySQL/Redis、源码构建、npm dev 代理）。用户需要在另一台 Windows 电脑上单机使用它，且该机器不承载任何开发职责——环境安装、数据库初始化、数据导入、前端代理配置等常规部署步骤都应消失。

## Key Decisions

- **便携绿色包，而非 Docker 或目标机安装原生服务。** Docker Desktop 本身就是最重的环境安装；原生服务方案步骤最多。绿色包把全部操作量前置到开发机的一次性打包脚本，目标机只剩"解压 + 双击"。
- **便携 MySQL 打包时预灌整库数据（快照式迁移）。** 目标机零导入；建库建表脚本（无建库语句、增量菜单脚本分散）对目标机不可见。
- **仅本机使用。** 不考虑局域网访问、HTTPS、域名与固定 IP，组件只绑定本机回环地址，弱口令（MySQL root/root、Redis 无密码）在该前提下可接受。
- **快照单向迁移，不做双向同步。** 部署后新机器产生的数据不回流开发机；重新部署以新包数据为准。

## Requirements

**打包（开发机侧）**

- R1. 提供一键打包脚本，在开发机上产出单个 ZIP 部署包，可重复执行（代码或数据更新后重打包）。
- R2. 包内含后端 fat jar（当前代码构建）与 notepad 前端生产构建产物（dist）。
- R3. 包内含便携 JDK 8、便携 MySQL（预灌 `ry-vue` 整库数据）、便携 Redis。
- R4. 包内携带现有运行数据：数据库快照、`D:/ruoyi/uploadPath` 上传文件、`application-local.yml`（GLM API key）。
- R5. 包内含启动与停止脚本。

**目标机体验**

- R6. 目标机部署仅需两步：解压 ZIP、双击启动脚本；无需管理员权限、无需安装任何软件或注册服务。
- R7. 启动脚本拉起全部组件并自动打开浏览器进入应用。
- R8. 停止脚本可干净关闭全部组件；停止后可再次启动（幂等）。
- R9. 数据开箱即用：登录后笔记、多维表格、记录与上传文件与开发机当前状态一致。

**运行约束**

- R10. 前端静态服务与后端之间必须按"剥离 `/api` 前缀后转发至 8090"的约定工作（与 vite 代理 rewrite 行为一致；后端 context-path 为 `/`）。

## Key Flows

- F1. 首次部署
  - **Trigger:** 打包完成，ZIP 已传输到目标机。
  - **Steps:** 解压 ZIP → 双击启动脚本 → 组件拉起 → 浏览器自动打开。
  - **Outcome:** 登录后即可使用，数据与开发机一致。
  - **Covers:** R6, R7, R9
- F2. 更新
  - **Trigger:** 开发机上代码或数据更新。
  - **Steps:** 重跑打包脚本 → 传输新 ZIP → 覆盖解压目录 → 双击启动。
  - **Outcome:** 新版本/新数据生效；目标机侧产生的数据不合并（见 Scope Boundaries）。
  - **Covers:** R1, R5, R8

## Acceptance Examples

- AE1. **首次启动（Covers R6, R7, R9）** — Given 目标机已解压且从未启动；When 双击启动脚本；Then 浏览器自动打开并进入登录页，登录后可见与开发机一致的笔记与多维表格。
- AE2. **停止后重启（Covers R8）** — Given 应用正在运行；When 执行停止脚本后再次双击启动；Then 全部组件正常拉起，数据无损。

## Success Criteria

- 目标机从拿到 ZIP 到进入应用页，全程无安装、无导入、无手工配置动作；由非开发人员按 README 式说明即可完成。
- 部署包在未签名的便携 exe 首次运行时允许出现 SmartScreen 提示（点"仍要运行"即可），除此之外无任何环境障碍。

## Scope Boundaries

- 不含局域网/外部访问、HTTPS、域名、固定 IP。
- 不含开机自启、注册为 Windows 服务（更新数据靠重跑启动脚本）。
- 不含两机数据同步、定期备份与数据合并机制（快照单向迁移；重部署会以新包数据覆盖）。
- 不含 ruoyi-ui 管理后台（仅打包 notepad 前端）。
- 不含 Linux/macOS 版本。

## Dependencies / Assumptions

- 已核实的技术事实：Spring Boot 2.5.14 / Java 1.8（`pom.xml`）；后端端口 8090（`ruoyi-admin/src/main/resources/application.yml`）；MySQL `localhost:3306/ry-vue` root/root、脚本无建库语句（`ruoyi-admin/src/main/resources/application-druid.yml`、`sql/ry_20220822.sql`）；Redis 127.0.0.1:6379 无密码（`application.yml`）；无 MinIO/S3 依赖，文件走本地磁盘；上传路径硬编码 `D:/ruoyi/uploadPath`（`application.yml` 的 `ruoyi.profile`）；`application-local.yml` 含明文 GLM key 且被 `.gitignore` 排除、从未入库。
- GLM key 供给：生产环境须以 `GLM_API_KEY` 环境变量或 `application-local.yml` 提供，否则 AI agent 功能不可用。key 明文随包携带，仅本机单用户前提下可接受。
- 前端生产构建走相对路径 `/api`（`notepad/src/hooks/useFetch.ts`）；开发代理 rewrite 会剥离 `/api` 前缀（`notepad/vite.config.js`），部署包的静态服务必须复刻该行为，否则接口 404。
- 目标机假设：Windows x64、可解压 ZIP、首次运行便携程序时允许 SmartScreen 放行。
- 仓库根的 `docker-compose.yml` 是 PostgreSQL/pgvector 测试用途，与主数据源 MySQL 无关，部署包不使用它。

## Outstanding Questions

**Deferred to Planning**

- 静态服务机制二选一：便携 nginx（零代码改动，多一个进程） vs 后端直接托管前端静态资源（零额外进程，少量代码改动）。
- 上传路径整合策略：保持 `D:/ruoyi/uploadPath` 并由启动脚本同步包内数据 vs 改为部署包内相对路径。
- 便携组件选型：Windows 版 MySQL/Redis/JDK 的具体发行版与获取方式；启动脚本的端口冲突处理。

## Sources

- `pom.xml`、`ruoyi-admin/src/main/resources/application.yml`、`ruoyi-admin/src/main/resources/application-druid.yml`、`ruoyi-admin/src/main/resources/application-local.yml` — 后端版本、端口、数据源、上传路径与 GLM key 配置
- `notepad/vite.config.js`、`notepad/src/hooks/useFetch.ts`、`notepad/.env.development` — 前端代理与生产 API 路径约定
- `sql/ry_20220822.sql`、`.gitignore`、`docker-compose.yml` — 初始化脚本形态、敏感文件排除、无关 compose 的确认
- `docs/solutions/` — 无既有部署沉淀（本次为首个部署方案）
