<#
.SYNOPSIS
    Builds all five Cake Delight services in one go.

.DESCRIPTION
    There is no parent aggregator pom - each service is an independent Maven project by
    design - so "build everything" means five sequential builds. This script does that,
    stops at the first failure, and reports the jars it produced.

    It also handles the two things that most often go wrong:

      1. Maven not being on PATH. Pass -MavenPath, or set the CD_MVN environment variable,
         and the script uses that instead.
      2. A running service holding its own jar open. On Windows a jar being executed cannot
         be deleted, so `clean` fails with "Failed to delete ...jar". The script detects
         this before building and tells you to run .\stop-all.ps1, or does it for you with
         -StopRunning.

.PARAMETER MavenPath
    Full path to mvn.cmd, when Maven is not on PATH.

.PARAMETER StopRunning
    Stop any running services first, so `clean` can delete their jars.

.PARAMETER SkipTests
    Pass -DskipTests to Maven. Faster, and useful when you only want runnable jars.

.EXAMPLE
    .\build-all.ps1
.EXAMPLE
    .\build-all.ps1 -StopRunning -SkipTests
.EXAMPLE
    .\build-all.ps1 -MavenPath 'C:\tools\apache-maven-3.9.9\bin\mvn.cmd'
#>
[CmdletBinding()]
param(
    [string] $MavenPath,
    [switch] $StopRunning,
    [switch] $SkipTests
)

# Deliberately NOT 'Stop'. Native programs here write to stderr as a matter of course -
# `java -version` prints the version banner there, and Maven prints warnings there - and
# under 'Stop' PowerShell turns any native stderr output into a terminating error. Failure
# is detected from $LASTEXITCODE after each command instead, which is the thing that
# actually indicates a failed build.
$ErrorActionPreference = 'Continue'
Set-Location -Path $PSScriptRoot

$services = @(
    'catalog-service',
    'rating-service',
    'order-service',
    'notification-service',
    'api-gateway'
)

function Write-Step($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }
function Write-Ok($text)   { Write-Host "    $text"   -ForegroundColor Green }
function Write-Warn($text) { Write-Host "    $text"   -ForegroundColor Yellow }
function Write-Err($text)  { Write-Host "    $text"   -ForegroundColor Red }

# ---------------------------------------------------------------- JDK
Write-Step 'Checking the JDK'
if (-not $env:JAVA_HOME) {
    Write-Err 'JAVA_HOME is not set. Point it at a JDK 21 installation and retry.'
    exit 1
}
$javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $javaExe)) {
    Write-Err "No java.exe under JAVA_HOME ($env:JAVA_HOME)."
    exit 1
}
# `java -version` writes to stderr, so route it through cmd and capture stdout only.
$javaVersion = (cmd /c "`"$javaExe`" -version 2>&1" | Select-Object -First 1)
Write-Ok "JAVA_HOME : $env:JAVA_HOME"
Write-Ok "version   : $javaVersion"
if ($javaVersion -notmatch '"?2[1-9]') {
    Write-Warn 'This does not look like JDK 21+. The build targets release 21 and will fail on an older JDK.'
}

# ---------------------------------------------------------------- Maven
Write-Step 'Locating Maven'
$mvn = $null
if ($MavenPath)                    { $mvn = $MavenPath }
elseif ($env:CD_MVN)               { $mvn = $env:CD_MVN }
elseif (Get-Command mvn -ErrorAction SilentlyContinue) { $mvn = (Get-Command mvn).Source }
else {
    # Last resort: the usual per-user install locations.
    $guesses = @(
        "$env:LOCALAPPDATA\apache-maven\*\bin\mvn.cmd",
        "$env:ProgramFiles\apache-maven\*\bin\mvn.cmd",
        "$env:USERPROFILE\apache-maven\*\bin\mvn.cmd",
        'C:\tools\apache-maven\*\bin\mvn.cmd'
    )
    foreach ($g in $guesses) {
        $hit = Get-ChildItem $g -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($hit) { $mvn = $hit.FullName; break }
    }
}
if (-not $mvn -or -not (Test-Path $mvn)) {
    Write-Err 'Maven not found. Add it to PATH, or pass -MavenPath "<dir>\bin\mvn.cmd".'
    exit 1
}
Write-Ok "mvn : $mvn"

# ---------------------------------------------------------------- locked jars
Write-Step 'Checking for running services'
$running = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
             Where-Object { $_.CommandLine -match 'cake|catalog-service|order-service|rating-service|notification-service|api-gateway' })
if ($running.Count -gt 0) {
    Write-Warn "$($running.Count) service process(es) are running and hold their jars open."
    if ($StopRunning) {
        Write-Warn 'Stopping them (-StopRunning).'
        & (Join-Path $PSScriptRoot 'stop-all.ps1')
        Start-Sleep -Seconds 3
    } else {
        Write-Err 'Run .\stop-all.ps1 first, or re-run this with -StopRunning.'
        Write-Err 'Windows cannot delete a jar that is being executed, so `clean` would fail.'
        exit 1
    }
} else {
    Write-Ok 'None running.'
}

# ---------------------------------------------------------------- build
$mvnArgs = @('-B')
if ($SkipTests) { $mvnArgs += '-DskipTests' }

$results = @()
$started = Get-Date
foreach ($s in $services) {
    Write-Step "Building $s"
    $t0 = Get-Date
    & $mvn @mvnArgs -f "$s\pom.xml" clean package
    $code = $LASTEXITCODE
    $secs = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1)

    if ($code -ne 0) {
        Write-Err "$s FAILED after ${secs}s (exit $code). Stopping here."
        Write-Err 'Scroll up for the Maven error. Nothing after this service was built.'
        exit $code
    }

    $jar = "$s\target\$s-1.0.0.jar"
    if (-not (Test-Path $jar)) {
        Write-Err "$s reported success but $jar is missing."
        exit 1
    }
    $mb = [math]::Round((Get-Item $jar).Length / 1MB, 1)
    Write-Ok "$s ok in ${secs}s -> $jar ($mb MB)"
    $results += [pscustomobject]@{ Service = $s; Seconds = $secs; SizeMB = $mb }
}

$total = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
Write-Step "All five built in ${total}s"
$results | Format-Table -AutoSize
Write-Host "Next: .\run-all.ps1`n" -ForegroundColor Cyan
