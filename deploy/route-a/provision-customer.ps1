[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9][a-z0-9-]{1,30}$')]
    [string]$Customer,

    [Parameter(Mandatory = $true)]
    [string]$Domain,

    [int]$BackendPort = 18100,
    [string]$Root = 'D:\qqai-tenants',
    [string]$RepoRoot = '',
    [string]$Jar = '',
    [string]$FrontendDist = '',
    [string]$JavaExe = 'java',

    [string]$MySqlExe = '',
    [string]$MySqlHost = '127.0.0.1',
    [int]$MySqlPort = 3306,
    [string]$MySqlAdminUser = 'root',
    [string]$MySqlAdminPassword = '',

    [string]$AstrBotApiUrl = 'http://127.0.0.1:6185',
    [string]$AstrBotToken = '',
    [string]$AstrBotDataPath = '',
    [string]$NapCatApiUrl = 'http://127.0.0.1:6100',
    [string]$NapCatToken = '',
    [string]$NapCatWebhookToken = '',
    [string]$GptSovitsApiUrl = 'http://127.0.0.1:8000',
    [string]$SelfQq = '',
    [string]$MinioEndpoint = 'http://127.0.0.1:9000',
    [string]$MinioAccessKey = 'minioadmin',
    [string]$MinioSecretKey = 'minioadmin',

    [switch]$Https,
    [switch]$CreateDatabase,
    [switch]$Force,
    [switch]$Reconfigure,
    [switch]$InstallTask
)

$ErrorActionPreference = 'Stop'

function Fail([string]$Message) {
    Write-Host "[ERROR] $Message" -ForegroundColor Red
    exit 1
}

function New-Secret([int]$Length) {
    $bytes = New-Object byte[] $Length
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    return ([Convert]::ToBase64String($bytes)).Replace('+', '-').Replace('/', '_').TrimEnd('=')
}

function Write-TextFile([string]$Path, [string]$Text) {
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Text, $utf8)
}

function Get-PropValue([string]$Path, [string]$Key) {
    if (-not (Test-Path $Path)) { return '' }
    foreach ($line in Get-Content $Path) {
        if ($line -match ('^' + [Regex]::Escape($Key) + '=(.*)$')) { return $Matches[1] }
    }
    return ''
}

if ($Domain -notmatch '^[A-Za-z0-9][A-Za-z0-9.-]*$') { Fail "Domain contains unsupported characters: $Domain" }

if (-not $RepoRoot) { $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path }
if (-not $Jar) {
    $jars = Get-ChildItem (Join-Path $RepoRoot 'backend\target') -Filter '*.jar' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc|original' } |
        Sort-Object LastWriteTime -Descending
    if (-not $jars) { Fail "No jar found under backend/target. Run: mvn -B -ntp package -DskipTests" }
    $Jar = $jars[0].FullName
}
if (-not (Test-Path $Jar)) { Fail "Jar not found: $Jar" }
if (-not $FrontendDist) { $FrontendDist = Join-Path $RepoRoot 'frontend\dist' }
if (-not (Test-Path (Join-Path $FrontendDist 'index.html'))) { Fail "Frontend dist not found: $FrontendDist. Run: npm run build" }

$javaCmd = Get-Command $JavaExe -ErrorAction SilentlyContinue
if (-not $javaCmd) { Fail "Java executable not found: $JavaExe" }
$JavaExe = $javaCmd.Source

$tenantRoot = Join-Path $Root $Customer
$isNew = -not (Test-Path $tenantRoot)
if (-not $isNew -and -not ($Force -or $Reconfigure)) {
    Fail "Tenant already exists: $tenantRoot. Use -Force (update artifacts) or -Reconfigure (rewrite config, keep secrets)."
}
if (Get-NetTCPConnection -LocalPort $BackendPort -State Listen -ErrorAction SilentlyContinue) {
    Fail "Port $BackendPort is already in use"
}

foreach ($d in @('app', 'frontend', 'conf', 'data', 'logs', 'run', 'uploads')) {
    New-Item -ItemType Directory -Force -Path (Join-Path $tenantRoot $d) | Out-Null
}

$sid = 'qqai_' + ($Customer -replace '-', '_')
$oldProps = Join-Path $tenantRoot 'conf\application.properties'

