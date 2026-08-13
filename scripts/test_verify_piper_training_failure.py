#!/usr/bin/env python3

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from verify_piper_training_failure import verify


SHA = "a" * 64


class PiperTrainingFailureVerificationTest(unittest.TestCase):
    def verify_values(self, failure: dict, submitted: dict | None = None) -> dict:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            failure_path = root / "FAILED.json"
            submitted_path = root / "submitted.json"
            failure_path.write_text(json.dumps(failure), encoding="utf-8")
            submitted_path.write_text(json.dumps(submitted or {"sha256": SHA}), encoding="utf-8")
            return verify(failure_path, submitted_path)

    def valid(self) -> dict:
        return {
            "status": "failed",
            "datasetSha256": SHA,
            "stage": "training_step_2400",
            "exitCode": 3,
            "line": 123,
            "command": "piper.train fit",
            "failedAt": "2026-08-12T00:00:00+00:00",
        }

    def test_accepts_matching_failure(self) -> None:
        self.assertEqual("training_step_2400", self.verify_values(self.valid())["stage"])

    def test_rejects_another_dataset(self) -> None:
        failure = self.valid()
        failure["datasetSha256"] = "b" * 64
        with self.assertRaisesRegex(ValueError, "another dataset"):
            self.verify_values(failure)

    def test_rejects_unknown_stage(self) -> None:
        failure = self.valid()
        failure["stage"] = "uploaded_shell"
        with self.assertRaisesRegex(ValueError, "unsupported stage"):
            self.verify_values(failure)

    def test_rejects_success_exit_code(self) -> None:
        failure = self.valid()
        failure["exitCode"] = 0
        with self.assertRaisesRegex(ValueError, "invalid exit code"):
            self.verify_values(failure)


if __name__ == "__main__":
    unittest.main()
