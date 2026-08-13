#!/usr/bin/env bash
set -euo pipefail

root=/home/ubuntu/piper-voice
base_run="$root/ze-studio387-fp32-stage420-2026-08-08"
run_root="$root/ze-studio387-fp32-stages1200-2026-08-08"
config="$root/ze-studio387-fp32-2026-07-28/config-step300.yaml"
dataset="$root/studio-388-export-2026-07-28/metadata.accepted.csv"
reference="$root/studio-388-export-2026-07-28/wav/0000000001_0200000100_General/0000000001.wav"
text='안녕하세요. 오늘 서버 상태와 프로젝트 진행 상황을 정확하게 알려드리겠습니다.'

[[ -f "$base_run/step420.ckpt" ]]
[[ -f "$config" ]]
[[ "$(wc -l < "$dataset")" -eq 387 ]]

if pgrep -f '^/home/ubuntu/piper-voice/.venv/bin/python -m piper.train fit' >/dev/null; then
  echo "A Piper training process is already active" >&2
  exit 1
fi

mkdir -p "$run_root"
exec > >(tee -a "$run_root/orchestrator.log") 2>&1

previous="$base_run/step420.ckpt"
for target in 600 800 1000 1200; do
  stage="$run_root/step$target"
  mkdir -p "$stage"
  final_ckpt="$stage/step$target.ckpt"

  if [[ ! -f "$final_ckpt" ]]; then
    echo "[$(date --iso-8601=seconds)] training target=$target previous=$previous"
    cd "$root"
    env \
      PATH="/usr/lib/wsl/lib:$PATH" \
      PIPER_FINAL_CKPT="$final_ckpt" \
      "$root/.venv/bin/python" -m piper.train fit \
        --config "$config" \
        --trainer.max_steps="$target" \
        --trainer.default_root_dir="$stage/run" \
        --ckpt_path="$previous" \
        >"$stage/train.log" 2>&1
  fi

  [[ -s "$final_ckpt" ]]
  if grep -Eqi 'CUDA out of memory|I/O error|Input/output error|Xid|nan|inf loss' "$stage/train.log"; then
    echo "unsafe training signature detected at target=$target" >&2
    exit 2
  fi

  if [[ ! -f "$stage/model.onnx" ]]; then
    cd "$root"
    "$root/.venv/bin/python" -m piper.train.export_onnx \
      --checkpoint "$final_ckpt" \
      --output-file "$stage/model.onnx" \
      >"$stage/export.log" 2>&1
    cp "$root/ze-studio387-fp32-2026-07-28/step300/model.onnx.json" "$stage/model.onnx.json"
  fi

  "$root/.venv-cu126/bin/python" -c \
    'import onnx,sys; onnx.checker.check_model(onnx.load(sys.argv[1]))' \
    "$stage/model.onnx"
  printf '%s\n' "$text" | "$root/.venv-cu126/bin/piper" \
    --model "$stage/model.onnx" --output_file "$stage/eval.wav"
  "$root/.venv-cu126/bin/python" "$root/evaluate_tts_candidate.py" \
    --audio "$stage/eval.wav" --text "$text" --reference "$reference" \
    --output "$stage/evaluation.json"
  sha256sum "$final_ckpt" "$stage/model.onnx" "$stage/eval.wav" >"$stage/SHA256SUMS"

  previous="$final_ckpt"
done

echo "[$(date --iso-8601=seconds)] completed through step1200"
