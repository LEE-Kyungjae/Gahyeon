#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
image="${GAHYEON_HEADLESS_CONTAINER_IMAGE:-gahyeon-headless-smoke:local}"
container_name="${GAHYEON_HEADLESS_CONTAINER_NAME:-gahyeon-headless-smoke-local}"
startup_timeout="${GAHYEON_HEADLESS_CONTAINER_STARTUP_TIMEOUT:-600}"
smoke_dir="$(mktemp -d "${TMPDIR:-/tmp}/gahyeon-headless-container-smoke.XXXXXX")"
client_token="$(python3 - <<'PY'
import secrets
print(secrets.token_urlsafe(32))
PY
)"
auth_header="Authorization: Bearer $client_token"

if [[ -n "${GAHYEON_HEADLESS_CONTAINER_PORT:-}" ]]; then
  port="$GAHYEON_HEADLESS_CONTAINER_PORT"
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
  echo "invalid Headless container smoke port: $port" >&2
  exit 2
}
[[ "$container_name" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]+$ ]] || {
  echo "invalid Headless container name: $container_name" >&2
  exit 2
}
[[ "$startup_timeout" =~ ^[0-9]+$ ]] && (( startup_timeout >= 30 && startup_timeout <= 1200 )) || {
  echo "invalid Headless container startup timeout: $startup_timeout" >&2
  exit 2
}

created=false
cleanup() {
  if [[ "$created" == true ]]; then
    docker rm -f "$container_name" >/dev/null 2>&1 || true
  fi
  rm -rf -- "$smoke_dir"
}
trap cleanup EXIT INT TERM

if docker container inspect "$container_name" >/dev/null 2>&1; then
  echo "refusing to replace existing container: $container_name" >&2
  exit 2
fi

cd "$repo_root"
if [[ "${GAHYEON_HEADLESS_CONTAINER_SKIP_BUILD:-false}" != true ]]; then
  ./gradlew --no-daemon bootJar --console=plain
  docker build --pull=false --tag "$image" .
fi

docker run --detach \
  --name "$container_name" \
  --publish "127.0.0.1:$port:8080" \
  --env SPRING_PROFILES_ACTIVE=dev \
  --env SERVER_PORT=8080 \
  --env BOT_ENABLED=false \
  --env WEATHER_PREFETCH_ENABLED=false \
  --env GAHYEON_HEADLESS_ENABLED=true \
  --env GAHYEON_CLIENT_TOKEN="$client_token" \
  --env GAHYEON_BEHAVIOR_ENABLED=true \
  --env GAHYEON_UNREAL_WEBSOCKET_ENABLED=false \
  --env TTS_ENABLED=false \
  --env MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always \
  --env SPRING_JPA_SHOW_SQL=false \
  --env LOGGING_LEVEL_COM_GAHYEONBOT=INFO \
  --env LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_WEB=INFO \
  --env LOGGING_LEVEL_ORG_HIBERNATE_SQL=OFF \
  "$image" >"$smoke_dir/container-id"
created=true

base="http://127.0.0.1:$port/api"
booted=false
deadline=$((SECONDS + startup_timeout))
while (( SECONDS < deadline )); do
  if curl -fsS --max-time 1 "$base/" \
      >"$smoke_dir/root.json" 2>/dev/null; then
    booted=true
    break
  fi
  if [[ "$(docker inspect --format '{{.State.Running}}' "$container_name")" != true ]]; then
    echo "Headless Core container exited before serving HTTP" >&2
    docker logs "$container_name" >&2
    exit 1
  fi
  sleep 1
done
if [[ "$booted" != true ]]; then
  echo "Headless Core container did not serve HTTP within $startup_timeout seconds" >&2
  docker logs "$container_name" >&2
  exit 1
fi

curl -sS --max-time 5 "$base/actuator/health" >"$smoke_dir/actuator.json"
curl -sS --max-time 5 -H "$auth_header" "$base/health" >"$smoke_dir/health.json"
curl -fsS --max-time 5 -H "$auth_header" "$base/gahyeon/desktop/speech/status" \
  >"$smoke_dir/speech.json"
curl -fsS --max-time 5 -H "$auth_header" \
  "$base/gahyeon/desktop/worlds/container-smoke-world" \
  >"$smoke_dir/world-before.json"

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
assert speech == {
    "transcriptionReady": False,
    "synthesisReady": False,
    "expressiveSynthesisReady": False,
}, speech
assert world["revision"] == 0 and world["activity"] == "IDLE", world
print(world["revision"])
PY
)"

curl -fsS --max-time 5 \
  -H 'Content-Type: application/json' \
  -H "$auth_header" \
  -d "{\"expectedRevision\":$revision,\"emotion\":\"curious\",\"intensity\":0.6}" \
  "$base/gahyeon/desktop/worlds/container-smoke-world/emotion" \
  >"$smoke_dir/world-after.json"

python3 - "$smoke_dir/world-after.json" <<'PY'
import json, pathlib, sys
world = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert world["revision"] == 1, world
assert world["emotion"] == "curious", world
assert world["emotionIntensity"] == 0.6, world
PY

echo "Headless container smoke passed: credential-free boot, expected conversation DOWN, World revision 0->1"
