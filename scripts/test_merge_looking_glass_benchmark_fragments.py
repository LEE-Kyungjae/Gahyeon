#!/usr/bin/env python3

import json
import tempfile
import unittest
from pathlib import Path

from build_looking_glass_acceptance import build
from looking_glass_contract_fixture import fixture_attestation, write_fixture_quilt
from merge_looking_glass_benchmark_fragments import merge


class MergeLookingGlassFragmentsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.fragments = self.root / "fragments"
        self.fragments.mkdir()
        self.metadata = self.root / "metadata.json"
        self.metadata.write_text(json.dumps({
            "schemaVersion": 1, "measurementRunId": "fixture-run-0001",
            "platform": {"os": "Win64", "gpu": "GTX 1660 Ti", "driver": "fixture"},
            "display": {"model": "Looking Glass Go", "serialHash": "c" * 64},
            "software": {"unreal": "5.6", "plugin": "2.1.1", "bridge": "2.6.3"},
        }))
        for mode in ("Realtime", "RealtimeAdaptive", "NonRealtime"):
            profile = mode.lower()
            for scenario in ("idle", "listening", "thinking", "speaking"):
                quilt_name = f"{profile}--{scenario}--quilt.png"
                quilt = self.fragments / quilt_name
                write_fixture_quilt(quilt, 4092, 4092, f"{profile}-{scenario}")
                (self.fragments / f"{profile}--{scenario}.json").write_text(json.dumps({
                    "schemaVersion": 2, "latencyBoundary": "physical-presentation-v1",
                    "attestationPolicy": "runtime-quilt-capture-v1",
                    "measurementRunId": "fixture-run-0001",
                    "id": profile, "mode": mode, "name": scenario,
                    "views": 66, "quiltWidth": 4092, "quiltHeight": 4092,
                    "durationSeconds": 60, "frameMs": [16.7] * 600,
                    "vadToListeningMs": [50] * 20,
                    "bargeInToAudioStopMs": [50] * 20,
                    "audioToVisemeMs": [50] * 20,
                    "presentationAttestation": fixture_attestation(
                        quilt, uri=quilt_name, run_id="fixture-run-0001",
                        mode=mode),
                }))

    def tearDown(self):
        self.temporary.cleanup()

    def test_fragments_merge_and_feed_acceptance_builder(self):
        raw = self.root / "raw.json"
        merged = merge(self.fragments, self.metadata, raw)
        self.assertEqual(3, len(merged["profiles"]))
        acceptance = self.root / "acceptance.json"
        self.assertEqual("passed", build(raw, acceptance)["status"])

    def test_missing_fragment_is_rejected(self):
        next(self.fragments.glob("*.json")).unlink()
        with self.assertRaisesRegex(ValueError, "twelve"):
            merge(self.fragments, self.metadata, self.root / "raw.json")

    def test_profile_drift_is_rejected(self):
        path = self.fragments / "realtime--speaking.json"
        payload = json.loads(path.read_text())
        payload["views"] = 45
        path.write_text(json.dumps(payload))
        with self.assertRaisesRegex(ValueError, "settings changed"):
            merge(self.fragments, self.metadata, self.root / "raw.json")

    def test_fragment_from_another_run_is_rejected(self):
        path = self.fragments / "realtime--idle.json"
        payload = json.loads(path.read_text())
        payload["measurementRunId"] = "different-run-0002"
        path.write_text(json.dumps(payload))
        with self.assertRaisesRegex(ValueError, "different run"):
            merge(self.fragments, self.metadata, self.root / "raw.json")

    def test_legacy_cursor_only_fragment_is_rejected(self):
        path = self.fragments / "realtime--idle.json"
        payload = json.loads(path.read_text())
        del payload["latencyBoundary"]
        path.write_text(json.dumps(payload))
        with self.assertRaisesRegex(ValueError, "physical presentation"):
            merge(self.fragments, self.metadata, self.root / "raw.json")

    def test_cli_only_fragment_without_runtime_attestation_is_rejected(self):
        path = self.fragments / "realtime--idle.json"
        payload = json.loads(path.read_text())
        del payload["presentationAttestation"]
        path.write_text(json.dumps(payload))
        with self.assertRaisesRegex(ValueError, "runtime presentation attestation"):
            merge(self.fragments, self.metadata, self.root / "raw.json")


if __name__ == "__main__":
    unittest.main()
