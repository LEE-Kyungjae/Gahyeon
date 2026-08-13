#!/usr/bin/env python3

from __future__ import annotations

import json
import hashlib
import subprocess
import tempfile
import unittest
from pathlib import Path


BUILD = Path(__file__).with_name("build_piper_listening_review.py")
RECORD = Path(__file__).with_name("record_piper_listening_decision.py")


class PiperListeningReviewTest(unittest.TestCase):
    @staticmethod
    def write_checksums(stage: Path) -> None:
        names = [
            "model.onnx", "model.onnx.json", "evaluation-suite.json",
            *(f"eval-{index}.wav" for index in range(1, 6)),
        ]
        (stage / "model.onnx.json").write_text("{}", encoding="utf-8")
        lines = [
            f"{hashlib.sha256((stage / name).read_bytes()).hexdigest()}  {name}\n"
            for name in names
        ]
        (stage / "SHA256SUMS").write_text("".join(lines), encoding="utf-8")

    def fixture(self, root: Path) -> Path:
        candidates = []
        prompts = [f"평가 문장 {index}" for index in range(1, 6)]
        for step in (600, 1200):
            stage = root / "results" / f"step{step}"
            stage.mkdir(parents=True)
            (stage / "model.onnx").write_bytes(f"model-{step}".encode())
            results = []
            for index, prompt in enumerate(prompts, 1):
                audio = stage / f"eval-{index}.wav"
                audio.write_bytes(f"audio-{step}-{index}".encode())
                results.append({"audio": str(audio), "expected": prompt})
            (stage / "evaluation-suite.json").write_text(
                json.dumps({"results": results}), encoding="utf-8")
            self.write_checksums(stage)
            candidates.append({"step": step, "model": str(stage / "model.onnx")})
        baseline = root / "results" / "baseline"
        baseline.mkdir(parents=True)
        (baseline / "model.onnx").write_bytes(b"baseline-model")
        baseline_results = []
        for index, prompt in enumerate(prompts, 1):
            audio = baseline / f"eval-{index}.wav"
            audio.write_bytes(f"baseline-audio-{index}".encode())
            baseline_results.append({"audio": str(audio), "expected": prompt})
        (baseline / "evaluation-suite.json").write_text(
            json.dumps({"results": baseline_results}), encoding="utf-8")
        self.write_checksums(baseline)
        completion = root / "piper_training_complete.json"
        completion.write_text(json.dumps({
            "status": "complete", "ranking": {
                "candidates": candidates,
                "baseline": {"model": str(baseline / "model.onnx")},
            },
        }), encoding="utf-8")
        return completion

    def test_builds_blind_sheet_and_records_only_an_all_gate_pass(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            completion = self.fixture(root)
            review = root / "review"
            built = subprocess.run([
                "python3", str(BUILD), "--completion", str(completion),
                "--output", str(review),
            ], text=True, capture_output=True)
            self.assertEqual(built.returncode, 0, built.stderr)
            page = (review / "index.html").read_text(encoding="utf-8")
            self.assertIn("후보 A", page)
            self.assertNotIn("step600", page)
            key = json.loads((review / "review-key.json").read_text(encoding="utf-8"))
            self.assertEqual({item["step"] for item in key["candidates"]}, {None, 600, 1200})
            selected = next(item for item in key["candidates"] if item["kind"] == "candidate")
            decision = root / "decision.json"
            incomplete = subprocess.run([
                "python3", str(RECORD), "--review-key", str(review / "review-key.json"),
                "--selected-label", selected["label"], "--approved-by", "owner",
                "--identity-pass", "--output", str(decision),
            ], text=True, capture_output=True)
            self.assertNotEqual(incomplete.returncode, 0)
            self.assertFalse(decision.exists())
            approved = subprocess.run([
                "python3", str(RECORD), "--review-key", str(review / "review-key.json"),
                "--selected-label", selected["label"], "--approved-by", "owner",
                "--identity-pass", "--pronunciation-pass", "--naturalness-pass",
                "--artifact-pass", "--output", str(decision),
            ], text=True, capture_output=True)
            self.assertEqual(approved.returncode, 0, approved.stderr)
            payload = json.loads(decision.read_text(encoding="utf-8"))
            self.assertEqual(payload["selectedStep"], selected["step"])
            self.assertEqual(payload["selectedKind"], "candidate")
            self.assertEqual(payload["modelSha256"], selected["modelSha256"])
            self.assertTrue(all(payload["gates"].values()))

    def test_refuses_blind_review_when_evaluation_was_changed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            completion = self.fixture(root)
            (root / "results" / "step600" / "evaluation-suite.json").write_text(
                '{"results":[]}', encoding="utf-8")
            built = subprocess.run([
                "python3", str(BUILD), "--completion", str(completion),
                "--output", str(root / "review"),
            ], text=True, capture_output=True)
            self.assertNotEqual(built.returncode, 0)
            self.assertIn("checksum mismatch", built.stderr)


if __name__ == "__main__":
    unittest.main()
