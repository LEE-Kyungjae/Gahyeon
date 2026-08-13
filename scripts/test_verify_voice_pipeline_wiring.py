#!/usr/bin/env python3

import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("verify_voice_pipeline_wiring.py")
SPEC = importlib.util.spec_from_file_location("verify_voice_pipeline_wiring", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class VoicePipelineWiringTest(unittest.TestCase):
    def test_repository_pipeline_is_fail_closed_and_complete(self) -> None:
        self.assertEqual([], MODULE.verify())

    def test_ordered_rejects_missing_or_reversed_markers(self) -> None:
        errors = []
        MODULE.ordered("second first", ("first", "second"), "sample", errors)
        self.assertEqual(1, len(errors))


if __name__ == "__main__":
    unittest.main()
