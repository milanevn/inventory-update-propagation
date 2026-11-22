<#
.SYNOPSIS
  Start node JVMs S1..S4 of the local cluster and wait until they are READY.

.DESCRIPTION
  Each node is an independent java.exe process: S1=PRIMARY, S2..S4=REPLICA,
  gRPC ports 9101..9104, PostgreSQL ports 5541..5544 (see scripts/local-pg.ps1).
  Output goes to logs\sN.log / logs\sN.err.log, the PID to logs\sN.pid.
  By default the script waits until every started node logs "Started InventoryNodeApplication"
  (about 10 s) and exits with code 1 if a node dies or times out, printing the cause.
  Stop with scripts\stop-local.ps1.

.EXAMPLE
  .\scripts\run-local.ps1              # all four nodes, wait for READY
  .\scripts\run-local.ps1 -Node S2     # only S2 (e.g. restart after a crash test)
  .\scripts\run-local.ps1 -NoWait      # return immediately
#>
param(
    [ValidateSet('S1', 'S2', 'S3', 'S4', 'All')]
    [string]$Node = 'All',
    [switch]$NoWait,
    [int]$TimeoutSec = 90
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $root 'node-server\target\node-server-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path $jar)) { throw "Missing $jar. Build first: .\mvnw.cmd -B -ntp clean install" }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
# Fast-start JVM settings for node processes (docs/thiet-ke.md, decision 8). Tests and CI keep JVM defaults.
$jvmOpts = @('-XX:TieredStopAtLevel=1', '-XX:+UseSerialGC', '-Xms64m', '-Xmx256m')
$logs = Join-Path $root 'logs'
New-Item -ItemType Directory -Force $logs | Out-Null
$nodes = if ($Node -eq 'All') { 1..4 } else { @([int]$Node.Substring(1)) }

function Get-NodeProcess([string]$pidFile) {
    if (-not (Test-Path $pidFile)) { return $null }
    $procId = [int](Get-Content $pidFile)
    $cim = Get-CimInstance Win32_Process -Filter "ProcessId=$procId" -ErrorAction SilentlyContinue
    # Guard against PID reuse: only treat it as ours if it is a java process running node-server.
    if ($cim -and $cim.Name -eq 'java.exe' -and "$($cim.CommandLine)" -match 'node-server') { return Get-Process -Id $procId }
    return $null
}

$started = @()
foreach ($n in $nodes) {
    $id = "S$n"
    $pidFile = Join-Path $logs "s$n.pid"
    $old = Get-NodeProcess $pidFile
    if ($old) { Write-Host "$id already running (pid $($old.Id))"; continue }
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    # Child processes inherit these; values are per node, set right before each start.
    $env:NODE_ID = $id
    $env:NODE_ROLE = if ($n -eq 1) { 'PRIMARY' } else { 'REPLICA' }
    $env:GRPC_PORT = "$(9100 + $n)"
    $env:DB_PORT = "$(5540 + $n)"
    $log = Join-Path $logs "s$n.log"
    $proc = Start-Process -FilePath $java -ArgumentList ($jvmOpts + @('-jar', "`"$jar`"")) -WorkingDirectory $root `
        -RedirectStandardOutput $log -RedirectStandardError (Join-Path $logs "s$n.err.log") `
        -WindowStyle Hidden -PassThru
    Set-Content -Path $pidFile -Value $proc.Id -Encoding ASCII
    Write-Host "Started $id role=$($env:NODE_ROLE) pid=$($proc.Id) gRPC=$($env:GRPC_PORT) postgres=$($env:DB_PORT) log=logs\s$n.log"
    $started += [pscustomobject]@{ Id = $id; Proc = $proc; Log = $log; Err = Join-Path $logs "s$n.err.log"; State = 'STARTING' }
}
Remove-Item Env:NODE_ID, Env:NODE_ROLE, Env:GRPC_PORT, Env:DB_PORT -ErrorAction SilentlyContinue

if ($NoWait -or $started.Count -eq 0) {
    Write-Host 'Check: .\scripts\cluster-status.ps1    Stop: .\scripts\stop-local.ps1'
    exit 0
}

Write-Host "Waiting for READY (timeout $TimeoutSec s)..."
$deadline = (Get-Date).AddSeconds($TimeoutSec)
while ((Get-Date) -lt $deadline -and @($started | Where-Object State -eq 'STARTING').Count -gt 0) {
    Start-Sleep -Seconds 1
    foreach ($s in $started | Where-Object State -eq 'STARTING') {
        if (Select-String -Path $s.Log -Pattern 'Started InventoryNodeApplication' -Quiet -ErrorAction SilentlyContinue) {
            $s.State = 'READY'
        } elseif ($s.Proc.HasExited) {
            $s.State = 'FAILED'
        }
    }
}
$failed = 0
foreach ($s in $started) {
    if ($s.State -eq 'STARTING') { $s.State = 'TIMEOUT' }
    if ($s.State -eq 'READY') { Write-Host ("{0}: READY" -f $s.Id); continue }
    $failed++
    Write-Host ("{0}: {1} - see {2}" -f $s.Id, $s.State, $s.Log) -ForegroundColor Red
    # Show the most useful cause: the last "Caused by" lines, otherwise the tail of the log.
    $causes = @(Select-String -Path $s.Log, $s.Err -Pattern '^Caused by: |APPLICATION FAILED TO START|Description:' -ErrorAction SilentlyContinue | Select-Object -Last 3)
    if ($causes.Count -gt 0) { $causes | ForEach-Object { Write-Host "    $($_.Line)" } }
    else { Get-Content $s.Log -Tail 5 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "    $_" } }
}
if ($failed -gt 0) {
    Write-Host 'Hint: .\scripts\doctor.ps1 checks Java, PostgreSQL, ports and build output.' -ForegroundColor Yellow
    exit 1
}
Write-Host 'All started nodes READY. Check: .\scripts\cluster-status.ps1    Stop: .\scripts\stop-local.ps1'
exit 0
