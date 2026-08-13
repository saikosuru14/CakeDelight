<#
.SYNOPSIS
    Starts the whole Cake Delight stack: Kafka, four services, the gateway, and the web UI.

.DESCRIPTION
    Each component gets its own window so its log stays readable, and the script waits for
    each one to report ready before starting the next. Start order matters:

      1. Kafka          - so the topic exists and the consumer group forms cleanly
      2. catalog-service - order-service reads cake price and availability from it
      3. rating-service  - independent
      4. order-service
      5. notification-service
      6. api-gateway     - the only client entry point
      7. web-ui          - static client, proxies /api/ to the gateway

    The four services run on the `local` Spring profile, which uses an in-memory H2 database
    and defaults their catalog and Kafka addresses. The gateway has no `local` profile, so
    its four downstream URLs are set here explicitly.

    Anything already listening on its port is left alone, so this is safe to re-run to fill
    in a component that died.

.PARAMETER SkipKafka
    Do not start the broker. Checkout still returns 201 without it, but no order.completed
    event is published, so no confirmation is recorded.

.PARAMETER SkipUi
    Do not start the browser client. Use curl or Postman against port 8080 instead.

.PARAMETER TimeoutSeconds
    How long to wait for each service to become ready. Default 120.

.EXAMPLE
    .\run-all.ps1
.EXAMPLE
    .\run-all.ps1 -SkipUi
#>
[CmdletBinding()]
param(
    [switch] $SkipKafka,
    [switch] $SkipUi,
    [int]    $TimeoutSeconds = 120
)

# Not 'Stop': the readiness polling below expects connection failures while a service is
# still starting, and native tools write progress to stderr. Failures are handled explicitly.
$ErrorActionPreference = 'Continue'
Set-Location -Path $PSScriptRoot

function Write-Step($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }
function Write-Ok($text)   { Write-Host "    $text"   -ForegroundColor Green }
function Write-Warn($text) { Write-Host "    $text"   -ForegroundColor Yellow }
function Write-Err($text)  { Write-Host "    $text"   -ForegroundColor Red }

function Test-Port([int] $Port) {
    $c = New-Object System.Net.Sockets.TcpClient
    try   { $c.Connect('127.0.0.1', $Port); $true }
    catch { $false }
    finally { $c.Close() }
}

# Readiness, not just a listening socket. The four services include a database check in
# their readiness group, so this only returns true once Flyway has finished migrating.
function Wait-Ready([string] $Name, [int] $Port, [string] $Path = '/actuator/health/readiness') {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-RestMethod -Uri "http://localhost:$Port$Path" -TimeoutSec 3
            if ($r.status -eq 'UP') { return $true }
        } catch { }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Start-Component([string] $Title, [string] $Command) {
    # A separate window per component keeps the logs separable, which matters because the
    # interesting evidence (the Kafka group forming, the notification being recorded) shows
    # up in different windows.
    Start-Process -FilePath 'powershell.exe' `
        -ArgumentList '-NoExit', '-NoProfile', '-Command', "`$Host.UI.RawUI.WindowTitle='$Title'; $Command" `
        -WorkingDirectory $PSScriptRoot | Out-Null
}

# ---------------------------------------------------------------- preflight
Write-Step 'Preflight'
if (-not $env:JAVA_HOME) { Write-Err 'JAVA_HOME is not set.'; exit 1 }
$javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $javaExe)) { Write-Err "No java.exe under $env:JAVA_HOME."; exit 1 }
Write-Ok "java : $javaExe"

$services = @(
    @{ Name = 'catalog-service';      Port = 8081 },
    @{ Name = 'rating-service';       Port = 8083 },
    @{ Name = 'order-service';        Port = 8082 },
    @{ Name = 'notification-service'; Port = 8084 },
    @{ Name = 'api-gateway';          Port = 8080 }
)

$missing = @()
foreach ($s in $services) {
    $jar = "$($s.Name)\target\$($s.Name)-1.0.0.jar"
    if (-not (Test-Path $jar)) { $missing += $jar }
}
if ($missing.Count -gt 0) {
    Write-Err 'These jars are missing:'
    $missing | ForEach-Object { Write-Err "  $_" }
    Write-Err 'Run .\build-all.ps1 first.'
    exit 1
}
Write-Ok 'All five jars present.'

# ---------------------------------------------------------------- 1. Kafka
if (-not $SkipKafka) {
    Write-Step '1/7  Kafka broker (9092)'
    if (Test-Port 9092) {
        Write-Ok 'Already listening, leaving it alone.'
    } else {
        $kafkaHome = if ($env:KAFKA) { $env:KAFKA } else { "$env:USERPROFILE\kafka_2.13-3.7.1" }
        if (-not (Test-Path "$kafkaHome\libs")) {
            Write-Warn "No Kafka distribution at $kafkaHome - skipping the broker."
            Write-Warn 'Checkout will still return 201, but no confirmation will be recorded.'
            Write-Warn 'See the README for the one-time Kafka setup, or set $env:KAFKA.'
            $SkipKafka = $true
        } else {
            Start-Component 'cake-delight kafka' 'cmd /c kafka-run.cmd'
            $deadline = (Get-Date).AddSeconds(60)
            while ((Get-Date) -lt $deadline -and -not (Test-Port 9092)) { Start-Sleep -Seconds 2 }
            if (Test-Port 9092) { Write-Ok 'Broker is accepting connections.' }
            else { Write-Warn 'Broker did not come up in 60s. Continuing; the clients reconnect on their own.' }
        }
    }
} else {
    Write-Step '1/7  Kafka broker - skipped (-SkipKafka)'
    Write-Warn 'No order.completed event will be published, so step 6 of the journey stays empty.'
}

