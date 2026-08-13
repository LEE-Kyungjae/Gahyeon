#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("check_voicebox_catalog_diversity.py")
SPEC = importlib.util.spec_from_file_location("voicebox_catalog_diversity", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class VoiceboxCatalogDiversityTest(unittest.TestCase):
    def test_punctuation_only_variants_are_exact_duplicates(self) -> None:
        report = MODULE.analyze([
            {"index": 1, "text": "같은 문장입니다."},
            {"index": 2, "text": "같은 문장입니다!"},
        ])
        self.assertEqual(report["normalizedExactDuplicates"], 1)

    def test_detects_high_overlap_template_variants(self) -> None:
        report = MODULE.analyze([
            {"index": 1, "text": "먼저 서버 상태 관련 내용을 확인하고 결과를 정확히 알려드리겠습니다."},
            {"index": 2, "text": "지금 서버 상태 관련 내용을 확인하고 결과를 정확히 알려드리겠습니다."},
            {"index": 3, "text": "비가 그친 오후에는 공원을 천천히 걷겠습니다."},
        ])
        self.assertGreaterEqual(report["nearDuplicatePairs"]["jaccardAbove080"], 1)
        self.assertEqual(report["nearDuplicatePairs"]["maximumPair"], [1, 2])


if __name__ == "__main__":
    unittest.main()
