<#
.SYNOPSIS
  Stop node JVMs started by scripts\run-local.ps1 (uses logs\sN.pid).

.NOTES
  Uses Stop-Process (TerminateProcess). Windows has no SIGTERM for a console-less java
  process, so Spring shutdown hooks do NOT run: this behaves like a crash (kill -9).
  Durable state must therefore come from PostgreSQL, never from shutdown hooks.

.EXAMPLE
  .\scripts\stop-local.ps1             # all nodes
  .\scripts\stop-local.ps1 -Node S2    # only S2
#>
param(
    [ValidateSet('S1', 'S2', 'S3', 'S4', 'All')]
    [string]$Node = 'All'
)
$ErrorActionPreference = 'Stop'
$logs = Join-Path (Split-Path -Parent $PSScriptRoot) 'logs'
$nodes = if ($Node -eq 'All') { 1..4 } else { @([int]$Node.Substring(1)) }
foreach ($n in $nodes) {
    $pidFile = Join-Path $logs "s$n.pid"
    if (-not (Test-Path $pidFile)) { Write-Host "S$n not started by run-local.ps1 (no pid file)"; continue }
    $procId = [int](Get-Content $pidFile)
    $cim = Get-CimInstance Win32_Process -Filter "ProcessId=$procId" -ErrorAction SilentlyContinue
    # Guard against PID reuse: never kill an unrelated java process (e.g. the IDE's language server).
    $proc = if ($cim -and $cim.Name -eq 'java.exe' -and "$($cim.CommandLine)" -match 'node-server') { Get-Process -Id $procId } else { $null }
    if ($proc) {
        Stop-Process -Id $proc.Id -Force
        $proc.WaitForExit(10000) | Out-Null
        Write-Host "Stopped S$n (pid $($proc.Id))"
    } else {
        Write-Host "S$n was not running"
    }
    Remove-Item $pidFile -Force
}
exit 0
