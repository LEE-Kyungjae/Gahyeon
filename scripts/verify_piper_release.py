#!/usr/bin/env python3
"""Verify every artifact in an immutable approved Piper runtime release."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path, PurePosixPath


REQUIRED = {
    "voice.onnx",
    "voice.onnx.json",
    "evaluation-suite.json",
    "listening-decision.json",
    *(f"listening-samples/eval-{index}.wav" for index in range(1, 6)),
}


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def verify(release: Path) -> dict:
    release = release.resolve()
    manifest = release / "release.json"
    payload = json.loads(manifest.read_text(encoding="utf-8"))
    alias = payload.get("modelAlias")
    if (payload.get("schemaVersion") != 2 or payload.get("status") != "approved"
            or not isinstance(alias, str) or not re.fullmatch(r"[A-Za-z0-9._-]+", alias)):
        raise ValueError("Piper release is not an approved schema v2 bundle")
    artifacts = payload.get("artifacts")
    if not isinstance(artifacts, dict) or set(artifacts) != REQUIRED:
        raise ValueError("Piper release artifact inventory is incomplete or unexpected")
    actual = {
        path.relative_to(release).as_posix()
        for path in release.rglob("*") if path.is_file()
    } - {"release.json"}
    if actual != REQUIRED:
        raise ValueError("Piper release files do not match its artifact inventory")
    for name, identity in artifacts.items():
        path = PurePosixPath(name)
        if path.is_absolute() or ".." in path.parts or not isinstance(identity, dict):
            raise ValueError(f"Piper release contains an unsafe artifact identity: {name}")
        artifact = release / name
        if artifact.is_symlink() or not artifact.is_file():
            raise ValueError(f"Piper release artifact is missing or unsafe: {name}")
        if artifact.stat().st_size != identity.get("bytes"):
            raise ValueError(f"Piper release artifact byte size mismatch: {name}")
        if digest(artifact) != identity.get("sha256"):
            raise ValueError(f"Piper release artifact checksum mismatch: {name}")
    if artifacts["voice.onnx"]["sha256"] != payload.get("modelSha256"):
        raise ValueError("Piper release model identity is inconsistent")
    if artifacts["voice.onnx.json"]["sha256"] != payload.get("configSha256"):
        raise ValueError("Piper release config identity is inconsistent")
    if artifacts["listening-decision.json"]["sha256"] != payload.get("listeningDecisionSha256"):
        raise ValueError("Piper release listening decision identity is inconsistent")
    return payload


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("release", type=Path)
    args = parser.parse_args()
    payload = verify(args.release)
    print(json.dumps({"valid": True, "modelAlias": payload["modelAlias"],
                      "modelSha256": payload["modelSha256"],
                      "configSha256": payload["configSha256"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
