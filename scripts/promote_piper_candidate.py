#!/usr/bin/env python3
"""Promote one listened-to Piper stage into an immutable runtime release bundle."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from verify_piper_release import REQUIRED as RELEASE_ARTIFACTS
from verify_piper_release import digest as release_digest
from verify_piper_release import verify as verify_release


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def verify_stage(stage: Path) -> dict[str, str]:
    checksum_file = stage / "SHA256SUMS"
    if not checksum_file.is_file():
        raise RuntimeError(f"missing stage checksums: {checksum_file}")
    verified: dict[str, str] = {}
    for line in checksum_file.read_text(encoding="utf-8").splitlines():
        expected, separator, filename = line.partition("  ")
        if not separator or len(expected) != 64 or not filename or Path(filename).name != filename:
            raise RuntimeError(f"invalid checksum entry: {line!r}")
        artifact = stage / filename
        if not artifact.is_file() or digest(artifact) != expected:
            raise RuntimeError(f"checksum mismatch: {artifact}")
        verified[filename] = expected
    required = {
        "model.onnx", "model.onnx.json", "evaluation-suite.json",
        *(f"eval-{index}.wav" for index in range(1, 6)),
    }
    missing = sorted(required - verified.keys())
    if missing:
        raise RuntimeError(f"stage checksum set is incomplete: {missing}")
    return verified


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--completion", type=Path, required=True)
    parser.add_argument("--step", type=int, required=True)
    parser.add_argument("--output-root", type=Path, required=True)
    parser.add_argument("--decision", type=Path, required=True)
    args = parser.parse_args()

    completion = json.loads(args.completion.read_text(encoding="utf-8"))
    if completion.get("status") != "complete":
        raise RuntimeError("Piper training completion is not authoritative")
    candidates = completion.get("ranking", {}).get("candidates", [])
    candidate = next((item for item in candidates if int(item.get("step", -1)) == args.step), None)
    if candidate is None:
        raise RuntimeError(f"step {args.step} is not a ranked candidate")
    if not candidate.get("hardPass"):
        raise RuntimeError(f"step {args.step} did not pass the objective hard gate")
    if not candidate.get("objectiveNoRegression"):
        raise RuntimeError(f"step {args.step} regressed against the operating baseline")

    stage = Path(candidate["model"]).resolve().parent
    verified = verify_stage(stage)
    decision = json.loads(args.decision.read_text(encoding="utf-8"))
    if (decision.get("status") != "approved"
            or decision.get("selectedKind") != "candidate"
            or int(decision.get("selectedStep", -1)) != args.step
            or decision.get("modelSha256") != verified["model.onnx"]
            or Path(decision.get("completion", "")).resolve() != args.completion.resolve()
            or not all(decision.get("gates", {}).get(name) is True for name in (
                "identity", "pronunciation", "naturalness", "artifactFree"))):
        raise RuntimeError("blind listening decision does not authorize this candidate")
    approved_by = str(decision.get("approvedBy", "")).strip()
    if not approved_by:
        raise RuntimeError("blind listening decision has no approver")
    evaluation = stage / "evaluation-suite.json"
    if not evaluation.is_file():
        raise RuntimeError(f"missing evaluation: {evaluation}")
    model_sha = verified["model.onnx"]
    alias = f"gahyeon-voicebox-diverse5000-step{args.step}-{model_sha[:12]}"
    destination = args.output_root.resolve() / alias
    if destination.exists():
        try:
            existing = verify_release(destination)
        except (OSError, ValueError, json.JSONDecodeError):
            existing = None
        if existing is not None and existing.get("modelSha256") == model_sha:
            print(json.dumps({"status": "already-promoted", "alias": alias,
                              "release": str(destination)}, ensure_ascii=False))
            return
        raise RuntimeError(f"release destination already exists: {destination}")

    args.output_root.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=f".{alias}-", dir=args.output_root))
    try:
        shutil.copy2(stage / "model.onnx", temporary / "voice.onnx")
        shutil.copy2(stage / "model.onnx.json", temporary / "voice.onnx.json")
        shutil.copy2(evaluation, temporary / "evaluation-suite.json")
        shutil.copy2(args.decision, temporary / "listening-decision.json")
        sample_dir = temporary / "listening-samples"
        sample_dir.mkdir()
        for index in range(1, 6):
            shutil.copy2(stage / f"eval-{index}.wav", sample_dir / f"eval-{index}.wav")
        artifacts = {
            name: {"bytes": (temporary / name).stat().st_size,
                   "sha256": release_digest(temporary / name)}
            for name in sorted(RELEASE_ARTIFACTS)
        }
        release = {
            "schemaVersion": 2,
            "status": "approved",
            "modelAlias": alias,
            "step": args.step,
            "modelSha256": model_sha,
            "configSha256": verified["model.onnx.json"],
            "approvedBy": approved_by,
            "approvedAt": datetime.now(timezone.utc).isoformat(),
            "listeningDecision": str(args.decision.resolve()),
            "listeningDecisionSha256": digest(temporary / "listening-decision.json"),
            "reviewId": decision.get("reviewId"),
            "sourceCompletion": str(args.completion.resolve()),
            "objectiveMetrics": {
                key: candidate[key] for key in (
                    "score", "meanCer", "maxCer", "meanSpeakerSimilarity", "minSpeakerSimilarity"
                )
            },
            "artifacts": artifacts,
        }
        (temporary / "release.json").write_text(
            json.dumps(release, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        verify_release(temporary)
        os.replace(temporary, destination)
    except BaseException:
        shutil.rmtree(temporary, ignore_errors=True)
        raise
    print(json.dumps({"status": "promoted", "alias": alias,
                      "release": str(destination)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
