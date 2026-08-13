#!/usr/bin/env python3
"""Record an explicit all-gates-pass decision for one blind Piper candidate."""

from __future__ import annotations

import argparse
import json
import os
import tempfile
from datetime import datetime, timezone
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--review-key", type=Path, required=True)
    parser.add_argument("--selected-label", required=True)
    parser.add_argument("--approved-by", required=True)
    parser.add_argument("--identity-pass", action="store_true")
    parser.add_argument("--pronunciation-pass", action="store_true")
    parser.add_argument("--naturalness-pass", action="store_true")
    parser.add_argument("--artifact-pass", action="store_true",
                        help="No unacceptable wobble, metallic sound, clipping, or cadence break")
    parser.add_argument("--notes", default="")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    gates = {
        "identity": args.identity_pass, "pronunciation": args.pronunciation_pass,
        "naturalness": args.naturalness_pass, "artifactFree": args.artifact_pass,
    }
    if not all(gates.values()):
        raise SystemExit(f"refusing approval while listening gates are incomplete: {gates}")
    approved_by = args.approved_by.strip()
    if not approved_by:
        raise SystemExit("--approved-by must not be blank")
    key = json.loads(args.review_key.read_text(encoding="utf-8"))
    label = args.selected_label.strip().upper()
    candidate = next((item for item in key["candidates"] if item["label"] == label), None)
    if candidate is None:
        raise RuntimeError(f"unknown blind candidate label: {label}")
    decision = {
        "schemaVersion": 1, "status": "approved", "reviewId": key["reviewId"],
        "completion": key["completion"], "selectedLabel": label,
        "selectedKind": candidate["kind"], "selectedStep": candidate.get("step"),
        "modelSha256": candidate["modelSha256"],
        "gates": gates, "approvedBy": approved_by, "notes": args.notes.strip(),
        "approvedAt": datetime.now(timezone.utc).isoformat(),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.exists():
        existing = json.loads(args.output.read_text(encoding="utf-8"))
        stable = ("reviewId", "selectedStep", "modelSha256", "approvedBy")
        if all(existing.get(name) == decision.get(name) for name in stable):
            print(json.dumps(existing, ensure_ascii=False)); return
        raise RuntimeError(f"decision already exists with different approval: {args.output}")
    descriptor, temporary_name = tempfile.mkstemp(dir=args.output.parent, text=True)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        json.dump(decision, handle, ensure_ascii=False, indent=2)
        handle.flush(); os.fsync(handle.fileno())
    os.replace(temporary_name, args.output)
    print(json.dumps(decision, ensure_ascii=False))


if __name__ == "__main__":
    main()
