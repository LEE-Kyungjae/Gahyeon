#!/usr/bin/env bash
set -Eeuo pipefail

root=/home/ubuntu/piper-voice
exec 9>"$root/.gahyeon-piper-training.lock"
if ! flock -n 9; then
  echo "Another Gahyeon Piper pipeline owns the worker lock" >&2
  exit 75
fi
dataset_root=${GAHYEON_PIPER_DATASET_ROOT:?GAHYEON_PIPER_DATASET_ROOT is required}
dataset_marker="$dataset_root/.dataset-ready"
[[ -s "$dataset_marker" ]]
dataset_sha256=$(tr -d '[:space:]' <"$dataset_marker")
[[ "$dataset_sha256" =~ ^[0-9a-f]{64}$ ]] || {
  echo "Invalid dataset identity marker" >&2
  exit 2
}
source_dataset="$dataset_root/piper_dataset/metadata.csv"
audio_dir="$dataset_root/piper_dataset/wav"
run_root="$dataset_root/training-stages4800"
speaker_qc_root="$run_root/speaker-consistency"
dataset="$speaker_qc_root/metadata-speaker-accepted.csv"
config="$root/ze-studio387-fp32-2026-07-28/config-step300.yaml"
base_ckpt="$root/ze9-fp32-adapt1000-2026-07-27/final-step.ckpt"
reference="$root/real-v3-dataset-2026-07-28/reference9.wav"
evaluator="$root/evaluate_tts_suite.py"
speaker_qc="$root/speaker_consistency_qc.py"
baseline_model="$root/ze69-blend-fp32-320-2026-07-27/stages/step420/model.onnx"
baseline_config="$root/ze69-blend-fp32-320-2026-07-27/stages/step420/model.onnx.json"

mkdir -p "$run_root"
if [[ -f "$run_root/FAILED.json" ]]; then
  mv "$run_root/FAILED.json" \
    "$run_root/FAILED.previous.$(date -u +%Y%m%dT%H%M%SZ).json"
fi
current_stage=preflight
record_failure() {
  local exit_code=$1 line=$2 command=$3
  trap - ERR
  "$root/.venv-cu126/bin/python" - \
    "$run_root/FAILED.json" "$dataset_sha256" "$current_stage" \
    "$exit_code" "$line" "$command" <<'PY'
import datetime, json, os, sys, tempfile
target, dataset_sha256, stage, exit_code, line, command = sys.argv[1:]
payload = {
    "status": "failed",
    "datasetSha256": dataset_sha256,
    "stage": stage,
    "exitCode": int(exit_code),
    "line": int(line),
    "command": command[:1000],
    "failedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
}
fd, temporary = tempfile.mkstemp(dir=os.path.dirname(target), text=True)
with os.fdopen(fd, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, ensure_ascii=False, indent=2)
    handle.flush(); os.fsync(handle.fileno())
os.replace(temporary, target)
PY
  exit "$exit_code"
}
trap 'record_failure "$?" "$LINENO" "$BASH_COMMAND"' ERR

[[ -f "$config" ]]
[[ -f "$base_ckpt" ]]
[[ -f "$evaluator" ]]
[[ -f "$speaker_qc" ]]
[[ -f "$baseline_model" && -f "$baseline_config" ]]
[[ -f "$reference" ]]
source_clip_count=$(wc -l <"$source_dataset")
if (( source_clip_count < 4000 || source_clip_count > 5000 )); then
  echo "Unexpected source clip count: $source_clip_count" >&2
  record_failure 2 "$LINENO" "unexpected source clip count: $source_clip_count"
fi

if pgrep -f '^/home/ubuntu/piper-voice/.venv/bin/python -m piper.train fit' >/dev/null; then
  echo "A Piper training process is already active" >&2
  record_failure 75 "$LINENO" "another Piper training process is active"
fi

exec > >(tee -a "$run_root/orchestrator.log") 2>&1
current_stage=speaker_consistency_qc
echo "[$(date --iso-8601=seconds)] speaker-qc source-clips=$source_clip_count"
"$root/.venv-cu126/bin/python" "$speaker_qc" \
  --metadata "$source_dataset" --audio-dir "$audio_dir" --reference "$reference" \
  --output-root "$speaker_qc_root" --min-similarity 0.85 --min-retained 4000 \
  --device cuda
clip_count=$(wc -l <"$dataset")
echo "[$(date --iso-8601=seconds)] dataset=$dataset_root source-clips=$source_clip_count clips=$clip_count"

eval_texts=(
  '안녕하세요. 오늘 서버 상태와 프로젝트 진행 상황을 정확하게 알려드리겠습니다.'
  'GPU 사용률은 72퍼센트이고, API 응답 시간은 183밀리초입니다.'
  '정말 잘됐네요! 생각보다 훨씬 자연스럽게 완성됐어요.'
  '지금 바로 실행할까요, 아니면 설정을 한 번 더 확인할까요?'
  '오류가 다시 발생하더라도 당황하지 말고 로그를 확인한 다음 안전하게 복구하면 됩니다.'
)

