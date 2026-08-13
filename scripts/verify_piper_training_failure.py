#!/usr/bin/env python3
"""Verify that a remote Piper failure marker belongs to the submitted dataset."""

import argparse
import json
import re
from pathlib import Path


STAGE = re.compile(
    r"^(preflight|speaker_consistency_qc|baseline_evaluation|completion_manifest|"
    r"(?:training|export|evaluation)_step_(?:600|1200|2400|3600|4800))$"
)


def verify(failure_path: Path, submitted_path: Path) -> dict:
    failure = json.loads(failure_path.read_text(encoding="utf-8"))
    submitted = json.loads(submitted_path.read_text(encoding="utf-8"))
    if failure.get("status") != "failed":
        raise ValueError("invalid remote Piper failure marker status")
    dataset_sha = failure.get("datasetSha256")
    if not isinstance(dataset_sha, str) or not re.fullmatch(r"[0-9a-f]{64}", dataset_sha):
        raise ValueError("invalid remote Piper failure dataset identity")
    if dataset_sha != submitted.get("sha256"):
        raise ValueError("remote Piper failure marker belongs to another dataset")
    stage = failure.get("stage")
    if not isinstance(stage, str) or not STAGE.fullmatch(stage):
        raise ValueError("remote Piper failure marker has an unsupported stage")
    exit_code = failure.get("exitCode")
    if not isinstance(exit_code, int) or isinstance(exit_code, bool) or not 1 <= exit_code <= 255:
        raise ValueError("remote Piper failure marker has an invalid exit code")
    if not isinstance(failure.get("failedAt"), str) or not failure["failedAt"]:
        raise ValueError("remote Piper failure marker has no timestamp")
    if not isinstance(failure.get("command"), str):
        raise ValueError("remote Piper failure marker has no command")
    return failure


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--failure", type=Path, required=True)
    parser.add_argument("--submitted", type=Path, required=True)
    args = parser.parse_args()
    failure = verify(args.failure, args.submitted)
    print(f"Piper failed at {failure['stage']} (exit {failure['exitCode']}): {failure['command']}")


if __name__ == "__main__":
    main()
