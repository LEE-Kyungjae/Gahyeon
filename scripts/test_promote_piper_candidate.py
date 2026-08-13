#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("promote_piper_candidate.py")


class PromotePiperCandidateTest(unittest.TestCase):
    def fixture(self, root: Path, *, hard_pass: bool = True) -> Path:
        stage = root / "results" / "step2400"
        stage.mkdir(parents=True)
        artifacts = [
            "model.onnx", "model.onnx.json", "evaluation-suite.json",
            *(f"eval-{index}.wav" for index in range(1, 6)),
        ]
        checksums = []
        for filename in artifacts:
            payload = f"payload:{filename}".encode()
            (stage / filename).write_bytes(payload)
            checksums.append(f"{hashlib.sha256(payload).hexdigest()}  {filename}\n")
        (stage / "SHA256SUMS").write_text("".join(checksums), encoding="utf-8")
        completion = root / "piper_training_complete.json"
        completion.write_text(json.dumps({
            "status": "complete",
            "ranking": {"candidates": [{
                "step": 2400, "hardPass": hard_pass, "objectiveNoRegression": True,
                "score": 0.8,
                "meanCer": 0.02, "maxCer": 0.04,
                "meanSpeakerSimilarity": 0.9, "minSpeakerSimilarity": 0.85,
                "model": str(stage / "model.onnx"),
            }]},
        }), encoding="utf-8")
        return completion

    def decision(self, root: Path, completion: Path) -> Path:
        model = root / "results" / "step2400" / "model.onnx"
        decision = root / "listening-decision.json"
        decision.write_text(json.dumps({
            "status": "approved", "selectedKind": "candidate", "selectedStep": 2400,
            "modelSha256": hashlib.sha256(model.read_bytes()).hexdigest(),
            "completion": str(completion.resolve()), "approvedBy": "owner",
            "gates": {"identity": True, "pronunciation": True,
                      "naturalness": True, "artifactFree": True},
        }), encoding="utf-8")
        return decision

    def test_requires_explicit_listening_approval(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            completion = self.fixture(root)
            completed = subprocess.run([
                "python3", str(SCRIPT), "--completion", str(completion),
                "--step", "2400", "--output-root", str(root / "releases"),
            ], text=True, capture_output=True)
            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("--decision", completed.stderr)

    def test_promotes_a_verified_hard_pass_candidate_idempotently(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            completion = self.fixture(root)
            decision = self.decision(root, completion)
            command = [
                "python3", str(SCRIPT), "--completion", str(completion),
                "--step", "2400", "--output-root", str(root / "releases"),
                "--decision", str(decision),
            ]
            first = subprocess.run(command, text=True, capture_output=True)
            self.assertEqual(first.returncode, 0, first.stderr)
            result = json.loads(first.stdout)
            release = Path(result["release"])
            self.assertTrue((release / "voice.onnx").is_file())
            self.assertTrue((release / "listening-decision.json").is_file())
            self.assertEqual(len(list((release / "listening-samples").glob("*.wav"))), 5)
            release_manifest = json.loads((release / "release.json").read_text())
            self.assertEqual(release_manifest["status"], "approved")
            self.assertEqual(len(release_manifest["listeningDecisionSha256"]), 64)
            second = subprocess.run(command, text=True, capture_output=True)
            self.assertEqual(json.loads(second.stdout)["status"], "already-promoted")

    def test_rejects_a_candidate_that_failed_the_hard_gate(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            completion = self.fixture(root, hard_pass=False)
            decision = self.decision(root, completion)
            completed = subprocess.run([
                "python3", str(SCRIPT), "--completion", str(completion),
                "--step", "2400", "--output-root", str(root / "releases"),
                "--decision", str(decision),
            ], text=True, capture_output=True)
            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("did not pass", completed.stderr)

    def test_rejects_a_listening_decision_bound_to_other_weights(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            completion = self.fixture(root)
            decision = self.decision(root, completion)
            payload = json.loads(decision.read_text(encoding="utf-8"))
            payload["modelSha256"] = "0" * 64
            decision.write_text(json.dumps(payload), encoding="utf-8")
            completed = subprocess.run([
                "python3", str(SCRIPT), "--completion", str(completion),
                "--step", "2400", "--output-root", str(root / "releases"),
                "--decision", str(decision),
            ], text=True, capture_output=True)
            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("does not authorize", completed.stderr)

    def test_rejects_a_candidate_that_regressed_against_baseline(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            completion = self.fixture(root)
            payload = json.loads(completion.read_text(encoding="utf-8"))
            payload["ranking"]["candidates"][0]["objectiveNoRegression"] = False
            completion.write_text(json.dumps(payload), encoding="utf-8")
            decision = self.decision(root, completion)
            completed = subprocess.run([
                "python3", str(SCRIPT), "--completion", str(completion),
                "--step", "2400", "--output-root", str(root / "releases"),
                "--decision", str(decision),
            ], text=True, capture_output=True)
            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("regressed against", completed.stderr)

    def test_rejects_evaluation_changed_after_stage_sealing(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            completion = self.fixture(root)
            decision = self.decision(root, completion)
            (root / "results" / "step2400" / "evaluation-suite.json").write_text(
                '{"tampered":true}', encoding="utf-8")
            completed = subprocess.run([
                "python3", str(SCRIPT), "--completion", str(completion),
                "--step", "2400", "--output-root", str(root / "releases"),
                "--decision", str(decision),
            ], text=True, capture_output=True)
            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("checksum mismatch", completed.stderr)


if __name__ == "__main__":
    unittest.main()