# ---------------------------------------------------------------- 2-5. the four services
$step = 2
foreach ($s in $services | Where-Object { $_.Name -ne 'api-gateway' }) {
    Write-Step "$step/7  $($s.Name) ($($s.Port))"
    if (Test-Port $s.Port) {
        Write-Ok 'Already listening, leaving it alone.'
    } else {
        $jar = "$($s.Name)\target\$($s.Name)-1.0.0.jar"
        Start-Component "cake-delight $($s.Name)" "& '$javaExe' -jar '$jar' --spring.profiles.active=local"
        if (Wait-Ready $s.Name $s.Port) { Write-Ok 'Ready (database migrated).' }
        else {
            Write-Err "Not ready after ${TimeoutSeconds}s. Check its window for the failure."
            exit 1
        }
    }
    $step++
}

# ---------------------------------------------------------------- 6. gateway
Write-Step '6/7  api-gateway (8080)'
if (Test-Port 8080) {
    Write-Ok 'Already listening, leaving it alone.'
} else {
    # The gateway has no `local` profile and no defaults: all four URLs are mandatory, and a
    # missing one fails startup with an unresolved-placeholder error.
    $env:CATALOG_SERVICE_URL      = 'http://localhost:8081'
    $env:ORDER_SERVICE_URL        = 'http://localhost:8082'
    $env:RATING_SERVICE_URL       = 'http://localhost:8083'
    $env:NOTIFICATION_SERVICE_URL = 'http://localhost:8084'
    $envSetup = @(
        "`$env:CATALOG_SERVICE_URL='http://localhost:8081'",
        "`$env:ORDER_SERVICE_URL='http://localhost:8082'",
        "`$env:RATING_SERVICE_URL='http://localhost:8083'",
        "`$env:NOTIFICATION_SERVICE_URL='http://localhost:8084'"
    ) -join '; '
    Start-Component 'cake-delight api-gateway' "$envSetup; & '$javaExe' -jar 'api-gateway\target\api-gateway-1.0.0.jar'"
    if (Wait-Ready 'api-gateway' 8080) { Write-Ok 'Ready.' }
    else { Write-Err "Not ready after ${TimeoutSeconds}s. Check its window."; exit 1 }
}

# ---------------------------------------------------------------- 7. web UI
if (-not $SkipUi) {
    Write-Step '7/7  web-ui (8090)'
    if (Test-Port 8090) {
        Write-Ok 'Already listening, leaving it alone.'
    } elseif (-not (Get-Command node -ErrorAction SilentlyContinue)) {
        Write-Warn 'Node.js not found - skipping the UI. Use curl or Postman against 8080.'
    } else {
        Start-Component 'cake-delight web-ui' 'node web-ui\dev-server.js'
        $deadline = (Get-Date).AddSeconds(30)
        while ((Get-Date) -lt $deadline -and -not (Test-Port 8090)) { Start-Sleep -Seconds 1 }
        if (Test-Port 8090) { Write-Ok 'Serving.' } else { Write-Warn 'Did not come up in 30s.' }
    }
} else {
    Write-Step '7/7  web-ui - skipped (-SkipUi)'
}

# ---------------------------------------------------------------- verify
Write-Step 'Verifying end to end through the gateway'
try {
    $health = Invoke-RestMethod -Uri 'http://localhost:8080/actuator/health' -TimeoutSec 10
    Write-Ok "gateway health   : $($health.status)"
} catch { Write-Err "gateway health   : unreachable - $($_.Exception.Message)" }

try {
    $cakes = Invoke-RestMethod -Uri 'http://localhost:8080/api/cakes?size=1' -TimeoutSec 10
    Write-Ok "catalogue        : $($cakes.totalElements) cakes reachable through the gateway"
} catch { Write-Err "catalogue        : failed - $($_.Exception.Message)" }

Write-Step 'Stack is up'
Write-Host @"
    Storefront   http://localhost:8090
    API gateway  http://localhost:8080
    Swagger UI   http://localhost:8081/swagger-ui.html   (8082, 8083, 8084 too)
    H2 console   http://localhost:8081/h2-console         (user sa, blank password)

    Data is in-memory: restarting a service wipes its tables and replays its migrations,
    so the 24 seeded cakes return but baskets, orders and ratings do not.

    Stop everything with .\stop-all.ps1
"@ -ForegroundColor Gray
