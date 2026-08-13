#!/bin/zsh
set -euo pipefail

repo=/Users/ze/work/gahyeonbot
root="$repo/artifacts/voicebox-teacher-diverse1000-2026-08-09"
catalog="$repo/artifacts/voicebox-teacher-pure4000-2026-08-09/sentences-v4-diverse5000.jsonl"
ready="$root/piper_handoff_ready.json"
submitted="$root/piper_training_submitted.json"
status_file="$root/piper_training_status.json"
archive="$root/voicebox-teacher-piper-dataset.tar.gz"
remote_script=/home/ubuntu/piper-voice/land_run_piper_voicebox_diverse5000_stages4800.sh
remote_evaluator=/home/ubuntu/piper-voice/evaluate_tts_suite.py
remote_speaker_qc=/home/ubuntu/piper-voice/speaker_consistency_qc.py
lock="$root/.piper-deploy.lock"

[[ -f "$ready" ]]
if [[ -f "$submitted" ]]; then
  python3 "$repo/scripts/verify_voicebox_handoff_identity.py" \
    --ready "$ready" --catalog "$catalog" --manifest "$root/manifest.jsonl"
  python3 "$repo/scripts/verify_piper_training_identity.py" \
    --ready "$ready" --submitted "$submitted"
  exec /bin/zsh "$repo/scripts/monitor_piper_training_land.sh"
fi

[[ -f "$archive" ]]
python3 "$repo/scripts/verify_voicebox_handoff_identity.py" \
  --ready "$ready" --catalog "$catalog" --manifest "$root/manifest.jsonl" \
  --archive "$archive"
expected=$(python3 - "$ready" <<'PY'
import json, sys
print(json.load(open(sys.argv[1], encoding="utf-8"))["package"]["archive_sha256"])
PY
)
[[ "$expected" =~ '^[0-9a-f]{64}$' ]] || { echo "Invalid archive digest" >&2; exit 2; }
actual=$(shasum -a 256 "$archive" | awk '{print $1}')
[[ "$actual" == "$expected" ]] || { echo "Archive digest mismatch" >&2; exit 2; }

remote_root="/home/ubuntu/piper-voice/voicebox-diverse5000-${expected[1,12]}"
remote_archive="$remote_root/voicebox-teacher-piper-dataset.tar.gz"
if ! mkdir "$lock" 2>/dev/null; then
  if [[ -f "$lock/pid" ]] && kill -0 "$(<"$lock/pid")" 2>/dev/null; then
    exit 0
  fi
  rm -rf -- "$lock"
  mkdir "$lock"
fi
echo $$ >"$lock/pid"
trap 'rm -rf -- "$lock"' EXIT INT TERM

# Training is submitted by launchd without an operator present. Refuse the handoff
# before any upload when the media worker cannot safely absorb a multi-stage run.
ssh land 'bash -s' <<'REMOTE'
set -euo pipefail
mem_available_kb=$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)
(( mem_available_kb >= 8 * 1024 * 1024 )) || {
  echo "land preflight: less than 8 GiB RAM available" >&2
  exit 75
}
for mount in /opt/zaeze-ai /mnt/d; do
  read -r free_gb used_percent < <(df --output=avail,pcent -BG "$mount" | tail -1 | tr -d 'G%')
  (( free_gb >= 100 && used_percent <= 85 )) || {
    echo "land preflight: unsafe free space on $mount" >&2
    exit 75
  }
done
gpu_row=$(nvidia-smi --query-gpu=memory.free,temperature.gpu \
  --format=csv,noheader,nounits | head -1 | tr -d ',\r')