if (Test-Path $oldProps) {
    $dbUser = Get-PropValue $oldProps 'spring.datasource.username'
    $dbPass = Get-PropValue $oldProps 'spring.datasource.password'
    $jwtSecret = Get-PropValue $oldProps 'jwt.secret'
    $rabbitUser = Get-PropValue $oldProps 'spring.rabbitmq.username'
    $rabbitPass = Get-PropValue $oldProps 'spring.rabbitmq.password'
}
if (-not $dbUser) { $dbUser = $sid }
if (-not $dbPass) { $dbPass = New-Secret 24 }
if (-not $jwtSecret) { $jwtSecret = New-Secret 48 }
if (-not $rabbitUser) { $rabbitUser = $sid }
if (-not $rabbitPass) { $rabbitPass = New-Secret 24 }
if (-not $NapCatToken) { $NapCatToken = Get-PropValue $oldProps 'napcat.token' }
if (-not $NapCatToken) { $NapCatToken = New-Secret 24 }
if (-not $NapCatWebhookToken) { $NapCatWebhookToken = Get-PropValue $oldProps 'napcat.webhook-token' }
if (-not $NapCatWebhookToken) { $NapCatWebhookToken = New-Secret 32 }

$publicBase = if ($Https) { 'https://' + $Domain } else { 'http://' + $Domain }
$cookieSecure = if ($Https) { 'true' } else { 'false' }
$tenantPath = $tenantRoot -replace '\\', '/'

Copy-Item -LiteralPath $Jar -Destination (Join-Path $tenantRoot 'app\qq-ai-assistant.jar') -Force
$frontTarget = Join-Path $tenantRoot 'frontend'
Get-ChildItem -LiteralPath $frontTarget -Force -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force
Copy-Item -Path (Join-Path $FrontendDist '*') -Destination $frontTarget -Recurse -Force

$jdbc = 'jdbc:mysql://' + $MySqlHost + ':' + $MySqlPort + '/' + $sid + '?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false'
$props = @(
    ('# qq-ai-assistant Route A customer: ' + $Customer),
    '# Generated by deploy/route-a/provision-customer.ps1. Do not commit.',
    ('server.port=' + $BackendPort),
    'server.forward-headers-strategy=framework',
    ('spring.datasource.url=' + $jdbc),
    ('spring.datasource.username=' + $dbUser),
    ('spring.datasource.password=' + $dbPass),
    'spring.jpa.hibernate.ddl-auto=update',
    'spring.flyway.enabled=false',
    'spring.rabbitmq.host=127.0.0.1',
    'spring.rabbitmq.port=5672',
    ('spring.rabbitmq.username=' + $rabbitUser),
    ('spring.rabbitmq.password=' + $rabbitPass),
    ('spring.rabbitmq.virtual-host=/' + $sid),
    'component.enabled=false',
    'app.registration.enabled=true',
    ('app.cookie.secure=' + $cookieSecure),
    ('app.cors.allowed-origins=' + $publicBase),
    'app.security.trusted-proxies=127.0.0.1,::1',
    ('jwt.secret=' + $jwtSecret),
    'jwt.expiration=86400000',
    ('astrbot.api-url=' + $AstrBotApiUrl),
    ('astrbot.token=' + $AstrBotToken),
    ('astrbot.data-path=' + $AstrBotDataPath),
    ('napcat.api-url=' + $NapCatApiUrl),
    ('napcat.onebot-api-url=' + $NapCatApiUrl),
    ('napcat.token=' + $NapCatToken),
    ('napcat.webhook-token=' + $NapCatWebhookToken),
    ('napcat.self-qq=' + $SelfQq),
    ('gpt-sovits.api-url=' + $GptSovitsApiUrl),
    ('gpt-sovits.output-dir=' + $tenantPath + '/uploads/tts'),
    ('minio.endpoint=' + $MinioEndpoint),
    ('minio.access-key=' + $MinioAccessKey),
    ('minio.secret-key=' + $MinioSecretKey),
    'minio.bucket-name=qq-chat-files'
)
$propBuilder = New-Object System.Text.StringBuilder
foreach ($line in $props) { [void]$propBuilder.AppendLine($line) }
Write-TextFile -Path $oldProps -Text $propBuilder.ToString()

