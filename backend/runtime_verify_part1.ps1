$h = "http://localhost:18081"
function Test-Uri($method, $path, $expectCode, $body=$null) {
  try {
    $params = @{ UseBasicParsing=$true; Method=$method; Uri="$h$path"; SkipHttpErrorCheck=$true }
    if ($null -ne $body) {
      $params.Body = ($body | ConvertTo-Json -Depth 5)
      $params.ContentType = "application/json"
    }
    $resp = Invoke-WebRequest @params
    $code = [int]$resp.StatusCode
    $ok = if ($code -eq $expectCode) { "PASS" } else { "FAIL" }
    $pad = New-Object string(' ', [Math]::Max(0, 6 - $method.Length))
    Write-Host ("{0}  {1}{2} {3}  ->  HTTP {4}  (expect {5})" -f $ok, $pad, $method, $path, $code, $expectCode)
    return $resp
  } catch {
    Write-Host ("ERROR $method $path -> " + $_.Exception.Message)
    return $null
  }
}

Write-Host "`n=================================="
Write-Host " 【Task 4 白名单验证】"
Write-Host "=================================="
Test-Uri GET "/" 200 | Out-Null
Test-Uri GET "/api/system/health" 200 | Out-Null
Test-Uri GET "/uploads/avatars/notfound.png" 404 | Out-Null
Test-Uri GET "/api/avatar/notfound.png" 404 | Out-Null
Test-Uri GET "/api/auth/me" 401 | Out-Null
Test-Uri GET "/api/messages/recent-groups" 401 | Out-Null
Test-Uri GET "/api/admin/stats" 401 | Out-Null
$r = Test-Uri POST "/api/auth/login" 400 @{username=""; password=""}
if ($r) { $b = $r.Content; Write-Host ("    login-empty snippet: " + $b.Substring(0, [Math]::Min(260, $b.Length))) }

Write-Host "`n=================================="
Write-Host " 【Task 1 全局异常】未知路径 -> JSON 无堆栈"
Write-Host "=================================="
$r = Test-Uri GET "/api/does-not-exist-xyz-12345" 500
if ($r) {
  $b = $r.Content
  $hasTrace = [regex]::IsMatch($b, 'stacktrace|StackTrace|"trace"|Exception in thread|at com\.qqai\.', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
  $isJson = ($b.StartsWith("{") -or $b.StartsWith("["))
  Write-Host ("    isJson=$isJson  containsStackTrace=$hasTrace")
  Write-Host ("    body = " + $b)
}

Write-Host "`n=================================="
Write-Host " 【Task 8a】注册 - 弱密码 -> 400"
Write-Host "=================================="
$weak = @{username="weakuser_$(Get-Random -Max 999999)"; password="123456"}
$r = Test-Uri POST "/api/auth/register" 400 $weak
if ($r) { Write-Host ("    response = " + $r.Content) }

Write-Host "`n=================================="
Write-Host " 【Task 8b】注册 - IP 限流 429（同一 IP 连打 7 次）"
Write-Host "=================================="
$baseUser = "rateuser_$(Get-Random -Max 999999)"
$goodPwd = "Abc123456"
$got429 = $false
for ($i = 1; $i -le 7; $i++) {
  $body = @{username = "$baseUser`_$i"; password = $goodPwd}
  try {
    $resp = Invoke-WebRequest -UseBasicParsing -Method POST -Uri "$h/api/auth/register" -Body ($body | ConvertTo-Json) -ContentType "application/json" -SkipHttpErrorCheck
    $code = [int]$resp.StatusCode
    $snippet = if ($resp.Content.Length -gt 180) { $resp.Content.Substring(0,180) } else { $resp.Content }
    Write-Host ("  #$i HTTP $code -> $snippet")
    if ($code -eq 429 -and -not $got429) {
      Write-Host ("  PASS -> 第 $i 次触发 429 限流")
      $got429 = $true
      break
    }
  } catch {
    Write-Host ("  #$i EXCEPTION: " + $_.Exception.Message)
  }
}
if (-not $got429) { Write-Host "  FAIL -> 7 次尝试未触发 429" }
