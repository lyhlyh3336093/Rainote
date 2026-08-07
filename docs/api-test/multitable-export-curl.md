# 多维表格导出端点测试脚本

验证 `POST /system/dwtable/exportData` 端点，覆盖 Excel/SQL 两种格式、权限、限流、超时兜底场景。

## 环境信息

- **服务地址**: `http://localhost:8090`
- **登录端点**: `POST /login`（返回 JWT token）
- **Token Header**: `Authorization: Bearer <token>`
- **导出端点**: `POST /system/dwtable/exportData?noteId={id}&format=excel|sql`
- **响应**: `application/zip`，`Content-Disposition: attachment; filename="multitable-export.zip"`

> **注意**: 当前 `SecurityConfig` 中 `/system` 路径配置为 `permitAll()`，本地开发可不带 token。
> 生产环境或修改安全配置后，需先登录获取 token。下方脚本同时提供带 token 与不带 token 两种写法。

---

## 前置：登录获取 Token

```bash
# 登录获取 token（admin/admin123 为 RuoYi 默认账号）
curl -X POST "http://localhost:8090/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

响应示例：
```json
{ "code": 200, "msg": "操作成功", "token": "eyJhbGciOiJIUzUxMiJ9..." }
```

提取 token 后设为环境变量：
```bash
# Linux/Mac/Git Bash
export TOKEN="eyJhbGciOiJIUzUxMiJ9..."

# PowerShell
$env:TOKEN = "eyJhbGciOiJIUzUxMiJ9..."

# CMD
set TOKEN=eyJhbGciOiJIUzUxMiJ9...
```

---

## 场景 1：导出 Excel（默认格式）

```bash
# 带鉴权（推荐，兼容生产配置）
curl -X POST "http://localhost:8090/system/dwtable/exportData?noteId=100&format=excel" \
  -H "Authorization: Bearer $TOKEN" \
  -o multitable-export-excel.zip \
  -w "HTTP状态: %{http_code}\n下载大小: %{size_download} 字节\n耗时: %{time_total}s\n"
```

```bash
# 不带鉴权（仅本地开发，/system permitAll 时可用）
curl -X POST "http://localhost:8090/system/dwtable/exportData?noteId=100&format=excel" \
  -o multitable-export-excel.zip
```

**预期结果**:
- HTTP 200
- 下载文件 `multitable-export-excel.zip`
- 解压后含 `multitable-export.xlsx` + `manifest.json`
- `manifest.json` 含 noteId、format=excel、tables 列表、generatedAt、schemaVersion

**验证 zip 内容**:
```bash
# Linux/Mac/Git Bash
unzip -l multitable-export-excel.zip

# PowerShell
Expand-Archive -Path multitable-export-excel.zip -DestinationPath ./export-check-excel
Get-ChildItem ./export-check-excel
cat ./export-check-excel/manifest.json
```

---

## 场景 2：导出 SQL

```bash
curl -X POST "http://localhost:8090/system/dwtable/exportData?noteId=100&format=sql" \
  -H "Authorization: Bearer $TOKEN" \
  -o multitable-export-sql.zip \
  -w "HTTP状态: %{http_code}\n下载大小: %{size_download} 字节\n"
```

**预期结果**:
- HTTP 200
- 解压后含每张数据表一个 `.sql` 文件（如 `T1.sql`, `T2.sql`）+ `manifest.json`
- 每个 `.sql` 文件含 `CREATE TABLE` + `INSERT` 语句

**验证 SQL 内容**:
```bash
# 查看 SQL 文件
cat ./export-check-sql/T1.sql | head -20

# 验证 SQL 语法（可选，用 MySQL client）
mysql -u root -p test_db < ./export-check-sql/T1.sql
```

---

## 场景 3：超限表分片（SQL）

构造 noteId 下存在 >2000 行记录的数据表，验证分片：

```bash
curl -X POST "http://localhost:8090/system/dwtable/exportData?noteId=100&format=sql" \
  -H "Authorization: Bearer $TOKEN" \
  -o multitable-export-shards.zip
