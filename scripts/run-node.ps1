<#
.SYNOPSIS
  Run ONE node in the foreground (one node per machine in a multi-machine deployment, or a single local node for debugging).

.DESCRIPTION
  Runs node-server in this console. Ctrl+C stops it gracefully (Spring shutdown hooks run),
  unlike scripts\stop-local.ps1 which kills the process.
  Role defaults to PRIMARY for S1 and REPLICA otherwise. Ports default to the local-cluster
  convention (S<n>: gRPC 910<n>, PostgreSQL 554<n>) so scripts\local-pg.ps1 -Node S<n> works
  unchanged on each physical machine.

  Peer addresses are read by the application from PEER_S1..PEER_S4 (host:port). Set them in
  this console before running, e.g. $env:PEER_S2 = '192.168.1.12:9102'.

.EXAMPLE
  .\scripts\run-node.ps1 -Id S2
  .\scripts\run-node.ps1 -Id S2 -GrpcPort 9100 -DbPort 5432
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('S1', 'S2', 'S3', 'S4')]
    [string]$Id,
    [ValidateSet('PRIMARY', 'REPLICA')]
    [string]$Role,
    [int]$GrpcPort,
    [string]$DbHost = 'localhost',
    [int]$DbPort
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $root 'node-server\target\node-server.jar'
if (-not (Test-Path $jar)) { throw "Missing $jar. Build first: .\mvnw.cmd -B -ntp clean install" }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
# Fast-start JVM settings for node processes (docs/thiet-ke.md, decision 8). Tests and CI keep JVM defaults.
$jvmOpts = @('-XX:TieredStopAtLevel=1', '-XX:+UseSerialGC', '-Xms64m', '-Xmx256m')
$n = [int]$Id.Substring(1)
if (-not $Role) { $Role = if ($n -eq 1) { 'PRIMARY' } else { 'REPLICA' } }
if (-not $GrpcPort) { $GrpcPort = 9100 + $n }
if (-not $DbPort) { $DbPort = 5540 + $n }

$env:NODE_ID = $Id
$env:NODE_ROLE = $Role
$env:GRPC_PORT = "$GrpcPort"
$env:DB_HOST = $DbHost
$env:DB_PORT = "$DbPort"
Write-Host "Starting $Id role=$Role gRPC=0.0.0.0:$GrpcPort db=${DbHost}:$DbPort (Ctrl+C to stop)"
foreach ($p in 'PEER_S1', 'PEER_S2', 'PEER_S3', 'PEER_S4') {
    $v = [Environment]::GetEnvironmentVariable($p)
    Write-Host ("  {0} = {1}" -f $p, $(if ($v) { $v } else { '(default from application.yml)' }))
}
& $java @jvmOpts -jar $jar
exit $LASTEXITCODE
