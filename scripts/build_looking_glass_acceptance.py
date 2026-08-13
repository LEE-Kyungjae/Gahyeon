#!/usr/bin/env python3
"""Aggregate raw UE/Go samples into a checksum-bound physical acceptance record."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path

from looking_glass_runtime_attestation import (
    rebase_runtime_attestation,
    verify_runtime_attestation,
)
from verify_looking_glass_acceptance import verify


MODES = {"Realtime", "RealtimeAdaptive", "NonRealtime"}
SCENARIOS = {"idle", "listening", "thinking", "speaking"}


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    index = max(0, math.ceil(len(ordered) * fraction) - 1)
    return round(ordered[index], 3)


def samples(value: object, name: str, minimum: int) -> list[float]:
    if not isinstance(value, list) or len(value) < minimum:
        raise ValueError(f"{name} requires at least {minimum} raw samples")
    result = []
    for item in value:
        if isinstance(item, bool) or not isinstance(item, (int, float)) or not math.isfinite(item) or item < 0:
            raise ValueError(f"{name} contains an invalid sample")
        result.append(float(item))
    return result


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build(raw_path: Path, output_path: Path) -> dict:
    raw_path = raw_path.resolve()
    output_path = output_path.resolve()
    if raw_path.is_symlink() or not raw_path.is_file() or raw_path == output_path:
        raise ValueError("raw measurement must be a distinct regular file")
    raw = json.loads(raw_path.read_text(encoding="utf-8"))
    if raw.get("schemaVersion") != 2:
        raise ValueError("unsupported Looking Glass raw measurement version")
    if raw.get("latencyBoundary") != "physical-presentation-v1":
        raise ValueError("raw measurement does not prove physical presentation latency")
    if raw.get("attestationPolicy") != "runtime-quilt-capture-v1":
        raise ValueError("raw measurement lacks runtime quilt capture attestation")
    run_id = raw.get("measurementRunId")
    if (not isinstance(run_id, str) or len(run_id) < 8 or len(run_id) > 64
            or not run_id[0].isalnum()
            or any(character not in "abcdefghijklmnopqrstuvwxyz0123456789_-" for character in run_id)):
        raise ValueError("raw measurement has an unsafe or missing run ID")
    profiles = raw.get("profiles")
    if not isinstance(profiles, list) or {item.get("mode") for item in profiles} != MODES:
        raise ValueError("raw measurement must contain all three performance modes")
    profile_ids = [item.get("id") for item in profiles]
    if any(not isinstance(item, str) or not item for item in profile_ids) or len(profile_ids) != len(set(profile_ids)):
        raise ValueError("raw measurement profile IDs are missing or duplicated")

    aggregated = []
    capture_evidence: dict[str, dict] = {}
    realtime_passed = False
    for profile in profiles:
        scenarios = profile.get("scenarios")
        if not isinstance(scenarios, list) or {item.get("name") for item in scenarios} != SCENARIOS:
            raise ValueError(f"profile {profile['id']} must contain four unique scenarios")
        results = []
        profile_passed = True
        for scenario in scenarios:
            duration = scenario.get("durationSeconds")
            if isinstance(duration, bool) or not isinstance(duration, (int, float)) or duration < 60:
                raise ValueError(f"{profile['id']}/{scenario.get('name')} duration is below 60 seconds")
            frame = samples(scenario.get("frameMs"), "frameMs", max(600, int(duration * 10)))
            vad = samples(scenario.get("vadToListeningMs"), "vadToListeningMs", 20)
            barge = samples(scenario.get("bargeInToAudioStopMs"), "bargeInToAudioStopMs", 20)
            viseme = samples(scenario.get("audioToVisemeMs"), "audioToVisemeMs", 20)
            attestation_source = verify_runtime_attestation(
                scenario.get("presentationAttestation"), run_id=run_id,
                mode=profile["mode"], views=profile["views"],
                quilt_width=profile["quiltWidth"], quilt_height=profile["quiltHeight"],
                evidence_base=raw_path.parent,
            )
            attestation = rebase_runtime_attestation(
                scenario["presentationAttestation"], attestation_source, output_path.parent)
            quilt_evidence = attestation["captureEvidence"]
            if quilt_evidence["uri"] in capture_evidence:
                raise ValueError("each benchmark scenario requires unique quilt evidence")
            capture_evidence[quilt_evidence["uri"]] = quilt_evidence
            result = {
                "name": scenario["name"], "durationSeconds": float(duration), "frames": len(frame),
                "p95FrameMs": percentile(frame, 0.95), "p99FrameMs": percentile(frame, 0.99),
                "droppedFrameRate": round(sum(value > 33.34 for value in frame) / len(frame), 6),
                "vadToListeningP95Ms": percentile(vad, 0.95),
                "bargeInToAudioStopP95Ms": percentile(barge, 0.95),
                "audioToVisemeP95Ms": percentile(viseme, 0.95),
                "presentationAttestation": attestation,
            }
            scenario_passed = (
                result["p95FrameMs"] <= 33.34 and result["p99FrameMs"] <= 50.0
                and result["droppedFrameRate"] <= 0.02
                and result["vadToListeningP95Ms"] <= 100.0
                and result["bargeInToAudioStopP95Ms"] <= 150.0
                and result["audioToVisemeP95Ms"] <= 80.0)
            profile_passed = profile_passed and scenario_passed
            results.append(result)
        if profile["mode"] in {"Realtime", "RealtimeAdaptive"} and profile_passed:
            realtime_passed = True
        aggregated.append({
            "id": profile["id"], "mode": profile["mode"], "views": profile["views"],
            "quiltWidth": profile["quiltWidth"], "quiltHeight": profile["quiltHeight"],
            "scenarios": results,
        })

    output_path.parent.mkdir(parents=True, exist_ok=True)
    if output_path.parent == raw_path.parent:
        evidence_uri = raw_path.name
    else:
        try:
            evidence_uri = raw_path.relative_to(output_path.parent).as_posix()
        except ValueError as error:
            raise ValueError("raw measurement must be inside the acceptance workspace") from error
    payload = {
        "schemaVersion": 1,
        "latencyBoundary": "physical-presentation-v1",
        "measurementRunId": run_id,
        "status": "passed" if realtime_passed else "failed",
        "platform": raw["platform"], "display": raw["display"], "software": raw["software"],
        "profiles": aggregated,
        "evidence": [{"uri": evidence_uri, "bytes": raw_path.stat().st_size,
                      "sha256": digest(raw_path)}, *capture_evidence.values()],
    }
    temporary = output_path.with_suffix(output_path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, output_path)
    verify(output_path, require_passed=realtime_passed)
    return payload


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = build(args.raw, args.output)
    print(json.dumps({"status": result["status"], "profiles": len(result["profiles"]),
                      "output": str(args.output.resolve())}, ensure_ascii=False))


if __name__ == "__main__":
    main()
