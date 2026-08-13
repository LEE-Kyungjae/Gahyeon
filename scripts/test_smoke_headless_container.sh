#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
script="$repo_root/scripts/smoke_headless_container.sh"
workflow="$repo_root/.github/workflows/build-image.yml"

bash -n "$script"
[[ -x "$script" ]]
grep -q 'run: ./scripts/smoke_headless_container.sh' "$workflow"
grep -q 'GAHYEON_HEADLESS_CONTAINER_SKIP_BUILD: "true"' "$workflow"
grep -q 'platforms: linux/amd64$' "$workflow"
! grep -q 'linux/arm64' "$workflow"
grep -q 'libdave is currently packaged only as natives-linux-x86-64' "$workflow"
grep -q 'refusing to replace existing container' "$script"
grep -q 'SPRING_PROFILES_ACTIVE=dev' "$script"
grep -q 'BOT_ENABLED=false' "$script"
grep -q 'GAHYEON_HEADLESS_ENABLED=true' "$script"
grep -q 'GAHYEON_CLIENT_TOKEN="$client_token"' "$script"
grep -q 'Authorization: Bearer' "$script"
grep -q 'GAHYEON_BEHAVIOR_ENABLED=true' "$script"
grep -q 'worldRuntime' "$script"
grep -q 'health\["botState"\] == "DISABLED"' "$script"
grep -q '"state": "DISABLED", "reason": "bot.enabled=false"' "$script"
grep -q 'health\["conversation"\] == "DOWN"' "$script"
grep -q 'actuator\["status"\] == "OUT_OF_SERVICE"' "$script"
grep -q 'world\["revision"\] == 1' "$script"
grep -q 'trap cleanup EXIT INT TERM' "$script"
grep -q 'GAHYEON_HEADLESS_CONTAINER_SKIP_BUILD:-false' "$script"
grep -q 'GAHYEON_HEADLESS_CONTAINER_STARTUP_TIMEOUT:-600' "$script"
grep -q 'deadline=$((SECONDS + startup_timeout))' "$script"
grep -q 'SPRING_JPA_SHOW_SQL=false' "$script"
! grep -q -- '--env APPLICATION_ID=' "$script"
! grep -q -- '--env TOKEN=' "$script"
! grep -q -- '--env SPOTIFY_CLIENT_ID=' "$script"
! grep -q -- '--env SPOTIFY_CLIENT_SECRET=' "$script"
! grep -q -- '--env OPENAI_API_KEY=' "$script"
grep -q 'curl -fsS --max-time 1 "$base/"' "$script"

set +e
GAHYEON_HEADLESS_CONTAINER_PORT=invalid "$script" >/dev/null 2>&1
exit_code=$?
set -e
[[ "$exit_code" -eq 2 ]] || {
  echo "invalid container smoke port must fail with exit 2, got $exit_code" >&2
  exit 1
}

set +e
GAHYEON_HEADLESS_CONTAINER_STARTUP_TIMEOUT=10 "$script" >/dev/null 2>&1
exit_code=$?
set -e
[[ "$exit_code" -eq 2 ]] || {
  echo "invalid container smoke timeout must fail with exit 2, got $exit_code" >&2
  exit 1
}

echo "Headless container smoke contract passed"
