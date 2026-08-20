#!/usr/bin/env bash
set -euo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
release=${1:?usage: deploy_piper_release_to_land.sh RELEASE_DIRECTORY}
release=$(cd "$release" && pwd)
manifest="$release/release.json"
[[ -f "$manifest" && -f "$release/voice.onnx" && -f "$release/voice.onnx.json" ]]

metadata=()
while IFS= read -r line; do metadata+=("$line"); done < <(python3 - "$repo/scripts" "$release" <<'PY'
import sys
sys.path.insert(0, sys.argv[1])
from verify_piper_release import verify
payload = verify(__import__('pathlib').Path(sys.argv[2]))
print(payload["modelAlias"]); print(payload["modelSha256"]); print(payload["configSha256"])
PY
)
alias=${metadata[0]}
model_sha=${metadata[1]}
config_sha=${metadata[2]}
remote_root=/opt/zaeze-ai/gahyeon-piper
remote_release="$remote_root/releases/$alias"
incoming="$remote_root/incoming/$alias"

# Read-only safety gate before uploading or touching the service.
ssh land 'bash -s' <<'REMOTE'
set -euo pipefail
mem_available_kb=$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)
(( mem_available_kb >= 8 * 1024 * 1024 ))
read -r free_gb used_percent < <(df --output=avail,pcent -BG /opt/zaeze-ai | tail -1 | tr -d 'G%')
(( free_gb >= 100 && used_percent <= 85 ))
gpu_row=$(nvidia-smi --query-gpu=memory.free,temperature.gpu --format=csv,noheader,nounits \
  | head -1 | tr -d ',\r')
