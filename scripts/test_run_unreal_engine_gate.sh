#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/gahyeon-ue-gate.XXXXXX")"
trap 'rm -rf -- "$fixture_root"' EXIT

version_file="$fixture_root/Engine/Build/Build.version"
mkdir -p "$(dirname "$version_file")"

case "$(uname -s)" in
  Darwin)
    build_script="$fixture_root/Engine/Build/BatchFiles/Mac/Build.sh"
    editor="$fixture_root/Engine/Binaries/Mac/UnrealEditor.app/Contents/MacOS/UnrealEditor"
    ;;
  Linux)
    build_script="$fixture_root/Engine/Build/BatchFiles/Linux/Build.sh"
    editor="$fixture_root/Engine/Binaries/Linux/UnrealEditor"
    ;;
  *) echo "unsupported test host" >&2; exit 1 ;;
esac

mkdir -p "$(dirname "$build_script")" "$(dirname "$editor")"
printf '%s\n' '#!/usr/bin/env bash' 'echo "mock GahyeonStageEditor build succeeded"' > "$build_script"
printf '%s\n' '#!/usr/bin/env bash' \
  'echo "Result={Success} Name={Gahyeon.Runtime.MockCognitionDelayFailureAndReordering}"' \
  'echo "Result={Success} Name={Gahyeon.Presentation.FacialCurveBindingsAreDataDrivenAndBounded}"' \
  > "$editor"
chmod +x "$build_script" "$editor"

printf '%s\n' '{"MajorVersion":5,"MinorVersion":6}' > "$version_file"
GAHYEON_UE_ROOT="$fixture_root" \
  "$repo_root/scripts/run_unreal_engine_gate.sh" --check-only \
  | grep -q 'Unreal gate environment OK: UE 5.6'

printf '%s\n' '{}' >"$fixture_root/unapproved-hero.json"
set +e
GAHYEON_UE_ROOT="$fixture_root" \
GAHYEON_HERO_MANIFEST="$fixture_root/unapproved-hero.json" \
  "$repo_root/scripts/run_unreal_engine_gate.sh" --check-only \
  >"$fixture_root/hero-gate.out" 2>"$fixture_root/hero-gate.err"
hero_gate_rc=$?
set -e
if [[ "$hero_gate_rc" -eq 0 ]]; then
  echo "unapproved Hero manifest unexpectedly passed the Unreal gate" >&2
  exit 1
fi
grep -q 'Hero manifest validation failed:' "$fixture_root/hero-gate.err"

GAHYEON_UE_ROOT="$fixture_root" \
GAHYEON_UNREAL_EVIDENCE_ROOT="$fixture_root/evidence" \
  "$repo_root/scripts/run_unreal_engine_gate.sh" >/dev/null
grep -q 'mock GahyeonStageEditor build succeeded' "$fixture_root/evidence/build.log"
grep -q 'Result={Success} Name={Gahyeon.Runtime.MockCognitionDelayFailureAndReordering}' "$fixture_root/evidence/automation.log"
python3 - "$fixture_root/evidence" "$repo_root/unreal/GahyeonStage/GahyeonStage.uproject" <<'PY'
import hashlib
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
project = pathlib.Path(sys.argv[2]).resolve()
manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
assert manifest["schemaVersion"] == 2
assert manifest["status"] == "passed"
assert manifest["engineVersion"] == "5.6"
assert manifest["configuration"] == "Development"
assert manifest["packagedBuild"] is False
assert pathlib.Path(manifest["project"]) == project
assert manifest["projectSha256"] == hashlib.sha256(project.read_bytes()).hexdigest()
assert manifest["requiredAutomationTests"] == [
    "Gahyeon.Runtime.MockCognitionDelayFailureAndReordering",
    "Gahyeon.Presentation.FacialCurveBindingsAreDataDrivenAndBounded",
]
for key, filename in (("buildLog", "build.log"), ("automationLog", "automation.log")):
    item = manifest["evidence"][key]
    assert item["path"] == filename
    assert item["sha256"] == hashlib.sha256((root / filename).read_bytes()).hexdigest()
PY
python3 "$repo_root/scripts/verify_unreal_engine_evidence.py" "$fixture_root/evidence" \
  | grep -q 'Unreal Engine evidence verified:'
cp -R "$fixture_root/evidence" "$fixture_root/forged-evidence"
python3 - "$fixture_root/forged-evidence" <<'PY'
import hashlib
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
log = root / "automation.log"
lines = [line for line in log.read_text(encoding="utf-8").splitlines()
         if "FacialCurveBindingsAreDataDrivenAndBounded" not in line]
log.write_text("\n".join(lines) + "\n", encoding="utf-8")
manifest_path = root / "manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
manifest["evidence"]["automationLog"]["sha256"] = hashlib.sha256(log.read_bytes()).hexdigest()
manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
PY
set +e
python3 "$repo_root/scripts/verify_unreal_engine_evidence.py" "$fixture_root/forged-evidence" \
  >"$fixture_root/forged.out" 2>"$fixture_root/forged.err"
