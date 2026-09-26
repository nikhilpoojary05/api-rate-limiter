#!/usr/bin/env pwsh
# ============================================================
# start-all.ps1  —  One-click launcher for API Rate Limiter
# Usage: .\start-all.ps1
# ============================================================

$ErrorActionPreference = "Continue"
$ROOT = "C:\api-rate-limiter"
$JAVA = "java"
$JVM_OPTS = "-Xms256m -Xmx512m"
$PG_PASS = "<redacted>"
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

# ── Step 4: Stop any previously running services on target ports ─────────────
Write-Host "[4/5] Freeing ports 8080, 8081, 8082, 8083..." -ForegroundColor Yellow
@(8081, 8082, 8083, 8090) | ForEach-Object {
    $port = $_
    $pids = (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue).OwningProcess
    $pids | Where-Object { $_ -gt 0 } | ForEach-Object {
        Write-Host "      Killing process $_ on port $port" -ForegroundColor DarkGray
        Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue
    }
}
Start-Sleep 2
Write-Host "      Ports cleared." -ForegroundColor Green

# ── Step 5: Launch all services ───────────────────────────────────────────────
Write-Host "[5/5] Starting services..." -ForegroundColor Yellow
Write-Host ""

$JWT_SECRET = "SuperSecretKeyForJwtSigningThatIsAtLeast256BitsLong!!"

function Start-Service {
    param($Name, $JarPath, $Port, $ExtraArgs)
    Write-Host "      Starting $Name on port $Port..." -ForegroundColor Cyan
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
    -Port 8081 `
    -ExtraArgs "--spring.datasource.password=$PG_PASS --jwt.secret=$JWT_SECRET"

Start-Sleep 3

# Launch Admin Service
Start-Service -Name "admin-service" `
    -JarPath "$ROOT\admin-service\target\admin-service-1.0.0.jar" `
    -Port 8082 `
    -ExtraArgs "--spring.datasource.password=$PG_PASS --jwt.secret=$JWT_SECRET"

Start-Sleep 3

# Launch Demo Service
Start-Service -Name "demo-service" `
    -JarPath "$ROOT\demo-service\target\demo-service-1.0.0.jar" `
    -Port 8083

Start-Sleep 2

# Launch Gateway Service (last — it needs all upstream services)
Start-Service -Name "gateway-service" `
    -JarPath "$ROOT\gateway-service\target\gateway-service-1.0.0.jar" `
    -Port 8090 `
    -ExtraArgs "--jwt.secret=$JWT_SECRET"

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  All services launched!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Waiting for services to become ready..." -ForegroundColor Yellow

# ── Health Check Loop ─────────────────────────────────────────────────────────
$services = @(
    @{ Name="Auth Service";    Url="http://localhost:8081/actuator/health"; Port=8081 },
    @{ Name="Admin Service";   Url="http://localhost:8082/actuator/health"; Port=8082 },
    @{ Name="Demo Service";    Url="http://localhost:8083/actuator/health"; Port=8083 },
    @{ Name="Gateway Service"; Url="http://localhost:8090/actuator/health"; Port=8090 }
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
Write-Host "    Gateway (main entry):  http://localhost:8090"
Write-Host "    Auth Service:          http://localhost:8081/swagger-ui.html"
Write-Host "    Admin Service:         http://localhost:8082/swagger-ui.html"
Write-Host "    Demo Service:          http://localhost:8083/actuator/health"
Write-Host ""
Write-Host "  Test login:" -ForegroundColor Cyan
Write-Host '    Invoke-RestMethod http://localhost:8090/api/auth/login -Method POST -ContentType "application/json" -Body ' + "'" + '{"username":"admin","password":"Admin@123!","tenantId":"acme-corp"}' + "'"
Write-Host ""
Write-Host "  Logs directory: $ROOT\logs\" -ForegroundColor DarkGray
Write-Host ""
