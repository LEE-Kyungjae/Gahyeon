#!/usr/bin/env python3
"""Atomically install an approved Gahyeon Hero package into an Unreal project."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path

from verify_gahyeon_hero_asset import (
    digest,
    local_path,
    verify,
    verify_unreal_content_package,
)


CONTENT_PREFIX = "Content/GahyeonGenerated/"
RECEIPT_DIRECTORY = "Saved/GahyeonHeroInstall"


def validate_project(project: Path) -> Path:
    project = project.resolve()
    if not project.is_dir() or not any(project.glob("*.uproject")):
        raise ValueError(f"not an Unreal project directory: {project}")
    return project


def verify_installed(target: Path, inventory: dict) -> None:
    if not target.is_dir() or target.is_symlink():
        raise ValueError(f"Gahyeon Hero install is missing or unsafe: {target}")
    expected = {
        item["path"].removeprefix(CONTENT_PREFIX): item
        for item in inventory["files"]
    }
    actual: set[str] = set()
    for path in target.rglob("*"):
        if path.is_symlink():
            raise ValueError(f"installed Hero content contains a symbolic link: {path}")
        if path.is_file():
            actual.add(path.relative_to(target).as_posix())
    if actual != set(expected):
        raise ValueError(
            f"installed Hero inventory mismatch: missing={sorted(set(expected) - actual)}, "
            f"undeclared={sorted(actual - set(expected))}")
    for relative, item in expected.items():
        path = target / relative
        if path.stat().st_size != item["bytes"]:
            raise ValueError(f"installed Hero byte size mismatch: {relative}")
        if digest(path) != item["sha256"]:
            raise ValueError(f"installed Hero checksum mismatch: {relative}")


def _write_receipt(project: Path, payload: dict) -> Path:
    directory = project / RECEIPT_DIRECTORY
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / "receipt.json"
    descriptor, temporary_name = tempfile.mkstemp(prefix=".receipt.", dir=directory)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_name, target)
    finally:
        Path(temporary_name).unlink(missing_ok=True)
    return target


def install_verified_package(package: Path, project: Path, *, replace: bool = False) -> dict:
    project = validate_project(project)
    package = package.resolve()
    inventory = verify_unreal_content_package(package)
    package_sha = digest(package)
    target = project / "Content/GahyeonGenerated"
    receipt_path = project / RECEIPT_DIRECTORY / "receipt.json"

    if target.exists():
        try:
            verify_installed(target, inventory)
            if receipt_path.is_file():
                receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
                if receipt.get("packageSha256") == package_sha:
                    return {"status": "already-installed", "packageSha256": package_sha,
                            "target": str(target), "backup": None}
        except (ValueError, OSError, json.JSONDecodeError):
            pass
        if not replace:
            raise ValueError(
                f"different Hero content already exists at {target}; rerun with --replace to back it up")
        if target.is_symlink() or not target.is_dir():
            raise ValueError(f"refusing to replace unsafe Hero target: {target}")

    work_root = project / RECEIPT_DIRECTORY
    work_root.mkdir(parents=True, exist_ok=True)
    staging_root = Path(tempfile.mkdtemp(prefix="staging-", dir=work_root))
    staging_target = staging_root / "GahyeonGenerated"
    backup: Path | None = None
    installed = False
    try:
        with zipfile.ZipFile(package) as archive:
            by_name = {info.filename: info for info in archive.infolist()}
            for item in inventory["files"]:
                relative = item["path"].removeprefix(CONTENT_PREFIX)
                destination = staging_target / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(by_name[item["path"]], "r") as source, destination.open("wb") as output:
                    shutil.copyfileobj(source, output, length=1024 * 1024)
                    output.flush()
                    os.fsync(output.fileno())
        verify_installed(staging_target, inventory)
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
            backup = work_root / "backups" / f"{stamp}-{package_sha[:12]}"
            backup.parent.mkdir(parents=True, exist_ok=True)
            os.replace(target, backup)
        try:
            os.replace(staging_target, target)
            installed = True
        except BaseException:
            if backup is not None and backup.exists() and not target.exists():
                os.replace(backup, target)
            raise
        verify_installed(target, inventory)
        receipt = _write_receipt(project, {
            "schemaVersion": 1,
            "characterId": "gahyeon",
            "packageSha256": package_sha,
            "packageBytes": package.stat().st_size,
            "entryAsset": inventory["entryAsset"],
            "runtimeClass": inventory["runtimeClass"],
            "runtimeContract": inventory["runtimeContract"],
            "engineVersion": inventory["engineVersion"],
            "installedAt": datetime.now(timezone.utc).isoformat(),
            "backup": str(backup) if backup is not None else None,
        })
        return {"status": "installed", "packageSha256": package_sha,
                "target": str(target), "backup": str(backup) if backup else None,
                "receipt": str(receipt)}
    except BaseException:
        if installed and backup is not None and backup.exists():
            failed = work_root / f"failed-{package_sha[:12]}"
            if target.exists():
                os.replace(target, failed)
            os.replace(backup, target)
        raise
    finally:
        shutil.rmtree(staging_root, ignore_errors=True)


def install_from_manifest(manifest: Path, project: Path, *, replace: bool = False) -> dict:
    payload = verify(manifest, require_approved=True, renderer="hero-engine", verify_files=True)
    package = next(item for item in payload["packages"] if item["renderer"] == "hero-engine")
    return install_verified_package(local_path(manifest.resolve(), package["uri"]), project,
                                    replace=replace)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--project", required=True, type=Path)
    parser.add_argument("--replace", action="store_true",
                        help="move existing GahyeonGenerated content to Saved before installing")
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    if args.check_only:
        payload = verify(args.manifest, require_approved=True,
                         renderer="hero-engine", verify_files=True)
        package = next(item for item in payload["packages"] if item["renderer"] == "hero-engine")
        inventory = verify_unreal_content_package(local_path(args.manifest.resolve(), package["uri"]))
        project = validate_project(args.project)
        verify_installed(project / "Content/GahyeonGenerated", inventory)
        result = {"status": "verified", "target": str(project / "Content/GahyeonGenerated")}
    else:
        result = install_from_manifest(args.manifest, args.project, replace=args.replace)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