gpu_free_mb=${gpu_row%% *}
gpu_temp=${gpu_row##* }
(( gpu_free_mb >= 4096 && gpu_temp < 85 )) || {
  echo "land preflight: GPU memory or temperature is unsafe" >&2
  exit 75
}
if pgrep -f '^/home/ubuntu/piper-voice/.venv/bin/python -m piper.train fit' >/dev/null; then
  echo "land preflight: Piper training is already active" >&2
  exit 75
fi
if pgrep -af '[l]and_run_piper_voicebox_diverse5000_stages4800\.sh|[s]peaker_consistency_qc\.py' >/dev/null; then
  echo "land preflight: another Gahyeon Piper pipeline is already active" >&2
  exit 75
fi
if pgrep -af '[t]rain_network\.py|[r]un-hf-media-comfy-benchmark\.py|[h]uggingface-cli download' >/dev/null; then
  echo "land preflight: another model job is active" >&2
  exit 75
fi
required_files=(
  /home/ubuntu/piper-voice/.venv/bin/python
  /home/ubuntu/piper-voice/.venv-cu126/bin/python
  /home/ubuntu/piper-voice/.venv-cu126/bin/piper
  /home/ubuntu/piper-voice/real-v3-dataset-2026-07-28/reference9.wav
  /home/ubuntu/piper-voice/ze69-blend-fp32-320-2026-07-27/stages/step420/model.onnx
  /home/ubuntu/piper-voice/ze69-blend-fp32-320-2026-07-27/stages/step420/model.onnx.json
  /home/ubuntu/piper-voice/ze-studio387-fp32-2026-07-28/config-step300.yaml
  /home/ubuntu/piper-voice/ze-studio387-fp32-2026-07-28/step300/model.onnx.json
  /home/ubuntu/piper-voice/ze9-fp32-adapt1000-2026-07-27/final-step.ckpt
)
for required in "${required_files[@]}"; do
  [[ -s "$required" ]] || {
    echo "land preflight: required Piper asset is missing: $required" >&2
    exit 75
  }
done
/home/ubuntu/piper-voice/.venv/bin/python - <<'PY'
import onnx
import piper.train
import piper.train.export_onnx
PY
/home/ubuntu/piper-voice/.venv-cu126/bin/python - <<'PY'
import faster_whisper
import librosa
import numpy
import onnx
import torch
from resemblyzer import VoiceEncoder
if not torch.cuda.is_available():
    raise SystemExit("land preflight: CUDA is unavailable for speaker QC")
PY
whisper_cache=/home/ubuntu/.cache/huggingface/hub/models--Systran--faster-whisper-small
[[ -d "$whisper_cache" ]] || {
  echo "land preflight: cached faster-whisper small model is missing" >&2
  exit 75
}
find "$whisper_cache" -name model.bin -exec test -s {} \; -print -quit | grep -q . || {
  echo "land preflight: cached faster-whisper small weights are incomplete" >&2
  exit 75
}
REMOTE

ssh land "mkdir -p '$remote_root'"
rsync -a --partial --info=progress2 "$archive" "land:$remote_archive"
rsync -a "$repo/scripts/land_run_piper_voicebox_diverse5000_stages4800.sh" "land:$remote_script"
rsync -a "$repo/scripts/evaluate_tts_suite.py" "land:$remote_evaluator"
rsync -a "$repo/scripts/speaker_consistency_qc.py" "land:$remote_speaker_qc"

response=$(ssh land "bash -s" -- "$remote_root" "$remote_archive" "$expected" "$remote_script" <<'REMOTE'
set -euo pipefail
dataset_root=$1
archive=$2
expected=$3
runner=$4
echo "$expected  $archive" | sha256sum --check --status
if [[ ! -f "$dataset_root/.dataset-ready" ]]; then
  tar -xzf "$archive" -C "$dataset_root"
  [[ -s "$dataset_root/piper_dataset/metadata.csv" ]]
  echo "$expected" >"$dataset_root/.dataset-ready"
fi
[[ "$(tr -d '[:space:]' <"$dataset_root/.dataset-ready")" == "$expected" ]]
[[ -s "$dataset_root/piper_dataset/metadata.csv" ]]
if pgrep -f '^/home/ubuntu/piper-voice/.venv/bin/python -m piper.train fit' >/dev/null; then
  exit 75
fi
chmod +x "$runner"
nohup env GAHYEON_PIPER_DATASET_ROOT="$dataset_root" bash "$runner" \
  >"$dataset_root/training-launch.log" 2>&1 </dev/null &
pid=$!
sleep 2
kill -0 "$pid"
printf '{"pid":%s,"remoteRoot":"%s","sha256":"%s"}\n' "$pid" "$dataset_root" "$expected"
REMOTE
)

python3 - "$submitted" "$response" <<'PY'
import json, os, sys, tempfile
target, response = sys.argv[1:]
payload = json.loads(response.splitlines()[-1])
fd, temporary = tempfile.mkstemp(dir=os.path.dirname(target), text=True)
with os.fdopen(fd, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, ensure_ascii=False, indent=2)
    handle.flush()
    os.fsync(handle.fileno())
os.replace(temporary, target)
PY

python3 "$repo/scripts/record_piper_training_status.py" \
  --output "$status_file" --submitted "$submitted" --state submitted >/dev/null

# Close the submission handoff in the same supervisor run.  The monitor verifies
# that the remote runner survived launch and publishes the first training status;
# subsequent LaunchAgent intervals continue monitoring until completion.
exec /bin/zsh "$repo/scripts/monitor_piper_training_land.sh"