evaluate_model() {
  local stage=$1 model=$2 model_config=$3
  local eval_suite="$stage/evaluation-suite-input.jsonl"
  mkdir -p "$stage"
  : >"$eval_suite"
  for index in "${!eval_texts[@]}"; do
    local number=$((index + 1))
    local eval_wav="$stage/eval-$number.wav"
    printf '%s\n' "${eval_texts[$index]}" | "$root/.venv-cu126/bin/piper" \
      --model "$model" --config "$model_config" --output_file "$eval_wav"
    "$root/.venv-cu126/bin/python" - "$number" "$eval_wav" "${eval_texts[$index]}" >>"$eval_suite" <<'PY'
import json, sys
print(json.dumps({"id": int(sys.argv[1]), "audio": sys.argv[2], "text": sys.argv[3]}, ensure_ascii=False))
PY
  done
  "$root/.venv-cu126/bin/python" "$evaluator" \
    --suite "$eval_suite" --reference "$reference" --output "$stage/evaluation-suite.json"
}

baseline_stage="$run_root/baseline"
current_stage=baseline_evaluation
baseline_ready=true
for required in evaluation-suite.json eval-1.wav eval-2.wav eval-3.wav eval-4.wav eval-5.wav; do
  [[ -s "$baseline_stage/$required" ]] || baseline_ready=false
done
if [[ "$baseline_ready" != true ]]; then
  evaluate_model "$baseline_stage" "$baseline_model" "$baseline_config"
fi
cp "$baseline_model" "$baseline_stage/model.onnx"
cp "$baseline_config" "$baseline_stage/model.onnx.json"
(
  cd "$baseline_stage"
  sha256sum model.onnx model.onnx.json evaluation-suite.json eval-*.wav >SHA256SUMS
)

previous=""
for target in 600 1200 2400 3600 4800; do
  current_stage="training_step_$target"
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
    --data.voice_name=gahyeon_voicebox_diverse5000
  )
  if [[ -n "$previous" ]]; then
    train_args+=(--ckpt_path="$previous")
  else
    train_args+=(--model.warmstart_ckpt="$base_ckpt")
  fi

  if [[ ! -s "$final_ckpt" ]]; then
    echo "[$(date --iso-8601=seconds)] training target=$target previous=${previous:-$base_ckpt}"
    cd "$root"
    env PATH="/usr/lib/wsl/lib:$PATH" PIPER_FINAL_CKPT="$final_ckpt" \
      "$root/.venv/bin/python" -m piper.train fit "${train_args[@]}" \
      >"$stage/train.log" 2>&1
  fi

  [[ -s "$final_ckpt" ]]
  if grep -Eqi 'CUDA out of memory|I/O error|Input/output error|Xid|nan|inf loss' "$stage/train.log"; then
    echo "Unsafe training signature detected at target=$target" >&2
    record_failure 3 "$LINENO" "unsafe training signature at target=$target"
  fi

  cd "$root"
  current_stage="export_step_$target"
  "$root/.venv/bin/python" -m piper.train.export_onnx \
    --checkpoint "$final_ckpt" --output-file "$stage/model.onnx" \
    >"$stage/export.log" 2>&1
  cp "$root/ze-studio387-fp32-2026-07-28/step300/model.onnx.json" "$stage/model.onnx.json"
  "$root/.venv-cu126/bin/python" -c \
    'import onnx,sys; onnx.checker.check_model(onnx.load(sys.argv[1]))' "$stage/model.onnx"
  current_stage="evaluation_step_$target"
  evaluate_model "$stage" "$stage/model.onnx" "$stage/model.onnx.json"
  (
    cd "$stage"
    sha256sum model.onnx model.onnx.json evaluation-suite.json eval-*.wav >SHA256SUMS
  )
  previous="$final_ckpt"
done

current_stage=completion_manifest
"$root/.venv-cu126/bin/python" - "$run_root/COMPLETED.json" "$source_clip_count" "$clip_count" "$speaker_qc_root/speaker-consistency-summary.json" "$dataset_sha256" <<'PY'
import datetime, json, os, sys, tempfile
target, source_clips, clips, speaker_summary, dataset_sha256 = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4], sys.argv[5]
payload = {"status": "complete", "sourceClips": source_clips, "clips": clips,
           "datasetSha256": dataset_sha256,
           "speakerConsistency": json.load(open(speaker_summary, encoding="utf-8")),
           "steps": [600, 1200, 2400, 3600, 4800],
           "baseline": "ze69-blend-fp32-step420",
           "completedAt": datetime.datetime.now(datetime.timezone.utc).isoformat()}
fd, temporary = tempfile.mkstemp(dir=os.path.dirname(target), text=True)
with os.fdopen(fd, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, ensure_ascii=False, indent=2)
    handle.flush(); os.fsync(handle.fileno())
os.replace(temporary, target)
PY
trap - ERR
echo "[$(date --iso-8601=seconds)] completed through step4800"
