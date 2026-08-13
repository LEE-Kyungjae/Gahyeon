#!/bin/zsh
set -euo pipefail

repo=/Users/ze/work/gahyeonbot
output="$repo/artifacts/voicebox-teacher-pure1000-2026-08-08"

cd "$repo"
mkdir -p "$output"
exec /usr/bin/caffeinate -dimsu -- \
  /usr/bin/python3 "$repo/scripts/build_voicebox_teacher_corpus_v2.py" \
    --output "$output" \
    --limit 1000 \
    >>"$output/generator.log" 2>&1
