#!/usr/bin/env python3
"""Atomically record a validated local view of remote Piper training state."""

import argparse
import datetime
import json
import os
import re
import tempfile
from pathlib import Path


STEPS = (600, 1200, 2400, 3600, 4800)


def build(submitted: dict, state: str, details: dict) -> dict:
    digest = submitted.get("sha256")
    remote_root = submitted.get("remoteRoot")
    if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("submitted dataset identity is invalid")
    if not isinstance(remote_root, str) or not remote_root.startswith(
            "/home/ubuntu/piper-voice/voicebox-diverse5000-"):
        raise ValueError("submitted remote root is invalid")
    if state not in {"submitted", "training", "failed", "complete"}:
        raise ValueError("unsupported Piper status")

    if state == "training":
        completed = details.get("completedSteps")
        active = details.get("activeStep")
        phase = details.get("phase")
        if not isinstance(completed, list) or any(step not in STEPS for step in completed):
            raise ValueError("training status has invalid completed steps")
        if completed != sorted(set(completed), key=STEPS.index):
            raise ValueError("training completed steps must be unique and ordered")
        if active is not None and active not in STEPS:
            raise ValueError("training status has invalid active step")
        if active in completed:
            raise ValueError("active step cannot already be complete")
        allowed_phases = {
            "speaker_consistency_qc", "baseline_evaluation", "completion_manifest"}
        if active is not None:
            allowed_phases.update({
                f"training_step_{active}", f"export_step_{active}",
                f"evaluation_step_{active}",
            })
        if phase not in allowed_phases:
            raise ValueError("training status has an invalid phase")
    elif details.get("datasetSha256") not in {None, digest}:
        raise ValueError("status details belong to another dataset")

    return {
        "schemaVersion": 1,
        "status": state,
        "checkedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "datasetSha256": digest,
        "remoteRoot": remote_root,
        "details": details,
    }


def write(output: Path, submitted_path: Path, state: str, details: dict) -> dict:
    submitted = json.loads(submitted_path.read_text(encoding="utf-8"))
    payload = build(submitted, state, details)
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(dir=output.parent, text=True)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, output)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return payload


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--submitted", type=Path, required=True)
    parser.add_argument("--state", required=True)
    parser.add_argument("--details-json", default="{}")
    args = parser.parse_args()
    details = json.loads(args.details_json)
    if not isinstance(details, dict):
        raise SystemExit("details must be a JSON object")
    payload = write(args.output, args.submitted, args.state, details)
    print(json.dumps(payload, ensure_ascii=False))


if __name__ == "__main__":
    main()
