#!/bin/zsh
set -euo pipefail

repo=/Users/ze/work/gahyeonbot
root="$repo/artifacts/voicebox-teacher-diverse1000-2026-08-09"
catalog="$repo/artifacts/voicebox-teacher-pure4000-2026-08-09/sentences-v4-diverse5000.jsonl"
ready="$root/piper_handoff_ready.json"
submitted="$root/piper_training_submitted.json"
complete="$root/piper_training_complete.json"
status_file="$root/piper_training_status.json"
results="$root/piper_training_results"
review="$root/listening-review"

[[ -f "$submitted" ]] || exit 0
python3 "$repo/scripts/verify_voicebox_handoff_identity.py" \
  --ready "$ready" --catalog "$catalog" --manifest "$root/manifest.jsonl" >/dev/null
python3 "$repo/scripts/verify_piper_training_identity.py" \
  --ready "$ready" --submitted "$submitted" >/dev/null
if [[ -f "$complete" ]]; then
  python3 "$repo/scripts/verify_piper_training_identity.py" \
    --ready "$ready" --submitted "$submitted" --local-complete "$complete"
  if [[ ! -f "$review/review-key.json" ]]; then
    python3 "$repo/scripts/build_piper_listening_review.py" \
      --completion "$complete" --output "$review"
  fi
  python3 "$repo/scripts/record_piper_training_status.py" \
    --output "$status_file" --submitted "$submitted" --state complete \
    --details-json "$(cat "$complete")" >/dev/null
  exit 0
fi

remote_root=$(python3 - "$submitted" <<'PY'
import json, sys
value = json.load(open(sys.argv[1], encoding="utf-8"))["remoteRoot"]
if not value.startswith("/home/ubuntu/piper-voice/voicebox-diverse5000-"):
    raise SystemExit("unexpected remote root")
print(value)
PY
)
remote_run="$remote_root/training-stages4800"
remote_failed="$remote_run/FAILED.json"

if ssh land "test -f '$remote_failed'"; then
  mkdir -p "$results"
  rsync -a "land:$remote_failed" "$results/FAILED.json"
  python3 "$repo/scripts/verify_piper_training_failure.py" \
    --failure "$results/FAILED.json" --submitted "$submitted" >&2
  python3 "$repo/scripts/record_piper_training_status.py" \
    --output "$status_file" --submitted "$submitted" --state failed \
    --details-json "$(cat "$results/FAILED.json")" >/dev/null
  rsync -a "land:$remote_run/orchestrator.log" "$results/orchestrator.log" 2>/dev/null || true
  exit 1
fi

if ! ssh land "test -f '$remote_run/COMPLETED.json'"; then
  if ssh land "pgrep -f 'piper.train fit.*$remote_root|[l]and_run_piper_voicebox_diverse5000_stages4800.sh' >/dev/null"; then
    training_status=$(ssh land "python3 - '$remote_run' <<'PY'
import json, pathlib, sys
root = pathlib.Path(sys.argv[1])
steps = (600, 1200, 2400, 3600, 4800)
def complete(step):
    stage = root / f'step{step}'
    return all((stage / name).is_file() for name in (
        'model.onnx', 'evaluation-suite.json', 'SHA256SUMS'))
done = [step for step in steps if complete(step)]
active = next((step for step in steps if step not in done), None)
if not (root / 'speaker-consistency' / 'speaker-consistency-summary.json').is_file():
    phase = 'speaker_consistency_qc'
    active = None
elif not (root / 'baseline' / 'evaluation-suite.json').is_file():
    phase = 'baseline_evaluation'
    active = None
elif active is None:
    phase = 'completion_manifest'
else:
    stage = root / f'step{active}'
    if not (stage / f'step{active}.ckpt').is_file():
        phase = f'training_step_{active}'
    elif not (stage / 'model.onnx').is_file():
        phase = f'export_step_{active}'
    else:
        phase = f'evaluation_step_{active}'
print(json.dumps({'state': 'training', 'completedSteps': done,
                  'activeStep': active, 'phase': phase}))
PY"
    )
    python3 "$repo/scripts/record_piper_training_status.py" \
      --output "$status_file" --submitted "$submitted" --state training \
      --details-json "$training_status" >/dev/null
    echo "$training_status"
    exit 0
  fi
  echo "Piper runner stopped before completion" >&2
  ssh land "tail -n 40 '$remote_run/orchestrator.log' 2>/dev/null || tail -n 40 '$remote_root/training-launch.log' 2>/dev/null || true" >&2
  exit 1
