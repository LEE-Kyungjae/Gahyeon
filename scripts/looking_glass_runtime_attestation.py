#!/usr/bin/env python3
"""Validate runtime-derived Looking Glass adapter attestation and quilt evidence."""

from __future__ import annotations

import copy
import hashlib
import re
from pathlib import Path


RUN_ID = re.compile(r"^[a-z0-9][a-z0-9_-]{7,63}$")
SHA256 = re.compile(r"^[a-f0-9]{64}$")
ATTESTATION_KEYS = {
    "schemaVersion", "source", "pluginRelease", "runtimeModule",
    "measurementRunId", "mode", "deviceClass",
    "views", "quiltWidth", "quiltHeight", "playerActive",
    "physicalDeviceActive", "captureComponentActive", "quiltFrameObserved",
    "captureEvidence",
}
EVIDENCE_KEYS = {"uri", "bytes", "sha256"}


def _safe_source(base: Path, uri: object) -> Path:
    if not isinstance(uri, str) or not uri:
        raise ValueError("quilt evidence URI is missing")
    relative = Path(uri)
    if relative.is_absolute() or ".." in relative.parts or relative.suffix.lower() != ".png":
        raise ValueError("quilt evidence must be a local PNG")
    base = base.resolve()
    source = (base / relative).resolve()
    if base != source.parent and base not in source.parents:
        raise ValueError("quilt evidence escapes its workspace")
    if not source.is_file() or source.is_symlink():
        raise ValueError(f"quilt evidence is missing or unsafe: {uri}")
    return source


def _png_dimensions(path: Path) -> tuple[int, int]:
    header = path.read_bytes()[:24]
    if len(header) != 24 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
        raise ValueError("quilt evidence is not a PNG with an IHDR")
    return int.from_bytes(header[16:20], "big"), int.from_bytes(header[20:24], "big")


def verify_runtime_attestation(
    value: object,
    *,
    run_id: str,
    mode: str,
    views: int,
    quilt_width: int,
    quilt_height: int,
    evidence_base: Path,
) -> Path:
    if not isinstance(value, dict) or set(value) != ATTESTATION_KEYS:
        raise ValueError("runtime presentation attestation is missing or has unknown fields")
    expected = {
        "schemaVersion": 1,
        "source": "GahyeonLookingGlassAdapter",
        "pluginRelease": "2.1.1",
        "runtimeModule": "LookingGlassRuntime",
        "measurementRunId": run_id,
        "mode": mode,
        "deviceClass": "Looking Glass Go",
        "views": views,
        "quiltWidth": quilt_width,
        "quiltHeight": quilt_height,
        "playerActive": True,
        "physicalDeviceActive": True,
        "captureComponentActive": True,
        "quiltFrameObserved": True,
    }
    if not RUN_ID.fullmatch(run_id) or any(value.get(key) != item for key, item in expected.items()):
        raise ValueError("runtime presentation attestation does not match the physical run")
    evidence = value["captureEvidence"]
    if not isinstance(evidence, dict) or set(evidence) != EVIDENCE_KEYS:
        raise ValueError("runtime presentation attestation has invalid quilt evidence")
    source = _safe_source(evidence_base, evidence.get("uri"))
    size = source.stat().st_size
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    if (isinstance(evidence.get("bytes"), bool)
            or not isinstance(evidence.get("bytes"), int)
            or evidence["bytes"] != size or size < 24
            or not isinstance(evidence.get("sha256"), str)
            or not SHA256.fullmatch(evidence["sha256"])
            or evidence["sha256"] != digest):
        raise ValueError("quilt evidence size or SHA-256 does not match")
    if _png_dimensions(source) != (quilt_width, quilt_height):
        raise ValueError("quilt evidence dimensions do not match active capture settings")
    return source


def rebase_runtime_attestation(value: dict, source: Path, destination_base: Path) -> dict:
    destination_base = destination_base.resolve()
    source = source.resolve()
    try:
        relative = source.relative_to(destination_base).as_posix()
    except ValueError as error:
        raise ValueError("quilt evidence must remain inside the acceptance workspace") from error
    result = copy.deepcopy(value)
    result["captureEvidence"]["uri"] = relative
    return result
