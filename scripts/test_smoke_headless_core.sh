#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
script="$repo_root/scripts/smoke_headless_core.sh"

bash -n "$script"
grep -q 'BOT_ENABLED=false' "$script"
grep -q 'GAHYEON_HEADLESS_ENABLED=true' "$script"
grep -q 'GAHYEON_BEHAVIOR_ENABLED=true' "$script"
grep -q 'worldRuntime' "$script"
grep -q 'health\["bot"\] == "DISABLED"' "$script"
grep -q 'health\["botState"\] == "DISABLED"' "$script"
grep -q '"state": "DISABLED", "reason": "bot.enabled=false"' "$script"
grep -q 'health\["conversation"\] == "DOWN"' "$script"
grep -q 'actuator\["status"\] == "OUT_OF_SERVICE"' "$script"
grep -q 'world\["revision"\] == 1' "$script"
grep -q 'trap cleanup EXIT INT TERM' "$script"
grep -q 'GAHYEON_HEADLESS_SMOKE_MODE:-bootRun' "$script"
grep -q 'GAHYEON_HEADLESS_SMOKE_STARTUP_TIMEOUT:-300' "$script"
grep -q 'deadline=$((SECONDS + startup_timeout))' "$script"
grep -q 'server_command=(java -jar "$jar_path")' "$script"
grep -q 'invalid GAHYEON_HEADLESS_SMOKE_MODE' "$script"
grep -q -- '-u APPLICATION_ID' "$script"
grep -q -- '-u TOKEN' "$script"
grep -q -- '-u SPOTIFY_CLIENT_ID' "$script"
grep -q -- '-u SPOTIFY_CLIENT_SECRET' "$script"
grep -q -- '-u OPENAI_API_KEY' "$script"
grep -q 'curl -fsS --max-time 1 "$base/"' "$script"
! grep -q 'APPLICATION_ID=disabled' "$script"
! grep -q 'TOKEN=disabled' "$script"

set +e
GAHYEON_HEADLESS_SMOKE_MODE=invalid "$script" >/dev/null 2>&1
exit_code=$?
set -e
[[ "$exit_code" -eq 2 ]] || {
  echo "invalid Headless smoke mode must fail with exit 2, got $exit_code" >&2
  exit 1
}

set +e
GAHYEON_HEADLESS_SMOKE_STARTUP_TIMEOUT=10 "$script" >/dev/null 2>&1
exit_code=$?
set -e
[[ "$exit_code" -eq 2 ]] || {
  echo "invalid Headless smoke timeout must fail with exit 2, got $exit_code" >&2
  exit 1
}

echo "Headless Core smoke contract passed"