$sql = 'CREATE DATABASE IF NOT EXISTS ' + $sid + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;' + [Environment]::NewLine +
    "CREATE USER IF NOT EXISTS '$dbUser'@'localhost' IDENTIFIED BY '$dbPass';" + [Environment]::NewLine +
    "CREATE USER IF NOT EXISTS '$dbUser'@'127.0.0.1' IDENTIFIED BY '$dbPass';" + [Environment]::NewLine +
    "GRANT ALL PRIVILEGES ON $sid.* TO '$dbUser'@'localhost';" + [Environment]::NewLine +
    "GRANT ALL PRIVILEGES ON $sid.* TO '$dbUser'@'127.0.0.1';" + [Environment]::NewLine +
    'FLUSH PRIVILEGES;' + [Environment]::NewLine
Write-TextFile -Path (Join-Path $tenantRoot 'conf\mysql-setup.sql') -Text $sql

$rabbit = 'REM Run these as a RabbitMQ administrator (rabbitmqctl on PATH):' + [Environment]::NewLine +
    'rabbitmqctl add_vhost /' + $sid + [Environment]::NewLine +
    'rabbitmqctl add_user ' + $rabbitUser + ' ' + $rabbitPass + [Environment]::NewLine +
    'rabbitmqctl set_permissions -p /' + $sid + ' ' + $rabbitUser + ' ".*" ".*" ".*"' + [Environment]::NewLine
Write-TextFile -Path (Join-Path $tenantRoot 'conf\rabbitmq-setup.txt') -Text $rabbit

$runCmd = @'
@echo off
setlocal
cd /d "__ROOT__"
if not exist "__ROOT__\logs" mkdir "__ROOT__\logs"
"__JAVA__" -Xms256m -Xmx1g -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -jar "__ROOT__\app\qq-ai-assistant.jar" "--spring.config.additional-location=file:__TENANT__/conf/application.properties" >> "__ROOT__\logs\backend.log" 2>&1
'@.Replace('__ROOT__', $tenantRoot).Replace('__TENANT__', $tenantPath).Replace('__JAVA__', $JavaExe)
Write-TextFile -Path (Join-Path $tenantRoot 'run-backend.cmd') -Text ($runCmd -replace "\r?\n", ([Environment]::NewLine))

$startPs = @'
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$port = __PORT__
$tcp = New-Object System.Net.Sockets.TcpClient
try { $tcp.Connect("127.0.0.1", $port); $tcp.Close(); Write-Host "Backend already listening on $port"; exit 0 } catch { }
$args = @('/c', ('"' + $root + '\run-backend.cmd"'))
Start-Process -FilePath "cmd.exe" -ArgumentList $args -WorkingDirectory $root -WindowStyle Hidden | Out-Null
for ($i = 0; $i -lt 120; $i++) {
    Start-Sleep -Seconds 1
    $tcp = New-Object System.Net.Sockets.TcpClient
    try { $tcp.Connect("127.0.0.1", $port); $tcp.Close(); Write-Host "Backend ready on $port"; exit 0 } catch { }
}
Write-Host "Backend did not open port $port within 120s; check logs\backend.log" -ForegroundColor Red
exit 1
'@.Replace('__PORT__', [string]$BackendPort)
Write-TextFile -Path (Join-Path $tenantRoot 'start-customer.ps1') -Text $startPs

$stopPs = @'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$escaped = [Regex]::Escape($root)
$targets = Get-CimInstance Win32_Process | Where-Object {
    $_.CommandLine -and $_.CommandLine -match $escaped -and $_.Name -match 'java|cmd'
}
if (-not $targets) { Write-Host "No backend process found for $root"; exit 0 }
foreach ($p in $targets) {
    Write-Host "Stopping PID $($p.ProcessId) $($p.Name)"
    & taskkill /PID $p.ProcessId /T /F | Out-Null
}
'@.Replace('__ROOT__', $tenantRoot)
Write-TextFile -Path (Join-Path $tenantRoot 'stop-customer.ps1') -Text $stopPs

