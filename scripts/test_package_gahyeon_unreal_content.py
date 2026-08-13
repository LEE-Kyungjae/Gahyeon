#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from package_gahyeon_unreal_content import package
from verify_gahyeon_hero_asset import verify_unreal_content_package


ENTRY = "/Game/GahyeonGenerated/Characters/Gahyeon.Gahyeon"
RUNTIME_CLASS = ENTRY + "_C"


class PackageGahyeonUnrealContentTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.source = self.root / "Content" / "GahyeonGenerated"
        (self.source / "Characters").mkdir(parents=True)
        (self.source / "Characters" / "Gahyeon.uasset").write_bytes(b"asset")
        (self.source / "Characters" / "Gahyeon.uexp").write_bytes(b"payload")
        self.output = self.root / "gahyeon-unreal.zip"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_builds_reproducible_verified_package(self) -> None:
        first = package(self.source, self.output, engine_version="5.6",
                        entry_asset=ENTRY, runtime_class=RUNTIME_CLASS)
        first_digest = hashlib.sha256(self.output.read_bytes()).hexdigest()
        second = package(self.source, self.output, engine_version="5.6",
                         entry_asset=ENTRY, runtime_class=RUNTIME_CLASS)
        self.assertEqual(first_digest, hashlib.sha256(self.output.read_bytes()).hexdigest())
        self.assertEqual(first["sha256"], second["sha256"])
        inventory = verify_unreal_content_package(self.output)
        self.assertEqual(2, len(inventory["files"]))
        self.assertEqual(RUNTIME_CLASS, inventory["runtimeClass"])
        self.assertEqual(2, inventory["schemaVersion"])
        self.assertTrue(inventory["runtimeContract"]["requiresPresentationProfile"])

    def test_rejects_empty_or_wrongly_mounted_source(self) -> None:
        (self.source / "Characters" / "Gahyeon.uasset").unlink()
        with self.assertRaisesRegex(ValueError, "at least one .uasset"):
            package(self.source, self.output, engine_version="5.6",
                    entry_asset=ENTRY, runtime_class=RUNTIME_CLASS)
        wrong = self.root / "Other"
        wrong.mkdir()
        with self.assertRaisesRegex(ValueError, "GahyeonGenerated"):
            package(wrong, self.output, engine_version="5.6",
                    entry_asset="/Game/GahyeonGenerated/X.X",
                    runtime_class="/Game/GahyeonGenerated/X.X_C")

    def test_rejects_runtime_class_not_owned_by_entry_blueprint(self) -> None:
        with self.assertRaisesRegex(ValueError, "runtimeClass"):
            package(self.source, self.output, engine_version="5.6", entry_asset=ENTRY,
                    runtime_class="/Game/GahyeonGenerated/Characters/Other.Other_C")


if __name__ == "__main__":
    unittest.main()