```

**预期结果**:
- 解压后含 `表名_p1.sql`, `表名_p2.sql`, `表名_p3.sql`（5000 行 → 3 片）
- `表名_p1.sql` 含 `CREATE TABLE` + 前 2000 条 INSERT
- `表名_p2.sql`, `表名_p3.sql` 仅含 INSERT
- `manifest.json` 中该表的 shardCount=3

---

## 场景 4：超限表分片（Excel）

```bash
curl -X POST "http://localhost:8090/system/dwtable/exportData?noteId=100&format=excel" \
  -H "Authorization: Bearer $TOKEN" \
  -o multitable-export-excel-shards.zip
```

**预期结果**:
- `multitable-export.xlsx` 内含 `表名_p1`, `表名_p2`, `表名_p3` 三个 sheet
- 各 sheet 列名行一致

---

## 场景 5：空表导出

构造 noteId 下存在 0 记录的数据表：

```bash
curl -X POST "http://localhost:8090/system/dwtable/exportData?noteId=100&format=sql" \
  -H "Authorization: Bearer $TOKEN" \
  -o multitable-export-empty.zip
```

**预期结果**:
- 解压后含 `空表名.sql`，内容只有 `CREATE TABLE`，无 `INSERT`

---

## 场景 6：无权限拒绝

用用户 A 的 token 导出用户 B 的笔记（noteId 归属用户 B）：

```bash
curl -X POST "http://localhost:8090/system/dwtable/exportData?noteId=200&format=excel" \
  -H "Authorization: Bearer $TOKEN_USER_A" \
  -w "HTTP状态: %{http_code}\n"
```

**预期结果**:
- HTTP 500（RuoYi 默认错误响应）
- 响应 body 含 `ServiceException` 消息: `无权操作他人数据` 或 `笔记不存在`

---

## 场景 7：限流触发（按用户）

连续调用超过 10 次（60s 内）：

```bash
# 连续调用 12 次
for i in {1..12}; do
  echo "=== 调用 #$i ==="
  curl -X POST "http://localhost:8090/system/dwtable/exportData?noteId=100&format=excel" \
    -H "Authorization: Bearer $TOKEN" \
    -o /dev/null \
    -w "HTTP状态: %{http_code}\n"
