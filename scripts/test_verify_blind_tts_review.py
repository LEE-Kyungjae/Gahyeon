#!/usr/bin/env python3

import json
import tempfile
import unittest
from pathlib import Path

from verify_blind_tts_review import BlindReviewError, verify


class VerifyBlindTtsReviewTest(unittest.TestCase):
    def fixture(self, page: str = "후보 A 후보 B") -> tuple[tempfile.TemporaryDirectory, Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        (root / "index.html").write_text(page, encoding="utf-8")
        candidates = [
            {"label": "A", "step": 2000, "modelSha256": "a" * 64},
            {"label": "B", "step": 4000, "modelSha256": "b" * 64},
        ]
        (root / "review-key.json").write_text(
            json.dumps({"candidates": candidates}), encoding="utf-8"
        )
        for label in ("A", "B"):
            media = root / "media" / label
            media.mkdir(parents=True)
            for index in range(1, 6):
                (media / f"case-{index}.wav").write_bytes(b"RIFF")
        return temporary, root

    def test_accepts_blind_review(self) -> None:
        temporary, root = self.fixture()
        try:
            self.assertEqual(verify(root), 2)
        finally:
            temporary.cleanup()

    def test_rejects_step_leak(self) -> None:
        temporary, root = self.fixture("후보 A step 2000")
        try:
            with self.assertRaisesRegex(BlindReviewError, "identity leaked"):
                verify(root)
        finally:
            temporary.cleanup()

    def test_rejects_incomplete_candidate_samples(self) -> None:
        temporary, root = self.fixture()
        try:
            (root / "media/A/case-5.wav").unlink()
            with self.assertRaisesRegex(BlindReviewError, "exactly five"):
                verify(root)
        finally:
            temporary.cleanup()


if __name__ == "__main__":
    unittest.main()
