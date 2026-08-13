#!/usr/bin/env python3

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from preflight_streaming_stt_evaluation import REQUIRED_TRUE, check


class StreamingSttEvaluationPreflightTest(unittest.TestCase):
    def test_missing_provider_configuration_fails_without_exposing_values(self) -> None:
        with tempfile.TemporaryDirectory() as root:
            suite = self._suite(Path(root))
            with patch.dict(os.environ, {}, clear=True):
                result = check(suite)
        self.assertFalse(result["ready"])
        self.assertEqual("missing", result["providerCredential"])
        self.assertEqual(list(REQUIRED_TRUE), result["missingEnabledFlags"])
        self.assertNotIn("apiKey", json.dumps(result))

    def test_valid_10_source_20_trial_suite_is_ready(self) -> None:
        with tempfile.TemporaryDirectory() as root:
            suite = self._suite(Path(root))
            environment = {name: "true" for name in REQUIRED_TRUE}
            environment["ASSISTANT_STT_API_KEY"] = "x" * 20
            with patch.dict(os.environ, environment, clear=True):
                result = check(suite)
        self.assertTrue(result["ready"])
        self.assertEqual(10, result["uniqueWavs"])
        self.assertEqual(20, result["trials"])

    @staticmethod
    def _suite(root: Path) -> Path:
        suite = root / "suite.jsonl"
        rows = []
        for index in range(10):
            wav = root / f"{index}.wav"
            wav.write_bytes(b"RIFF" + b"\0" * 64)
            rows.append(json.dumps({
                "id": f"sample-{index}", "wav": wav.name,
                "expected": f"문장 {index}", "repeats": 2,
            }, ensure_ascii=False))
        suite.write_text("\n".join(rows) + "\n", encoding="utf-8")
        return suite


if __name__ == "__main__":
    unittest.main()
