#!/usr/bin/env pwsh
# ============================================================
# start-all.ps1  —  One-click launcher for API Rate Limiter
# Usage: .\start-all.ps1
# ============================================================

$ErrorActionPreference = "Continue"
# The folder this script lives in, so the project runs from wherever it is checked
# out. This used to be hardcoded to C:\api-rate-limiter: a copy elsewhere silently
# started the C: copy's jars with the C: copy's .env.
$ROOT = $PSScriptRoot
$JAVA = "java"
$JVM_OPTS = "-Xms256m -Xmx512m"
# Secrets are read from .env (gitignored), never hardcoded here.
$envFile = Join-Path $ROOT ".env"
if (-not (Test-Path $envFile)) {
    Write-Host "Missing .env - copy .env.example to .env and fill in the values." -ForegroundColor Red
    exit 1
}
Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
        Set-Item -Path "env:$($Matches[1])" -Value $Matches[2].Trim()
    }
}
# .env is written for the Docker Compose network, where Redis is the host "redis".
# This script runs everything on this machine, so point the services at localhost.
$env:REDIS_HOST = "localhost"

# The gateway defaults to 8080, which other software often holds (Oracle Database's
# listener, for one). Set GATEWAY_PORT in .env to move it.
$GATEWAY_PORT = if ($env:GATEWAY_PORT) { [int]$env:GATEWAY_PORT } else { 8080 }

foreach ($required in @("JWT_SECRET", "POSTGRES_PASSWORD")) {
    if (-not (Get-Item "env:$required" -ErrorAction SilentlyContinue)) {
        Write-Host "$required is not set in .env" -ForegroundColor Red
        exit 1
    }
}
$PG_PASS = $env:POSTGRES_PASSWORD
$env:PGPASSWORD = $PG_PASS

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  API Rate Limiter - Startup Script" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# ── Step 1: Check PostgreSQL ─────────────────────────────────────────────────
Write-Host "[1/5] Checking PostgreSQL..." -ForegroundColor Yellow
$pgReady = & 'C:\Program Files\PostgreSQL\18\bin\pg_isready.exe' -U postgres 2>&1
if ($LASTEXITCODE -eq 0) {
    Write-Host "      PostgreSQL is running." -ForegroundColor Green
} else {
    Write-Host "      PostgreSQL not ready: $pgReady" -ForegroundColor Red
    Write-Host "      Please start PostgreSQL service and retry." -ForegroundColor Red
    exit 1
}

# ── Step 2: Check Redis ───────────────────────────────────────────────────────
Write-Host "[2/5] Checking Redis on port 6379..." -ForegroundColor Yellow
$redisUp = Test-NetConnection -ComputerName localhost -Port 6379 -WarningAction SilentlyContinue
if ($redisUp.TcpTestSucceeded) {
    Write-Host "      Redis is running." -ForegroundColor Green
} else {
    Write-Host "      Redis is NOT running! Attempting to start..." -ForegroundColor Red
    # Try common Redis locations
    $redisLocations = @(
        "C:\Program Files\Redis\redis-server.exe",
        "C:\redis\redis-server.exe",
        "redis-server"
    )
    $started = $false
    foreach ($loc in $redisLocations) {
        if (Test-Path $loc -ErrorAction SilentlyContinue) {
            Start-Process -FilePath $loc -WindowStyle Minimized
            Start-Sleep 3
            $started = $true
            Write-Host "      Redis started from $loc" -ForegroundColor Green
            break
        }
    }
    if (-not $started) {
        Write-Host ""
        Write-Host "  Redis not found. Install it:" -ForegroundColor Red
        Write-Host "    winget install Redis.Redis" -ForegroundColor Yellow
        Write-Host "  Then run this script again." -ForegroundColor Red
        exit 1
    }
}

# ── Step 3: Ensure databases exist ───────────────────────────────────────────
Write-Host "[3/5] Ensuring databases exist..." -ForegroundColor Yellow
$psql = 'C:\Program Files\PostgreSQL\18\bin\psql.exe'
& $psql -U postgres -c "CREATE DATABASE ratelimiter_auth;" 2>&1 | Out-Null
& $psql -U postgres -c "CREATE DATABASE ratelimiter_admin;" 2>&1 | Out-Null
Write-Host "      Databases ready (ratelimiter_auth, ratelimiter_admin)." -ForegroundColor Green

# ── Step 4: Free the ports — but only from a previous run of this project ─────
# This used to kill whatever was listening on these ports. That includes software
# unrelated to this project, such as Oracle's TNS listener on 8080. Now only a java
# process running one of this repo's jars is stopped; anything else stops the script.
Write-Host "[4/5] Checking ports $GATEWAY_PORT, 8081, 8082, 8083..." -ForegroundColor Yellow
$blocked = @()
foreach ($port in @(8081, 8082, 8083, $GATEWAY_PORT)) {
    $owners = (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue).OwningProcess |
        Where-Object { $_ -gt 0 } | Sort-Object -Unique
    foreach ($owner in $owners) {
        $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$owner" -ErrorAction SilentlyContinue
        if ($proc -and $proc.Name -like "java*" -and
            $proc.CommandLine -match "(auth|admin|demo|gateway)-service-[\d.]+\.jar") {
            Write-Host "      Stopping previous $($Matches[1])-service (pid $owner) on port $port" -ForegroundColor DarkGray
            Stop-Process -Id $owner -Force -ErrorAction SilentlyContinue
        } else {
            $name = if ($proc) { $proc.Name } else { "an unknown process" }
            $blocked += "Port $port is in use by $name (pid $owner)."
        }
    }
}
if ($blocked.Count -gt 0) {
    $blocked | ForEach-Object { Write-Host "      $_" -ForegroundColor Red }
    Write-Host "      This script only stops services it started. Free the port, or for the" -ForegroundColor Red
    Write-Host "      gateway set GATEWAY_PORT in .env." -ForegroundColor Red
    exit 1
}
Start-Sleep 2
Write-Host "      Ports free." -ForegroundColor Green

