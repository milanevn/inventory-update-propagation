<#
.SYNOPSIS
  Manage INDEPENDENT local PostgreSQL instances for S1..S4 and for integration tests, without Docker.

.DESCRIPTION
  Each instance has its own data directory, its own postgres server process and its own port:
  S1=5541, S2=5542, S3=5543, S4=5544, TEST=5549 (used only by *IT integration tests).
  Stopping one instance does not affect the others, which is required for node-specific
  failure tests (acceptance scenario T16).

  Requires PostgreSQL 16 server binaries (initdb, pg_ctl, psql). No admin rights and no
  Windows service are needed; the portable EDB "binaries" ZIP is enough.

.PARAMETER Action
  init     Create data directories (skips instances that already exist), start them and
           create database/user 'inventory'.
  start    Start the instance(s).
  stop     Stop the instance(s) (fast shutdown, data is kept).
  status   Show whether each instance is running.
  reset    Drop and recreate database 'inventory' (all tables and Flyway history are lost; the
           instance keeps running). Use after switching branches or editing an unmerged migration
           when Flyway reports a checksum/validation error. Stop the node JVMs first.
  destroy  Stop the instance and DELETE its whole data directory (run init afterwards).

.PARAMETER Node
  S1, S2, S3, S4, TEST or All (default: all five).

.PARAMETER Force
  Do not ask for confirmation (reset / destroy).

.EXAMPLE
  .\scripts\local-pg.ps1 init
  .\scripts\local-pg.ps1 stop -Node S3      # simulate S3 database failure
  .\scripts\local-pg.ps1 start -Node S3
  .\scripts\local-pg.ps1 reset -Force       # fresh empty databases everywhere

.NOTES
  Environment overrides:
    PG_BIN           folder containing initdb.exe/pg_ctl.exe (else: PATH, then %ProgramFiles%\PostgreSQL\16\bin)
    INVENTORY_PG_DATA root folder for data dirs (default: %LOCALAPPDATA%\inventory-replication\pg)
    DB_PASSWORD      password for user 'inventory' (default: local_only_change_me, localhost only)
#>
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('init', 'start', 'stop', 'status', 'reset', 'destroy')]
    [string]$Action,
    [ValidateSet('S1', 'S2', 'S3', 'S4', 'TEST', 'All')]
    [string]$Node = 'All',
    [switch]$Force
)
$ErrorActionPreference = 'Stop'

function Resolve-PgBin {
    if ($env:PG_BIN) { return $env:PG_BIN }
    $onPath = Get-Command pg_ctl.exe -ErrorAction SilentlyContinue
    if ($onPath) { return (Split-Path -Parent $onPath.Source) }
    $default = Join-Path $env:ProgramFiles 'PostgreSQL\16\bin'
    if (Test-Path (Join-Path $default 'pg_ctl.exe')) { return $default }
    throw 'PostgreSQL 16 binaries not found. Set PG_BIN to the PostgreSQL bin folder or add it to PATH (docs/van-hanh.md).'
}

$pgBin = Resolve-PgBin
$initdb = Join-Path $pgBin 'initdb.exe'
$pgCtl = Join-Path $pgBin 'pg_ctl.exe'
$psql = Join-Path $pgBin 'psql.exe'
$dataRoot = if ($env:INVENTORY_PG_DATA) { $env:INVENTORY_PG_DATA } else { Join-Path $env:LOCALAPPDATA 'inventory-replication\pg' }
$password = if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { 'local_only_change_me' }
$ports = [ordered]@{ S1 = 5541; S2 = 5542; S3 = 5543; S4 = 5544; TEST = 5549 }
$nodes = if ($Node -eq 'All') { @($ports.Keys) } else { @($Node) }

function Get-NodeInfo([string]$id) {
    $name = $id.ToLowerInvariant()
    [pscustomobject]@{ Id = $id; Port = $ports[$id]; Data = Join-Path $dataRoot $name; Log = Join-Path $dataRoot "$name.log" }
}

function Confirm-Action($info, [string]$what) {
    if ($Force) { return $true }
    $answer = Read-Host "$what for $($info.Id) ($($info.Data))? Type the node id to confirm"
    return ($answer -eq $info.Id)
}

function Reset-Database($info) {
    if (-not (Confirm-Action $info 'Drop and recreate database inventory')) { Write-Host "$($info.Id): skipped"; return }
    Start-Node $info
    $env:PGPASSWORD = $password
    try {
        # WITH (FORCE) also terminates open connections (e.g. a node JVM that is still running).
        & $psql -h localhost -p $info.Port -U inventory -d postgres -q -c 'DROP DATABASE IF EXISTS inventory WITH (FORCE)' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "$($info.Id): DROP DATABASE failed" }
        & $psql -h localhost -p $info.Port -U inventory -d postgres -q -c 'CREATE DATABASE inventory' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "$($info.Id): CREATE DATABASE failed" }
    } finally {
        Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    }
    Write-Host "$($info.Id): database 'inventory' recreated (empty; Flyway migrates on next node start)"
}

