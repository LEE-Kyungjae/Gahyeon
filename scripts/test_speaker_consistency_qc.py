#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("speaker_consistency_qc.py")
SPEC = importlib.util.spec_from_file_location("speaker_consistency_qc", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class SpeakerConsistencyQcTest(unittest.TestCase):
    def test_accepts_the_calibrated_profile9_distribution(self) -> None:
        similarities = [0.94] * 95 + [0.91] * 4 + [0.86]
        report = MODULE.summarize(similarities, 100, 90)
        self.assertTrue(report["ready"])
        self.assertGreaterEqual(report["p05"], 0.90)

    def test_rejects_cluster_drift_and_excessive_outliers(self) -> None:
        similarities = [0.95] * 80 + [0.70] * 20
        report = MODULE.summarize(similarities, 80, 75)
        self.assertFalse(report["ready"])
        self.assertIn("speaker_outlier_ratio", report["violations"])
        self.assertIn("speaker_similarity_p05", report["violations"])

    def test_rejects_too_few_retained_clips(self) -> None:
        report = MODULE.summarize([0.96] * 10, 8, 9)
        self.assertFalse(report["ready"])
        self.assertIn("retained_clips", report["violations"])

    def test_does_not_round_a_failing_p05_up_to_the_gate(self) -> None:
        similarities = [0.89999] * 6 + [0.96] * 94
        report = MODULE.summarize(similarities, 100, 90)
        self.assertEqual(0.9, report["p05"])
        self.assertFalse(report["ready"])
        self.assertIn("speaker_similarity_p05", report["violations"])


if __name__ == "__main__":
    unittest.main()
