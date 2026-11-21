# Windows PowerShell 5+ — requires Java 21 and built node-server JAR.
$root = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $root 'node-server\target\node-server-0.1.0-SNAPSHOT.jar'
if (!(Test-Path $jar)) { throw 'Run mvn package first.' }
for ($i = 1; $i -le 4; $i++) {
    $role = if ($i -eq 1) { 'PRIMARY' } else { 'REPLICA' }
    $id = "S$i"
    $grpc = 9100 + $i
    $db = 5540 + $i
    $command = "`$env:NODE_ID='$id'; `$env:NODE_ROLE='$role'; `$env:GRPC_PORT='$grpc'; `$env:DB_PORT='$db'; java -jar '$jar'"
    Start-Process -FilePath 'powershell.exe' -ArgumentList '-NoExit', '-Command', $command
    Write-Host "Started $id gRPC=$grpc postgres=$db in new console"
}