function Test-Running($info) {
    if (-not (Test-Path (Join-Path $info.Data 'PG_VERSION'))) { return $false }
    & $pgCtl status -D $info.Data *> $null
    return ($LASTEXITCODE -eq 0)
}

function Start-Node($info) {
    if (-not (Test-Path (Join-Path $info.Data 'PG_VERSION'))) { throw "$($info.Id): not initialized. Run: .\scripts\local-pg.ps1 init -Node $($info.Id)" }
    if (Test-Running $info) { Write-Host "$($info.Id): already running on port $($info.Port)"; return }
    # Do NOT call pg_ctl with captured/piped output: the long-lived postgres child would inherit
    # the pipe handle and the caller would hang forever. Start-Process (ShellExecute) avoids that;
    # WaitForExit waits for pg_ctl only, not for the postgres server it spawns.
    $ctl = Start-Process -FilePath $pgCtl -ArgumentList 'start', '-D', "`"$($info.Data)`"", '-l', "`"$($info.Log)`"", '-w', '-t', '30' `
        -WindowStyle Hidden -PassThru
    $ctl.WaitForExit()
    if ($ctl.ExitCode -ne 0) { throw "$($info.Id): pg_ctl start failed (exit $($ctl.ExitCode)), see $($info.Log)" }
    Write-Host "$($info.Id): started on localhost:$($info.Port) (data $($info.Data))"
}

function Stop-Node($info) {
    if (-not (Test-Running $info)) { Write-Host "$($info.Id): not running"; return }
    & $pgCtl stop -D $info.Data -m fast -w | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "$($info.Id): pg_ctl stop failed" }
    Write-Host "$($info.Id): stopped"
}

function Initialize-Node($info) {
    if (Test-Path (Join-Path $info.Data 'PG_VERSION')) {
        Write-Host "$($info.Id): already initialized at $($info.Data)"
    } else {
        New-Item -ItemType Directory -Force $dataRoot | Out-Null
        $pwFile = Join-Path $dataRoot "pw-$($info.Id).tmp"
        try {
            [System.IO.File]::WriteAllText($pwFile, $password)
            & $initdb -D $info.Data -U inventory --pwfile=$pwFile -E UTF8 --locale=C --auth-local=scram-sha-256 --auth-host=scram-sha-256 | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "$($info.Id): initdb failed" }
        } finally {
            Remove-Item $pwFile -Force -ErrorAction SilentlyContinue
        }
        # Node-specific port, reachable from this machine only.
        Add-Content -Path (Join-Path $info.Data 'postgresql.conf') -Encoding ASCII -Value @(
            '', '# inventory-replication local node settings',
            "port = $($info.Port)", "listen_addresses = 'localhost'")
        Write-Host "$($info.Id): initialized at $($info.Data)"
    }
    Start-Node $info
    $env:PGPASSWORD = $password
    try {
        $exists = & $psql -h localhost -p $info.Port -U inventory -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = 'inventory'"
        if ($LASTEXITCODE -ne 0) { throw "$($info.Id): cannot connect with psql" }
        if ("$exists".Trim() -ne '1') {
            & $psql -h localhost -p $info.Port -U inventory -d postgres -c 'CREATE DATABASE inventory' | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "$($info.Id): CREATE DATABASE failed" }
            Write-Host "$($info.Id): database 'inventory' created"
        }
    } finally {
        Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    }
}

foreach ($n in $nodes) {
    $info = Get-NodeInfo $n
    switch ($Action) {
        'init'   { Initialize-Node $info }
        'start'  { Start-Node $info }
        'stop'   { Stop-Node $info }
        'status' {
            $state = if (-not (Test-Path (Join-Path $info.Data 'PG_VERSION'))) { 'NOT INITIALIZED' } elseif (Test-Running $info) { 'RUNNING' } else { 'STOPPED' }
            '{0,-4}  port {1}  {2,-15}  {3}' -f $info.Id, $info.Port, $state, $info.Data
        }
        'reset'  { Reset-Database $info }
        'destroy' {
            if (-not (Test-Path $info.Data)) { Write-Host "$($info.Id): nothing to delete"; continue }
            if (-not (Confirm-Action $info 'DELETE the whole data directory')) { Write-Host "$($info.Id): skipped"; continue }
            Stop-Node $info
            Remove-Item -Recurse -Force $info.Data
            Write-Host "$($info.Id): data directory deleted. Run init to recreate."
        }
    }
}
# pg_ctl status leaves a non-zero LASTEXITCODE behind; report success explicitly.
exit 0
