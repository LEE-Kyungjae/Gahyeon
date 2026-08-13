#!/usr/bin/env python3
"""Build a deterministic blind listening sheet for staged Piper candidates."""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import os
import random
import shutil
import tempfile
from pathlib import Path


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def verify_stage(stage: Path) -> dict[str, str]:
    checksum_file = stage / "SHA256SUMS"
    if not checksum_file.is_file():
        raise RuntimeError(f"missing candidate checksums: {checksum_file}")
    verified: dict[str, str] = {}
    for line in checksum_file.read_text(encoding="utf-8").splitlines():
        expected, separator, filename = line.partition("  ")
        if (not separator or len(expected) != 64 or not filename
                or Path(filename).name != filename):
            raise RuntimeError(f"invalid candidate checksum entry: {line!r}")
        artifact = stage / filename
        if not artifact.is_file() or digest(artifact) != expected:
            raise RuntimeError(f"candidate checksum mismatch: {artifact}")
        verified[filename] = expected
    required = {
        "model.onnx", "model.onnx.json", "evaluation-suite.json",
        *(f"eval-{index}.wav" for index in range(1, 6)),
    }
    missing = sorted(required - verified.keys())
    if missing:
        raise RuntimeError(f"candidate checksum set is incomplete: {missing}")
    return verified


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--completion", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    completion = json.loads(args.completion.read_text(encoding="utf-8"))
    if completion.get("status") != "complete":
        raise RuntimeError("Piper training is not complete")
    candidates = completion.get("ranking", {}).get("candidates", [])
    if len(candidates) < 2:
        raise RuntimeError("blind review requires at least two candidates")
    prepared = []
    for candidate in candidates:
        stage = Path(candidate["model"]).resolve().parent
        model = stage / "model.onnx"
        evaluation_path = stage / "evaluation-suite.json"
        if not model.is_file() or not evaluation_path.is_file():
            raise RuntimeError(f"candidate artifacts are incomplete: {stage}")
        verified = verify_stage(stage)
        evaluation = json.loads(evaluation_path.read_text(encoding="utf-8"))
        results = evaluation.get("results", [])
        if len(results) != 5:
            raise RuntimeError(f"candidate must have five listening cases: {stage}")
        samples = []
        for result in results:
            source = stage / Path(result["audio"]).name
            if not source.is_file():
                raise RuntimeError(f"missing listening sample: {source}")
            samples.append((str(result["expected"]), source))
        prepared.append({"kind": "candidate", "step": int(candidate["step"]),
                         "modelSha256": verified["model.onnx"], "samples": samples})
    baseline = completion.get("ranking", {}).get("baseline")
    if not baseline:
        raise RuntimeError("blind review requires the operating baseline")
    baseline_stage = Path(baseline["model"]).resolve().parent
    baseline_model = baseline_stage / "model.onnx"
    baseline_evaluation_path = baseline_stage / "evaluation-suite.json"
    if not baseline_model.is_file() or not baseline_evaluation_path.is_file():
        raise RuntimeError(f"baseline artifacts are incomplete: {baseline_stage}")
    baseline_verified = verify_stage(baseline_stage)
    baseline_results = json.loads(
        baseline_evaluation_path.read_text(encoding="utf-8")).get("results", [])
    if len(baseline_results) != 5:
        raise RuntimeError("baseline must have five listening cases")
    baseline_samples = []
    for result in baseline_results:
        source = baseline_stage / Path(result["audio"]).name
        if not source.is_file():
            raise RuntimeError(f"missing baseline listening sample: {source}")
        baseline_samples.append((str(result["expected"]), source))
    prepared.append({"kind": "baseline", "step": None,
                     "modelSha256": baseline_verified["model.onnx"], "samples": baseline_samples})
    seed_material = "|".join(sorted(item["modelSha256"] for item in prepared))
    review_id = hashlib.sha256(seed_material.encode()).hexdigest()[:16]
    random.Random(int(review_id, 16)).shuffle(prepared)
    labels = [chr(ord("A") + index) for index in range(len(prepared))]

    destination = args.output.resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=f".{destination.name}-", dir=destination.parent))
    try:
        rows = []
        for case_index in range(5):
            expected = prepared[0]["samples"][case_index][0]
            if any(item["samples"][case_index][0] != expected for item in prepared):
                raise RuntimeError("candidate listening prompts do not match")
            cells = []
            for label, item in zip(labels, prepared):
                media_dir = temporary / "media" / label
                media_dir.mkdir(parents=True, exist_ok=True)
                target = media_dir / f"case-{case_index + 1}.wav"
                shutil.copy2(item["samples"][case_index][1], target)
                cells.append(
                    f'<td><audio controls preload="none" src="media/{label}/{target.name}"></audio></td>'
                )
            rows.append(f"<tr><th>{case_index + 1}. {html.escape(expected)}</th>{''.join(cells)}</tr>")
        headings = "".join(f"<th>후보 {label}</th>" for label in labels)
        page = f"""<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><title>Gahyeon Piper Blind Review</title>
<style>body{{font-family:sans-serif;max-width:1500px;margin:32px auto;padding:0 20px}}
table{{border-collapse:collapse;width:100%}}th,td{{border:1px solid #ccc;padding:12px;vertical-align:top}}
th:first-child{{max-width:360px;text-align:left}}audio{{width:220px}}li{{margin:.5em 0}}</style></head>
<body><h1>Gahyeon Piper 블라인드 청취</h1><p>Review ID: <code>{review_id}</code></p>
<ol><li>가능하면 같은 헤드폰과 볼륨으로 각 행을 여러 번 듣습니다.</li>
<li>정체성, 발음, 자연스러움, 울렁임·금속성·끊김 부재를 각각 평가합니다.</li>
<li>자동 점수나 step은 공개하지 않습니다. 모든 기준을 통과한 후보 하나만 선택합니다.</li></ol>
<table><thead><tr><th>문장</th>{headings}</tr></thead><tbody>{''.join(rows)}</tbody></table></body></html>"""
        (temporary / "index.html").write_text(page, encoding="utf-8")
        key = {
            "schemaVersion": 1, "reviewId": review_id,
            "completion": str(args.completion.resolve()),
            "candidates": [
                {"label": label, "kind": item["kind"], "step": item["step"],
                 "modelSha256": item["modelSha256"]}
                for label, item in zip(labels, prepared)
            ],
        }
        (temporary / "review-key.json").write_text(
            json.dumps(key, ensure_ascii=False, indent=2), encoding="utf-8")
        if destination.exists():
            existing = destination / "review-key.json"
            if existing.is_file() and json.loads(existing.read_text())["reviewId"] == review_id:
                shutil.rmtree(temporary)
                print(json.dumps({"status": "already-built", "review": str(destination)}, ensure_ascii=False))
                return
            raise RuntimeError(f"review destination already exists: {destination}")
        os.replace(temporary, destination)
    except BaseException:
        shutil.rmtree(temporary, ignore_errors=True)
        raise
    print(json.dumps({"status": "built", "review": str(destination),
                      "reviewId": review_id}, ensure_ascii=False))


if __name__ == "__main__":
    main()
