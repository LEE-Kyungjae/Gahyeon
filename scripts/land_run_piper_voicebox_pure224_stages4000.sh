#!/usr/bin/env bash
set -euo pipefail

root=/home/ubuntu/piper-voice
dataset_root="$root/voicebox-pure232-2026-08-09"
dataset="$dataset_root/piper_dataset/metadata.csv"
audio_dir="$dataset_root/piper_dataset/wav"
run_root="$root/voicebox-pure224-piper-stages4000-2026-08-09"
config="$root/ze-studio387-fp32-2026-07-28/config-step300.yaml"
base_ckpt="$root/ze9-fp32-adapt1000-2026-07-27/final-step.ckpt"
reference="$audio_dir/teacher_001_p9.wav"
text='안녕하세요. 오늘 서버 상태와 프로젝트 진행 상황을 정확하게 알려드리겠습니다.'

[[ -f "$config" ]]
[[ -f "$base_ckpt" ]]
[[ -f "$reference" ]]
[[ "$(wc -l < "$dataset")" -eq 224 ]]

if pgrep -f '^/home/ubuntu/piper-voice/.venv/bin/python -m piper.train fit' >/dev/null; then
  echo "A Piper training process is already active" >&2
  exit 1
fi

mkdir -p "$run_root"
exec > >(tee -a "$run_root/orchestrator.log") 2>&1

previous=""
for target in 500 1000 2000 4000; do
  stage="$run_root/step$target"
  mkdir -p "$stage"
  final_ckpt="$stage/step$target.ckpt"
  train_args=(
    --config "$config"
    --trainer.max_steps="$target"
    --trainer.default_root_dir="$stage/run"
    --trainer.precision=32-true
    --model.learning_rate=2e-5
    --model.learning_rate_d=1e-5
    --data.csv_path="$dataset"
    --data.audio_dir="$audio_dir"
    --data.cache_dir="$run_root/cache"
    --data.voice_name=ze_voicebox_pure224
  )

  if [[ -n "$previous" ]]; then
    train_args+=(--ckpt_path="$previous")
  else
    train_args+=(--model.warmstart_ckpt="$base_ckpt")
  fi

  if [[ ! -f "$final_ckpt" ]]; then
    echo "[$(date --iso-8601=seconds)] training target=$target previous=${previous:-$base_ckpt}"
    cd "$root"
    env PATH="/usr/lib/wsl/lib:$PATH" PIPER_FINAL_CKPT="$final_ckpt" \
      "$root/.venv/bin/python" -m piper.train fit "${train_args[@]}" \
      >"$stage/train.log" 2>&1
  fi

  [[ -s "$final_ckpt" ]]
  if grep -Eqi 'CUDA out of memory|I/O error|Input/output error|Xid|nan|inf loss' "$stage/train.log"; then
    echo "unsafe training signature detected at target=$target" >&2
    exit 2
  fi

  cd "$root"
  "$root/.venv/bin/python" -m piper.train.export_onnx \
    --checkpoint "$final_ckpt" --output-file "$stage/model.onnx" \
    >"$stage/export.log" 2>&1
  cp "$root/ze-studio387-fp32-2026-07-28/step300/model.onnx.json" "$stage/model.onnx.json"
  "$root/.venv-cu126/bin/python" -c \
    'import onnx,sys; onnx.checker.check_model(onnx.load(sys.argv[1]))' "$stage/model.onnx"
  printf '%s\n' "$text" | "$root/.venv-cu126/bin/piper" \
    --model "$stage/model.onnx" --output_file "$stage/eval.wav"
  "$root/.venv-cu126/bin/python" "$root/evaluate_tts_candidate.py" \
    --audio "$stage/eval.wav" --text "$text" --reference "$reference" \
    --output "$stage/evaluation.json"
  sha256sum "$final_ckpt" "$stage/model.onnx" "$stage/eval.wav" >"$stage/SHA256SUMS"
  previous="$final_ckpt"
done

echo "[$(date --iso-8601=seconds)] completed through step4000"
