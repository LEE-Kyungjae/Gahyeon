#!/bin/zsh
set -euo pipefail

repo=/Users/ze/work/gahyeonbot
root="$repo/artifacts/voicebox-teacher-diverse1000-2026-08-09"
catalog="$repo/artifacts/voicebox-teacher-pure4000-2026-08-09/sentences-v4-diverse5000.jsonl"
lock="$root/.piper-finalize.lock"
ready="$root/piper_handoff_ready.json"
submitted="$root/piper_training_submitted.json"

cd "$repo"

python3 scripts/check_voicebox_teacher_progress.py \
  --catalog "$catalog" --output "$root" --target 5000 \
  --probe-wav --require-complete

python3 scripts/check_voicebox_catalog_diversity.py \
  --catalog "$catalog" --output "$root/catalog_diversity_report.json"

if [[ -f "$ready" && -f "$submitted" ]]; then
  if python3 scripts/verify_voicebox_handoff_identity.py \
    --ready "$ready" --catalog "$catalog" --manifest "$root/manifest.jsonl"
  then
    exec /bin/zsh "$repo/scripts/deploy_voicebox_teacher_piper_to_land.sh"
  fi
fi

if [[ -f "$ready" ]]; then
  if python3 scripts/verify_voicebox_handoff_identity.py \
    --ready "$ready" --catalog "$catalog" --manifest "$root/manifest.jsonl" \
    --archive "$root/voicebox-teacher-piper-dataset.tar.gz"
  then
    exec /bin/zsh "$repo/scripts/deploy_voicebox_teacher_piper_to_land.sh"
  fi
fi

if ! mkdir "$lock" 2>/dev/null; then
  if [[ -f "$lock/pid" ]] && kill -0 "$(<"$lock/pid")" 2>/dev/null; then
    exit 0
  fi
  rm -rf -- "$lock"
  mkdir "$lock"
fi
echo $$ >"$lock/pid"
trap 'rm -rf -- "$lock"' EXIT INT TERM

python3 scripts/acoustic_qc_voicebox_teacher.py \
  --root "$root" --workers 4 --require-count 5000

# This starts only after generation has stopped, so STT does not compete with synthesis.
python3 scripts/stt_qc_voicebox_teacher.py \
  --root "$root" --base-url http://127.0.0.1:17493 \
  --require-count 5000 --retry-failures

python3 - "$root/stt_qc_summary.json" <<'PY'
import json, sys
summary = json.load(open(sys.argv[1], encoding="utf-8"))
if not summary.get("ready"):
    raise SystemExit(f"STT QC is incomplete: {summary}")
PY

report=$(python3 scripts/package_voicebox_teacher_dataset.py \
  --root "$root" --min-clips 4000 --require-audio-identity)
python3 - "$ready" "$root/stt_qc_summary.json" \
  "$root/catalog_diversity_report.json" "$catalog" "$root/manifest.jsonl" "$report" <<'PY'
import hashlib, json, os, pathlib, sys, tempfile
target, summary_path, diversity_path, catalog_path, manifest_path, package_json = sys.argv[1:]
payload = {
    "status": "ready",
    "sourceIdentity": {
        "catalogSha256": hashlib.sha256(pathlib.Path(catalog_path).read_bytes()).hexdigest(),
        "manifestSha256": hashlib.sha256(pathlib.Path(manifest_path).read_bytes()).hexdigest(),
        "completed": 5000,
    },
    "sttQc": json.load(open(summary_path, encoding="utf-8")),
    "catalogDiversity": json.load(open(diversity_path, encoding="utf-8")),
    "package": json.loads(package_json),
}
directory = os.path.dirname(target)
fd, temporary = tempfile.mkstemp(dir=directory, text=True)
with os.fdopen(fd, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, ensure_ascii=False, indent=2)
    handle.flush()
    os.fsync(handle.fileno())
os.replace(temporary, target)
PY

/bin/zsh "$repo/scripts/deploy_voicebox_teacher_piper_to_land.sh"
