#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_voicebox_handoff_identity import verify


class VoiceboxHandoffIdentityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.catalog = self.root / "catalog.jsonl"
        self.manifest = self.root / "manifest.jsonl"
        self.ready = self.root / "ready.json"
        self.archive = self.root / "dataset.tar.gz"
        self.catalog.write_text("catalog\n", encoding="utf-8")
        self.manifest.write_text("manifest\n", encoding="utf-8")
        self.archive.write_bytes(b"dataset")
        self.write_ready()

    def tearDown(self) -> None:
        self.temp.cleanup()

    @staticmethod
    def digest(path: Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def write_ready(self) -> None:
        self.ready.write_text(json.dumps({
            "sourceIdentity": {
                "catalogSha256": self.digest(self.catalog),
                "manifestSha256": self.digest(self.manifest),
                "completed": 5000,
            },
            "catalogDiversity": {"ready": True},
            "package": {"clips": 4200, "archive_bytes": self.archive.stat().st_size,
                        "archive_sha256": self.digest(self.archive)},
        }), encoding="utf-8")

    def test_accepts_exact_frozen_source_identity(self) -> None:
        self.assertEqual(verify(self.ready, self.catalog, self.manifest,
                                archive=self.archive)["package"]["clips"], 4200)

    def test_rejects_missing_or_changed_dataset_archive(self) -> None:
        self.archive.write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "byte size mismatch|checksum mismatch"):
            verify(self.ready, self.catalog, self.manifest, archive=self.archive)
        self.archive.unlink()
        with self.assertRaisesRegex(ValueError, "archive is missing"):
            verify(self.ready, self.catalog, self.manifest, archive=self.archive)

    def test_rejects_manifest_changed_after_qc(self) -> None:
        self.manifest.write_text("changed\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "does not match"):
            verify(self.ready, self.catalog, self.manifest)

    def test_rejects_incomplete_or_too_small_handoff(self) -> None:
        payload = json.loads(self.ready.read_text(encoding="utf-8"))
        payload["sourceIdentity"]["completed"] = 4000
        self.ready.write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "does not match"):
            verify(self.ready, self.catalog, self.manifest)

        self.write_ready()
        payload = json.loads(self.ready.read_text(encoding="utf-8"))
        payload["package"]["clips"] = 3999
        self.ready.write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "too few"):
            verify(self.ready, self.catalog, self.manifest)


if __name__ == "__main__":
    unittest.main()
