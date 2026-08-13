#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
project="$repo_root/unreal/GahyeonStage/GahyeonStage.uproject"
mode="${1:-all}"

case "$mode" in
  all|--check-only|--build-only) ;;
  *) echo "usage: $0 [--check-only|--build-only]" >&2; exit 2 ;;
esac

if [[ -n "${GAHYEON_HERO_MANIFEST:-}" ]]; then
  python3 "$repo_root/scripts/verify_gahyeon_hero_asset.py" \
    "$GAHYEON_HERO_MANIFEST" --require-approved --renderer hero-engine --verify-files
  python3 "$repo_root/scripts/install_gahyeon_unreal_content.py" \
    "$GAHYEON_HERO_MANIFEST" --project "$repo_root/unreal/GahyeonStage" --check-only
fi

find_engine_root() {
  if [[ -n "${GAHYEON_UE_ROOT:-}" ]]; then
    printf '%s\n' "$GAHYEON_UE_ROOT"
    return
  fi
  local candidate
  for candidate in \
    "/Users/Shared/Epic Games/UE_5.6" \
    "/opt/UnrealEngine/UE_5.6" \
    "/opt/UnrealEngine"; do
    if [[ -f "$candidate/Engine/Build/Build.version" ]]; then
      printf '%s\n' "$candidate"
      return
    fi
  done
  return 1
}

if ! engine_root="$(find_engine_root)"; then
  echo "Unreal Engine 5.6 was not found. Set GAHYEON_UE_ROOT to the installed engine root." >&2
  exit 3
fi

version_file="$engine_root/Engine/Build/Build.version"
if [[ ! -f "$version_file" ]]; then
  echo "missing Unreal Build.version: $version_file" >&2
  exit 4
fi
engine_version="$(python3 - "$version_file" <<'PY'
import json, sys
value = json.load(open(sys.argv[1], encoding="utf-8"))
print(f"{value.get('MajorVersion')}.{value.get('MinorVersion')}")
PY
)"
if [[ "$engine_version" != "5.6" ]]; then
  echo "GahyeonStage requires Unreal Engine 5.6; found $engine_version at $engine_root" >&2
  exit 5
fi

case "$(uname -s)" in
  Darwin)
    platform="Mac"
    build_script="$engine_root/Engine/Build/BatchFiles/Mac/Build.sh"
    editor="$engine_root/Engine/Binaries/Mac/UnrealEditor.app/Contents/MacOS/UnrealEditor"
    [[ -x "$editor" ]] || editor="$engine_root/Engine/Binaries/Mac/UnrealEditor"
    ;;
  Linux)
    platform="Linux"
    build_script="$engine_root/Engine/Build/BatchFiles/Linux/Build.sh"
    editor="$engine_root/Engine/Binaries/Linux/UnrealEditor"
    ;;
  *) echo "unsupported host for the Unreal gate: $(uname -s)" >&2; exit 6 ;;
esac

[[ -x "$build_script" ]] || { echo "missing executable Build.sh: $build_script" >&2; exit 7; }
[[ -x "$editor" ]] || { echo "missing executable UnrealEditor: $editor" >&2; exit 8; }
[[ -f "$project" ]] || { echo "missing GahyeonStage project: $project" >&2; exit 9; }

echo "Unreal gate environment OK: UE $engine_version ($platform) at $engine_root"
if [[ "$mode" == "--check-only" ]]; then
  exit 0
fi

evidence_root="${GAHYEON_UNREAL_EVIDENCE_ROOT:-$repo_root/artifacts/unreal-engine-gate}"
mkdir -p "$evidence_root"

"$build_script" GahyeonStageEditor "$platform" Development \
  -Project="$project" -WaitMutex -NoHotReloadFromIDE \
  2>&1 | tee "$evidence_root/build.log"

if [[ "$mode" == "--build-only" ]]; then
  exit 0
fi

"$editor" "$project" \
  -unattended -nop4 -nosplash -NullRHI -stdout -FullStdOutLogOutput \
  -TestExit="Automation Test Queue Empty" \
  -ExecCmds="Automation RunTests Gahyeon" \
  2>&1 | tee "$evidence_root/automation.log"

if grep -Eq "(Automation Test Failed|Result=Failed|Result=\{Fail)" \
    "$evidence_root/automation.log"; then
  echo "At least one Gahyeon Automation test failed." >&2
  exit 10
fi
if ! grep -Eq "(Automation Test Succeeded|Result=Passed|Result=\{Success\}.*Gahyeon)" \
    "$evidence_root/automation.log"; then
  echo "Gahyeon Automation did not emit a recognizable success marker." >&2
  exit 11
fi
if ! grep -Eq 'Result=\{Success\}.*Gahyeon\.Runtime\.MockCognitionDelayFailureAndReordering|Gahyeon\.Runtime\.MockCognitionDelayFailureAndReordering.*(Automation Test Succeeded|Result=Passed)' \
    "$evidence_root/automation.log"; then
  echo "VS-5 Mock Cognition Automation test did not emit a success result." >&2
  exit 12
fi
if ! grep -Eq 'Result=\{Success\}.*Gahyeon\.Presentation\.FacialCurveBindingsAreDataDrivenAndBounded|Gahyeon\.Presentation\.FacialCurveBindingsAreDataDrivenAndBounded.*(Automation Test Succeeded|Result=Passed)' \
    "$evidence_root/automation.log"; then
  echo "VS-8 facial/viseme Automation test did not emit a success result." >&2
  exit 13
fi

python3 - "$evidence_root" "$engine_version" "$platform" "$project" <<'PY'
import datetime
import hashlib
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1]).resolve()
project = pathlib.Path(sys.argv[4]).resolve()

def digest(path: pathlib.Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()

manifest = {
    "schemaVersion": 2,
    "status": "passed",
    "completedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "engineVersion": sys.argv[2],
    "platform": sys.argv[3],
    "configuration": "Development",
    "project": str(project),
    "projectSha256": digest(project),
    "packagedBuild": False,
    "requiredAutomationTests": [
        "Gahyeon.Runtime.MockCognitionDelayFailureAndReordering",
        "Gahyeon.Presentation.FacialCurveBindingsAreDataDrivenAndBounded",
    ],
    "evidence": {
        "buildLog": {"path": "build.log", "sha256": digest(root / "build.log")},
        "automationLog": {
            "path": "automation.log",
            "sha256": digest(root / "automation.log"),
        },
    },
}
temporary = root / "manifest.json.tmp"
temporary.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
temporary.replace(root / "manifest.json")
PY
python3 "$repo_root/scripts/verify_unreal_engine_evidence.py" "$evidence_root"

echo "Unreal Engine gate passed; evidence: $evidence_root"
