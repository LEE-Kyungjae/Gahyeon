#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from install_gahyeon_unreal_content import install_verified_package, verify_installed
from package_gahyeon_unreal_content import package


ENTRY = "/Game/GahyeonGenerated/Characters/Gahyeon.Gahyeon"
RUNTIME_CLASS = ENTRY + "_C"


class InstallGahyeonUnrealContentTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.project = self.root / "Project"
        self.project.mkdir()
        (self.project / "GahyeonStage.uproject").write_text("{}", encoding="utf-8")
        self.source = self.root / "Export" / "GahyeonGenerated"
        (self.source / "Characters").mkdir(parents=True)
        (self.source / "Characters/Gahyeon.uasset").write_bytes(b"asset-v1")
        self.archive = self.root / "hero.zip"
        package(self.source, self.archive, engine_version="5.6", entry_asset=ENTRY,
                runtime_class=RUNTIME_CLASS)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_installs_verifies_and_is_idempotent(self) -> None:
        first = install_verified_package(self.archive, self.project)
        self.assertEqual("installed", first["status"])
        second = install_verified_package(self.archive, self.project)
        self.assertEqual("already-installed", second["status"])
        receipt = json.loads((self.project / "Saved/GahyeonHeroInstall/receipt.json").read_text())
        self.assertEqual(first["packageSha256"], receipt["packageSha256"])
        self.assertEqual(RUNTIME_CLASS, receipt["runtimeClass"])
        self.assertEqual(
            "/Script/GahyeonStage.GahyeonCharacterAnimInstance",
            receipt["runtimeContract"]["animInstanceBaseClass"],
        )

    def test_refuses_silent_overwrite_and_replace_keeps_backup(self) -> None:
        install_verified_package(self.archive, self.project)
        (self.source / "Characters/Gahyeon.uasset").write_bytes(b"asset-v2")
        package(self.source, self.archive, engine_version="5.6", entry_asset=ENTRY,
                runtime_class=RUNTIME_CLASS)
        with self.assertRaisesRegex(ValueError, "--replace"):
            install_verified_package(self.archive, self.project)
        result = install_verified_package(self.archive, self.project, replace=True)
        self.assertIsNotNone(result["backup"])
        self.assertEqual(b"asset-v1", (Path(result["backup"]) / "Characters/Gahyeon.uasset").read_bytes())
        self.assertEqual(b"asset-v2", (self.project / "Content/GahyeonGenerated/Characters/Gahyeon.uasset").read_bytes())

    def test_detects_post_install_tampering(self) -> None:
        install_verified_package(self.archive, self.project)
        target = self.project / "Content/GahyeonGenerated"
        (target / "Characters/Gahyeon.uasset").write_bytes(b"tampered")
        inventory = __import__("verify_gahyeon_hero_asset").verify_unreal_content_package(self.archive)
        with self.assertRaisesRegex(ValueError, "byte size mismatch|checksum mismatch"):
            verify_installed(target, inventory)


if __name__ == "__main__":
    unittest.main()