gpu_free_mb=${gpu_row%% *}; gpu_temp=${gpu_row##* }
(( gpu_free_mb >= 1024 && gpu_temp < 85 ))
if pgrep -af '[t]rain_network\.py|[p]iper\.train fit|[r]un-hf-media-comfy-benchmark\.py' >/dev/null; then
  echo "land preflight: model job is active" >&2
  exit 75
fi
REMOTE

ssh land "mkdir -p '$remote_root/incoming' '$remote_root/releases' '$remote_root/runtime'"
rsync -a --delete "$release/" "land:$incoming/"
rsync -a "$repo/infra/images/piper/server.py" "$repo/infra/images/piper/requirements.txt" \
  "land:$remote_root/runtime/"
rsync -a "$repo/scripts/verify_piper_runtime_smoke.py" \
  "land:$remote_root/runtime/verify_piper_runtime_smoke.py"
rsync -a "$repo/scripts/verify_piper_release.py" \
  "land:$remote_root/runtime/verify_piper_release.py"
rsync -a "$repo/infra/images/piper/gahyeon-piper.service" \
  "$repo/infra/images/piper/gahyeon-piper-tunnel.service" \
  "land:$remote_root/"

ssh land 'bash -s' -- "$incoming" "$remote_release" "$remote_root" "$alias" \
  "$model_sha" "$config_sha" <<'REMOTE'
set -euo pipefail
incoming=$1; release=$2; root=$3; alias=$4; model_sha=$5; config_sha=$6
python3 "$root/runtime/verify_piper_release.py" "$incoming" >/dev/null
echo "$model_sha  $incoming/voice.onnx" | sha256sum --check --status
echo "$config_sha  $incoming/voice.onnx.json" | sha256sum --check --status
if [[ -e "$release" ]]; then
  existing=$(sha256sum "$release/voice.onnx" | awk '{print $1}')
  [[ "$existing" == "$model_sha" ]] || { echo "release alias collision" >&2; exit 2; }
else
  mv "$incoming" "$release"
fi
python3 "$root/runtime/verify_piper_release.py" "$release" >/dev/null
[[ -x "$root/venv/bin/python" ]] || python3 -m venv "$root/venv"
"$root/venv/bin/pip" install --disable-pip-version-check --requirement "$root/runtime/requirements.txt"
previous=""
[[ -L "$root/current" ]] && previous=$(readlink "$root/current")
ln -sfn "$release" "$root/current.next"
mv -Tf "$root/current.next" "$root/current"
umask 077
printf '%s\n' \
  "PIPER_MODEL_PATH=$root/current/voice.onnx" \
  "PIPER_CONFIG_PATH=$root/current/voice.onnx.json" \
  "PIPER_MODEL_ALIAS=$alias" \
  "PIPER_MODEL_SHA256=$model_sha" \
  "PIPER_CONFIG_SHA256=$config_sha" \
  'PIPER_USE_CUDA=false' \
  'PIPER_MAX_CHARS=500' \
  'PIPER_ADMISSION_TIMEOUT_SECONDS=0.05' \
  >"$root/current.env.next"
mv "$root/current.env.next" "$root/current.env"
printf '%s\n' "$previous" >"$root/previous-release"
sudo install -m 0644 "$root/gahyeon-piper.service" /etc/systemd/system/gahyeon-piper.service
sudo install -m 0644 "$root/gahyeon-piper-tunnel.service" /etc/systemd/system/gahyeon-piper-tunnel.service
sudo systemctl daemon-reload
sudo systemctl enable --now gahyeon-piper.service
sudo systemctl restart gahyeon-piper.service
sudo systemctl enable --now gahyeon-piper-tunnel.service
sudo systemctl restart gahyeon-piper-tunnel.service
REMOTE

rollback() {
  ssh land 'bash -s' -- "$remote_root" <<'REMOTE'
set -euo pipefail
root=$1
previous=$(<"$root/previous-release")
if [[ -n "$previous" && "$previous" == "$root/releases/"* && -f "$previous/release.json" ]]; then
  python3 "$root/runtime/verify_piper_release.py" "$previous" >/dev/null || {
    sudo systemctl stop gahyeon-piper-tunnel.service
    sudo systemctl stop gahyeon-piper.service
    echo "previous Piper release failed immutable bundle verification" >&2
    exit 1
  }
  metadata=()
  while IFS= read -r line; do metadata+=("$line"); done < <(python3 - "$previous/release.json" <<'PY'
import json, sys
payload=json.load(open(sys.argv[1], encoding="utf-8"))
print(payload["modelAlias"]); print(payload["modelSha256"]); print(payload["configSha256"])
PY
  )
  ln -sfn "$previous" "$root/current.next"
  mv -Tf "$root/current.next" "$root/current"
  printf '%s\n' \
    "PIPER_MODEL_PATH=$root/current/voice.onnx" \
    "PIPER_CONFIG_PATH=$root/current/voice.onnx.json" \
    "PIPER_MODEL_ALIAS=${metadata[0]}" \
    "PIPER_MODEL_SHA256=${metadata[1]}" \
    "PIPER_CONFIG_SHA256=${metadata[2]}" \
    'PIPER_USE_CUDA=false' \
    'PIPER_MAX_CHARS=500' \
    'PIPER_ADMISSION_TIMEOUT_SECONDS=0.05' \
    >"$root/current.env.next"
  mv "$root/current.env.next" "$root/current.env"
  sudo systemctl restart gahyeon-piper.service
  if ! curl -fsS --retry 20 --retry-connrefused --retry-delay 1 --max-time 3 \
      http://127.0.0.1:18767/health >/dev/null; then
    sudo systemctl stop gahyeon-piper-tunnel.service
    sudo systemctl stop gahyeon-piper.service
    echo "previous Piper release also failed health check" >&2
    exit 1
  fi
else
  sudo systemctl stop gahyeon-piper-tunnel.service
  sudo systemctl stop gahyeon-piper.service
  [[ -L "$root/current" ]] && unlink "$root/current"
fi
REMOTE
}

if ! health=$(ssh land "curl -fsS --retry 20 --retry-connrefused --retry-delay 1 --max-time 3 http://127.0.0.1:18767/health"); then
  echo "Piper health check failed; service status follows" >&2
  ssh land 'systemctl status gahyeon-piper.service --no-pager || true' >&2
  rollback
  exit 1
fi
if ! python3 - "$health" "$alias" "$model_sha" "$config_sha" <<'PY'
import json, sys
payload=json.loads(sys.argv[1])
if (not payload.get("ready") or payload.get("model") != sys.argv[2]
        or payload.get("modelSha256") != sys.argv[3]
        or payload.get("configSha256") != sys.argv[4]):
    raise SystemExit(f"unexpected Piper health payload: {payload}")
print(json.dumps(payload, ensure_ascii=False))
PY
then
  rollback
  exit 1
fi
if ! smoke=$(ssh land "'$remote_root/venv/bin/python' '$remote_root/runtime/verify_piper_runtime_smoke.py' \
    --endpoint http://127.0.0.1:18767/synthesize --model '$alias' \
    --model-sha256 '$model_sha' --config-sha256 '$config_sha' \
    --max-rtf 1.0 --timeout 30"); then
  echo "Piper synthesis smoke failed; rolling back" >&2
  rollback
  exit 1
fi
echo "$smoke"
if ! backend_health=$(ssh land \
  'ssh -o BatchMode=yes -o ConnectTimeout=5 zeze "curl -fsS --max-time 3 http://127.0.0.1:18767/health"'); then
  echo "Piper reverse-tunnel health check failed" >&2
  rollback
  exit 1
fi
if ! python3 - "$backend_health" "$alias" "$model_sha" "$config_sha" <<'PY'
import json, sys
payload=json.loads(sys.argv[1])
if (not payload.get("ready") or payload.get("model") != sys.argv[2]
        or payload.get("modelSha256") != sys.argv[3]
        or payload.get("configSha256") != sys.argv[4]):
    raise SystemExit(f"unexpected tunneled Piper health payload: {payload}")
PY
then
  rollback
  exit 1
fi
if ! backend_smoke=$(ssh land \
  "ssh -o BatchMode=yes -o ConnectTimeout=5 zeze python3 - \
    --endpoint http://127.0.0.1:18767/synthesize --model '$alias' \
    --model-sha256 '$model_sha' --config-sha256 '$config_sha' \
    --max-rtf 1.0 --timeout 30" \
  <"$repo/scripts/verify_piper_runtime_smoke.py"); then
  echo "Piper reverse-tunnel synthesis smoke failed; rolling back" >&2
  rollback
  exit 1
fi
echo "$backend_smoke"
