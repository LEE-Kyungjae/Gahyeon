#!/usr/bin/env python3
"""Verify Piper submission and completion markers belong to one dataset handoff."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


def load(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"marker must be a JSON object: {path}")
    return value


def verify_submission(ready: dict, submitted: dict) -> str:
    digest = ready.get("package", {}).get("archive_sha256")
    if not isinstance(digest, str) or re.fullmatch(r"[0-9a-f]{64}", digest) is None:
        raise ValueError("Piper handoff archive identity is invalid")
    if submitted.get("sha256") != digest:
        raise ValueError("Piper submission identity does not match current handoff")
    expected_root = f"/home/ubuntu/piper-voice/voicebox-diverse5000-{digest[:12]}"
    if submitted.get("remoteRoot") != expected_root:
        raise ValueError("Piper submission remote root does not match current handoff")
    return digest


def verify_remote_completion(submitted: dict, remote_complete: dict) -> None:
    if remote_complete.get("datasetSha256") != submitted.get("sha256"):
        raise ValueError("remote Piper completion does not match submitted dataset")


def verify_local_completion(ready: dict, submitted: dict, complete: dict) -> None:
    verify_remote_completion(submitted, complete.get("remote", {}))
    if complete.get("sourceIdentity") != ready.get("sourceIdentity"):
        raise ValueError("local Piper completion source identity is stale")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ready", type=Path, required=True)
    parser.add_argument("--submitted", type=Path, required=True)
    parser.add_argument("--remote-complete", type=Path)
    parser.add_argument("--local-complete", type=Path)
    parser.add_argument("--field", choices=("archiveSha256",))
    args = parser.parse_args()
    try:
        ready = load(args.ready)
        submitted = load(args.submitted)
        digest = verify_submission(ready, submitted)
        if args.remote_complete:
            verify_remote_completion(submitted, load(args.remote_complete))
        if args.local_complete:
            verify_local_completion(ready, submitted, load(args.local_complete))
    except (OSError, ValueError, TypeError, json.JSONDecodeError) as error:
        raise SystemExit(str(error)) from error
    print(digest if args.field else json.dumps({"ready": True, "archiveSha256": digest}))


if __name__ == "__main__":
    main()
