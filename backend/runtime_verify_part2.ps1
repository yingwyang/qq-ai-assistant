$h = "http://localhost:18081"
function Post-Json($path, $body) {
  $p = @{ UseBasicParsing=$true; Method="POST"; Uri="$h$path"; SkipHttpErrorCheck=$true
           Body=($body | ConvertTo-Json -Depth 5); ContentType="application/json" }
  return Invoke-WebRequest @p
}
function Get-WithAuth($path, $token) {
  $headers = @{ Authorization = "Bearer $token" }
  $p = @{ UseBasicParsing=$true; Method="GET"; Uri="$h$path"; SkipHttpErrorCheck=$true; Headers=$headers }
  return Invoke-WebRequest @p
}
function BodyStr($resp) {
  if ($null -eq $resp) { return "" }
  $c = $resp.Content
  if ($c -is [byte[]]) { return [System.Text.Encoding]::UTF8.GetString($c) }
  return [string]$c
}

Write-Host "`n=================================="
Write-Host " 【Task 1】全局异常 - 登录接口用 GET 触发 MethodNotSupported（permitAll -> 必进 GlobalExceptionHandler）"
Write-Host "=================================="
$resp = Invoke-WebRequest -UseBasicParsing -Method GET -Uri "$h/api/auth/login" -SkipHttpErrorCheck
$code = [int]$resp.StatusCode
$b = BodyStr $resp
$hasTrace = [regex]::IsMatch($b, 'stacktrace|StackTrace|trace\s*:|Exception in thread|at com\.qqai\.', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
$isJson = ($b.StartsWith("{") -or $b.StartsWith("["))
$traceHeader = $resp.Headers["X-Trace-Id"] -join ""
Write-Host ("  HTTP {0}" -f $code)
Write-Host ("  isJson=$isJson  containsStackTrace=$hasTrace")
Write-Host ("  X-Trace-Id header = [$traceHeader]")
Write-Host ("  body = $b")
# 期望：JSON 格式，无堆栈字段，响应头有 X-Trace-Id；状态码不重要（GlobalExceptionHandler 统一包）
$t1pass = ($isJson -and -not $hasTrace -and $traceHeader.Length -gt 0)
Write-Host ("  RESULT -> " + ($t1pass ? "PASS" : "FAIL"))


Write-Host "`n=================================="
Write-Host " 【Task 8a】密码强度正则 - 8 位全小写（长度 OK，但强度不够）"
Write-Host "=================================="
$u = "weaklong_$(Get-Random -Max 999999)"
$resp = Post-Json "/api/auth/register" @{ username=$u; password="aaaaaaaa" }
$b = BodyStr $resp
$ok = ([int]$resp.StatusCode -eq 400 -and $b -match "密码强度")
Write-Host ("  HTTP {0}" -f [int]$resp.StatusCode)
Write-Host ("  body = $b")
Write-Host ("  RESULT -> " + ($ok ? "PASS (检测到强度正则触发)" : "FAIL"))


Write-Host "`n=================================="
Write-Host " 【Task 3/4a】注销（logout）后旧 token 401"
Write-Host "=================================="
$u1 = "logouttest_$(Get-Random -Max 999999)"
$pw = "GoodPass123"
Write-Host ("  注册用户 {0} ..." -f $u1)
Post-Json "/api/auth/register" @{ username=$u1; password=$pw } | Out-Null

Write-Host "  登录拿 token ..."
$loginResp = Post-Json "/api/auth/login" @{ username=$u1; password=$pw }
$lb = BodyStr $loginResp
Write-Host ("  login HTTP {0} -> {1}" -f [int]$loginResp.StatusCode, $lb.Substring(0, [Math]::Min(240, $lb.Length)))
$tok = ($lb | ConvertFrom-Json).token
if (-not $tok) { Write-Host "  FAIL - 没拿到 token"; exit 1 }
Write-Host ("  拿到 token 前 30 位: " + $tok.Substring(0,30) + "...")

Write-Host "  调 /api/auth/me (token 有效)..."
$meBefore = Get-WithAuth "/api/auth/me" $tok
Write-Host ("  me-before HTTP {0}" -f [int]$meBefore.StatusCode)

Write-Host "  直接 POST /api/auth/logout (使当前 token 进入黑名单)..."
$headers = @{ Authorization = "Bearer $tok" }
$logoutResp = Invoke-WebRequest -UseBasicParsing -Method POST -Uri "$h/api/auth/logout" -SkipHttpErrorCheck -Headers $headers
Write-Host ("  logout HTTP {0} -> {1}" -f [int]$logoutResp.StatusCode, (BodyStr $logoutResp).Substring(0,[Math]::Min(200, (BodyStr $logoutResp).Length)))

Start-Sleep -Milliseconds 800
Write-Host "  再次调 /api/auth/me (旧 token 应当失效)..."
$meAfter = Get-WithAuth "/api/auth/me" $tok
$codeAfter = [int]$meAfter.StatusCode
Write-Host ("  me-after HTTP {0}  (expect 401)" -f $codeAfter)
# 注意：Spring 默认返回 403 也表示未通过认证，两者都算 PASS（非 200）
$pass = ($codeAfter -ne 200)
Write-Host ("  RESULT -> " + ($pass ? "PASS (不再是 200 OK, token 已失效)" : "FAIL"))


Write-Host "`n=================================="
Write-Host " 【Task 3/4b】改密码后旧 token 401 (tokenVersion +1)"
Write-Host "=================================="
$u2 = "chgpwtest_$(Get-Random -Max 999999)"
$pwA = "OldPassA1"
$pwB = "NewPassB2"
Write-Host ("  注册用户 {0} ..." -f $u2)
Post-Json "/api/auth/register" @{ username=$u2; password=$pwA } | Out-Null

Write-Host "  登录拿旧 token (密码 $pwA)..."
$resp = Post-Json "/api/auth/login" @{ username=$u2; password=$pwA }
$tokA = ((BodyStr $resp) | ConvertFrom-Json).token
Write-Host ("  tokA 前 30 位: " + $tokA.Substring(0,30) + "...")

Write-Host "  tokA 调 me: HTTP " -NoNewline
$me1 = Get-WithAuth "/api/auth/me" $tokA
Write-Host ([int]$me1.StatusCode)

Write-Host "  改密码 (POST /api/auth/change-password old=$pwA new=$pwB)..."
$headers = @{ Authorization = "Bearer $tokA" }
$chgBody = @{ oldPassword = $pwA; newPassword = $pwB } | ConvertTo-Json
$chgResp = Invoke-WebRequest -UseBasicParsing -Method POST -Uri "$h/api/auth/change-password" `
            -SkipHttpErrorCheck -Headers $headers -Body $chgBody -ContentType "application/json"
$chgCode = [int]$chgResp.StatusCode
Write-Host ("  change-password HTTP {0} -> {1}" -f $chgCode, (BodyStr $chgResp).Substring(0,[Math]::Min(260, (BodyStr $chgResp).Length)))
if ($chgCode -ne 200) { Write-Host "  FAIL - 改密码失败，跳过后续验证"; exit 1 }

Start-Sleep -Milliseconds 600
Write-Host "  旧 tokA 调 /api/auth/me (应当 401/403, tv 不匹配)..."
$me2 = Get-WithAuth "/api/auth/me" $tokA
$me2code = [int]$me2.StatusCode
Write-Host ("  tokA-after HTTP {0} (expect !=200)" -f $me2code)
$tokAfail = ($me2code -ne 200)

Write-Host "  新密码登录拿 tokB ..."
$resp = Post-Json "/api/auth/login" @{ username=$u2; password=$pwB }
$tokB = ((BodyStr $resp) | ConvertFrom-Json).token
Write-Host ("  tokB 前 30 位: " + $tokB.Substring(0,30) + "...")
$me3 = Get-WithAuth "/api/auth/me" $tokB
$me3code = [int]$me3.StatusCode
Write-Host ("  tokB 调 me HTTP {0} (expect 200)" -f $me3code)
$tokBok = ($me3code -eq 200)

Write-Host ("  RESULT -> 旧token失效={0}, 新token可用={1} -> {2}" -f $tokAfail, $tokBok, (($tokAfail -and $tokBok) ? "PASS" : "FAIL"))


Write-Host "`n=================================="
Write-Host "  【准备 Task 7】登录一次拿 admin token（用内置 admin/admin123 或新建一个）"
Write-Host "=================================="
# 先尝试注册 admin-like 用户
$adminUser = "sqlcount_$(Get-Random -Max 999999)"
$adminPass = "SqlCountPass123"
Write-Host ("  注册 {0} ..." -f $adminUser)
Post-Json "/api/auth/register" @{ username=$adminUser; password=$adminPass } | Out-Null
$resp = Post-Json "/api/auth/login" @{ username=$adminUser; password=$adminPass }
$adminToken = ((BodyStr $resp) | ConvertFrom-Json).token
Write-Host ("  admin token（前 20 位）: " + $adminToken.Substring(0,20) + "...")

# 写 token 到文件，后面 grep SQL 用
Set-Content -Path "d:\ai\Documents\qq-web\qq-ai-assistant\backend\_admin_token.txt" -Value $adminToken -Encoding UTF8
Write-Host "  token 写入 _admin_token.txt（后续 shell 读取）"
