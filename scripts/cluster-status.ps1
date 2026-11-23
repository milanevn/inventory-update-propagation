<#
.SYNOPSIS
  Call NodeService.GetStatus on S1..S4 (localhost:9101..9104) or on given targets.

.EXAMPLE
  .\scripts\cluster-status.ps1
  .\scripts\cluster-status.ps1 192.168.1.12:9102 192.168.1.13:9103
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $root 'cli-client\target\cli-client.jar'
if (-not (Test-Path $jar)) { throw "Missing $jar. Build first: .\mvnw.cmd -B -ntp clean install" }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
# @(...) matters: a single target must stay an array, otherwise splatting a string passes one char per argument.
$targets = @(if ($args.Count -gt 0) { $args } else { 'localhost:9101', 'localhost:9102', 'localhost:9103', 'localhost:9104' })
& $java -jar $jar @targets
exit $LASTEXITCODE
