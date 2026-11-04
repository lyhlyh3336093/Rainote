---
title: "nginx 长前缀 location 下 proxy_pass 尾斜杠静默改写请求路径（还原接口失效零日志）"
date: 2026-11-04
category: integration-issues
module: deploy/nginx
problem_type: integration_issue
component: tooling
symptoms:
  - "前端点「数据还原」上传备份包，笼统报「还原数据失败」，无具体错误"
  - "后端 journalctl 与 logback 日志零 restore 请求痕迹，仿佛从未调用"
  - "nginx access.log 显示 POST /api/system/note/restore 返回 200，但响应体仅 92 字节（正常预览响应应为 manifest 摘要，数百字节起）"
  - "数据核验笔记数为 0，证实还原从未执行"
root_cause: config_error
resolution_type: config_change
severity: critical
tags: [nginx, proxy-pass, trailing-slash, silent-rewrite, reverse-proxy, restore, deploy, troubleshooting]
---

# nginx 长前缀 location 下 proxy_pass 尾斜杠静默改写请求路径（还原接口失效零日志）

## Problem

全新部署 Rainote 到阿里云 ECS 后，用户在界面执行"数据还原"上传备份包，前端报"还原数据失败"。nginx 配置（来自部署手册第 5 节模板）语法检查通过、整站 API 全部正常，唯独备份还原两个特殊路由静默失效——请求被 nginx 改写到后端根路径，返回欢迎语 JSON（HTTP 200 + 92 字节），后端业务层零日志，极难排查。

## Symptoms

- 前端"数据备份"/"数据还原"按钮失败，错误信息笼统（HTTP 200 但非预期 JSON 结构）
- 后端日志无任何 restore/dataBak 请求记录
- nginx access.log 中该路由响应体仅 92~104 字节（实测为后端根路径欢迎语）
- 数据库核验笔记数为 0，证明还原从未真正执行

## What Didn't Work

- **查后端日志**：零痕迹——因为请求根本没到达 controller，方向性误导（一度怀疑用户没点确认）
- **`nginx -t`**：语法正确，语义错误，检查无效
- **检查 BackupAuthFilter / multipart 512MB / 前端 fetch 超时**：全部排除，浪费排查轮次
- **转折点**：预览"成功"响应只有 92 字节——预览成功的响应应是 manifest 摘要（表数/记录数/文件数），92 字节的"成功"就是反常信号
- **决定性复现**：redis 直接 `GET captcha_codes:{uuid}` 读出 math 验证码明文答案（绕过 OCR），curl 登录拿 token，带假 zip 调 restore——返回 `欢迎使用RuoYi后台管理框架，当前版本：v3.8.5，请通过前端地址访问。`，请求打到了后端**根路径**

## Solution

两个特殊路由的 `proxy_pass` 必须写**完整后端路径**，不能用尾斜杠：

```nginx
# 错误（来自部署手册模板）：尾斜杠使 location 前缀被替换为 "/"
location /api/system/note/restore {
    limit_req zone=restore_limit burst=5 nodelay;
    proxy_pass http://127.0.0.1:8090/;    # ← /api/system/note/restore 被改写为 /
    ...
}

# 正确：proxy_pass 带完整后端路径，URI 原样透传
location /api/system/note/restore {
    limit_req zone=restore_limit burst=5 nodelay;
    proxy_pass http://127.0.0.1:8090/system/note/restore;
    ...
}

location /api/system/note/dataBak {
    proxy_pass http://127.0.0.1:8090/system/note/dataBak;
    ...
}
```

修复后验证：假包返回真实业务错误 `备份包缺少 manifest.json，已拒绝还原`；真包预览返回 manifest 摘要、执行还原成功。

## Why This Works

nginx 的 `proxy_pass` 语义：**带 URI（哪怕只是一个尾斜杠 `/`）时，请求 URI 中匹配 location 前缀的部分被替换为该 URI**。

- `location /api/system/note/restore` + `proxy_pass http://127.0.0.1:8090/;` → 请求 `/api/system/note/restore` 被改写为 `/`，打到后端根路径
- `location /api/` + `proxy_pass http://127.0.0.1:8090/;` → 剥掉 `/api` 前缀，恰好是想要的转发语义——**正确**

致命的掩盖效应：整站 API 走 `/api/` 通用路由全部正常，只有脱离"纯前缀剥离"语义的特殊长前缀路由坏掉。模板 bug 藏在"整站都好使"的假象下，`nginx -t` 与功能抽查（登录、验证码）都不会暴露它。

## Prevention

- **规则化**：凡 location 前缀不是"纯前缀剥离"转发语义（目标路径 ≠ 原路径去掉通用前缀），`proxy_pass` 必须写完整路径，禁用尾斜杠
- **部署后响应体内容断言**：对每个特殊路由 `curl` 一次并 `grep` 业务字段（如 JSON 里的 `tableCount`），HTTP 200 不代表路由正确——200 + 错误响应体是本坑的特征签名
- **响应体字节数是高价值线索**：预期"大响应"的路由返回了 ~100 字节，先怀疑路由错配再怀疑业务逻辑
- **验证码自动化测试技巧**：RuoYi math 验证码在 redis 中存明文答案，`GET captcha_codes:{uuid}` 即可取答案绕过 OCR，构造登录态做接口级 E2E 复现
- 部署手册 `docs/deploy-aliyun-ecs.md` 第 5 节模板已同步修正（2026-11-04）

## Related Issues

- [full-backup-restore-mechanism.md](../architecture-patterns/full-backup-restore-mechanism.md)——备份还原六机制主文档；本坑是其"部署侧配套"环节的真实事故
- [init-script-schema-drift-restore-fingerprint.md](init-script-schema-drift-restore-fingerprint.md)——同一部署窗口的结构漂移连环坑，还原功能两次被卡的不同层面
- `docs/deploy-aliyun-ecs.md` 第 5 节——nginx 模板来源，已修正 proxy_pass 写法
