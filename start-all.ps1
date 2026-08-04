param([switch]$NoPause)

$ErrorActionPreference = 'Continue'
$LogDir = Join-Path $PSScriptRoot 'logs'
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

# ---------- 环境配置（按本机实际情况）----------
$MySqlExe      = 'C:\Program Files\MySQL\MySQL Server 8.3\bin\mysql.exe'
$DockerDesktop = 'C:\Users\15079\AppData\Local\Programs\DockerDesktop\Docker Desktop.exe'
$TBDir         = 'C:\Users\15079\ThingsBoard'
$TaskDir       = 'C:\Users\15079\Desktop\java\webtest\task-service'
$Mvn           = "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.16-bin\5grr65jo27hi51sujmtcldfovl\apache-maven-3.9.16\bin\mvn.cmd"
$SimDir        = 'C:\Users\15079\Desktop\q\物联网灌溉系统第一代第三版\simulators'
$AppDir        = 'C:\Users\15079\Desktop\q\kotlin-demo'
$AndroidSdk    = 'D:\Android\Sdk'
$Adb           = "$AndroidSdk\platform-tools\adb.exe"
$Emulator      = "$AndroidSdk\emulator\emulator.exe"
$AvdName       = 'Pixel_7'
$MySqlContainer = 'mysql57'          # Docker MySQL 容器（宿主 3307 -> 容器 3306）

function Log($msg) { Write-Host ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $msg) }

function Wait-Port([int]$port, [int]$timeoutSec, [string]$name) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { return $true }
        Start-Sleep -Seconds 3
    }
    Log "WARN: $name 端口 $port 在 ${timeoutSec}s 内未就绪"
    return $false
}

Log '================ 智能灌溉系统一键启动 ================'

# ---------- 1. Docker Desktop ----------
Log '[1/7] 检查 Docker daemon...'
docker info *>$null
if ($LASTEXITCODE -ne 0) {
    Log 'Docker daemon 未运行，启动 Docker Desktop...'
    if (Test-Path $DockerDesktop) { Start-Process $DockerDesktop | Out-Null } else { Log "ERROR: 找不到 Docker Desktop: $DockerDesktop" }
    $deadline = (Get-Date).AddSeconds(120)
    $ok = $false
    while ((Get-Date) -lt $deadline) {
        docker info *>$null
        if ($LASTEXITCODE -eq 0) { $ok = $true; break }
        Start-Sleep -Seconds 5
    }
    if ($ok) { Log 'Docker daemon 就绪' } else { Log 'ERROR: Docker daemon 120s 内未就绪，请手动启动 Docker Desktop' }
} else {
    Log 'Docker daemon 运行中'
}

# ---------- 2. Docker MySQL（宿主 3307）----------
Log '[2/7] 启动 Docker MySQL（容器 mysql57，宿主 :3307）...'
$mysqlUp = docker ps --filter "name=^/$MySqlContainer$" --format '{{.Names}}' 2>$null
if (-not $mysqlUp) {
    docker start $MySqlContainer 2>&1 | Out-Host
}
$myOk = Wait-Port 3307 60 'Docker MySQL'
if ($myOk) {
    & $MySqlExe -uroot -p147258 -P3307 -h127.0.0.1 -e "CREATE DATABASE IF NOT EXISTS task_service DEFAULT CHARACTER SET utf8mb4;" 2>$null
    Log 'MySQL :3307 就绪，task_service 库已就绪'
} else {
    Log 'WARN: MySQL 3307 未在 60s 内就绪'
}

# ---------- 3. ThingsBoard ----------
Log '[3/7] 启动 ThingsBoard（docker compose up -d）...'
Push-Location $TBDir
docker compose up -d 2>&1 | Out-Host
Pop-Location
$tbOk = Wait-Port 8080 180 'ThingsBoard'
if (-not $tbOk) { Log 'WARN: ThingsBoard 8080 未在 180s 内就绪（首次启动可能需数分钟）' }

