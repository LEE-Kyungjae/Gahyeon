#!/usr/bin/env python3
"""Fail when Core Home World and the Unreal prototype fixture drift apart."""

from __future__ import annotations

import argparse
import re
from pathlib import Path


JAVA_POINT = re.compile(
    r'"(?P<key>[^"]+)",\s*point\("(?P<id>[^"]+)",\s*"(?P<room>[^"]+)",'
    r'\s*(?P<x>-?\d+(?:\.\d+)?),\s*(?P<y>-?\d+(?:\.\d+)?),'
    r'\s*(?P<z>-?\d+(?:\.\d+)?),\s*(?P<activities>[^)]*)\)')
CPP_POINT = re.compile(
    r'(?P<variable>\w+)->SetRelativeLocation\(FVector\('
    r'(?P<x>-?\d+(?:\.\d+)?),\s*(?P<y>-?\d+(?:\.\d+)?),'
    r'\s*(?P<z>-?\d+(?:\.\d+)?)\)\);.*?'
    r'(?P=variable)->Configure\(TEXT\("(?P<id>[^"]+)"\),'
    r'\s*TEXT\("(?P<room>[^"]+)"\),'
    r'\s*\{(?P<activities>[^}]*)\}\);',
    re.DOTALL)


def parse_core(path: Path) -> dict[str, tuple[tuple[float, float, float], set[str]]]:
    source = path.read_text(encoding="utf-8")
    result = {}
    for match in JAVA_POINT.finditer(source):
        if match["key"] != match["id"]:
            raise ValueError(f'Core point map key/id differ: {match["key"]}/{match["id"]}')
        activities = {
            value.lower()
            for value in re.findall(r"WorldActivity\.([A-Z_]+)", match["activities"])
        }
        position = tuple(float(match[name]) for name in ("x", "y", "z"))
        result[match["id"]] = (position, activities)
    if not result:
        raise ValueError(f"No Core Home World points parsed from {path}")
    return result


def parse_unreal(path: Path) -> dict[str, tuple[str, tuple[float, float, float], set[str]]]:
    source = path.read_text(encoding="utf-8")
    result = {}
    for match in CPP_POINT.finditer(source):
        activities = set(re.findall(r'TEXT\("([a-z_]+)"\)', match["activities"]))
        position = tuple(float(match[name]) for name in ("x", "y", "z"))
        result[match["id"]] = (match["room"], position, activities)
    if not result:
        raise ValueError(f"No Unreal interaction points parsed from {path}")
    return result


def verify(core_path: Path, unreal_path: Path) -> None:
    core = parse_core(core_path)
    unreal = parse_unreal(unreal_path)
    if core.keys() != unreal.keys():
        raise ValueError(
            f"Home World point IDs differ: Core={sorted(core)}, Unreal={sorted(unreal)}")
    core_source = core_path.read_text(encoding="utf-8")
    core_rooms = {match["id"]: match["room"] for match in JAVA_POINT.finditer(core_source)}
    for point_id, (core_position, core_activities) in core.items():
        x, elevation, depth = core_position
        expected_unreal = (x * 100.0, depth * 100.0, elevation * 100.0)
        unreal_room, unreal_position, unreal_activities = unreal[point_id]
        if unreal_room != core_rooms[point_id]:
            raise ValueError(
                f"{point_id} room differs: Core={core_rooms[point_id]}, Unreal={unreal_room}")
        if unreal_position != expected_unreal:
            raise ValueError(
                f"{point_id} position differs: expected Unreal {expected_unreal}, "
                f"found {unreal_position}")
        if unreal_activities != core_activities:
            raise ValueError(
                f"{point_id} activities differ: Core={sorted(core_activities)}, "
                f"Unreal={sorted(unreal_activities)}")


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument("--core", type=Path, default=root / "src/main/java/com/gahyeonbot/core/behavior/GahyeonHomeWorld.java")
    parser.add_argument("--unreal", type=Path, default=root / "unreal/GahyeonStage/Source/GahyeonStage/Private/World/GahyeonPrototypeRoom.cpp")
    args = parser.parse_args()
    verify(args.core, args.unreal)
    print("Gahyeon Home World Core/Unreal contract passed")


if __name__ == "__main__":
    main()
