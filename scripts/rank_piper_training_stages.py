#!/usr/bin/env python3
"""Rank staged Piper candidates from multi-prompt objective evaluations."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


def score(summary: dict) -> float:
    return (
        float(summary["meanSpeakerSimilarity"])
        - 0.6 * float(summary["meanCer"])
        - 0.2 * float(summary["maxCer"])
        - (0.5 if not summary["hardPass"] else 0.0)
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    baseline_evaluation = args.root / "baseline" / "evaluation-suite.json"
    if not baseline_evaluation.is_file():
        raise RuntimeError(f"missing baseline evaluation: {baseline_evaluation}")
    baseline_summary = json.loads(baseline_evaluation.read_text(encoding="utf-8"))["summary"]
    baseline_score = score(baseline_summary)
    baseline = {
        "kind": "baseline",
        "alias": "ze69-blend-fp32-step420",
        "score": round(baseline_score, 6),
        "hardPass": bool(baseline_summary["hardPass"]),
        "meanCer": baseline_summary["meanCer"],
        "maxCer": baseline_summary["maxCer"],
        "meanSpeakerSimilarity": baseline_summary["meanSpeakerSimilarity"],
        "minSpeakerSimilarity": baseline_summary["minSpeakerSimilarity"],
        "evaluation": str(baseline_evaluation),
        "model": str(baseline_evaluation.parent / "model.onnx"),
        "samples": sorted(str(path) for path in baseline_evaluation.parent.glob("eval-*.wav")),
    }
    candidates = []
    for evaluation in args.root.glob("step*/evaluation-suite.json"):
        match = re.fullmatch(r"step(\d+)", evaluation.parent.name)
        if not match:
            continue
        payload = json.loads(evaluation.read_text(encoding="utf-8"))
        summary = payload["summary"]
        candidate_score = score(summary)
        objective_no_regression = (
            bool(summary["hardPass"])
            and float(summary["meanCer"]) <= float(baseline_summary["meanCer"]) + 0.02
            and float(summary["maxCer"]) <= float(baseline_summary["maxCer"]) + 0.05
            and float(summary["meanSpeakerSimilarity"])
                >= float(baseline_summary["meanSpeakerSimilarity"])
            and float(summary["minSpeakerSimilarity"])
                >= float(baseline_summary["minSpeakerSimilarity"]) - 0.02
            and candidate_score >= baseline_score
        )
        candidates.append({
            "kind": "candidate",
            "step": int(match.group(1)),
            "score": round(candidate_score, 6),
            "hardPass": bool(summary["hardPass"]),
            "objectiveNoRegression": objective_no_regression,
            "meanCer": summary["meanCer"],
            "maxCer": summary["maxCer"],
            "meanSpeakerSimilarity": summary["meanSpeakerSimilarity"],
            "minSpeakerSimilarity": summary["minSpeakerSimilarity"],
            "evaluation": str(evaluation),
            "model": str(evaluation.parent / "model.onnx"),
            "samples": sorted(str(path) for path in evaluation.parent.glob("eval-*.wav")),
        })
    candidates.sort(
        key=lambda item: (item["objectiveNoRegression"], item["hardPass"], item["score"]),
        reverse=True,
    )
    if not candidates:
        raise RuntimeError(f"no staged evaluations under {args.root}")
    output = args.output or (args.root / "ranking.json")
    result = {
        "recommendedStep": next(
            (item["step"] for item in candidates if item["objectiveNoRegression"]), None),
        "recommendation": "objective no-regression preselection; baseline must also lose blind listening",
        "baseline": baseline,
        "candidates": candidates,
    }
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
