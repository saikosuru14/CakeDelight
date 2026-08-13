<#
.SYNOPSIS
    Stops every Cake Delight process: the five services, the web UI, and the Kafka broker.

.DESCRIPTION
    Matches processes by command line rather than by port, so it only ever touches processes
    this project started. An unrelated JVM or Node app on the machine is left alone.

    Run this before .\build-all.ps1: Windows will not delete a jar that is currently being
    executed, so `mvn clean` fails while a service is up.

.PARAMETER KeepKafka
    Leave the broker running. Handy between rebuilds, since the broker holds the topic and
    its log on disk and takes the longest to start.

.EXAMPLE
    .\stop-all.ps1
.EXAMPLE
    .\stop-all.ps1 -KeepKafka
#>
[CmdletBinding()]
param(
    [switch] $KeepKafka
)

Set-Location -Path $PSScriptRoot

function Write-Step($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }
function Write-Ok($text)   { Write-Host "    $text"   -ForegroundColor Green }
function Write-Warn($text) { Write-Host "    $text"   -ForegroundColor Yellow }

$jarPattern = 'catalog-service-1\.0\.0\.jar|rating-service-1\.0\.0\.jar|order-service-1\.0\.0\.jar|notification-service-1\.0\.0\.jar|api-gateway-1\.0\.0\.jar'
$stopped = 0

Write-Step 'Stopping the five services'
$svc = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
         Where-Object { $_.CommandLine -match $jarPattern })
if ($svc.Count -eq 0) {
    Write-Warn 'None running.'
} else {
    foreach ($p in $svc) {
        $name = if ($p.CommandLine -match '([a-z-]+)-1\.0\.0\.jar') { $Matches[1] } else { 'service' }
        try {
            Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop
            Write-Ok "stopped $name (PID $($p.ProcessId))"
            $stopped++
        } catch {
            Write-Warn "could not stop PID $($p.ProcessId): $($_.Exception.Message)"
        }
    }
}

Write-Step 'Stopping the web UI'
$ui = @(Get-CimInstance Win32_Process -Filter "Name='node.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match 'dev-server\.js' })
if ($ui.Count -eq 0) {
    Write-Warn 'Not running.'
} else {
    foreach ($p in $ui) {
        try {
            Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop
            Write-Ok "stopped web-ui (PID $($p.ProcessId))"
            $stopped++
        } catch { Write-Warn "could not stop PID $($p.ProcessId)" }
    }
}

if ($KeepKafka) {
    Write-Step 'Kafka broker - left running (-KeepKafka)'
} else {
    Write-Step 'Stopping the Kafka broker'
    $kafka = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
               Where-Object { $_.CommandLine -match 'kafka\.Kafka' })
    if ($kafka.Count -eq 0) {
        Write-Warn 'Not running.'
    } else {
        foreach ($p in $kafka) {
            try {
                Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop
                Write-Ok "stopped kafka (PID $($p.ProcessId))"
                $stopped++
            } catch { Write-Warn "could not stop PID $($p.ProcessId)" }
        }
    }
}

Write-Step 'Port check'
Start-Sleep -Seconds 2
foreach ($port in 8080, 8081, 8082, 8083, 8084, 8090, 9092) {
    $c = New-Object System.Net.Sockets.TcpClient
    $open = $false
    try { $c.Connect('127.0.0.1', $port); $open = $true } catch { } finally { $c.Close() }
    if ($open) { Write-Warn "$port still listening" } else { Write-Ok "$port closed" }
}

Write-Step "Stopped $stopped process(es)"
Write-Host "    The service windows stay open so you can read the final log lines; close them when done.`n" -ForegroundColor Gray