# ---------- 4. 微服务端 ----------
Log '[4/7] 启动微服务端 task-service（:9091）...'
if (Get-NetTCPConnection -LocalPort 9091 -State Listen -ErrorAction SilentlyContinue) {
    Log '微服务端已在运行，跳过'
} else {
    Start-Process -FilePath $Mvn -ArgumentList 'spring-boot:run' -WorkingDirectory $TaskDir `
        -RedirectStandardOutput "$LogDir\task-service.log" -RedirectStandardError "$LogDir\task-service.err.log" -WindowStyle Hidden
    $srvOk = Wait-Port 9091 150 '微服务端'
    if (-not $srvOk) { Log 'WARN: 微服务端 150s 未就绪，请查看 logs\task-service.log' }
}

# ---------- 5. 设备模拟器（27 台）----------
Log '[5/7] 清理旧模拟器进程（避免重复进程导致状态混乱）...'
$procs = Get-CimInstance Win32_Process -Filter "Name='python.exe'" -ErrorAction SilentlyContinue
foreach ($p in $procs) {
    if ($p.CommandLine -match 'start_all\.py|simulators[\/]') {
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
        Log "  已停止 PID $($p.ProcessId)"
    }
}
Start-Sleep -Seconds 2
Log '[5/7] 启动设备模拟器（27 台，MQTT -> ThingsBoard）...'
Wait-Port 1883 60 'ThingsBoard MQTT'
Start-Process -FilePath 'py' -ArgumentList 'start_all.py' -WorkingDirectory $SimDir `
    -RedirectStandardOutput "$LogDir\simulators.log" -RedirectStandardError "$LogDir\simulators.err.log" -WindowStyle Hidden
Log '模拟器进程已启动（日志 logs\simulators.log）'

# ---------- 6. Android 模拟器 + APP ----------
Log '[6/7] 启动 Android 模拟器（Pixel_7）...'
$deviceReady = $false
$online = (& $Adb devices 2>$null | Select-String '^emulator-\d+\s+device')
if ($online) {
    Log '已有模拟器在线'
    $deviceReady = $true
} else {
    Start-Process -FilePath $Emulator -ArgumentList "-avd $AvdName -no-snapshot-load -no-boot-anim" | Out-Null
    Log '等待模拟器启动（可能需 1-3 分钟）...'
    $deadline = (Get-Date).AddSeconds(240)
    while ((Get-Date) -lt $deadline) {
        & $Adb wait-for-device 2>$null
        $boot = (& $Adb shell getprop sys.boot_completed 2>$null).Trim()
        if ($boot -eq '1') { $deviceReady = $true; break }
        Start-Sleep -Seconds 5
    }
    if ($deviceReady) { Log '模拟器启动完成' } else { Log 'WARN: 模拟器 240s 内未完全启动' }
}

if ($deviceReady) {
    Log '构建并安装 APP（gradlew installDebug，首次较慢）...'
    Push-Location $AppDir
    & .\gradlew.bat installDebug 2>&1 | Select-Object -Last 6 | Out-Host
    Pop-Location
    & $Adb shell am start -n com.demo.kotlindemo/.MainActivity 2>$null | Out-Null
    Log 'APP 已启动'
} else {
    Log 'WARN: 无可用设备，跳过 APP 安装（可手动 adb install）'
}

# ---------- 7. 摘要 ----------
Log '[7/7] 启动流程结束，服务一览：'
Log '  Docker MySQL      localhost:3307（库 task_service，容器 mysql57）'
Log '  ThingsBoard UI    http://localhost:8080'
Log '  ThingsBoard MQTT  localhost:1883'
Log '  微服务端 REST     http://localhost:9091'
Log '  设备模拟器 27 台  -> ThingsBoard'
Log '  Android APP       Pixel_7 模拟器'
Log "日志目录: $LogDir"
if (-not $NoPause) { Read-Host '按回车退出' }
