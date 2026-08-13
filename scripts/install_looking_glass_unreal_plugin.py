#!/usr/bin/env python3
"""Safely install the pinned Looking Glass UE plugin without enabling it globally."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import stat
import tempfile
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath

from verify_looking_glass_integration import DEFAULT_LOCK, DEFAULT_PROJECT, verify


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_archive(archive_path: Path, lock: dict) -> list[zipfile.ZipInfo]:
    expected = lock["upstream"]["archive"]
    if archive_path.stat().st_size != expected["bytes"] or digest(archive_path) != expected["sha256"]:
        raise ValueError("Looking Glass archive size or SHA-256 does not match the pinned release")
    with zipfile.ZipFile(archive_path) as archive:
        infos = archive.infolist()
        names = [info.filename for info in infos]
        if not infos or len(names) != len(set(names)):
            raise ValueError("Looking Glass archive is empty or contains duplicate entries")
        for info in infos:
            path = PurePosixPath(info.filename)
            mode = info.external_attr >> 16
            if (path.is_absolute() or ".." in path.parts
                    or not info.filename.startswith(expected["root"])
                    or stat.S_ISLNK(mode)):
                raise ValueError(f"unsafe Looking Glass archive entry: {info.filename}")
        if "LookingGlass/LookingGlass.uplugin" not in names:
            raise ValueError("Looking Glass plugin descriptor is missing")
        descriptor = json.loads(archive.read("LookingGlass/LookingGlass.uplugin"))
        modules = descriptor.get("Modules", [])
        if {item.get("Name") for item in modules} != {"LookingGlassRuntime", "LookingGlassEditor"}:
            raise ValueError("Looking Glass release has unexpected modules")
        if any(item.get("WhitelistPlatforms") != ["Win64"] for item in modules):
            raise ValueError("Looking Glass release platform contract changed")
    return infos


def download_pinned(lock: dict, destination: Path) -> Path:
    source = lock["upstream"]["archive"]["url"]
    request = urllib.request.Request(source, headers={"User-Agent": "Gahyeon-Stage-Installer/1"})
    with urllib.request.urlopen(request, timeout=120) as response, destination.open("wb") as output:
        shutil.copyfileobj(response, output, length=1024 * 1024)
        output.flush()
        os.fsync(output.fileno())
    return destination


def verify_installed(target: Path, archive_path: Path, lock: dict) -> dict:
    infos = validate_archive(archive_path, lock)
    expected_files = {
        PurePosixPath(info.filename).relative_to("LookingGlass").as_posix(): info
        for info in infos if not info.is_dir()
    }
    actual = set()
    if not target.is_dir() or target.is_symlink():
        raise ValueError("Looking Glass target is missing or unsafe")
    for path in target.rglob("*"):
        if path.is_symlink():
            raise ValueError(f"installed Looking Glass plugin contains a symlink: {path}")
        if path.is_file():
            actual.add(path.relative_to(target).as_posix())
    if actual != set(expected_files):
        raise ValueError("installed Looking Glass plugin inventory differs from the pinned archive")
    with zipfile.ZipFile(archive_path) as archive:
        for relative, info in expected_files.items():
            installed = target / relative
            archived_name = f"LookingGlass/{relative}"
            if installed.stat().st_size != info.file_size:
                raise ValueError(f"installed Looking Glass size mismatch: {relative}")
            if hashlib.sha256(installed.read_bytes()).digest() != hashlib.sha256(
                    archive.read(archived_name)).digest():
                raise ValueError(f"installed Looking Glass checksum mismatch: {relative}")
    return {"status": "verified", "target": str(target), "files": len(expected_files)}


def install(archive_path: Path, project_path: Path, lock_path: Path = DEFAULT_LOCK) -> dict:
    verify(lock_path, project_path)
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    archive_path = archive_path.resolve()
    infos = validate_archive(archive_path, lock)
    project_dir = project_path.resolve().parent
    target = project_dir / "Plugins/LookingGlass"
    if target.exists():
        verified = verify_installed(target, archive_path, lock)
        return {**verified, "status": "already-installed"}
    plugins_dir = target.parent
    plugins_dir.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=".looking-glass-", dir=plugins_dir))
    staged_target = staging / "LookingGlass"
    try:
        with zipfile.ZipFile(archive_path) as archive:
            for info in infos:
                relative = PurePosixPath(info.filename).relative_to("LookingGlass")
                if not relative.parts:
                    continue
                destination = staged_target.joinpath(*relative.parts)
                if info.is_dir():
                    destination.mkdir(parents=True, exist_ok=True)
                    continue
                destination.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(info) as source, destination.open("wb") as output:
                    shutil.copyfileobj(source, output, length=1024 * 1024)
                    output.flush()
                    os.fsync(output.fileno())
        verify_installed(staged_target, archive_path, lock)
        os.replace(staged_target, target)
        result = verify_installed(target, archive_path, lock)
        return {**result, "status": "installed", "release": lock["upstream"]["release"],
                "enabled": False}
    finally:
        shutil.rmtree(staging, ignore_errors=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", type=Path, default=DEFAULT_PROJECT)
    parser.add_argument("--lock", type=Path, default=DEFAULT_LOCK)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--archive", type=Path)
    group.add_argument("--download", action="store_true")
    args = parser.parse_args()
    if args.download:
        with tempfile.TemporaryDirectory() as temporary:
            lock = json.loads(args.lock.read_text(encoding="utf-8"))
            archive = download_pinned(lock, Path(temporary) / "LookingGlass.zip")
            result = install(archive, args.project, args.lock)
    else:
        result = install(args.archive, args.project, args.lock)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
