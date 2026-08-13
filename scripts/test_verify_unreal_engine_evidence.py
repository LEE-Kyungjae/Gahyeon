#!/usr/bin/env python3

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from verify_unreal_engine_evidence import REQUIRED_TESTS, verify


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class UnrealEngineEvidenceVerifierTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.project = self.root / "GahyeonStage.uproject"
        self.project.write_text("{}")
        (self.root / "build.log").write_text("build succeeded\n")
        automation = "\n".join(
            f"Result={{Success}} Name={name}" for name in REQUIRED_TESTS) + "\n"
        (self.root / "automation.log").write_text(automation)
        package = self.root / "package/Windows"
        package.mkdir(parents=True)
        self.executable = package / "GahyeonStage.exe"
        self.executable.write_bytes(b"packaged-development-fixture")
        (self.root / "package.log").write_text("package succeeded\n")
        inventory = {"schemaVersion": 1, "files": [{
            "path": "Windows/GahyeonStage.exe",
            "bytes": self.executable.stat().st_size,
            "sha256": sha(self.executable),
        }]}
        (self.root / "package-files.json").write_text(json.dumps(inventory))
        self.write_manifest(True)

    def tearDown(self):
        self.temporary.cleanup()

    def write_manifest(self, packaged: bool):
        evidence = {
            "buildLog": {"path": "build.log", "sha256": sha(self.root / "build.log")},
            "automationLog": {"path": "automation.log", "sha256": sha(self.root / "automation.log")},
        }
        if packaged:
            evidence.update({
                "packageLog": {"path": "package.log", "sha256": sha(self.root / "package.log")},
                "packageInventory": {"path": "package-files.json", "sha256": sha(self.root / "package-files.json")},
            })
        payload = {
            "schemaVersion": 2,
            "status": "passed",
            "engineVersion": "5.6",
            "platform": "Win64",
            "configuration": "Development",
            "project": str(self.project),
            "projectSha256": sha(self.project),
            "packagedBuild": packaged,
            "requiredAutomationTests": list(REQUIRED_TESTS),
            "evidence": evidence,
        }
        (self.root / "manifest.json").write_text(json.dumps(payload))

    def test_packaged_output_is_fully_verified(self):
        self.assertTrue(verify(self.root)["packagedBuild"])

    def test_tampered_packaged_file_is_rejected(self):
        self.executable.write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "packaged file mismatch"):
            verify(self.root)

    def test_unlisted_packaged_file_is_rejected(self):
        (self.root / "package/extra.bin").write_bytes(b"extra")
        with self.assertRaisesRegex(ValueError, "does not exactly cover"):
            verify(self.root)

    def test_false_package_flag_cannot_keep_package_evidence(self):
        self.write_manifest(False)
        manifest = json.loads((self.root / "manifest.json").read_text())
        manifest["evidence"]["packageLog"] = {
            "path": "package.log", "sha256": sha(self.root / "package.log")}
        (self.root / "manifest.json").write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, "packagedBuild is false"):
            verify(self.root)


if __name__ == "__main__":
    unittest.main()
