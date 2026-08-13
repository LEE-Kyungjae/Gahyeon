#!/usr/bin/env python3
"""Report generation, handoff and Piper state from validated local evidence."""

from __future__ import annotations

import argparse
import datetime
import json
import os
import plistlib
import statistics
import subprocess
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CHECKER = ROOT / "scripts/check_voicebox_teacher_progress.py"
SUPERVISOR_LABEL = "sh.gahyeonbot.voicebox-teacher-diverse4000"


def process_detected(pattern: str) -> bool:
    try:
        return subprocess.run(
            ["pgrep", "-f", pattern], stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, check=False).returncode == 0
    except FileNotFoundError:
        return False


def generator_mode() -> str | None:
    """Identify which resumable catalog target the live generator was started with."""
    try:
        result = subprocess.run(
            ["ps", "ax", "-o", "command="], capture_output=True, text=True, check=True)
    except (FileNotFoundError, subprocess.CalledProcessError):
        return None
    matching_process_seen = False
    legacy_process_seen = False
    for command in result.stdout.splitlines():
        if ("build_voicebox_teacher_from_catalog.py" not in command
                or "voicebox-teacher-diverse1000-2026-08-09" not in command):
            continue
        matching_process_seen = True
        if "sentences-v4-diverse5000.jsonl" in command and "--limit 5000" in command:
            return "target_5000"
        if "sentences-v3-diverse.jsonl" in command and "--limit 4000" in command:
            legacy_process_seen = True
    if legacy_process_seen:
        return "legacy_4000"
    return "unknown" if matching_process_seen else None


def supervisor_status() -> dict:
    """Report whether the loaded compatibility agent can continue through 5,000."""
    compatibility = ROOT / "scripts/ensure_voicebox_teacher_diverse4000.sh"
    target = ROOT / "scripts/ensure_voicebox_teacher_diverse5000.sh"
    runner = ROOT / "scripts/run_voicebox_teacher_diverse1000.sh"
    plist = Path.home() / "Library/LaunchAgents" / f"{SUPERVISOR_LABEL}.plist"
    compatibility_wired = (
        compatibility.is_file()
        and "ensure_voicebox_teacher_diverse5000.sh"
        in compatibility.read_text(encoding="utf-8")
    )
    target_text = target.read_text(encoding="utf-8") if target.is_file() else ""
    runner_text = runner.read_text(encoding="utf-8") if runner.is_file() else ""
    target_wired = all(token in target_text for token in (
        "sentences-v4-diverse5000.jsonl", "--target 5000",
        "finalize_voicebox_teacher_piper.sh",
    )) and all(token in runner_text for token in (
        "sentences-v4-diverse5000.jsonl", "--limit 5000",
    ))
    configured = False
    if plist.is_file():
        try:
            arguments = plistlib.loads(plist.read_bytes()).get("ProgramArguments", [])
            configured = str(compatibility) in arguments
        except (OSError, plistlib.InvalidFileException):
            configured = False
    try:
        registered = subprocess.run(
            ["launchctl", "print", f"gui/{os.getuid()}/{SUPERVISOR_LABEL}"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False,
        ).returncode == 0
    except FileNotFoundError:
        registered = False
    return {
        "label": SUPERVISOR_LABEL,
        "registered": registered,
        "launchAgentConfigured": configured,
        "compatibilityEntrypointTarget5000": compatibility_wired,
        "target5000Wired": target_wired,
        "ready": registered and configured and compatibility_wired and target_wired,
    }


def build_report(catalog: Path, output: Path, target: int, now: float | None = None) -> dict:
    checked = subprocess.run(
        ["python3", str(CHECKER), "--catalog", str(catalog),
         "--output", str(output), "--target", str(target)],
        capture_output=True, text=True, check=True)
    progress = json.loads(checked.stdout)
    records = [
        json.loads(line) for line in (output / "manifest.jsonl").read_text(encoding="utf-8").splitlines()
        if line.strip()
    ] if (output / "manifest.jsonl").exists() else []
    recent = [
        float(record["generation_seconds"])
        for record in records[-50:]
        if not record.get("reused")
        and isinstance(record.get("generation_seconds"), (int, float))
        and 0 < float(record["generation_seconds"]) <= 1800
    ]
    current_time = time.time() if now is None else now
    audio_paths = [Path(record["audio"]) for record in records if record.get("audio")]
    latest_mtime = max((path.stat().st_mtime for path in audio_paths if path.is_file()), default=None)
    age = None if latest_mtime is None else max(0, int(current_time - latest_mtime))
    median = statistics.median(recent) if recent else None
    eta = None if median is None else round(median * progress["remaining"])

    ready = output / "piper_handoff_ready.json"
    submitted = output / "piper_training_submitted.json"
    complete = output / "piper_training_complete.json"
    piper_status = output / "piper_training_status.json"
    pipeline_failure = output / "pipeline_supervisor_failure.json"
    if pipeline_failure.is_file():
        stage = "pipeline_failed"
    elif complete.is_file():
        stage = "piper_complete"
    elif piper_status.is_file():
        state = json.loads(piper_status.read_text(encoding="utf-8")).get("status")
        stage = f"piper_{state}"
    elif submitted.is_file():
        stage = "piper_submitted"
    elif ready.is_file():
        stage = "piper_handoff_ready"
    elif progress["ready"]:
        stage = "qc_pending"
    else:
        stage = "voicebox_generating"

    generator_detected = process_detected(
        r"build_voicebox_teacher_from_catalog.py.*voicebox-teacher-diverse1000-2026-08-09")
    mode = generator_mode() if generator_detected else None

    return {
        "schemaVersion": 1,
        "observedAt": datetime.datetime.fromtimestamp(
            current_time, datetime.timezone.utc).isoformat(),
        "stage": stage,
        "generation": {
            **progress,
            "percent": round(progress["completed"] * 100 / target, 2),
            "generatorProcessDetected": generator_detected,
            "generatorMode": mode,
            "legacy4000TransitionPending": mode == "legacy_4000" and target > 4000,
            "lastAudioAgeSeconds": age,
            "stale": age is not None and age > 2100,
            "recentSecondsPerClipMedian": None if median is None else round(median, 3),
            "estimateBasisClips": len(recent),
            "estimatedRemainingSeconds": eta,
            "estimatedRemainingHours": None if eta is None else round(eta / 3600, 2),
        },
        "supervisor": supervisor_status(),
        "pipelineFailure": json.loads(pipeline_failure.read_text(encoding="utf-8"))
        if pipeline_failure.is_file() else None,
        "piper": json.loads(piper_status.read_text(encoding="utf-8"))
        if piper_status.is_file() else None,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--target", type=int, default=5000)
    args = parser.parse_args()
    print(json.dumps(build_report(args.catalog, args.output, args.target), ensure_ascii=False))


if __name__ == "__main__":
    main()
