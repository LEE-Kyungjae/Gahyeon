#!/usr/bin/env python3
"""Verify a Gahyeon Hero manifest and optionally its immutable local artifacts."""

from __future__ import annotations

import argparse
import hashlib
import json
import stat
import sys
import zipfile
from pathlib import Path
from pathlib import PurePosixPath

import jsonschema

from verify_gahyeon_identity_reference import verify as verify_identity
from verify_gahyeon_modeling_input import verify as verify_modeling
from verify_gahyeon_g1_review import verify as verify_g1_review
from verify_gahyeon_g2_review import verify as verify_g2_review
from verify_gahyeon_g3_review import verify as verify_g3_review
from verify_gahyeon_g4_review import verify as verify_g4_review
from verify_gahyeon_g5_review import verify as verify_g5_review


SHA_LENGTH = 64
REQUIRED_VISEMES = {"sil", "aa", "ih", "ou", "ee", "oh", "fv", "l", "mbp", "wq"}
GATES = {"G1", "G2", "G3", "G4", "G5"}
RENDERERS = {"hero-engine", "three-vrm", "looking-glass"}
FORMATS = {
    "hero-engine": {"unreal-content-zip"},
    "three-vrm": {"vrm"},
    "looking-glass": {"vrm", "glb"},
}
ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT / "docs/contracts/gahyeon-hero-asset.schema.json"
UNREAL_PACKAGE_SCHEMA = ROOT / "docs/contracts/gahyeon-unreal-content-package.schema.json"
UNREAL_PACKAGE_MANIFEST = "hero-content-manifest.json"
MAX_UNREAL_ENTRIES = 20_000
MAX_UNREAL_FILE_BYTES = 8 * 1024**3
MAX_UNREAL_TOTAL_BYTES = 32 * 1024**3
MAX_COMPRESSION_RATIO = 200
EXPECTED_UNREAL_RUNTIME_CONTRACT = {
    "pawnBaseClass": "/Script/GahyeonStage.GahyeonCharacterPawn",
    "animInstanceBaseClass": "/Script/GahyeonStage.GahyeonCharacterAnimInstance",
    "requiresBodySkeletalMesh": True,
    "requiresPresentationProfile": True,
}


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def local_path(manifest: Path, uri: object) -> Path:
    if not isinstance(uri, str) or not uri.strip() or "://" in uri:
        raise ValueError(f"Hero build evidence must use a local relative URI: {uri!r}")
    relative = Path(uri)
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError(f"Hero build evidence escapes its manifest directory: {uri!r}")
    base = manifest.parent.resolve()
    candidate = (base / relative).resolve()
    if candidate != base and base not in candidate.parents:
        raise ValueError(f"Hero build evidence resolves outside its manifest directory: {uri!r}")
    return candidate


def valid_sha(value: object) -> bool:
    return (isinstance(value, str) and len(value) == SHA_LENGTH
            and all(character in "0123456789abcdef" for character in value))


def verify_file(manifest: Path, item: dict, *, require_bytes: bool = False) -> Path:
    path = local_path(manifest, item.get("uri"))
    if not path.is_file():
        raise ValueError(f"Hero artifact is missing: {path}")
    if require_bytes and path.stat().st_size != item.get("bytes"):
        raise ValueError(f"Hero artifact byte size mismatch: {path}")
    if digest(path) != item.get("sha256"):
        raise ValueError(f"Hero artifact checksum mismatch: {path}")
    return path


def _safe_unreal_member(name: str) -> bool:
    path = PurePosixPath(name)
    return (bool(name) and "\\" not in name and not name.startswith("/")
            and not path.is_absolute() and ".." not in path.parts
            and all(part not in {"", "."} for part in path.parts))


