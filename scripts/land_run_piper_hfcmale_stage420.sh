#!/usr/bin/env bash
set -euo pipefail

root=/home/ubuntu/piper-voice
source_run="$root/ze-hfcmale387-fp32-2026-07-29"
run_dir="$root/ze-hfcmale387-fp32-stage420-r2-2026-08-08"
source_config="$source_run/config-step300.yaml"
source_ckpt="$source_run/step300.ckpt"
dataset="$root/studio-388-export-2026-07-28/metadata.accepted.csv"
log="$run_dir/train-step420.log"
final_ckpt="$run_dir/step420.ckpt"

[[ -f "$source_config" ]]
[[ -f "$source_ckpt" ]]
[[ -f "$dataset" ]]
[[ "$(wc -l < "$dataset")" -eq 387 ]]

if pgrep -f '^/home/ubuntu/piper-voice/.venv/bin/python -m piper.train fit' >/dev/null; then
  echo "A Piper training process is already active" >&2
  exit 1
fi

mkdir -p "$run_dir"
cp --no-clobber "$source_config" "$run_dir/config-source-step300.yaml"

cat >"$run_dir/manifest.json" <<EOF
{
  "status": "training",
  "dataset": "$dataset",
  "clips": 387,
  "base_checkpoint": "$source_ckpt",
  "precision": "32-true",
  "start_step": 300,
  "target_step": 420,
  "final_checkpoint": "$final_ckpt",
  "log": "$log"
}
EOF

cd "$root"
nohup env \
  PATH="/usr/lib/wsl/lib:$PATH" \
  PIPER_FINAL_CKPT="$final_ckpt" \
  "$root/.venv/bin/python" -m piper.train fit \
    --config "$source_config" \
    --trainer.max_steps=420 \
    --trainer.default_root_dir="$run_dir/run" \
    --ckpt_path="$source_ckpt" \
    >"$log" 2>&1 &

pid=$!
printf '%s\n' "$pid" >"$run_dir/train.pid"
echo "started pid=$pid target_step=420 clips=387 run_dir=$run_dir"
