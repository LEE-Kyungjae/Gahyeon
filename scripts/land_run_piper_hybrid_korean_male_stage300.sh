#!/usr/bin/env bash
set -euo pipefail

root=/home/ubuntu/piper-voice
config="$root/ze-hfcmale387-fp32-2026-07-29/config-step300.yaml"
male_ckpt="$root/checkpoints/hfc_male/epoch=2785-step=2128064.ckpt"
korean_text_ckpt="$root/ze9-fp32-adapt1000-2026-07-27/final-step.ckpt"
dataset="$root/studio-388-export-2026-07-28/metadata.accepted.csv"
run_dir="$root/ze-hybrid-ko-text-hfcmale-real387-stage300-r2-2026-08-08"
log="$run_dir/train-step300.log"
final_ckpt="$run_dir/step300.ckpt"

for path in "$config" "$male_ckpt" "$korean_text_ckpt" "$dataset"; do
  [[ -f "$path" ]]
done
[[ "$(wc -l < "$dataset")" -eq 387 ]]

if pgrep -f '^/home/ubuntu/piper-voice/.venv/bin/python -m piper.train fit' >/dev/null; then
  echo "A Piper training process is already active" >&2
  exit 1
fi

mkdir -p "$run_dir"
cp --update=none "$config" "$run_dir/config-source.yaml"

cat >"$run_dir/manifest.json" <<EOF
{
  "status": "training",
  "clips": 387,
  "dataset": "$dataset",
  "male_acoustic_base": "$male_ckpt",
  "korean_text_encoder": "$korean_text_ckpt",
  "text_encoder_frozen": true,
  "precision": "32-true",
  "target_step": 300,
  "final_checkpoint": "$final_ckpt",
  "log": "$log"
}
EOF

cd "$root"
nohup env \
  PATH="/usr/lib/wsl/lib:$PATH" \
  PIPER_FINAL_CKPT="$final_ckpt" \
  "$root/.venv/bin/python" -m piper.train fit \
    --config "$config" \
    --trainer.max_steps=300 \
    --trainer.default_root_dir="$run_dir/run" \
    --model.warmstart_ckpt="$male_ckpt" \
    --model.text_warmstart_ckpt="$korean_text_ckpt" \
    --ckpt_path=null \
    >"$log" 2>&1 &

pid=$!
printf '%s\n' "$pid" >"$run_dir/train.pid"
echo "started pid=$pid target_step=300 clips=387 run_dir=$run_dir"
