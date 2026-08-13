#!/usr/bin/env python3
"""Create a deterministic, self-inventorying Gahyeon Unreal content package."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
import zipfile
from pathlib import Path

from verify_gahyeon_hero_asset import verify_unreal_content_package


MOUNT_ROOT = "Content/GahyeonGenerated"
ENTRY_ASSET = "/Game/GahyeonGenerated/Characters/Gahyeon.Gahyeon"
RUNTIME_CLASS = ENTRY_ASSET + "_C"
RUNTIME_CONTRACT = {
    "pawnBaseClass": "/Script/GahyeonStage.GahyeonCharacterPawn",
    "animInstanceBaseClass": "/Script/GahyeonStage.GahyeonCharacterAnimInstance",
    "requiresBodySkeletalMesh": True,
    "requiresPresentationProfile": True,
}
FIXED_ZIP_TIME = (2020, 1, 1, 0, 0, 0)


def digest_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def collect(source: Path) -> list[tuple[str, bytes]]:
    if not source.is_dir() or source.name != "GahyeonGenerated":
        raise ValueError("source must be the GahyeonGenerated content directory")
    result: list[tuple[str, bytes]] = []
    for path in sorted(source.rglob("*")):
        if path.is_symlink():
            raise ValueError(f"source contains a symbolic link: {path}")
        if not path.is_file():
            continue
        relative = path.relative_to(source).as_posix()
        if any(part.startswith(".") for part in Path(relative).parts):
            raise ValueError(f"source contains a hidden path: {relative}")
        content = path.read_bytes()
        if not content:
            raise ValueError(f"source contains an empty file: {relative}")
        result.append((f"{MOUNT_ROOT}/{relative}", content))
    if not result or not any(name.endswith(".uasset") for name, _ in result):
        raise ValueError("source must contain at least one .uasset")
    return result


def _write_entry(archive: zipfile.ZipFile, name: str, content: bytes) -> None:
    info = zipfile.ZipInfo(name, FIXED_ZIP_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    archive.writestr(info, content, compresslevel=9)


def package(source: Path, output: Path, *, engine_version: str,
            entry_asset: str, runtime_class: str) -> dict:
    if engine_version != "5.6":
        raise ValueError("Gahyeon Hero content must be exported from Unreal Engine 5.6")
    if entry_asset != ENTRY_ASSET or runtime_class != RUNTIME_CLASS:
        raise ValueError(
            f"entryAsset/runtimeClass must be the fixed Gahyeon Hero Blueprint: "
            f"{ENTRY_ASSET} and {RUNTIME_CLASS}")
    source = source.resolve()
    output = output.resolve()
    files = collect(source)
    inventory = {
        "schemaVersion": 2,
        "characterId": "gahyeon",
        "mountRoot": MOUNT_ROOT,
        "engineVersion": engine_version,
        "entryAsset": entry_asset,
        "runtimeClass": runtime_class,
        "runtimeContract": RUNTIME_CONTRACT,
        "files": [
            {"path": name, "bytes": len(content), "sha256": digest_bytes(content)}
            for name, content in files
        ],
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{output.name}.", suffix=".tmp",
                                                   dir=output.parent)
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        with zipfile.ZipFile(temporary, "w", allowZip64=True) as archive:
            manifest = json.dumps(inventory, ensure_ascii=False, sort_keys=True,
                                  separators=(",", ":")).encode("utf-8")
            _write_entry(archive, "hero-content-manifest.json", manifest)
            for name, content in files:
                _write_entry(archive, name, content)
        verify_unreal_content_package(temporary)
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)
    return {
        "uri": str(output),
        "bytes": output.stat().st_size,
        "sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
        "files": len(files),
        "entryAsset": entry_asset,
        "runtimeClass": runtime_class,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path,
                        help="exported Content/GahyeonGenerated directory")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--engine-version", default="5.6")
    parser.add_argument("--entry-asset", required=True,
                        help="for example /Game/GahyeonGenerated/Characters/Gahyeon.Gahyeon")
    parser.add_argument("--runtime-class", required=True,
                        help="generated pawn class, usually entry asset plus _C")
    args = parser.parse_args()
    print(json.dumps(package(args.source, args.output,
                             engine_version=args.engine_version,
                             entry_asset=args.entry_asset,
                             runtime_class=args.runtime_class), ensure_ascii=False))


if __name__ == "__main__":
    main()
