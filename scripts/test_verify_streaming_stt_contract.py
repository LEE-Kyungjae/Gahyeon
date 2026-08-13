#!/usr/bin/env python3

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_streaming_stt_contract import verify


class StreamingSttContractTest(unittest.TestCase):
    def test_canonical_controls_and_invalid_cases(self) -> None:
        self.assertEqual({"valid": True, "controls": 10, "invalidRejected": 6}, verify())


if __name__ == "__main__":
    unittest.main()
