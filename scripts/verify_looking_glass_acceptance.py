#!/usr/bin/env python3
"""Verify physical Looking Glass Go performance evidence and pass thresholds."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import jsonschema

from looking_glass_runtime_attestation import verify_runtime_attestation

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT / "docs/contracts/gahyeon-looking-glass-acceptance-v1.schema.json"
SCENARIOS = {"idle", "listening", "thinking", "speaking"}


def version_tuple(value: str) -> tuple[int, ...]:
    return tuple(int(part) for part in value.split("."))


def verify(path: Path, *, require_passed: bool = False) -> dict:
    path = path.resolve()
    payload = json.loads(path.read_text(encoding="utf-8"))
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    jsonschema.Draft202012Validator(schema).validate(payload)
    if payload["latencyBoundary"] != "physical-presentation-v1":
        raise ValueError("acceptance does not prove physical presentation latency")
    if version_tuple(payload["software"]["bridge"]) < (2, 5, 1):
        raise ValueError("Looking Glass Bridge must be 2.5.1 or newer")
    modes = [profile["mode"] for profile in payload["profiles"]]
    if len(modes) != len(set(modes)) or set(modes) != {"Realtime", "RealtimeAdaptive", "NonRealtime"}:
        raise ValueError("acceptance must compare all three unique performance modes")
    ids = [profile["id"] for profile in payload["profiles"]]
    if len(ids) != len(set(ids)):
        raise ValueError("Looking Glass profile IDs must be unique")
    passing_realtime = []
    quilt_evidence: dict[str, dict] = {}
    quilt_sources: set[Path] = set()
    for profile in payload["profiles"]:
        names = {item["name"] for item in profile["scenarios"]}
        if names != SCENARIOS:
            raise ValueError(f"profile {profile['id']} does not cover every runtime scenario")
        passed = all(
            item["p95FrameMs"] <= 33.34
            and item["p99FrameMs"] <= 50.0
            and item["droppedFrameRate"] <= 0.02
            and item["vadToListeningP95Ms"] <= 100.0
            and item["bargeInToAudioStopP95Ms"] <= 150.0
            and item["audioToVisemeP95Ms"] <= 80.0
            for item in profile["scenarios"]
        )
        for item in profile["scenarios"]:
            source = verify_runtime_attestation(
                item["presentationAttestation"],
                run_id=payload["measurementRunId"], mode=profile["mode"],
                views=profile["views"], quilt_width=profile["quiltWidth"],
                quilt_height=profile["quiltHeight"],
                evidence_base=path.parent,
            )
            evidence_item = item["presentationAttestation"]["captureEvidence"]
            if source in quilt_sources or evidence_item["uri"] in quilt_evidence:
                raise ValueError("each acceptance scenario requires unique quilt evidence")
            quilt_sources.add(source)
            quilt_evidence[evidence_item["uri"]] = evidence_item
        if profile["mode"] in {"Realtime", "RealtimeAdaptive"} and passed:
            passing_realtime.append(profile["id"])
    evidence = payload["evidence"]
    if payload["status"] != "draft" and not evidence:
        raise ValueError("measured Looking Glass acceptance requires raw evidence")
    seen = set()
    for item in evidence:
        relative = Path(item["uri"])
        if relative.is_absolute() or ".." in relative.parts or item["uri"] in seen:
            raise ValueError("Looking Glass evidence paths must be unique and local")
        seen.add(item["uri"])
        source = (path.parent / relative).resolve()
        if path.parent not in source.parents or not source.is_file() or source.is_symlink():
            raise ValueError(f"Looking Glass evidence is missing or unsafe: {item['uri']}")
        if source.stat().st_size != item["bytes"] or hashlib.sha256(source.read_bytes()).hexdigest() != item["sha256"]:
            raise ValueError(f"Looking Glass evidence checksum mismatch: {item['uri']}")
    indexed_evidence = {item["uri"]: item for item in evidence}
    if len(quilt_evidence) != 12 or any(
            indexed_evidence.get(uri) != item for uri, item in quilt_evidence.items()):
        raise ValueError("acceptance must checksum every attested quilt capture")
    if not (set(indexed_evidence) - set(quilt_evidence)):
        raise ValueError("acceptance must include the sealed raw measurement")
    if payload["status"] == "passed" and not passing_realtime:
        raise ValueError("passed acceptance requires at least one realtime profile to meet all budgets")
    if require_passed and payload["status"] != "passed":
        raise ValueError("Looking Glass physical acceptance is not passed")
    return {"valid": True, "status": payload["status"], "passingRealtimeProfiles": passing_realtime,
            "profileCount": len(payload["profiles"]), "evidenceCount": len(evidence)}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--require-passed", action="store_true")
    args = parser.parse_args()
    print(json.dumps(verify(args.manifest, require_passed=args.require_passed), ensure_ascii=False))


if __name__ == "__main__":
    main()
