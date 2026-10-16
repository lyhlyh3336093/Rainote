---
date: 2026-10-16
topic: aliyun-deploy
---

# 阿里云 ECS 部署 Rainote：需求

## Summary

Rainote 以单机直跑形态部署到已备案的阿里云 ECS（2核4G+）：nginx 托管 notepad 静态资源并以 HTTPS（域名免费证书）+ `/api` 反代 systemd 托管的后端 jar，MySQL/Redis 同机自建，本地数据全量迁移上云。配套一次性代码改造（生产 profile、前端 API 基址收敛、安全基线）与顺序化部署步骤，交付一个可长期运行的个人云上实例。

## Problem Frame

应用当前只在 Windows 开发机上以 dev 形态运行：后端配置指向 localhost 的 MySQL/Redis，上传路径是 `D:/ruoyi/uploadPath`（application.yml:12），前端 API 基址靠源码里 6 处 `window.location.origin === 'http://www.rainote.cn' ? 'http://www.rainote.cn:8089' : '/api'` 三元判断（useFetch.ts:11-13、api/agent.ts、api/import.ts、api/export.ts、stores/aiChat.ts:180-182、views/image/index.vue:138-140），仓库没有生产 profile，sql/ 目录缺全部 21 张 note_* 表的 DDL（只有 19 张 sys_*/gen_* 表）。代码里早有上云伏笔：application.yml:21-22 注释"阿里云服务器是 8089，上传到云服务器之前记得修改"。公网化还带来本地不存在的暴露面：MySQL root/root、Redis 空密码、druid 监控台弱口令对所有来源开放、admin 默认口令——部署若不改这些，等于把弱口令数据库挂上公网。

## Requirements

**配置与代码改造**

- R1. 新增生产 profile（如 `application-prod.yml`）：MySQL/Redis 指向本机但用强口令、上传路径 `/home/ruoyi/uploadPath`、日志路径与 logback 一致；生产以 `--spring.profiles.active=prod` 启动，本地 dev 行为不变。
- R2. 前端 API 基址收敛为单一来源（如 `.env.production` 的 `VITE_API_BASE` 或等价机制），删除 6 处域名三元硬编码；生产构建产物统一走 `/api` 相对路径由 nginx 反代，8089 端口不再公网暴露。
- R3. 21 张 note_* 表 DDL 落库进 sql/ 目录（从本地运行库导出结构+初始数据），使"全新环境可从仓库重建数据库"成立；sys_* 基础表沿用现有 ry_20220822.sql。
- R4. 安全基线：MySQL/Redis/后端 jar 仅监听 127.0.0.1；ECS 安全组仅放行 80/443（+SSH 限源）；admin 初始口令改为强口令；druid 监控台加访问控制（强口令或仅本机可访问）；验证码保持开启。
- R5. GLM key 等敏感配置经环境变量注入（现有 `GLM_API_KEY` 机制保留），不入仓库不入 jar 明文。

**部署形态与迁移**

- R6. 单机直跑：nginx（80/443，TLS 用阿里云免费 DV 证书，HTTP→HTTPS 301）托管 notepad dist 并反代 `/api` → `127.0.0.1:8090`；后端 jar 以 systemd 服务运行（开机自启、崩溃自动拉起）；MySQL/Redis 同机自建。
- R7. 数据全量迁移：本地 ry-vue 库 mysqldump（结构+数据+sys_user 账号）→ 导入 ECS，含笔记、多维表、上传附件目录 `D:/ruoyi/uploadPath` 的文件原样拷贝。
- R8. 备份基线：每日 mysqldump cron 到本机备份目录、保留最近 N 份（N 默认 7），上传目录可手动备份。

**部署步骤（顺序化清单）**

- R9. 交付可执行的顺序化步骤清单：环境准备（JDK8/MySQL/Redis/nginx 安装）→ 数据库初始化与导入 → 证书申请与 nginx 配置 → 后端部署与 systemd 配置 → 前端构建上传 → 安全组与口令收口 → 端到端验证（登录/笔记读写/多维表导入导出/附件上传/AI agent）。

## Key Decisions

- **单机直跑而非 Docker**——单用户单机、数据迁移是一次性 dump、排障偏好直接；可复现性对个人应用是负资产。
- **只部署 notepad，ruoyi-ui 管理端不上云**——管理操作低频，走数据库/接口即可，少维护一个 Vue2 工程。
- **前端恒走 `/api` 反代，后端端口不公网暴露**——退役 rainote.cn:8089 三元判断；比"直连 8089"少开一个安全组端口、TLS 统一在 nginx 终结。
- **HTTPS 上（免费 DV 证书）**——登录密码与笔记内容加密传输，成本仅一次性配置。
- **每日 mysqldump 本机备份**——自建库无云备份兜底，7 份轮转是成本趋近于零的最小保险。

## Key Flows

- F1. 用户访问 `https://www.rainote.cn` → nginx 返回 notepad SPA → 前端所有请求走 `/api/*` → nginx 反代 `127.0.0.1:8090` → 后端访问本机 MySQL/Redis/GLM API。
  - **Covers:** R2, R6
- F2. 部署后升级（换 jar/换前端产物）→ systemd restart / rsync dist，数据库不动。
  - **Covers:** R6

## Acceptance Examples

- AE1. 部署完成后浏览器访问 `https://www.rainote.cn`：登录（验证码正常）、打开既有笔记与多维表、双向链接跳转、Excel 导入导出、附件上传下载、AI agent 对话全部正常。
  - **Covers:** R7, R9
- AE2. 从公网扫描 ECS：仅 80/443 开放；`curl http://www.rainote.cn` 返回 301 至 https；直接访问 `https://www.rainote.cn:8090`（及 3306/6379）不可达。
  - **Covers:** R4, R6
- AE3. 服务器重启后不加人工干预：systemd 自动拉起后端，nginx 自启，站点可用。
  - **Covers:** R6

## Scope Boundaries

- 不部署 ruoyi-ui 管理端；不引入 RDS/OSS/云监控/CDN/负载均衡/多实例。
- 不做 CI/CD 流水线（升级手动：scp jar + systemctl restart）。
- 前端不做 SSR/同构改造，SPA 静态托管不变。

## Dependencies / Assumptions

- ECS 为 CentOS/阿里云 Cloud Linux 系（yum/dnf 包管理），2核4G+；域名 rainote.cn 已备案且 DNS 解析可控。
- ECS 安装 MySQL 8.x，与本地开发库版本对齐，dump 导入零兼容处理。
- 阿里云免费 DV 证书每年需续签一次（到期前日历提醒，人工重申）。
- GLM API 出站访问不受 ECS 出网限制。

## Outstanding Questions

- Deferred to planning: R2 的基址机制选型（env 变量 vs 构建参数）与 6 处改造的落点。
- Deferred to planning: 部署步骤清单的载体（wiki 风格文档 vs 仓库内脚本+文档）。
