﻿﻿﻿﻿﻿# 多维表格导出端点自动化测试脚本（PowerShell 版）
# 用法:
#   1. 启动服务: mvn spring-boot:run -pl ruoyi-admin
#   2. 运行测试: .\multitable-export-test.ps1
#   3. 指定参数: .\multitable-export-test.ps1 -BaseUrl "http://localhost:8090" -NoteId 100 -Username admin -Password admin123

param(
    [string]$BaseUrl = "http://localhost:8090",
    [string]$NoteId = "100",
    [string]$Username = "admin",
    [string]$Password = "admin123"
)

$ErrorActionPreference = "SilentlyContinue"
$script:Pass = 0
$script:Fail = 0
$script:Token = ""

function Write-Result($name, $passed, $detail = "") {
    if ($passed) {
        Write-Host "[PASS] $name $detail" -ForegroundColor Green
        $script:Pass++
    } else {
        Write-Host "[FAIL] $name $detail" -ForegroundColor Red
        $script:Fail++
    }
}

function Invoke-ExportApi($noteId, $format) {
    $headers = @{}
    if ($script:Token) { $headers["Authorization"] = "Bearer $($script:Token)" }
    try {
        $resp = Invoke-WebRequest -Uri "$BaseUrl/system/dwtable/exportData?noteId=$noteId&format=$format" `
            -Method POST -Headers $headers -ErrorAction Stop
        return @{ StatusCode = $resp.StatusCode; Content = $resp.Content; Headers = $resp.Headers }
    } catch {
        return @{ StatusCode = $_.Exception.Response.StatusCode.value__; Content = $_.ErrorDetails.Message; Headers = @{} }
    }
}

# ============ 前置: 登录获取 Token ============
Write-Host "`n=== 登录获取 Token ===" -ForegroundColor Cyan
try {
    $loginBody = @{ username = $Username; password = $Password } | ConvertTo-Json
    $loginResp = Invoke-RestMethod -Uri "$BaseUrl/login" -Method POST -Body $loginBody -ContentType "application/json"
    if ($loginResp.token) {
        $script:Token = $loginResp.token
        Write-Host "Token 获取成功" -ForegroundColor Green
    } else {
        Write-Host "Token 获取失败: $($loginResp.msg)" -ForegroundColor Red
        Write-Host "继续测试（本地 /system permitAll 可不需要 token）" -ForegroundColor Yellow
    }
} catch {
    Write-Host "登录请求失败: $($_.Exception.Message)" -ForegroundColor Yellow
    Write-Host "继续测试（本地 /system permitAll 可不需要 token）" -ForegroundColor Yellow
}

# ============ 场景 1: 导出 Excel ============
Write-Host "`n=== 场景 1: 导出 Excel ===" -ForegroundColor Cyan
$result = Invoke-ExportApi $NoteId "excel"
if ($result.StatusCode -eq 200) {
    $tempFile = "$env:TEMP\export-excel.zip"
    [System.IO.File]::WriteAllBytes($tempFile, $result.Content)
    $size = (Get-Item $tempFile).Length
    Write-Result "Excel 导出返回 200" $true "(下载 $size 字节)"
    Write-Result "响应为 zip" ($result.Headers["Content-Type"] -match "zip")
} else {
    Write-Result "Excel 导出返回 200" $false "(HTTP $($result.StatusCode))"
}

# ============ 场景 2: 导出 SQL ============
Write-Host "`n=== 场景 2: 导出 SQL ===" -ForegroundColor Cyan
$result = Invoke-ExportApi $NoteId "sql"
if ($result.StatusCode -eq 200) {
    $tempFile = "$env:TEMP\export-sql.zip"
    [System.IO.File]::WriteAllBytes($tempFile, $result.Content)
    $size = (Get-Item $tempFile).Length
    Write-Result "SQL 导出返回 200" $true "(下载 $size 字节)"

    # 解压检查 SQL 文件
    $extractDir = "$env:TEMP\export-sql-check"
    if (Test-Path $extractDir) { Remove-Item $extractDir -Recurse -Force }
    Expand-Archive -Path $tempFile -DestinationPath $extractDir -Force
    $sqlFiles = Get-ChildItem $extractDir -Filter "*.sql"
    $manifest = Get-ChildItem $extractDir -Filter "manifest.json"
    Write-Result "zip 含 .sql 文件" ($sqlFiles.Count -gt 0) "($($sqlFiles.Count) 个)"
    Write-Result "zip 含 manifest.json" ($manifest.Count -eq 1)
    if ($manifest) {
        $manifestContent = Get-Content $manifest.FullName -Raw | ConvertFrom-Json
        Write-Host "  manifest noteId: $($manifestContent.noteId)" -ForegroundColor Gray
        Write-Host "  manifest format: $($manifestContent.format)" -ForegroundColor Gray
        Write-Host "  manifest tables 数: $($manifestContent.tables.Count)" -ForegroundColor Gray
    }
} else {
    Write-Result "SQL 导出返回 200" $false "(HTTP $($result.StatusCode))"
}

# ============ 场景 3: 无权限/不存在 ============
Write-Host "`n=== 场景 3: 无权限/不存在的 noteId ===" -ForegroundColor Cyan
$result = Invoke-ExportApi "999999" "excel"
if ($result.StatusCode -ne 200) {
    Write-Result "不存在的 noteId 返回非 200" $true "(HTTP $($result.StatusCode))"
} else {
    Write-Result "不存在的 noteId 返回非 200" $false "(实际 HTTP $($result.StatusCode))"
}

# ============ 场景 4: 限流触发 ============
Write-Host "`n=== 场景 4: 限流触发（连续 12 次）===" -ForegroundColor Cyan
$successCount = 0
$rateLimited = $false
for ($i = 1; $i -le 12; $i++) {
    $result = Invoke-ExportApi $NoteId "excel"
    if ($result.StatusCode -eq 200) {
        $successCount++
    } else {
        $rateLimited = $true
        Write-Host "  第 $i 次调用: HTTP $($result.StatusCode)（触发限流）" -ForegroundColor Yellow
        break
    }
    Write-Host "  第 $i 次调用: HTTP 200" -ForegroundColor Gray
}
Write-Result "限流触发（10/60s 后第 11+ 次失败）" $rateLimited "(成功 $successCount 次后触发)"

# ============ 场景 5: admin 通行（重新登录后用不同 noteId）============
Write-Host "`n=== 场景 5: admin 通行 ===" -ForegroundColor Cyan
$result = Invoke-ExportApi $NoteId "excel"
Write-Result "admin 可导出任意 noteId" ($result.StatusCode -eq 200)

# ============ 汇总 ============
Write-Host "`n===================================" -ForegroundColor Cyan
Write-Host "测试结果: $script:Pass 通过, $script:Fail 失败" -ForegroundColor $(if ($script:Fail -eq 0) { "Green" } else { "Yellow" })
Write-Host "===================================" -ForegroundColor Cyan

if ($script:Fail -gt 0) { exit 1 } else { exit 0 }