forged_rc=$?
set -e
if [[ "$forged_rc" -ne 1 ]]; then
  echo "manifest listing a test absent from its log returned $forged_rc instead of 1" >&2
  exit 1
fi
grep -q 'required Automation success is absent from log: Gahyeon.Presentation.FacialCurveBindingsAreDataDrivenAndBounded' \
  "$fixture_root/forged.err"
printf '%s\n' 'tampered after gate' >> "$fixture_root/evidence/automation.log"
set +e
python3 "$repo_root/scripts/verify_unreal_engine_evidence.py" "$fixture_root/evidence" \
  >"$fixture_root/tamper.out" 2>"$fixture_root/tamper.err"
tamper_rc=$?
set -e
if [[ "$tamper_rc" -ne 1 ]]; then
  echo "tampered Unreal evidence returned $tamper_rc instead of 1" >&2
  exit 1
fi
grep -q 'automationLog SHA-256 mismatch' "$fixture_root/tamper.err"

printf '%s\n' '#!/usr/bin/env bash' 'echo "Result={Success} Name={Gahyeon.Runtime.Unrelated}"' > "$editor"
chmod +x "$editor"
set +e
GAHYEON_UE_ROOT="$fixture_root" \
GAHYEON_UNREAL_EVIDENCE_ROOT="$fixture_root/missing-vs5-evidence" \
  "$repo_root/scripts/run_unreal_engine_gate.sh" \
  >"$fixture_root/missing-vs5.out" 2>"$fixture_root/missing-vs5.err"
missing_vs5_rc=$?
set -e
if [[ "$missing_vs5_rc" -ne 12 ]]; then
  echo "missing VS-5 Automation returned $missing_vs5_rc instead of 12" >&2
  exit 1
fi
grep -q 'VS-5 Mock Cognition Automation test did not emit a success result' \
  "$fixture_root/missing-vs5.err"
[[ ! -e "$fixture_root/missing-vs5-evidence/manifest.json" ]] || {
  echo "failed VS-5 gate must not emit a passed evidence manifest" >&2
  exit 1
}

printf '%s\n' '#!/usr/bin/env bash' \
  'echo "Result={Success} Name={Gahyeon.Runtime.MockCognitionDelayFailureAndReordering}"' \
  > "$editor"
chmod +x "$editor"
set +e
GAHYEON_UE_ROOT="$fixture_root" \
GAHYEON_UNREAL_EVIDENCE_ROOT="$fixture_root/missing-vs8-evidence" \
  "$repo_root/scripts/run_unreal_engine_gate.sh" \
  >"$fixture_root/missing-vs8.out" 2>"$fixture_root/missing-vs8.err"
missing_vs8_rc=$?
set -e
if [[ "$missing_vs8_rc" -ne 13 ]]; then
  echo "missing VS-8 Automation returned $missing_vs8_rc instead of 13" >&2
  exit 1
fi
grep -q 'VS-8 facial/viseme Automation test did not emit a success result' \
  "$fixture_root/missing-vs8.err"
[[ ! -e "$fixture_root/missing-vs8-evidence/manifest.json" ]] || {
  echo "failed VS-8 gate must not emit a passed evidence manifest" >&2
  exit 1
}

printf '%s\n' '#!/usr/bin/env bash' 'echo "Result={Fail} Name={Gahyeon.Runtime.MockCognitionDelayFailureAndReordering}"' > "$editor"
chmod +x "$editor"
set +e
GAHYEON_UE_ROOT="$fixture_root" \
GAHYEON_UNREAL_EVIDENCE_ROOT="$fixture_root/failure-evidence" \
  "$repo_root/scripts/run_unreal_engine_gate.sh" \
  >"$fixture_root/failure.out" 2>"$fixture_root/failure.err"
automation_failure_rc=$?
set -e
if [[ "$automation_failure_rc" -ne 10 ]]; then
  echo "failed Automation returned $automation_failure_rc instead of 10" >&2
  exit 1
fi
grep -q 'At least one Gahyeon Automation test failed' "$fixture_root/failure.err"
[[ ! -e "$fixture_root/failure-evidence/manifest.json" ]] || {
  echo "failed Automation must not emit a passed evidence manifest" >&2
  exit 1
}

printf '%s\n' '{"MajorVersion":5,"MinorVersion":5}' > "$version_file"
set +e
GAHYEON_UE_ROOT="$fixture_root" \
  "$repo_root/scripts/run_unreal_engine_gate.sh" --check-only \
  >"$fixture_root/wrong-version.out" 2>"$fixture_root/wrong-version.err"
wrong_version_rc=$?
set -e
if [[ "$wrong_version_rc" -ne 5 ]]; then
  echo "wrong UE version returned $wrong_version_rc instead of 5" >&2
  exit 1
fi
grep -q 'requires Unreal Engine 5.6; found 5.5' "$fixture_root/wrong-version.err"

echo "Unreal Engine gate contract tests passed"
