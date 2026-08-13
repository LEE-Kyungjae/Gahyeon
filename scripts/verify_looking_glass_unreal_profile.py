#!/usr/bin/env python3
"""Verify that the Go profile differs from the normal Stage only by optional rendering."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BASE = ROOT / "unreal/GahyeonStage/GahyeonStage.uproject"
DEFAULT_GO = ROOT / "unreal/GahyeonStage/GahyeonStageLookingGlass.uproject"


def by_name(items: list[dict]) -> dict[str, dict]:
    result = {item.get("Name"): item for item in items}
    if None in result or len(result) != len(items):
        raise ValueError("Unreal project contains unnamed or duplicate entries")
    return result


def verify(base_path: Path, go_path: Path) -> dict:
    base = json.loads(base_path.read_text(encoding="utf-8"))
    go = json.loads(go_path.read_text(encoding="utf-8"))
    for key in ("FileVersion", "EngineAssociation", "Category"):
        if go.get(key) != base.get(key):
            raise ValueError(f"Looking Glass profile diverges from canonical Stage: {key}")
    if base.get("EngineAssociation") != "5.6":
        raise ValueError("Looking Glass profile requires the UE 5.6 baseline")
    base_modules = by_name(base.get("Modules", []))
    go_modules = by_name(go.get("Modules", []))
    if "GahyeonLookingGlassAdapter" in base_modules:
        raise ValueError("canonical Stage must not declare GahyeonLookingGlassAdapter")
    adapter = go_modules.pop("GahyeonLookingGlassAdapter", None)
    if adapter != {
        "Name": "GahyeonLookingGlassAdapter", "Type": "Runtime",
        "LoadingPhase": "PostDefault", "PlatformAllowList": ["Win64"],
    }:
        raise ValueError("Go profile must enable the Win64-only Looking Glass adapter")
    if go_modules != base_modules:
        raise ValueError("Go profile changed a canonical Stage module")
    base_plugins = by_name(base.get("Plugins", []))
    go_plugins = by_name(go.get("Plugins", []))
    if "LookingGlass" in base_plugins:
        raise ValueError("canonical Stage must not declare the Looking Glass plugin")
    looking_glass = go_plugins.pop("LookingGlass", None)
    if looking_glass != {"Name": "LookingGlass", "Enabled": True,
                         "SupportedTargetPlatforms": ["Win64"]}:
        raise ValueError("Go profile must enable LookingGlass for Win64 only")
    if go_plugins != base_plugins:
        raise ValueError("Go profile changed a non-Looking-Glass plugin")
    if go.get("Description") == base.get("Description") or "Looking Glass" not in go.get("Description", ""):
        raise ValueError("Go profile must identify itself as an opt-in Looking Glass prototype")
    return {"valid": True, "engine": "5.6", "platform": "Win64",
            "adapterModule": "GahyeonLookingGlassAdapter",
            "basePluginCount": len(base_plugins), "goPluginCount": len(go_plugins) + 1}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", type=Path, default=DEFAULT_BASE)
    parser.add_argument("--go", type=Path, default=DEFAULT_GO)
    args = parser.parse_args()
    print(json.dumps(verify(args.base, args.go), ensure_ascii=False))


if __name__ == "__main__":
    main()
