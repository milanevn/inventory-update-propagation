<#
.SYNOPSIS
  Check that this machine can build and run the local cluster. Prints OK / WARN / FAIL with a fix.

.DESCRIPTION
  Read-only: changes nothing. Exit code 1 if any check FAILs.
  Checks: Java 21 (PATH and JAVA_HOME), Maven Wrapper, PostgreSQL binaries, local PostgreSQL
  instances, build output, ports 9101..9104 / 5541..5544 / 5549 and Git settings.

.EXAMPLE
  .\scripts\doctor.ps1
#>
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$script:failures = 0

function Write-Check([string]$level, [string]$what, [string]$fix = '') {
    $color = @{ OK = 'Green'; WARN = 'Yellow'; FAIL = 'Red' }[$level]
    Write-Host ('[{0,-4}] {1}' -f $level, $what) -ForegroundColor $color
    if ($fix) { Write-Host "        -> $fix" }
    if ($level -eq 'FAIL') { $script:failures++ }
}

function Get-JavaMajor([string]$javaExe) {
    $out = & $javaExe -version 2>&1 | Select-Object -First 1
    if ("$out" -match 'version "(\d+)') { return [int]$Matches[1] }
    return $null
}

# --- Java ---------------------------------------------------------------
$javaCmd = Get-Command java -ErrorAction SilentlyContinue
if (-not $javaCmd) {
    Write-Check FAIL 'java not found on PATH' 'Install JDK 21 (Temurin) and add it to PATH'
} else {
    $major = Get-JavaMajor $javaCmd.Source
    if ($major -eq 21) { Write-Check OK "java on PATH is 21 ($($javaCmd.Source))" }
    else { Write-Check FAIL "java on PATH is version $major ($($javaCmd.Source))" 'Put JDK 21 first on PATH' }
}
if ($env:JAVA_HOME) {
    $jh = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (-not (Test-Path $jh)) { Write-Check FAIL "JAVA_HOME has no bin\java.exe ($env:JAVA_HOME)" 'Point JAVA_HOME to the JDK 21 folder' }
    else {
        $major = Get-JavaMajor $jh
        if ($major -eq 21) { Write-Check OK "JAVA_HOME is JDK 21" }
        else { Write-Check FAIL "JAVA_HOME points to Java $major (mvnw and scripts use JAVA_HOME)" 'Set JAVA_HOME to the JDK 21 folder' }
    }
} else {
    Write-Check WARN 'JAVA_HOME not set (java from PATH will be used)' 'Optional: set JAVA_HOME to the JDK 21 folder'
}

# --- Maven Wrapper --------------------------------------------------------
if ((Test-Path (Join-Path $root 'mvnw.cmd')) -and (Test-Path (Join-Path $root '.mvn\wrapper\maven-wrapper.properties'))) {
    Write-Check OK 'Maven Wrapper present (use .\mvnw.cmd, no Maven install needed)'
} else {
    Write-Check FAIL 'Maven Wrapper missing' 'Restore mvnw.cmd and .mvn\ from git'
}

# --- PostgreSQL binaries and instances ------------------------------------
$pgBin = if ($env:PG_BIN) { $env:PG_BIN } elseif (Get-Command pg_ctl.exe -ErrorAction SilentlyContinue) { Split-Path -Parent (Get-Command pg_ctl.exe).Source } else { Join-Path $env:ProgramFiles 'PostgreSQL\16\bin' }
$pgCtl = Join-Path $pgBin 'pg_ctl.exe'
if (Test-Path $pgCtl) {
    $ver = "$(& $pgCtl --version)"
    if ($ver -match ' 16\.') { Write-Check OK "PostgreSQL binaries: $ver ($pgBin)" }
    else { Write-Check WARN "PostgreSQL binaries are not 16.x: $ver" 'The project is tested with PostgreSQL 16' }
} else {
    Write-Check FAIL "PostgreSQL binaries not found ($pgBin)" 'Install PostgreSQL 16 and set PG_BIN to its bin folder (docs/van-hanh.md)'
}
$dataRoot = if ($env:INVENTORY_PG_DATA) { $env:INVENTORY_PG_DATA } else { Join-Path $env:LOCALAPPDATA 'inventory-replication\pg' }
$instances = [ordered]@{ S1 = 5541; S2 = 5542; S3 = 5543; S4 = 5544; TEST = 5549 }
$missing = @($instances.Keys | Where-Object { -not (Test-Path (Join-Path $dataRoot "$($_.ToLowerInvariant())\PG_VERSION")) })
if ($missing.Count -eq 0) { Write-Check OK "PostgreSQL instances initialized (S1..S4, TEST) in $dataRoot" }
else { Write-Check FAIL "PostgreSQL instances not initialized: $($missing -join ', ')" '.\scripts\local-pg.ps1 init' }

