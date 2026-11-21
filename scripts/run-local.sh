#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/node-server/target/node-server-0.1.0-SNAPSHOT.jar"
if [[ ! -f "$JAR" ]]; then echo "Run 'mvn -B package' first" >&2; exit 1; fi
mkdir -p "$ROOT/logs"
for n in 1 2 3 4; do
  if [[ "$n" == 1 ]]; then role=PRIMARY; else role=REPLICA; fi
  NODE_ID="S$n" NODE_ROLE="$role" GRPC_PORT=$((9100+n)) DB_PORT=$((5540+n)) java -jar "$JAR" > "$ROOT/logs/s$n.log" 2>&1 &
  echo $! > "$ROOT/logs/s$n.pid"
  echo "Started S$n (pid $!, gRPC $((9100+n)), postgres $((5540+n)))"
done
echo "Stop with: scripts/stop-local.sh"