$statusPs = @'
$port = __PORT__
$ok = $false
$tcp = New-Object System.Net.Sockets.TcpClient
try { $tcp.Connect("127.0.0.1", $port); $tcp.Close(); $ok = $true } catch { }
if ($ok) { Write-Host "BACKEND_LISTENING port=$port"; exit 0 }
Write-Host "BACKEND_DOWN port=$port"; exit 1
'@.Replace('__PORT__', [string]$BackendPort)
Write-TextFile -Path (Join-Path $tenantRoot 'status-customer.ps1') -Text $statusPs

$taskPs = @'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$action = 'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "' + $root + '\start-customer.ps1"'
& schtasks /Create /TN "qqai-__CUSTOMER__-backend" /TR $action /SC ONSTART /RU SYSTEM /RL HIGHEST /F
'@.Replace('__CUSTOMER__', $Customer)
Write-TextFile -Path (Join-Path $tenantRoot 'install-task.ps1') -Text $taskPs

$nginxRoot = $frontTarget -replace '\\', '/'
$nginx = @'
# Route A customer: __CUSTOMER__
# Add this file to the http {} include directory of your Nginx, then: nginx -t && nginx -s reload
server {
    listen 80;
    server_name __DOMAIN__;

    root __FRONTEND__;
    index index.html;
    client_max_body_size 100m;

    location /api/ {
        proxy_pass http://127.0.0.1:__PORT__;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
    }
    location /images/ {
        proxy_pass http://127.0.0.1:__PORT__;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
    location /uploads/ {
        proxy_pass http://127.0.0.1:__PORT__;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
    location /ws/ {
        proxy_pass http://127.0.0.1:__PORT__;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;
    }
    location / {
        try_files $uri $uri/ /index.html;
    }
}
'@.Replace('__CUSTOMER__', $Customer).Replace('__DOMAIN__', $Domain).Replace('__FRONTEND__', $nginxRoot).Replace('__PORT__', [string]$BackendPort)
Write-TextFile -Path (Join-Path $tenantRoot 'conf\nginx-site.conf') -Text $nginx

if ($CreateDatabase) {
    if (-not $MySqlExe) {
        $cmd = Get-Command mysql.exe -ErrorAction SilentlyContinue
        if ($cmd) { $MySqlExe = $cmd.Source }
    }
    if (-not $MySqlExe -or -not (Test-Path $MySqlExe)) { Fail "-CreateDatabase requires mysql.exe; pass -MySqlExe" }
    $env:MYSQL_PWD = $MySqlAdminPassword
    try {
        Get-Content -Raw (Join-Path $tenantRoot 'conf\mysql-setup.sql') | & $MySqlExe "--host=$MySqlHost" "--port=$MySqlPort" "--user=$MySqlAdminUser" --default-character-set=utf8mb4
        if ($LASTEXITCODE -ne 0) { Fail "mysql.exe exited with $LASTEXITCODE" }
    } finally {
        Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
    }
    Write-Host "[OK] MySQL database/user created: $sid" -ForegroundColor Green
} else {
    Write-Host "[TODO] Execute conf\mysql-setup.sql as a MySQL administrator (or use -CreateDatabase)." -ForegroundColor Yellow
}

if ($InstallTask) { & (Join-Path $tenantRoot 'install-task.ps1') }

Write-Host ""
Write-Host "Route A tenant provisioned: $tenantRoot" -ForegroundColor Green
Write-Host "  backend port : $BackendPort"
Write-Host "  domain       : $Domain"
Write-Host "  database     : $sid (user $dbUser)"
Write-Host "  rabbit vhost : /$sid (user $rabbitUser)"
Write-Host "  config       : $oldProps"
Write-Host "  nginx        : $(Join-Path $tenantRoot 'conf\nginx-site.conf')"
Write-Host "  start/stop   : start-customer.ps1 / stop-customer.ps1"
Write-Host ""
Write-Host "Next steps:" -ForegroundColor Cyan
Write-Host "  1) Execute conf\mysql-setup.sql and conf\rabbitmq-setup.txt."
Write-Host "  2) Set AstrBot/NapCat/GSV endpoints in conf\application.properties (currently defaults)."
Write-Host "  3) Copy conf\nginx-site.conf into Nginx, then nginx -t && nginx -s reload."
Write-Host "  4) Run .\start-customer.ps1 and open $publicBase"
Write-Host ""
