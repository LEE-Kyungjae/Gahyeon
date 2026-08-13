#!/usr/bin/env python3
"""Merge the twelve UE benchmark fragments into one raw acceptance measurement."""

from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path

from looking_glass_runtime_attestation import (
    rebase_runtime_attestation,
    verify_runtime_attestation,
)

MODES = {"Realtime", "RealtimeAdaptive", "NonRealtime"}
SCENARIOS = {"idle", "listening", "thinking", "speaking"}
SHA256 = re.compile(r"^[a-f0-9]{64}$")


def merge(fragment_dir: Path, metadata_path: Path, output_path: Path) -> dict:
    fragment_dir = fragment_dir.resolve()
    metadata_path = metadata_path.resolve()
    output_path = output_path.resolve()
    if not fragment_dir.is_dir() or fragment_dir.is_symlink():
        raise ValueError("benchmark fragment directory is missing or unsafe")
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    if metadata.get("schemaVersion") != 1:
        raise ValueError("unsupported benchmark metadata version")
    run_id = metadata.get("measurementRunId")
    if (not isinstance(run_id, str) or not re.fullmatch(r"[a-z0-9][a-z0-9_-]{7,63}", run_id)):
        raise ValueError("benchmark metadata has an unsafe or missing run ID")
    serial_hash = metadata.get("display", {}).get("serialHash", "")
    if not SHA256.fullmatch(serial_hash):
        raise ValueError("display serial must be stored as SHA-256, never plaintext")
    if metadata.get("platform", {}).get("os") != "Win64":
        raise ValueError("Looking Glass benchmark must be captured on Win64")
    if metadata.get("software", {}).get("unreal") != "5.6" or metadata.get("software", {}).get("plugin") != "2.1.1":
        raise ValueError("benchmark metadata does not match the pinned Unreal/plugin versions")

    paths = sorted(fragment_dir.glob("*.json"))
    if len(paths) != 12 or any(path.is_symlink() for path in paths):
        raise ValueError("exactly twelve regular benchmark fragments are required")
    profiles: dict[str, dict] = {}
    seen = set()
    attestation_sources: set[Path] = set()
    for path in paths:
        item = json.loads(path.read_text(encoding="utf-8"))
        if item.get("schemaVersion") != 2:
            raise ValueError(f"unsupported fragment version: {path.name}")
        if item.get("latencyBoundary") != "physical-presentation-v1":
            raise ValueError(f"fragment does not prove physical presentation latency: {path.name}")
        if item.get("attestationPolicy") != "runtime-quilt-capture-v1":
            raise ValueError(f"fragment lacks runtime quilt capture attestation: {path.name}")
        if item.get("measurementRunId") != run_id:
            raise ValueError(f"benchmark fragment belongs to a different run: {path.name}")
        profile_id, mode, scenario = item.get("id"), item.get("mode"), item.get("name")
        key = (profile_id, scenario)
        if (not isinstance(profile_id, str) or not profile_id or mode not in MODES
                or scenario not in SCENARIOS or key in seen):
            raise ValueError(f"invalid or duplicate benchmark fragment: {path.name}")
        seen.add(key)
        profile = profiles.setdefault(profile_id, {
            "id": profile_id, "mode": mode, "views": item.get("views"),
            "quiltWidth": item.get("quiltWidth"), "quiltHeight": item.get("quiltHeight"),
            "scenarios": [],
        })
        identity = (mode, item.get("views"), item.get("quiltWidth"), item.get("quiltHeight"))
        expected = (profile["mode"], profile["views"], profile["quiltWidth"], profile["quiltHeight"])
        if identity != expected:
            raise ValueError(f"profile settings changed between scenarios: {profile_id}")
        attestation_source = verify_runtime_attestation(
            item.get("presentationAttestation"), run_id=run_id, mode=mode,
            views=item.get("views"), quilt_width=item.get("quiltWidth"),
            quilt_height=item.get("quiltHeight"), evidence_base=fragment_dir,
        )
        if attestation_source in attestation_sources:
            raise ValueError("each benchmark scenario requires unique quilt evidence")
        attestation_sources.add(attestation_source)
        attestation = rebase_runtime_attestation(
            item["presentationAttestation"], attestation_source, output_path.parent)
        profile["scenarios"].append({key: item[key] for key in (
            "name", "durationSeconds", "frameMs", "vadToListeningMs",
            "bargeInToAudioStopMs", "audioToVisemeMs")} | {
                "presentationAttestation": attestation,
            })
    if len(profiles) != 3 or {item["mode"] for item in profiles.values()} != MODES:
        raise ValueError("fragments must describe one profile for each performance mode")
    for profile in profiles.values():
        if {item["name"] for item in profile["scenarios"]} != SCENARIOS:
            raise ValueError(f"profile has incomplete scenarios: {profile['id']}")
        profile["scenarios"].sort(key=lambda item: item["name"])

    result = {"schemaVersion": 2,
              "latencyBoundary": "physical-presentation-v1",
              "attestationPolicy": "runtime-quilt-capture-v1",
              "measurementRunId": run_id,
              "platform": metadata["platform"],
              "display": metadata["display"], "software": metadata["software"],
              "profiles": sorted(profiles.values(), key=lambda item: item["id"])}
    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = output_path.with_suffix(output_path.suffix + ".tmp")
    temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, output_path)
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fragments", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = merge(args.fragments, args.metadata, args.output)
    print(json.dumps({"profiles": len(result["profiles"]), "fragments": 12,
                      "output": str(args.output.resolve())}, ensure_ascii=False))


if __name__ == "__main__":
    main()
