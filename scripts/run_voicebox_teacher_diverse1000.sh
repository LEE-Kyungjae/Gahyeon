#!/bin/zsh
set -euo pipefail

repo=/Users/ze/work/gahyeonbot
output="$repo/artifacts/voicebox-teacher-diverse1000-2026-08-09"
catalog="$repo/artifacts/voicebox-teacher-pure4000-2026-08-09/sentences-v4-diverse5000.jsonl"
lock="$output/.voicebox-generator.lock"

cd "$repo"
mkdir -p "$output"

# The supervisor's process check avoids normal overlap, while this lock is the
# final single-writer boundary for manual starts and launchd timing races.
if pgrep -f '^.*/[Pp]ython.*build_voicebox_teacher_from_catalog.py.*voicebox-teacher-diverse1000-2026-08-09' >/dev/null; then
  echo "Voicebox generator is already active" >&2
  exit 0
fi
if ! mkdir "$lock" 2>/dev/null; then
  if [[ -f "$lock/pid" ]] && kill -0 "$(<"$lock/pid")" 2>/dev/null; then
    echo "Voicebox generator is already active (pid $(<"$lock/pid"))" >&2
    exit 0
  fi
  rm -rf -- "$lock"
  mkdir "$lock"
fi
echo $$ >"$lock/pid"
trap 'rm -rf -- "$lock"' EXIT INT TERM

/usr/bin/python3 "$repo/scripts/check_voicebox_teacher_progress.py" \
  --catalog "$catalog" --output "$output" --target 5000 --probe-wav >/dev/null

/usr/bin/caffeinate -dimsu -- /usr/bin/python3 \
  "$repo/scripts/build_voicebox_teacher_from_catalog.py" \
  --catalog "$catalog" \
  --output "$output" \
  --reuse-manifest "$repo/artifacts/voicebox-teacher-pure1000-2026-08-08/manifest.jsonl" \
  --limit 5000 \
  >>"$output/generator.log" 2>&1
