#!/usr/bin/env python3

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "scripts/run_desktop_realtime_acceptance.ps1").read_text(encoding="utf-8")


class DesktopRealtimeRunnerContractTest(unittest.TestCase):
    def test_requires_one_packaged_stage_executable(self):
        self.assertIn('Name -eq "GahyeonStage.exe"', SOURCE)
        self.assertIn("Executables.Count -ne 1", SOURCE)

    def test_bounds_duration_and_resolution(self):
        self.assertIn("DurationSeconds -lt 600", SOURCE)
        self.assertIn("DurationSeconds -gt 3600", SOURCE)
        self.assertIn("Width -lt 1280", SOURCE)
        self.assertIn("Height -lt 720", SOURCE)

    def test_never_overwrites_existing_evidence(self):
        self.assertIn("will not be overwritten", SOURCE)
        self.assertIn('"raw-desktop.json"', SOURCE)
        self.assertIn('"desktop-acceptance.json"', SOURCE)

    def test_runs_atomic_recorder_then_builder_and_verifier(self):
        self.assertIn("-GahyeonRtExit", SOURCE)
        self.assertIn("-GahyeonRtOutput=$Raw", SOURCE)
        self.assertIn("build_desktop_realtime_acceptance.py", SOURCE)
        self.assertIn("verify_desktop_realtime_acceptance.py", SOURCE)

    def test_full_physical_gate_is_explicit(self):
        self.assertIn("[switch]$RequirePassed", SOURCE)
        self.assertIn('$VerifyArguments += "--require-passed"', SOURCE)


if __name__ == "__main__":
    unittest.main()
