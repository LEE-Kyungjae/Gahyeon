#!/usr/bin/env python3
"""Keep Looking Glass optional until its real-time/device gates are evidenced."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LOCK = ROOT / "unreal/GahyeonStage/Config/LookingGlassIntegration.lock.json"
DEFAULT_PROJECT = ROOT / "unreal/GahyeonStage/GahyeonStage.uproject"
EXPECTED_COMMIT = "8a82363c80c6998357e8eb20994d6a69d8eff827"


def verify(lock_path: Path, project_path: Path) -> dict:
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    project = json.loads(project_path.read_text(encoding="utf-8"))
    if lock.get("schemaVersion") != 1 or lock.get("status") != "evaluated-not-enabled":
        raise ValueError("Looking Glass integration must remain explicitly evaluated-not-enabled")
    upstream = lock.get("upstream", {})
    compatibility = lock.get("compatibility", {})
    policy = lock.get("policy", {})
    if (upstream.get("release") != "2.1.1"
            or upstream.get("commit") != EXPECTED_COMMIT
            or upstream.get("license") != "MIT"):
        raise ValueError("Looking Glass upstream release pin changed without review")
    archive = upstream.get("archive", {})
    if (archive.get("bytes") != 28378982
            or archive.get("sha256") != "062943c9919deadfe479d352de7d817426b706c6a96b859c5c4eba44d1f96623"
            or archive.get("root") != "LookingGlass/"):
        raise ValueError("Looking Glass release archive is not byte-for-byte pinned")
    if compatibility != {
        "unrealEngine": "5.6",
        "platforms": ["Win64"],
        "minimumBridge": "2.5.1",
        "realtimeModeImplemented": True,
        "upstreamRecommendsRealtimeProduction": False,
        "defaultQuiltViews": 66,
    }:
        raise ValueError("Looking Glass compatibility facts are incomplete or unsafe")
    required_false = {
        "enabledByDefault", "coreDependencyAllowed", "desktopDependencyAllowed",
        "headlessDependencyAllowed", "separateCognitionOrVoicePipelineAllowed",
    }
    if any(policy.get(key) is not False for key in required_false):
        raise ValueError("Looking Glass must not become a required or duplicate AI runtime")
    if policy.get("deviceAbsentMustRemainHealthy") is not True or policy.get("sharesWorldAndSession") is not True:
        raise ValueError("Looking Glass renderer isolation policy is incomplete")
    gates = lock.get("adoptionGates", [])
    if len(gates) < 5 or len(gates) != len(set(gates)):
        raise ValueError("Looking Glass adoption gates are incomplete or duplicated")
    profiles = {item.get("mode") for item in lock.get("prototypeProfiles", [])}
    if profiles != {"Realtime", "RealtimeAdaptive", "NonRealtime"}:
        raise ValueError("Looking Glass prototype must evaluate all upstream performance modes")
    plugins = {item.get("Name"): item for item in project.get("Plugins", [])}
    if plugins.get("LookingGlass", {}).get("Enabled") is True:
        raise ValueError("Looking Glass cannot be enabled before device and latency evidence exists")
    if project.get("EngineAssociation") != "5.6":
        raise ValueError("Looking Glass evaluation is pinned to the UE 5.6 Stage baseline")
    return {"valid": True, "release": upstream["release"], "status": lock["status"],
            "adoptionGateCount": len(gates)}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lock", type=Path, default=DEFAULT_LOCK)
    parser.add_argument("--project", type=Path, default=DEFAULT_PROJECT)
    args = parser.parse_args()
    print(json.dumps(verify(args.lock, args.project), ensure_ascii=False))


if __name__ == "__main__":
    main()
