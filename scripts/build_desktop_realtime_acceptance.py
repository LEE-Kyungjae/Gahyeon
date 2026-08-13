#!/usr/bin/env python3
"""Aggregate a packaged single-view UE run into sealed realtime acceptance evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
from pathlib import Path

from verify_desktop_realtime_acceptance import verify


RUN_ID = re.compile(r"^[a-z0-9][a-z0-9_-]{7,63}$")


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    return round(ordered[max(0, math.ceil(len(ordered) * fraction) - 1)], 3)


def samples(value: object, name: str, minimum: int) -> list[float]:
    if not isinstance(value, list) or len(value) < minimum:
        raise ValueError(f"{name} requires at least {minimum} samples")
    result = []
    for item in value:
        if isinstance(item, bool) or not isinstance(item, (int, float)) \
                or not math.isfinite(item) or item < 0:
            raise ValueError(f"{name} contains an invalid sample")
        result.append(float(item))
    return result


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build(raw_path: Path, output_path: Path) -> dict:
    raw_path, output_path = raw_path.resolve(), output_path.resolve()
    if raw_path == output_path or raw_path.is_symlink() or not raw_path.is_file():
        raise ValueError("raw measurement must be a distinct regular file")
    raw = json.loads(raw_path.read_text(encoding="utf-8"))
    if raw.get("schemaVersion") != 1 or raw.get("renderer") != "desktop-single-view":
        raise ValueError("unsupported Desktop realtime measurement")
    if raw.get("latencyBoundary") != "physical-presentation-v1":
        raise ValueError("measurement does not use the physical presentation boundary")
    run_id = raw.get("measurementRunId")
    if not isinstance(run_id, str) or not RUN_ID.fullmatch(run_id):
        raise ValueError("measurement run ID is invalid")
    duration = raw.get("durationSeconds")
    if isinstance(duration, bool) or not isinstance(duration, (int, float)) \
            or not math.isfinite(duration) or duration < 600 or duration > 3605:
        raise ValueError("measurement duration must be 10 to 60 minutes")
    frames = samples(raw.get("frameMs"), "frameMs", math.ceil(duration * 10))
    reflex = raw.get("reflexUpdates")
    behavior = raw.get("behaviorUpdates")
    reflex_gap = raw.get("maxReflexGapMs")
    behavior_gap = raw.get("maxBehaviorGapMs")
    for value, name in ((reflex, "reflexUpdates"), (behavior, "behaviorUpdates")):
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise ValueError(f"{name} is invalid")
    for value, name in ((reflex_gap, "maxReflexGapMs"), (behavior_gap, "maxBehaviorGapMs")):
        if isinstance(value, bool) or not isinstance(value, (int, float)) \
                or not math.isfinite(value) or value < 0:
            raise ValueError(f"{name} is invalid")

    latency_names = (
        ("vadToListeningMs", 100.0),
        ("bargeInToAudioStopMs", 150.0),
        ("audioToVisemeMs", 80.0),
    )
    latency = {}
    latency_measured = True
    latency_passed = True
    for name, budget in latency_names:
        values = raw.get(name)
        if not isinstance(values, list) or len(values) < 20:
            latency_measured = False
            latency_passed = False
            latency[name.replace("Ms", "P95Ms")] = None
            continue
        checked = samples(values, name, 20)
        value = percentile(checked, 0.95)
        latency[name.replace("Ms", "P95Ms")] = value
        latency_passed = latency_passed and value <= budget

    p95 = percentile(frames, 0.95)
    p99 = percentile(frames, 0.99)
    dropped = round(sum(value > 33.34 for value in frames) / len(frames), 6)
    cadence_passed = (
        reflex >= math.floor(duration * 16)
        and behavior >= math.floor(duration * 4)
        and reflex_gap <= 250.0
        and behavior_gap <= 750.0)
    rendering_passed = p95 <= 33.34 and p99 <= 50.0 and dropped <= 0.02
    passed = cadence_passed and rendering_passed and latency_measured and latency_passed
    output_path.parent.mkdir(parents=True, exist_ok=True)
    try:
        evidence_uri = raw_path.relative_to(output_path.parent).as_posix()
    except ValueError as error:
        raise ValueError("raw measurement must be inside the acceptance workspace") from error
    result = {
        "schemaVersion": 1,
        "measurementRunId": run_id,
        "status": "passed" if passed else "measured",
        "renderer": "desktop-single-view",
        "latencyBoundary": "physical-presentation-v1",
        "durationSeconds": round(float(duration), 3),
        "frames": len(frames),
        "p95FrameMs": p95,
        "p99FrameMs": p99,
        "droppedFrameRate": dropped,
        "reflexUpdates": reflex,
        "behaviorUpdates": behavior,
        "maxReflexGapMs": round(float(reflex_gap), 3),
        "maxBehaviorGapMs": round(float(behavior_gap), 3),
        "cadencePassed": cadence_passed,
        "renderingPassed": rendering_passed,
        "interactionLatencyMeasured": latency_measured,
        "interactionLatencyPassed": latency_passed,
        **latency,
        "platform": {"os": raw.get("os", ""), "gpu": raw.get("gpu", "")},
        "evidence": {"uri": evidence_uri, "bytes": raw_path.stat().st_size, "sha256": sha(raw_path)},
    }
    temporary = output_path.with_suffix(output_path.suffix + ".tmp")
    temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, output_path)
    verify(output_path, require_passed=passed)
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = build(args.raw, args.output)
    print(json.dumps({"status": result["status"], "output": str(args.output.resolve())}))


if __name__ == "__main__":
    main()
