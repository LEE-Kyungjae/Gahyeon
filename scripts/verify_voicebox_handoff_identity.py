#!/usr/bin/env python3
"""Verify that a Piper handoff is bound to the current frozen Voicebox sources."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Optional


def sha256(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def verify(ready: Path, catalog: Path, manifest: Path, min_clips: int = 4000,
           archive: Optional[Path] = None) -> dict:
    payload = json.loads(ready.read_text(encoding="utf-8"))
    expected = {
        "catalogSha256": sha256(catalog),
        "manifestSha256": sha256(manifest),
        "completed": 5000,
    }
    if payload.get("sourceIdentity") != expected:
        raise ValueError("Piper handoff does not match the current 5,000-clip source identity")
    if payload.get("catalogDiversity", {}).get("ready") is not True:
        raise ValueError("Piper handoff catalog diversity gate is not ready")
    if int(payload.get("package", {}).get("clips", 0)) < min_clips:
        raise ValueError("Piper handoff contains too few QC-accepted clips")
    if archive is not None:
        package = payload.get("package", {})
        if not archive.is_file():
            raise ValueError("Piper handoff dataset archive is missing")
        if archive.stat().st_size != package.get("archive_bytes"):
            raise ValueError("Piper handoff dataset archive byte size mismatch")
        if sha256(archive) != package.get("archive_sha256"):
            raise ValueError("Piper handoff dataset archive checksum mismatch")
    return payload


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ready", type=Path, required=True)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--min-clips", type=int, default=4000)
    parser.add_argument("--archive", type=Path)
    args = parser.parse_args()
    try:
        payload = verify(args.ready, args.catalog, args.manifest, args.min_clips, args.archive)
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        raise SystemExit(str(error)) from error
    print(json.dumps({"ready": True, "clips": payload["package"]["clips"]}))


if __name__ == "__main__":
    main()
