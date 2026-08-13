#!/usr/bin/env python3

import json
import tempfile
import unittest
from pathlib import Path

from build_looking_glass_acceptance import build
from looking_glass_contract_fixture import fixture_attestation, write_fixture_quilt
from verify_looking_glass_acceptance import verify


def scenario(name, attestation, frame=16.7, latency=50):
    return {"name": name, "durationSeconds": 60,
            "frameMs": [frame] * 600,
            "vadToListeningMs": [latency] * 20,
            "bargeInToAudioStopMs": [latency] * 20,
            "audioToVisemeMs": [latency] * 20,
            "presentationAttestation": attestation}


class BuildLookingGlassAcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.raw = self.root / "raw.json"
        self.output = self.root / "acceptance.json"
        profiles = []
        for mode in ("Realtime", "RealtimeAdaptive", "NonRealtime"):
            scenarios = []
            for name in ("idle", "listening", "thinking", "speaking"):
                relative = f"captures/{mode.lower()}--{name}.png"
                quilt = self.root / relative
                write_fixture_quilt(quilt, 4092, 4092, f"{mode}-{name}")
                scenarios.append(scenario(name, fixture_attestation(
                    quilt, uri=relative, run_id="fixture-run-0001",
                    mode=mode)))
            profiles.append({"id": mode.lower(), "mode": mode, "views": 66,
                             "quiltWidth": 4092, "quiltHeight": 4092,
                             "scenarios": scenarios})
        self.payload = {
            "schemaVersion": 2, "latencyBoundary": "physical-presentation-v1",
            "attestationPolicy": "runtime-quilt-capture-v1",
            "measurementRunId": "fixture-run-0001",
            "platform": {"os": "Win64", "gpu": "GTX 1660 Ti", "driver": "fixture"},
            "display": {"model": "Looking Glass Go", "serialHash": "b" * 64},
            "software": {"unreal": "5.6", "plugin": "2.1.1", "bridge": "2.6.3"},
            "profiles": profiles,
        }

    def tearDown(self):
        self.temporary.cleanup()

    def write(self):
        self.raw.write_text(json.dumps(self.payload))

    def test_raw_samples_are_aggregated_and_sealed(self):
        self.write()
        result = build(self.raw, self.output)
        self.assertEqual("passed", result["status"])
        scenario_result = result["profiles"][0]["scenarios"][0]
        self.assertEqual(16.7, scenario_result["p95FrameMs"])
        self.assertEqual(600, scenario_result["frames"])
        self.assertEqual("passed", verify(self.output, require_passed=True)["status"])

    def test_nonrealtime_cannot_hide_slow_realtime_profiles(self):
        for profile in self.payload["profiles"][:2]:
            for item in profile["scenarios"]:
                item["frameMs"] = [45.0] * 600
        self.write()
        self.assertEqual("failed", build(self.raw, self.output)["status"])
        with self.assertRaisesRegex(ValueError, "not passed"):
            verify(self.output, require_passed=True)

    def test_too_few_latency_samples_are_rejected(self):
        self.payload["profiles"][0]["scenarios"][0]["audioToVisemeMs"] = [10] * 19
        self.write()
        with self.assertRaisesRegex(ValueError, "at least 20"):
            build(self.raw, self.output)
        self.assertFalse(self.output.exists())

    def test_nan_sample_is_rejected(self):
        self.payload["profiles"][0]["scenarios"][0]["frameMs"][0] = float("nan")
        self.write()
        with self.assertRaisesRegex(ValueError, "invalid sample"):
            build(self.raw, self.output)

    def test_unsafe_run_id_is_rejected_before_output(self):
        self.payload["measurementRunId"] = "../mixed-run"
        self.write()
        with self.assertRaisesRegex(ValueError, "run ID"):
            build(self.raw, self.output)
        self.assertFalse(self.output.exists())

    def test_legacy_cursor_only_latency_is_rejected(self):
        del self.payload["latencyBoundary"]
        self.write()
        with self.assertRaisesRegex(ValueError, "physical presentation"):
            build(self.raw, self.output)
        self.assertFalse(self.output.exists())

    def test_cli_only_raw_without_runtime_attestation_is_rejected(self):
        del self.payload["profiles"][0]["scenarios"][0]["presentationAttestation"]
        self.write()
        with self.assertRaisesRegex(ValueError, "runtime presentation attestation"):
            build(self.raw, self.output)
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
