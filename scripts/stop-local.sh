#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
for n in 1 2 3 4; do
  FILE="$ROOT/logs/s$n.pid"
  if [[ -f "$FILE" ]]; then kill "$(cat "$FILE")" 2>/dev/null || true; rm -f "$FILE"; echo "Stopped S$n"; fi
done
