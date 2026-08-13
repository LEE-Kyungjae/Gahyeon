#!/usr/bin/env python3

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/run_unreal_engine_gate.ps1"


class UnrealEngineWindowsGateContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SCRIPT.read_text(encoding="utf-8")

    def test_targets_canonical_win64_stage(self):
        self.assertIn("GahyeonStage.uproject", self.source)
        self.assertNotIn("GahyeonStageLookingGlass.uproject", self.source)
        self.assertIn("GahyeonStageEditor Win64 Development", self.source)
        self.assertIn("MajorVersion -ne 5", self.source)
        self.assertIn("MinorVersion -ne 6", self.source)

    def test_requires_both_vertical_slice_automation_results(self):
        self.assertIn("Gahyeon.Runtime.MockCognitionDelayFailureAndReordering", self.source)
        self.assertIn("Gahyeon.Presentation.FacialCurveBindingsAreDataDrivenAndBounded", self.source)
        self.assertIn("schemaVersion\": 2", self.source)
        self.assertIn("requiredAutomationTests", self.source)

    def test_seals_and_independently_verifies_evidence(self):
        self.assertIn('"projectSha256": digest(project)', self.source)
        self.assertIn('"automationLog": {"path": "automation.log"', self.source)
        self.assertIn("verify_unreal_engine_evidence.py", self.source)
        self.assertIn("temporary.replace(root / \"manifest.json\")", self.source)

    def test_optional_package_is_cooked_and_fully_inventoried(self):
        self.assertIn("[switch]$Package", self.source)
        self.assertIn("RunUAT.bat", self.source)
        self.assertIn("BuildCookRun", self.source)
        self.assertIn("-clientconfig=Development", self.source)
        self.assertIn("package-files.json", self.source)
        self.assertIn('"packagedBuild": packaged', self.source)

    def test_hero_gate_is_optional_but_strict(self):
        self.assertIn("[string]$HeroManifest", self.source)
        self.assertIn("--require-approved --renderer hero-engine --verify-files", self.source)
        self.assertIn("install_gahyeon_unreal_content.py", self.source)


if __name__ == "__main__":
    unittest.main()