# ── Step 5: Launch all services ───────────────────────────────────────────────
Write-Host "[5/5] Starting services..." -ForegroundColor Yellow
Write-Host ""

function Start-Service {
    param($Name, $JarPath, $Port, $ExtraArgs)
    Write-Host "      Starting $Name on port $Port..." -ForegroundColor Cyan
    # JWT_SECRET / POSTGRES_PASSWORD are inherited from this process's environment.
    $args = "$JVM_OPTS -jar `"$JarPath`" $ExtraArgs"
    Start-Process -FilePath $JAVA `
        -ArgumentList $args `
        -RedirectStandardOutput "$ROOT\logs\$Name-stdout.log" `
        -RedirectStandardError  "$ROOT\logs\$Name-stderr.log" `
        -WindowStyle Hidden `
        -PassThru | Out-Null
}

# Create logs directory
New-Item -ItemType Directory -Path "$ROOT\logs" -Force | Out-Null

# Launch Auth Service
Start-Service -Name "auth-service" `
    -JarPath "$ROOT\auth-service\target\auth-service-1.0.0.jar" `
    -Port 8081

Start-Sleep 3

# Launch Admin Service
Start-Service -Name "admin-service" `
    -JarPath "$ROOT\admin-service\target\admin-service-1.0.0.jar" `
    -Port 8082

Start-Sleep 3

# Launch Demo Service. Its endpoints only exist under the "demo" profile, so set it
# for this one process and clear it before launching anything else.
$env:SPRING_PROFILES_ACTIVE = "demo"
Start-Service -Name "demo-service" `
    -JarPath "$ROOT\demo-service\target\demo-service-1.0.0.jar" `
    -Port 8083
Remove-Item env:SPRING_PROFILES_ACTIVE

Start-Sleep 2

# Launch Gateway Service (last — it needs all upstream services)
$env:SERVER_PORT = "$GATEWAY_PORT"
Start-Service -Name "gateway-service" `
    -JarPath "$ROOT\gateway-service\target\gateway-service-1.0.0.jar" `
    -Port $GATEWAY_PORT
Remove-Item env:SERVER_PORT

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  All services launched!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Waiting for services to become ready..." -ForegroundColor Yellow

# ── Health Check Loop ─────────────────────────────────────────────────────────
$services = @(
    @{ Name="Auth Service";    Url="http://localhost:9081/actuator/health"; Port=8081 },
    @{ Name="Admin Service";   Url="http://localhost:9082/actuator/health"; Port=8082 },
    @{ Name="Demo Service";    Url="http://localhost:9083/actuator/health"; Port=8083 },
    @{ Name="Gateway Service"; Url="http://localhost:9080/actuator/health"; Port=$GATEWAY_PORT }
)

$maxWait = 90   # seconds
$interval = 5
$waited = 0
$allUp = $false

while ($waited -lt $maxWait) {
    Start-Sleep $interval
    $waited += $interval
    $upCount = 0

    Write-Host ""
    Write-Host "  Health check ($waited/${maxWait}s):" -ForegroundColor DarkGray
    foreach ($svc in $services) {
        try {
            $resp = Invoke-WebRequest -Uri $svc.Url -TimeoutSec 3 -UseBasicParsing -ErrorAction Stop
            $status = ($resp.Content | ConvertFrom-Json).status
            if ($status -eq "UP") {
                Write-Host "    [UP]   $($svc.Name)" -ForegroundColor Green
                $upCount++
            } else {
                Write-Host "    [WARN] $($svc.Name) status=$status" -ForegroundColor Yellow
            }
        } catch {
            Write-Host "    [DOWN] $($svc.Name) (still starting...)" -ForegroundColor DarkGray
        }
    }

    if ($upCount -eq $services.Count) {
        $allUp = $true
        break
    }
}

Write-Host ""
if ($allUp) {
    Write-Host "========================================" -ForegroundColor Green
    Write-Host "  ALL SERVICES ARE UP!" -ForegroundColor Green
    Write-Host "========================================" -ForegroundColor Green
} else {
    Write-Host "  Some services may still be starting." -ForegroundColor Yellow
    Write-Host "  Check logs at: $ROOT\logs\" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "  Service URLs:" -ForegroundColor Cyan
Write-Host "    Gateway (main entry):  http://localhost:$GATEWAY_PORT"
Write-Host "    Auth Service:          http://localhost:8081/swagger-ui.html"
Write-Host "    Admin Service:         http://localhost:8082/swagger-ui.html"
Write-Host "    Demo Service:          http://localhost:8083/actuator/health"
Write-Host ""
Write-Host "  Test login:" -ForegroundColor Cyan
Write-Host ("    Invoke-RestMethod http://localhost:$GATEWAY_PORT/api/auth/login -Method POST -ContentType application/json -Body '" + '{"username":"admin","password":"Admin@123!"}' + "'")
Write-Host ""
Write-Host "  Logs directory: $ROOT\logs\" -ForegroundColor DarkGray
Write-Host ""