# --- Ports ------------------------------------------------------------------
$listeners = @{}
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | ForEach-Object { $listeners[[int]$_.LocalPort] = $_.OwningProcess }
function Get-ProcLabel([int]$procId) {
    $p = Get-CimInstance Win32_Process -Filter "ProcessId=$procId" -ErrorAction SilentlyContinue
    if (-not $p) { return "pid $procId" }
    $tag = if ("$($p.CommandLine)" -match 'node-server') { ' (node-server)' } elseif ("$($p.CommandLine)" -match 'inventory-replication') { ' (project postgres)' } else { '' }
    return "$($p.Name) pid $procId$tag"
}
foreach ($port in 9101..9104) {
    if ($listeners.ContainsKey($port)) {
        $label = Get-ProcLabel $listeners[$port]
        if ($label -match 'node-server') { Write-Check OK "port $port used by a running node ($label)" }
        else { Write-Check FAIL "port $port is taken by $label" 'Stop that program, or run the node on another port (GRPC_PORT)' }
    } else { Write-Check OK "port $port free" }
}
foreach ($id in $instances.Keys) {
    $port = $instances[$id]
    if ($listeners.ContainsKey($port)) {
        $label = Get-ProcLabel $listeners[$port]
        # Docker Desktop publishes container ports through its own proxy processes.
        if ($label -match 'postgres|docker|wslrelay|vpnkit') { Write-Check OK "port $port ($id) served by $label" }
        else { Write-Check FAIL "port $port ($id) is taken by $label" 'Stop that program; the project PostgreSQL needs this port' }
    } else {
        Write-Check WARN "port $port ($id): PostgreSQL not running" ".\scripts\local-pg.ps1 start$(if ($id -eq 'TEST') { ' -Node TEST' })"
    }
}

# --- Build output -------------------------------------------------------------
foreach ($jar in 'node-server\target\node-server-0.1.0-SNAPSHOT.jar', 'cli-client\target\cli-client-0.1.0-SNAPSHOT.jar') {
    if (Test-Path (Join-Path $root $jar)) { Write-Check OK "built: $jar" }
    else { Write-Check WARN "not built yet: $jar" '.\mvnw.cmd -B -ntp clean install' }
}

# --- Git ----------------------------------------------------------------------
if (Get-Command git -ErrorAction SilentlyContinue) {
    Push-Location $root
    $fileMode = "$(git config --get core.fileMode)"
    $userName = "$(git config --get user.name)"
    Pop-Location
    if ($fileMode -eq 'false') { Write-Check OK 'git core.fileMode=false' }
    else { Write-Check WARN 'git core.fileMode is not false (fake 100755->100644 diffs on Windows)' 'git config core.fileMode false' }
    if (-not $userName) { Write-Check WARN 'git user.name is not set' 'git config --global user.name "<name>"; git config --global user.email "<email>"' }
    else { Write-Check OK "git user.name = $userName" }
} else {
    Write-Check WARN 'git not found on PATH'
}

Write-Host ''
if ($script:failures -gt 0) { Write-Host "$($script:failures) check(s) FAILED." -ForegroundColor Red; exit 1 }
Write-Host 'No blocking problems found.' -ForegroundColor Green
exit 0
