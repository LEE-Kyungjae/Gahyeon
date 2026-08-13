#!/usr/bin/env python3

from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("rank_piper_training_stages.py")


class RankPiperStagesTest(unittest.TestCase):
    def test_prefers_hard_pass_then_composite_quality(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            summaries = {
                600: {"hardPass": False, "meanCer": 0.01, "maxCer": 0.02,
                      "meanSpeakerSimilarity": 0.99, "minSpeakerSimilarity": 0.98},
                1200: {"hardPass": True, "meanCer": 0.08, "maxCer": 0.15,
                       "meanSpeakerSimilarity": 0.82, "minSpeakerSimilarity": 0.75},
                2400: {"hardPass": True, "meanCer": 0.04, "maxCer": 0.08,
                       "meanSpeakerSimilarity": 0.88, "minSpeakerSimilarity": 0.80},
            }
            baseline = root / "baseline"
            baseline.mkdir()
            (baseline / "evaluation-suite.json").write_text(json.dumps({
                "summary": {"hardPass": True, "meanCer": 0.06, "maxCer": 0.10,
                            "meanSpeakerSimilarity": 0.84, "minSpeakerSimilarity": 0.78},
                "results": [],
            }), encoding="utf-8")
            for step, summary in summaries.items():
                stage = root / f"step{step}"
                stage.mkdir()
                (stage / "evaluation-suite.json").write_text(
                    json.dumps({"summary": summary, "results": []}), encoding="utf-8"
                )
            completed = subprocess.run(
                ["python3", str(SCRIPT), "--root", str(root)], text=True, capture_output=True
            )
            self.assertEqual(completed.returncode, 0, completed.stderr)
            result = json.loads(completed.stdout)
            self.assertEqual(result["recommendedStep"], 2400)
            self.assertEqual([row["step"] for row in result["candidates"]], [2400, 1200, 600])
            self.assertEqual(result["baseline"]["alias"], "ze69-blend-fp32-step420")
            self.assertTrue(result["candidates"][0]["objectiveNoRegression"])
            self.assertFalse(result["candidates"][1]["objectiveNoRegression"])


if __name__ == "__main__":
    unittest.main()
