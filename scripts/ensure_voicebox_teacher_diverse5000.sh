#!/bin/zsh
set -euo pipefail

repo=${GAHYEON_REPO_ROOT:-/Users/ze/work/gahyeonbot}
output="$repo/artifacts/voicebox-teacher-diverse1000-2026-08-09"
catalog="$repo/artifacts/voicebox-teacher-pure4000-2026-08-09/sentences-v4-diverse5000.jsonl"
submitted="$output/piper_training_submitted.json"
failure="$output/pipeline_supervisor_failure.json"

run_stage() {
  local stage=$1
  local script=$2
  set +e
  /bin/zsh "$script"
  local exit_code=$?
  set -e
  if (( exit_code == 0 )); then
    rm -f -- "$failure"
    return 0
  fi
  /usr/bin/python3 - "$failure" "$stage" "$exit_code" <<'PY'
import datetime, json, os, sys, tempfile
target, stage, status = sys.argv[1:]
payload = {
    "status": "failed",
    "stage": stage,
    "exitCode": int(status),
    "observedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
}
fd, temporary = tempfile.mkstemp(dir=os.path.dirname(target), text=True)
with os.fdopen(fd, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, ensure_ascii=False, indent=2)
    handle.flush()
    os.fsync(handle.fileno())
os.replace(temporary, target)
PY
  return "$exit_code"
}

# Once the immutable dataset has been submitted, monitoring must not depend on
# retaining 5,000 local WAV files or the upload archive.
if [[ -f "$submitted" ]]; then
  run_stage monitor "$repo/scripts/deploy_voicebox_teacher_piper_to_land.sh"
  exit $?
fi

completed_count() {
  /usr/bin/python3 "$repo/scripts/check_voicebox_teacher_progress.py" \
    --catalog "$catalog" \
    --output "$output" \
    --target 5000 \
    --field completed
}

completed=$(completed_count)

generator_active=false
if pgrep -f '^.*/[Pp]ython.*build_voicebox_teacher_from_catalog.py.*voicebox-teacher-diverse1000-2026-08-09' >/dev/null; then
  generator_active=true
fi

action=$(/usr/bin/python3 "$repo/scripts/voicebox_supervisor_decision.py" \
  --completed "$completed" --target 5000 --generator-active "$generator_active")
case "$action" in
  finalize)
    run_stage finalize "$repo/scripts/finalize_voicebox_teacher_piper.sh"
    exit $?
    ;;
  wait) exit 0 ;;
  generate)
    run_stage generate "$repo/scripts/run_voicebox_teacher_diverse1000.sh" || exit $?
    # Do not depend on a later launchd interval for the critical generation→QC
    # handoff. Re-read the durable manifest after the generator has exited and
    # finalize immediately only when the exact target is proven.
    completed=$(completed_count)
    if [[ "$completed" == 5000 ]]; then
      run_stage finalize "$repo/scripts/finalize_voicebox_teacher_piper.sh"
      exit $?
    fi
    exit 0
    ;;
  *) echo "unexpected Voicebox supervisor action: $action" >&2; exit 2 ;;
esac
