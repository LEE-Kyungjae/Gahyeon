#!/usr/bin/env python3

import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

from install_looking_glass_unreal_plugin import install
from verify_looking_glass_integration import DEFAULT_LOCK, DEFAULT_PROJECT


class InstallLookingGlassPluginTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.project = self.root / "GahyeonStage.uproject"
        self.project.write_bytes(DEFAULT_PROJECT.read_bytes())
        self.archive = self.root / "LookingGlass.zip"
        descriptor = {
            "FileVersion": 3,
            "Modules": [
                {"Name": "LookingGlassRuntime", "Type": "Runtime",
                 "WhitelistPlatforms": ["Win64"]},
                {"Name": "LookingGlassEditor", "Type": "Editor",
                 "WhitelistPlatforms": ["Win64"]},
            ],
        }
        with zipfile.ZipFile(self.archive, "w") as archive:
            archive.writestr("LookingGlass/LookingGlass.uplugin", json.dumps(descriptor))
            archive.writestr("LookingGlass/Source/example.cpp", "source")
        self.lock = self.root / "lock.json"
        payload = json.loads(DEFAULT_LOCK.read_text(encoding="utf-8"))
        payload["upstream"]["archive"]["bytes"] = self.archive.stat().st_size
        payload["upstream"]["archive"]["sha256"] = hashlib.sha256(
            self.archive.read_bytes()).hexdigest()
        self.lock.write_text(json.dumps(payload), encoding="utf-8")

    def tearDown(self):
        self.temporary.cleanup()

    @mock.patch("install_looking_glass_unreal_plugin.verify", return_value={"valid": True})
    def test_installs_without_enabling_and_is_idempotent(self, _verify):
        first = install(self.archive, self.project, self.lock)
        self.assertEqual("installed", first["status"])
        self.assertFalse(first["enabled"])
        second = install(self.archive, self.project, self.lock)
        self.assertEqual("already-installed", second["status"])
        project = json.loads(self.project.read_text(encoding="utf-8"))
        self.assertNotIn("LookingGlass", {item["Name"] for item in project["Plugins"]})

    @mock.patch("install_looking_glass_unreal_plugin.verify", return_value={"valid": True})
    def test_tampered_archive_is_rejected(self, _verify):
        self.archive.write_bytes(self.archive.read_bytes() + b"tamper")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            install(self.archive, self.project, self.lock)

    @mock.patch("install_looking_glass_unreal_plugin.verify", return_value={"valid": True})
    def test_existing_untracked_plugin_is_not_overwritten(self, _verify):
        target = self.root / "Plugins/LookingGlass"
        target.mkdir(parents=True)
        (target / "mine.txt").write_text("mine")
        with self.assertRaisesRegex(ValueError, "inventory differs"):
            install(self.archive, self.project, self.lock)
        self.assertEqual("mine", (target / "mine.txt").read_text())


if __name__ == "__main__":
    unittest.main()