def _zip_member_digest(archive: zipfile.ZipFile, info: zipfile.ZipInfo) -> str:
    value = hashlib.sha256()
    with archive.open(info, "r") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def verify_unreal_content_package(path: Path) -> dict:
    """Verify the sealed, Content-relative payload before Unreal sees it."""
    try:
        archive = zipfile.ZipFile(path)
    except (zipfile.BadZipFile, OSError) as error:
        raise ValueError(f"Hero Unreal package is not a valid ZIP: {path}") from error
    with archive:
        infos = archive.infolist()
        if not infos or len(infos) > MAX_UNREAL_ENTRIES:
            raise ValueError("Hero Unreal package has an invalid entry count")
        names = [info.filename for info in infos]
        if len(names) != len(set(names)):
            raise ValueError("Hero Unreal package contains duplicate paths")
        total_bytes = 0
        for info in infos:
            if not _safe_unreal_member(info.filename):
                raise ValueError(f"Hero Unreal package contains an unsafe path: {info.filename!r}")
            mode = info.external_attr >> 16
            if stat.S_ISLNK(mode):
                raise ValueError(f"Hero Unreal package contains a symbolic link: {info.filename}")
            if info.flag_bits & 0x1:
                raise ValueError(f"Hero Unreal package contains an encrypted entry: {info.filename}")
            if info.is_dir():
                continue
            if info.file_size > MAX_UNREAL_FILE_BYTES:
                raise ValueError(f"Hero Unreal package entry is too large: {info.filename}")
            total_bytes += info.file_size
            if total_bytes > MAX_UNREAL_TOTAL_BYTES:
                raise ValueError("Hero Unreal package expands beyond the size limit")
            if (info.file_size > 1024 * 1024 and info.compress_size > 0
                    and info.file_size / info.compress_size > MAX_COMPRESSION_RATIO):
                raise ValueError(f"Hero Unreal package has a suspicious compression ratio: {info.filename}")

        by_name = {info.filename: info for info in infos if not info.is_dir()}
        manifest_info = by_name.get(UNREAL_PACKAGE_MANIFEST)
        if manifest_info is None:
            raise ValueError(f"Hero Unreal package is missing {UNREAL_PACKAGE_MANIFEST}")
        try:
            internal = json.loads(archive.read(manifest_info).decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ValueError("Hero Unreal package manifest is invalid JSON") from error
        schema = json.loads(UNREAL_PACKAGE_SCHEMA.read_text(encoding="utf-8"))
        jsonschema.Draft202012Validator(schema).validate(internal)

        declared = internal["files"]
        declared_names = [item["path"] for item in declared]
        if len(declared_names) != len(set(declared_names)):
            raise ValueError("Hero Unreal package manifest contains duplicate paths")
        actual_names = set(by_name) - {UNREAL_PACKAGE_MANIFEST}
        if set(declared_names) != actual_names:
            missing = sorted(set(declared_names) - actual_names)
            undeclared = sorted(actual_names - set(declared_names))
            raise ValueError(f"Hero Unreal package inventory mismatch: missing={missing}, undeclared={undeclared}")
        if not any(name.endswith(".uasset") for name in declared_names):
            raise ValueError("Hero Unreal package contains no .uasset")
        entry_object = internal["entryAsset"]
        runtime_class = internal["runtimeClass"]
        if runtime_class != entry_object + "_C":
            raise ValueError("Hero Unreal runtimeClass must be the entry Blueprint generated class")
        if internal.get("runtimeContract") != EXPECTED_UNREAL_RUNTIME_CONTRACT:
            raise ValueError("Hero Unreal package does not preserve the Gahyeon runtime wiring contract")
        entry_package = entry_object.rsplit(".", 1)[0]
        expected_entry_file = "Content/" + entry_package.removeprefix("/Game/") + ".uasset"
        if expected_entry_file not in declared_names:
            raise ValueError(
                f"Hero Unreal entryAsset is absent from the inventory: {expected_entry_file}")
        for item in declared:
            if not _safe_unreal_member(item["path"]) or not item["path"].startswith("Content/GahyeonGenerated/"):
                raise ValueError(f"Hero Unreal payload must stay under Content/GahyeonGenerated: {item['path']}")
            info = by_name[item["path"]]
            if info.file_size != item["bytes"]:
                raise ValueError(f"Hero Unreal package byte size mismatch: {item['path']}")
            if _zip_member_digest(archive, info) != item["sha256"]:
                raise ValueError(f"Hero Unreal package checksum mismatch: {item['path']}")
        return internal


def verify(manifest: Path, *, require_approved: bool = False,
           renderer: str | None = None, verify_files: bool = False) -> dict:
    manifest = manifest.resolve()
    payload = json.loads(manifest.read_text(encoding="utf-8"))
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).validate(payload)
    if payload.get("schemaVersion") != 2 or payload.get("characterId") != "gahyeon":
        raise ValueError("Hero manifest must be Gahyeon schema v2")
    if payload.get("status") not in {"draft", "candidate", "approved", "retired"}:
        raise ValueError("Hero manifest has an invalid lifecycle status")
    if payload.get("gate") not in GATES:
        raise ValueError("Hero manifest has an invalid production gate")
    packages = payload.get("packages")
    if not isinstance(packages, list) or not packages:
        raise ValueError("Hero manifest has no renderer packages")
    for package in packages:
        if (not isinstance(package, dict) or package.get("renderer") not in RENDERERS
                or package.get("format") not in FORMATS.get(package.get("renderer"), set())
                or not valid_sha(package.get("sha256"))
                or not isinstance(package.get("bytes"), int) or package["bytes"] <= 0):
            raise ValueError("Hero manifest contains an invalid renderer package")
    missing_visemes = REQUIRED_VISEMES - set(payload.get("semantics", {}).get("visemes", []))
    if missing_visemes:
        raise ValueError(f"Hero manifest is missing visemes: {sorted(missing_visemes)}")
    provenance = payload.get("provenance")
    source_manifests = provenance.get("sourceManifests") if isinstance(provenance, dict) else None
    if not isinstance(source_manifests, list) or len(source_manifests) != 2:
        raise ValueError("Hero manifest must bind identity and modeling manifests")
    if any(not isinstance(item, dict) or not valid_sha(item.get("sha256"))
           for item in source_manifests):
        raise ValueError("Hero source manifest identity is invalid")
    source_kinds = {item.get("kind") for item in source_manifests}
    if source_kinds != {"identity-reference", "modeling-input"}:
        raise ValueError("Hero manifest must bind one identity and one modeling source manifest")

    if require_approved:
        if payload.get("status") != "approved" or payload.get("gate") != "G5":
            raise ValueError("Hero runtime package is not G5-approved")
        if not str(provenance.get("approvedBy", "")).strip() or not provenance.get("approvedAt"):
            raise ValueError("Hero runtime package has no explicit approval")
        evidence = payload.get("acceptanceEvidence")
        evidence_gates = {item.get("gate") for item in evidence if isinstance(item, dict)} \
            if isinstance(evidence, list) else set()
        if (not isinstance(evidence, list) or evidence_gates != GATES
                or len(evidence) != len(GATES)):
            raise ValueError("Hero runtime package requires exactly one G1-G5 evidence item")
        if any(not valid_sha(item.get("sha256")) for item in evidence):
            raise ValueError("Hero acceptance evidence identity is invalid")
        if any(package["sha256"] == "0" * SHA_LENGTH for package in packages):
            raise ValueError("Approved Hero package uses a placeholder checksum")

        g1_item = next(item for item in evidence if item.get("gate") == "G1")
        g1_path = verify_file(manifest, g1_item)
        verify_g1_review(g1_path, require_approved=True)
        g1_payload = json.loads(g1_path.read_text(encoding="utf-8"))
        hero_sources = {item["kind"]: item["sha256"] for item in source_manifests}
        g1_sources = {item["kind"]: item["sha256"]
                      for item in g1_payload["sourceManifests"]}
        if g1_sources != hero_sources:
            raise ValueError("Hero and G1 review bind different source manifests")

        g2_item = next(item for item in evidence if item.get("gate") == "G2")
        g2_path = verify_file(manifest, g2_item)
        verify_g2_review(g2_path, require_approved=True)
        g2_payload = json.loads(g2_path.read_text(encoding="utf-8"))
        if g2_payload["g1Review"]["sha256"] != g1_item["sha256"]:
            raise ValueError("Hero G2 review does not descend from the accepted G1 review")

        g3_item = next(item for item in evidence if item.get("gate") == "G3")
        g3_path = verify_file(manifest, g3_item)
        verify_g3_review(g3_path, require_approved=True)
        g3_payload = json.loads(g3_path.read_text(encoding="utf-8"))
        if g3_payload["g2Review"]["sha256"] != g2_item["sha256"]:
            raise ValueError("Hero G3 review does not descend from the accepted G2 review")

        g4_item = next(item for item in evidence if item.get("gate") == "G4")
        g4_path = verify_file(manifest, g4_item)
        verify_g4_review(g4_path, require_approved=True)
        g4_payload = json.loads(g4_path.read_text(encoding="utf-8"))
        if g4_payload["g3Review"]["sha256"] != g3_item["sha256"]:
            raise ValueError("Hero G4 review does not descend from the accepted G3 review")

        g5_item = next(item for item in evidence if item.get("gate") == "G5")
        g5_path = verify_file(manifest, g5_item)
        verify_g5_review(g5_path, require_approved=True)
        g5_payload = json.loads(g5_path.read_text(encoding="utf-8"))
        if g5_payload["g4Review"]["sha256"] != g4_item["sha256"]:
            raise ValueError("Hero G5 review does not descend from the accepted G4 review")

    selected = packages
    if renderer:
        if renderer not in RENDERERS:
            raise ValueError(f"unsupported renderer: {renderer}")
        selected = [package for package in packages if package["renderer"] == renderer]
        if len(selected) != 1:
            raise ValueError(f"Hero manifest requires exactly one {renderer} package")

    if verify_files:
        for item in source_manifests:
            verify_file(manifest, item)
            source_path = local_path(manifest, item["uri"])
            if item["kind"] == "identity-reference":
                verify_identity(source_path)
            elif item["kind"] == "modeling-input":
                verify_modeling(source_path)
        for item in payload.get("acceptanceEvidence", []):
            verify_file(manifest, item)
        for item in selected:
            verify_file(manifest, item, require_bytes=True)
            if item["renderer"] == "hero-engine" and item["format"] == "unreal-content-zip":
                verify_unreal_content_package(local_path(manifest, item["uri"]))
    return payload


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--require-approved", action="store_true")
    parser.add_argument("--renderer", choices=sorted(RENDERERS))
    parser.add_argument("--verify-files", action="store_true")
    args = parser.parse_args()
    try:
        result = verify(args.manifest, require_approved=args.require_approved,
                        renderer=args.renderer, verify_files=args.verify_files)
    except (ValueError, json.JSONDecodeError, jsonschema.ValidationError, OSError) as error:
        print(f"Hero manifest validation failed: {error}", file=sys.stderr)
        raise SystemExit(2) from None
    print(json.dumps({"valid": True, "status": result["status"],
                      "gate": result["gate"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