done
```

```powershell
# PowerShell 版本
1..12 | ForEach-Object {
  Write-Host "=== 调用 #$_ ===" -NoNewline
  $resp = curl -X POST "http://localhost:8090/system/dwtable/exportData?noteId=100&format=excel" `
    -H "Authorization: Bearer $env:TOKEN" `
    -o $null `
    -PassHeaders
  Write-Host " HTTP $($resp.StatusCode)"
}
```

**预期结果**:
- 前 10 次成功（HTTP 200）
- 第 11、12 次返回限流错误（HTTP 500 或 429，消息含 `访问过于频繁` 或 `服务器限流异常`）
- 同时另一用户调用不受影响（验证 `LimitType.USER` 隔离性）

---

## 场景 8：多表叠加超时兜底

构造 noteId 下总记录数 >10000：

```bash
curl -X POST "http://localhost:8090/system/dwtable/exportData?noteId=100&format=excel" \
  -H "Authorization: Bearer $TOKEN" \
  -w "HTTP状态: %{http_code}\n"
```

**预期结果**:
- HTTP 500
- 响应 body 含: `数据量过大（XXX 条记录），请缩小数据范围后重试`

---

## 场景 9：admin 通行

admin 用户导出任意 noteId：

```bash
curl -X POST "http://localhost:8090/system/dwtable/exportData?noteId=200&format=excel" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -o admin-export.zip \
  -w "HTTP状态: %{http_code}\n"
```

**预期结果**:
- HTTP 200（admin 通行，不受归属限制）

---

## 场景 10：PII 审计验证

导出含 PII 列（人员 type=11、创建人 type=1003、修改人 type=1004）的数据表后，检查审计日志：

```bash
# 导出含 PII 的表
curl -X POST "http://localhost:8090/system/dwtable/exportData?noteId=100&format=sql" \
  -H "Authorization: Bearer $TOKEN" \
  -o pii-export.zip
```

```bash
# 检查 PII 审计日志（Linux 服务器路径 /home/ruoyi/logs）
tail -1 /home/ruoyi/logs/pii-export-audit.log
```

**预期审计记录（JSON 行）**:
```json
{
  "operatorUserId": 1,
  "operatorName": "admin",
  "noteId": 100,
  "tableName": "员工信息",
  "format": "sql",
  "timestamp": "2026-08-07T10:30:00.123Z",
  "containsPii": true,
  "recordCount": 5000,
  "shardCount": 3,
  "zipBytes": 123456
}
```

**验证要点**:
- 含 PII 列的表才写 `pii-export-audit.log`
- 不含 PII 列的表不写专用日志（仍写基线 `sys_oper_log`）
- 数据量字段（recordCount/shardCount/zipBytes）与实际导出物一致

---

## 自动化测试脚本（Bash）

把上述场景串成可重复运行的脚本：

```bash
#!/bin/bash
# multitable-export-test.sh
# 用法: TOKEN=xxx ./multitable-export-test.sh

BASE_URL="http://localhost:8090"
NOTE_ID="${NOTE_ID:-100}"
PASS=0; FAIL=0

assert_status() {
  local expected=$1; local actual=$2; local name=$3
  if [ "$expected" = "$actual" ]; then
    echo "[PASS] $name (HTTP $actual)"
    PASS=$((PASS+1))
  else
    echo "[FAIL] $name (期望 $expected, 实际 $actual)"
    FAIL=$((FAIL+1))
  fi
}

# 场景 1: Excel 导出
STATUS=$(curl -s -o /tmp/excel.zip -w "%{http_code}" -X POST \
  "$BASE_URL/system/dwtable/exportData?noteId=$NOTE_ID&format=excel" \
  -H "Authorization: Bearer $TOKEN")
assert_status 200 "$STATUS" "Excel 导出"

# 场景 2: SQL 导出
STATUS=$(curl -s -o /tmp/sql.zip -w "%{http_code}" -X POST \
  "$BASE_URL/system/dwtable/exportData?noteId=$NOTE_ID&format=sql" \
  -H "Authorization: Bearer $TOKEN")
assert_status 200 "$STATUS" "SQL 导出"

# 场景 6: 无权限
STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
  "$BASE_URL/system/dwtable/exportData?noteId=999999&format=excel" \
  -H "Authorization: Bearer $TOKEN")
# 不存在的 noteId 应失败
if [ "$STATUS" != "200" ]; then
  echo "[PASS] 无权限/不存在 (HTTP $STATUS)"; PASS=$((PASS+1))
else
  echo "[FAIL] 无权限/不存在 (期望非200, 实际 200)"; FAIL=$((FAIL+1))
fi

echo ""
echo "=== 结果: $PASS 通过, $FAIL 失败 ==="
```

---

## 常见问题排查

| 现象 | 原因 | 解决 |
|------|------|------|
| HTTP 401 | 未带 token 或 token 过期 | 重新登录获取 token |
| HTTP 404 | 服务未启动或端口错误 | 确认 `application.yml` 的 `server.port` |
| HTTP 500 `笔记不存在` | noteId 无效或无权访问 | 确认 noteId 存在且归属当前用户 |
| HTTP 500 `数据量过大` | 总记录数超 10000 | 缩小数据范围 |
| HTTP 500 `访问过于频繁` | 触发限流 | 60s 后重试 |
| 下载文件为 0 字节 | 空表且无记录 | 检查 noteId 下是否有数据表 |
| zip 解压失败 | 下载中断 | 重新导出 |
| SQL 执行报语法错误 | 标识符含特殊字符 | 检查 `SqlIdentifierSanitizer` 净化结果 |
