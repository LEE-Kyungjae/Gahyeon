#!/usr/bin/env python3
"""Static gate for the optional, plugin-dependent Looking Glass adapter boundary."""

from __future__ import annotations

import json
from pathlib import Path

from verify_looking_glass_unreal_profile import DEFAULT_BASE, DEFAULT_GO, verify as verify_profile


ROOT = Path(__file__).resolve().parents[1]
STAGE = ROOT / "unreal/GahyeonStage"


def _text(path: Path) -> str:
    if not path.is_file() or path.stat().st_size == 0:
        raise ValueError(f"Looking Glass adapter source is missing: {path}")
    return path.read_text(encoding="utf-8")


def verify() -> dict:
    profile = verify_profile(DEFAULT_BASE, DEFAULT_GO)
    base = json.loads(DEFAULT_BASE.read_text(encoding="utf-8"))
    if any(item.get("Name") == "GahyeonLookingGlassAdapter" for item in base.get("Modules", [])):
        raise ValueError("base Stage cannot load the Looking Glass adapter")

    stage_build = _text(STAGE / "Source/GahyeonStage/GahyeonStage.Build.cs")
    if "LookingGlassRuntime" in stage_build or "GahyeonLookingGlassAdapter" in stage_build:
        raise ValueError("base Stage build acquired an optional renderer dependency")
    adapter_build = _text(
        STAGE / "Source/GahyeonLookingGlassAdapter/GahyeonLookingGlassAdapter.Build.cs")
    for marker in ("UnrealTargetPlatform.Win64", '"GahyeonStage"', '"LookingGlassRuntime"'):
        if marker not in adapter_build:
            raise ValueError(f"adapter build contract is incomplete: {marker}")

    boundary = _text(
        STAGE / "Source/GahyeonStage/Public/LookingGlass/GahyeonLookingGlassAttestation.h")
    adapter = _text(
        STAGE / "Source/GahyeonLookingGlassAdapter/Private/GahyeonLookingGlassAdapterModule.cpp")
    benchmark = _text(
        STAGE / "Source/GahyeonStage/Private/Debug/GahyeonLookingGlassBenchmarkComponent.cpp")
    for marker in ("IModularFeature", "BeginAttestedCapture", "ReadAttestation",
                   "IsPhysicalPresentationReady"):
        if marker not in boundary:
            raise ValueError(f"plugin-neutral attestation boundary is incomplete: {marker}")
    for marker in ("ILookingGlassRuntime::IsAvailable", "IsRenderingOnDevice",
                   "GetGameLookingGlassCaptureComponent", "TakeQuiltScreenshot",
                   "GahyeonLookingGlassAdapter"):
        if marker not in adapter:
            raise ValueError(f"adapter runtime attestation is incomplete: {marker}")
    if "LookingGlassBridge.h" in adapter or "/Private/" in adapter:
        raise ValueError("adapter must use only the pinned plugin public API")
    required_benchmark = ("GetModularFeatureImplementations", "BeginAttestedCapture",
                          "ReadAttestation", "CopyFile", "runtime-quilt-capture-v1",
                          "presentationAttestation")
    if any(marker not in benchmark for marker in required_benchmark):
        raise ValueError("benchmark can bypass runtime quilt capture attestation")
    if benchmark.index("ReadAttestation") > benchmark.index(
            'SetStringField(TEXT("latencyBoundary")'):
        raise ValueError("physical latency boundary is emitted before runtime attestation")

    helper = _text(ROOT / "scripts/looking_glass_runtime_attestation.py")
    for script_name in ("merge_looking_glass_benchmark_fragments.py",
                        "build_looking_glass_acceptance.py",
                        "verify_looking_glass_acceptance.py"):
        script = _text(ROOT / "scripts" / script_name)
        if "verify_runtime_attestation" not in script:
            raise ValueError(f"{script_name} does not fail closed on runtime attestation")
    if "_png_dimensions" not in helper or "runtime presentation attestation" not in helper:
        raise ValueError("quilt evidence validation is incomplete")
    return {"valid": True, "adapter": profile["adapterModule"],
            "baseIndependent": True, "runtimeAttestation": True}


def main() -> None:
    print(json.dumps(verify(), ensure_ascii=False))


if __name__ == "__main__":
    main()
