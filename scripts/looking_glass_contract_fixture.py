"""Deterministic contract fixtures; these never represent physical acceptance evidence."""

from __future__ import annotations

import hashlib
from pathlib import Path


def write_fixture_quilt(path: Path, width: int, height: int, marker: str) -> None:
    payload = (
        b"\x89PNG\r\n\x1a\n" + (13).to_bytes(4, "big") + b"IHDR"
        + width.to_bytes(4, "big") + height.to_bytes(4, "big")
        + b"\x08\x06\x00\x00\x00" + marker.encode("utf-8")
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)


def fixture_attestation(
    path: Path,
    *,
    uri: str,
    run_id: str,
    mode: str,
    views: int = 66,
    quilt_width: int = 4092,
    quilt_height: int = 4092,
) -> dict:
    return {
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
        "captureEvidence": {
            "uri": uri,
            "bytes": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        },
    }
