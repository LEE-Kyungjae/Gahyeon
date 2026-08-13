#!/usr/bin/env python3
"""Verify sealed single-view packaged Unreal realtime acceptance evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    return round(ordered[max(0, math.ceil(len(ordered) * fraction) - 1)], 3)


def checked_samples(value: object, name: str, minimum: int) -> list[float]:
    if not isinstance(value, list) or len(value) < minimum:
        raise ValueError(f"raw {name} requires at least {minimum} samples")
    result = []
    for item in value:
        if isinstance(item, bool) or not isinstance(item, (int, float)) \
                or not math.isfinite(item) or item < 0:
            raise ValueError(f"raw {name} contains an invalid sample")
        result.append(float(item))
    return result


def verify(path: Path, *, require_passed: bool = False) -> dict:
    path = path.resolve()
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("schemaVersion") != 1 \
            or value.get("renderer") != "desktop-single-view" \
            or value.get("latencyBoundary") != "physical-presentation-v1":
        raise ValueError("unsupported Desktop realtime acceptance")
    status = value.get("status")
    if status not in {"measured", "passed"}:
        raise ValueError("Desktop realtime acceptance status is invalid")
    numeric = (
        "durationSeconds", "p95FrameMs", "p99FrameMs", "droppedFrameRate",
        "maxReflexGapMs", "maxBehaviorGapMs")
    for name in numeric:
        item = value.get(name)
        if isinstance(item, bool) or not isinstance(item, (int, float)) \
                or not math.isfinite(item) or item < 0:
            raise ValueError(f"invalid aggregate: {name}")
    if value["durationSeconds"] < 600 or value.get("frames", 0) < math.ceil(value["durationSeconds"] * 10):
        raise ValueError("acceptance does not contain a complete ten-minute run")
    rendering = value["p95FrameMs"] <= 33.34 and value["p99FrameMs"] <= 50.0 \
        and value["droppedFrameRate"] <= 0.02
    cadence = value.get("reflexUpdates", 0) >= math.floor(value["durationSeconds"] * 16) \
        and value.get("behaviorUpdates", 0) >= math.floor(value["durationSeconds"] * 4) \
        and value["maxReflexGapMs"] <= 250.0 and value["maxBehaviorGapMs"] <= 750.0
    latency_fields = (
        ("vadToListeningP95Ms", 100.0),
        ("bargeInToAudioStopP95Ms", 150.0),
        ("audioToVisemeP95Ms", 80.0),
    )
    latency_measured = all(isinstance(value.get(name), (int, float))
                           and not isinstance(value.get(name), bool)
                           and math.isfinite(value[name]) and value[name] >= 0
                           for name, _ in latency_fields)
    latency_passed = latency_measured and all(value[name] <= budget for name, budget in latency_fields)
    expected_passed = rendering and cadence and latency_measured and latency_passed
    flags = (value.get("renderingPassed"), value.get("cadencePassed"),
             value.get("interactionLatencyMeasured"), value.get("interactionLatencyPassed"))
    if flags != (rendering, cadence, latency_measured, latency_passed):
        raise ValueError("acceptance pass flags do not match aggregates")
    if (status == "passed") != expected_passed:
        raise ValueError("acceptance status does not match required budgets")
    evidence = value.get("evidence")
    if not isinstance(evidence, dict):
        raise ValueError("raw evidence binding is missing")
    relative = evidence.get("uri")
    if not isinstance(relative, str) or not relative or Path(relative).is_absolute() \
            or ".." in Path(relative).parts:
        raise ValueError("raw evidence path is unsafe")
    raw = (path.parent / relative).resolve()
    if path.parent not in raw.parents or not raw.is_file() or raw.is_symlink():
        raise ValueError("raw evidence file is missing or unsafe")
    digest = hashlib.sha256(raw.read_bytes()).hexdigest()
    if raw.stat().st_size != evidence.get("bytes") or digest != evidence.get("sha256"):
        raise ValueError("raw evidence checksum mismatch")
    raw_value = json.loads(raw.read_text(encoding="utf-8"))
    if raw_value.get("schemaVersion") != 1 \
            or raw_value.get("renderer") != "desktop-single-view" \
            or raw_value.get("latencyBoundary") != "physical-presentation-v1" \
            or raw_value.get("measurementRunId") != value.get("measurementRunId"):
        raise ValueError("raw evidence identity does not match acceptance")
    raw_duration = raw_value.get("durationSeconds")
    if isinstance(raw_duration, bool) or not isinstance(raw_duration, (int, float)) \
            or not math.isfinite(raw_duration) or raw_duration < 600:
        raise ValueError("raw evidence duration is invalid")
    raw_frames = checked_samples(
        raw_value.get("frameMs"), "frameMs", math.ceil(raw_duration * 10))
    raw_p95 = percentile(raw_frames, 0.95)
    raw_p99 = percentile(raw_frames, 0.99)
    raw_dropped = round(sum(item > 33.34 for item in raw_frames) / len(raw_frames), 6)
    exact = {
        "durationSeconds": round(float(raw_duration), 3),
        "frames": len(raw_frames),
        "p95FrameMs": raw_p95,
        "p99FrameMs": raw_p99,
        "droppedFrameRate": raw_dropped,
        "reflexUpdates": raw_value.get("reflexUpdates"),
        "behaviorUpdates": raw_value.get("behaviorUpdates"),
        "maxReflexGapMs": round(float(raw_value.get("maxReflexGapMs")), 3),
        "maxBehaviorGapMs": round(float(raw_value.get("maxBehaviorGapMs")), 3),
    }
    for name, expected in exact.items():
        if value.get(name) != expected:
            raise ValueError(f"acceptance aggregate does not match raw evidence: {name}")
    for raw_name, aggregate_name in (
        ("vadToListeningMs", "vadToListeningP95Ms"),
        ("bargeInToAudioStopMs", "bargeInToAudioStopP95Ms"),
        ("audioToVisemeMs", "audioToVisemeP95Ms"),
    ):
        raw_samples = raw_value.get(raw_name)
        expected = None
        if isinstance(raw_samples, list) and len(raw_samples) >= 20:
            expected = percentile(checked_samples(raw_samples, raw_name, 20), 0.95)
        if value.get(aggregate_name) != expected:
            raise ValueError(
                f"acceptance aggregate does not match raw evidence: {aggregate_name}")
    expected_platform = {"os": raw_value.get("os", ""), "gpu": raw_value.get("gpu", "")}
    if value.get("platform") != expected_platform:
        raise ValueError("acceptance platform does not match raw evidence")
    if require_passed and status != "passed":
        raise ValueError("Desktop realtime physical acceptance is not passed")
    return {"valid": True, "status": status, "renderingPassed": rendering,
            "cadencePassed": cadence, "interactionLatencyPassed": latency_passed}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--require-passed", action="store_true")
    args = parser.parse_args()
    print(json.dumps(verify(args.manifest, require_passed=args.require_passed)))


if __name__ == "__main__":
    main()
