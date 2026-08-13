#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
smoke_dir="$(mktemp -d "${TMPDIR:-/tmp}/gahyeon-headless-smoke.XXXXXX")"
log_file="$smoke_dir/boot.log"
mode="${GAHYEON_HEADLESS_SMOKE_MODE:-bootRun}"
startup_timeout="${GAHYEON_HEADLESS_SMOKE_STARTUP_TIMEOUT:-300}"

if [[ -n "${GAHYEON_HEADLESS_SMOKE_PORT:-}" ]]; then
  port="$GAHYEON_HEADLESS_SMOKE_PORT"
else
  port="$(python3 - <<'PY'
import socket
with socket.socket() as value:
    value.bind(("127.0.0.1", 0))
    print(value.getsockname()[1])
PY
)"
fi
[[ "$port" =~ ^[0-9]+$ ]] && (( port >= 1024 && port <= 65535 )) || {
  echo "invalid Headless smoke port: $port" >&2
  exit 2
}
[[ "$startup_timeout" =~ ^[0-9]+$ ]] \
  && (( startup_timeout >= 30 && startup_timeout <= 900 )) || {
  echo "invalid Headless smoke startup timeout: $startup_timeout" >&2
  exit 2
}

server_pid=""
cleanup() {
  if [[ -n "$server_pid" ]] && kill -0 "$server_pid" 2>/dev/null; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  rm -rf -- "$smoke_dir"
}
trap cleanup EXIT INT TERM

cd "$repo_root"
case "$mode" in
  bootRun)
    server_command=(./gradlew --no-daemon bootRun --console=plain)
    ;;
  jar)
    if ! ./gradlew --no-daemon bootJar --console=plain >"$smoke_dir/build.log" 2>&1; then
      echo "Headless release JAR build failed" >&2
      tail -n 120 "$smoke_dir/build.log" >&2
      exit 1
    fi
    jar_path="$(python3 - "$repo_root/build/libs" <<'PY'
import pathlib, sys
root = pathlib.Path(sys.argv[1])
candidates = [path for path in root.glob("*.jar") if not path.name.endswith("-plain.jar")]
if not candidates:
    raise SystemExit("no executable bootJar found")
print(max(candidates, key=lambda path: path.stat().st_mtime_ns))
PY
)"
    server_command=(java -jar "$jar_path")
    ;;
  *)
    echo "invalid GAHYEON_HEADLESS_SMOKE_MODE: $mode (expected bootRun or jar)" >&2
    exit 2
    ;;
esac

env \
  -u APPLICATION_ID \
  -u TOKEN \
  -u SPOTIFY_CLIENT_ID \
  -u SPOTIFY_CLIENT_SECRET \
  -u OPENAI_API_KEY \
  BOT_ENABLED=false \
  WEATHER_PREFETCH_ENABLED=false \
  GAHYEON_HEADLESS_ENABLED=true \
  GAHYEON_BEHAVIOR_ENABLED=true \
  GAHYEON_UNREAL_WEBSOCKET_ENABLED=false \
  TTS_ENABLED=false \
  MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always \
  SERVER_PORT="$port" \
  "${server_command[@]}" >"$log_file" 2>&1 &
server_pid=$!

base="http://127.0.0.1:$port/api"
booted=false
deadline=$((SECONDS + startup_timeout))
while (( SECONDS < deadline )); do
  if curl -fsS --max-time 1 "$base/" \
      >"$smoke_dir/root.json" 2>/dev/null; then
    booted=true
    break
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo "Headless Core exited before serving HTTP" >&2
    tail -n 120 "$log_file" >&2
    exit 1
  fi
  sleep 1
done
if [[ "$booted" != true ]]; then
  echo "Headless Core did not serve HTTP within $startup_timeout seconds" >&2
  tail -n 120 "$log_file" >&2
  exit 1
fi

curl -sS --max-time 5 "$base/actuator/health" >"$smoke_dir/actuator.json"
curl -sS --max-time 5 "$base/health" >"$smoke_dir/health.json"
curl -fsS --max-time 5 "$base/gahyeon/desktop/speech/status" >"$smoke_dir/speech.json"
curl -fsS --max-time 5 "$base/gahyeon/desktop/worlds/smoke-world" >"$smoke_dir/world-before.json"

revision="$(python3 - "$smoke_dir" <<'PY'
import json, pathlib, sys
root = pathlib.Path(sys.argv[1])
actuator = json.loads((root / "actuator.json").read_text())
health = json.loads((root / "health.json").read_text())
speech = json.loads((root / "speech.json").read_text())
world = json.loads((root / "world-before.json").read_text())
assert actuator["status"] == "OUT_OF_SERVICE", actuator
assert actuator["components"]["discord"]["status"] == "UP", actuator
assert actuator["components"]["discord"]["details"] == {
    "state": "DISABLED", "reason": "bot.enabled=false"
}, actuator
assert actuator["components"]["agentRuntime"]["details"] == {
    "required": True, "runtimeReady": False
}, actuator
assert actuator["components"]["worldRuntime"]["status"] == "UP", actuator
assert health["status"] == "STARTING" and health["bot"] == "DISABLED", health
assert health["botState"] == "DISABLED" and health["botReason"] == "bot.enabled=false", health
assert health["db"] == "UP", health
assert health["conversationRequired"] is True and health["conversation"] == "DOWN", health
assert speech == {"transcriptionReady": False, "synthesisReady": False}, speech
assert world["revision"] == 0 and world["activity"] == "IDLE", world
print(world["revision"])
PY
)"

curl -fsS --max-time 5 \
  -H 'Content-Type: application/json' \
  -d "{\"expectedRevision\":$revision,\"emotion\":\"curious\",\"intensity\":0.6}" \
  "$base/gahyeon/desktop/worlds/smoke-world/emotion" >"$smoke_dir/world-after.json"

python3 - "$smoke_dir/world-after.json" <<'PY'
import json, pathlib, sys
world = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert world["revision"] == 1, world
assert world["emotion"] == "curious", world
assert world["emotionIntensity"] == 0.6, world
PY

echo "Headless Core smoke passed ($mode): credential-free boot, expected conversation DOWN, World revision 0->1"
