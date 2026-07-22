$h = "http://localhost:18081"
$logPath = "C:\Users\46142\AppData\Local\Temp\trae-agent-toolhost\jobs\job-a169e18f9e58432594a1fa98ed07a359\output.log"
$tok = Get-Content "d:\ai\Documents\qq-web\qq-ai-assistant\backend\_admin_token.txt"
Write-Host ("Token 读取 OK, 长度 {0}" -f $tok.Length)

# 先清空后端日志的缓存感：调一次空的健康检查触发 IO
Write-Host "预热：调 /api/system/health ..."
Invoke-WebRequest -UseBasicParsing -Uri "$h/api/system/health" | Out-Null
Start-Sleep -Milliseconds 600

# 记录当前日志最后行号
$beforeLines = (Get-Content $logPath).Count
Write-Host ("调接口前日志行数 = {0}" -f $beforeLines)

# 调 getRecentGroups
Write-Host "调用 GET /api/messages/recent-groups (带 token) ..."
$headers = @{ Authorization = "Bearer $tok" }
$resp = Invoke-WebRequest -UseBasicParsing -Method GET -Uri "$h/api/messages/recent-groups" -SkipHttpErrorCheck -Headers $headers
$code = [int]$resp.StatusCode
Write-Host ("  recent-groups HTTP {0}" -f $code)
if ($code -eq 200) {
  $c = [string]$resp.Content
  $groups = ($c | ConvertFrom-Json)
  Write-Host ("  返回群聊数 = {0}" -f @($groups).Count)
  if ($groups.Count -gt 0) { Write-Host ("  第一个群: groupId={0} unreadCount={1}" -f $groups[0].groupId, $groups[0].unreadCount) }
}
Start-Sleep -Seconds 4

# 统计新增的 Hibernate: 开头行数（show-sql 每条 SQL 开头一个 Hibernate:）
$afterLines = (Get-Content $logPath).Count
Write-Host ("`n调接口后日志总行数 = {0}，新增 {1} 行" -f $afterLines, ($afterLines - $beforeLines))

$newLines = Get-Content $logPath | Select-Object -Skip ($beforeLines - 1)
Write-Host ("--- 新增日志中所有 Hibernate: 行 ({0}) ---" -f @($newLines | Select-String "Hibernate:").Count)
$hibernates = @($newLines | Select-String -Pattern "^\s*Hibernate:" -CaseSensitive:$false)
foreach ($m in $hibernates) { Write-Host ("  >> " + $m.Line.Trim()) }

# 检查是否有循环查询的痕迹
Write-Host "`n--- 可疑关键词检查（N+1 会出现的函数） ---"
$keywords = @("countUnreadMessagesSince", "findLastMessageTimes", "findByGroupIdAndOwnerQqInAndActiveTrue")
foreach ($kw in $keywords) {
  $cnt = @($newLines | Select-String -Pattern $kw).Count
  Write-Host ("  {0} 出现次数 = {1}" -f $kw, $cnt)
}

# 检查 recentGroupStats（Task7 新增的 native query）是否出现
Write-Host "`n--- recentGroupStats 关键词（证明走了批量 SQL） ---"
$rgLines = @($newLines | Select-String -Pattern "recentGroupStats|MAX\(m\.server_recv_ms\)|CASE.*last_read_epoch|SUM\(CASE.*unread")
foreach ($l in $rgLines) { Write-Host ("  ✓ " + $l.Line.Trim()) }
Write-Host ("  recentGroupStats/SQL 片段匹配次数 = {0}" -f $rgLines.Count)

# 最终 PASS/FAIL 判定
$hibernateCount = $hibernates.Count
$task7pass = ($hibernateCount -gt 0 -and $hibernateCount -le 4 -and $rgLines.Count -gt 0)
Write-Host ("`n>>> Task7 最终判定: Hibernate count={0} -> {1}" -f $hibernateCount, ($task7pass ? "PASS（SQL 数在 1~4 之间=批量而非 N+1）" : "NEED MANUAL CHECK"))
