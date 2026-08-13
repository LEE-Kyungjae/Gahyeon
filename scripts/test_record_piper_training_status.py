#!/usr/bin/env python3

import json
import tempfile
import unittest
from pathlib import Path

from record_piper_training_status import build, write


SHA = "a" * 64
SUBMITTED = {
    "sha256": SHA,
    "remoteRoot": "/home/ubuntu/piper-voice/voicebox-diverse5000-aaaaaaaaaaaa",
}


class PiperTrainingStatusTest(unittest.TestCase):
    def test_records_ordered_training_progress_atomically(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            submitted = root / "submitted.json"
            output = root / "status.json"
            submitted.write_text(json.dumps(SUBMITTED), encoding="utf-8")
            result = write(output, submitted, "training", {
                "completedSteps": [600, 1200], "activeStep": 2400,
                "phase": "training_step_2400"})
            self.assertEqual("training", result["status"])
            self.assertEqual(result, json.loads(output.read_text(encoding="utf-8")))

    def test_rejects_unordered_or_duplicate_steps(self) -> None:
        for steps in ([1200, 600], [600, 600]):
            with self.subTest(steps=steps), self.assertRaisesRegex(ValueError, "unique and ordered"):
                build(SUBMITTED, "training", {"completedSteps": steps, "activeStep": 2400})

    def test_rejects_completed_active_step(self) -> None:
        with self.assertRaisesRegex(ValueError, "already be complete"):
            build(SUBMITTED, "training", {
                "completedSteps": [600], "activeStep": 600,
                "phase": "training_step_600"})

    def test_rejects_phase_for_another_step(self) -> None:
        with self.assertRaisesRegex(ValueError, "invalid phase"):
            build(SUBMITTED, "training", {
                "completedSteps": [600], "activeStep": 1200,
                "phase": "export_step_2400"})

    def test_rejects_mismatched_failure_identity(self) -> None:
        with self.assertRaisesRegex(ValueError, "another dataset"):
            build(SUBMITTED, "failed", {"datasetSha256": "b" * 64})


if __name__ == "__main__":
    unittest.main()