fi

mkdir -p "$results"
rsync -a --prune-empty-dirs \
  --include='COMPLETED.json' \
  --include='speaker-consistency/' \
  --include='speaker-consistency/metadata-speaker-accepted.csv' \
  --include='speaker-consistency/speaker-consistency.jsonl' \
  --include='speaker-consistency/speaker-consistency-summary.json' \
  --include='baseline/' \
  --include='baseline/evaluation-suite.json' \
  --include='baseline/eval-*.wav' \
  --include='baseline/model.onnx' \
  --include='baseline/model.onnx.json' \
  --include='baseline/SHA256SUMS' \
  --include='step*/' \
  --include='step*/evaluation-suite.json' \
  --include='step*/eval-*.wav' \
  --include='step*/model.onnx' \
  --include='step*/model.onnx.json' \
  --include='step*/SHA256SUMS' \
  --exclude='*' \
  "land:$remote_run/" "$results/"

python3 "$repo/scripts/verify_piper_training_identity.py" \
  --ready "$ready" --submitted "$submitted" \
  --remote-complete "$results/COMPLETED.json"

python3 - "$results/speaker-consistency/speaker-consistency-summary.json" <<'PY'
import json, sys
summary = json.load(open(sys.argv[1], encoding="utf-8"))
if not summary.get("ready") or int(summary.get("accepted", 0)) < 4000:
    raise SystemExit("speaker consistency gate is not ready")
if not isinstance(summary.get("referenceSha256"), str) or len(summary["referenceSha256"]) != 64:
    raise SystemExit("speaker consistency reference identity is missing")
PY

for stage_name in baseline; do
  stage="$results/$stage_name"
  [[ -s "$stage/evaluation-suite.json" ]]
  [[ -s "$stage/model.onnx" ]]
  [[ -s "$stage/model.onnx.json" ]]
  [[ -s "$stage/SHA256SUMS" ]]
  (cd "$stage" && shasum -a 256 --check SHA256SUMS)
done

for step in 600 1200 2400 3600 4800; do
  stage="$results/step$step"
  [[ -s "$stage/evaluation-suite.json" ]]
  [[ -s "$stage/model.onnx" ]]
  [[ -s "$stage/model.onnx.json" ]]
  [[ -s "$stage/SHA256SUMS" ]]
  (cd "$stage" && shasum -a 256 --check SHA256SUMS)
done

ranking=$(python3 "$repo/scripts/rank_piper_training_stages.py" --root "$results")
python3 - "$complete" "$results/COMPLETED.json" "$ready" "$ranking" <<'PY'
import json, os, sys, tempfile
target, remote_complete, ready_path, ranking_json = sys.argv[1:]
payload = {
    "status": "complete",
    "remote": json.load(open(remote_complete, encoding="utf-8")),
    "sourceIdentity": json.load(open(ready_path, encoding="utf-8"))["sourceIdentity"],
    "ranking": json.loads(ranking_json),
}
fd, temporary = tempfile.mkstemp(dir=os.path.dirname(target), text=True)
with os.fdopen(fd, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, ensure_ascii=False, indent=2)
    handle.flush(); os.fsync(handle.fileno())
os.replace(temporary, target)
PY

python3 "$repo/scripts/build_piper_listening_review.py" \
  --completion "$complete" --output "$review"
python3 "$repo/scripts/record_piper_training_status.py" \
  --output "$status_file" --submitted "$submitted" --state complete \
  --details-json "$(cat "$complete")" >/dev/null
