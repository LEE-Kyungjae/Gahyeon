#!/usr/bin/env python3

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from looking_glass_contract_fixture import fixture_attestation, write_fixture_quilt
from verify_looking_glass_acceptance import verify


def scenario(name, attestation, frame=30, latency=70):
    return {"name": name, "durationSeconds": 60, "frames": 1800,
            "p95FrameMs": frame, "p99FrameMs": 40, "droppedFrameRate": 0.01,
            "vadToListeningP95Ms": 90, "bargeInToAudioStopP95Ms": latency,
            "audioToVisemeP95Ms": latency,
            "presentationAttestation": attestation}


class LookingGlassAcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        evidence = self.root / "raw.csv"
        evidence.write_text("frame_ms\n16.7\n16.7\n16.7\n")
        evidence_items = [{"uri": "raw.csv", "bytes": evidence.stat().st_size,
                           "sha256": hashlib.sha256(evidence.read_bytes()).hexdigest()}]
        profiles = []
        for mode in ("Realtime", "RealtimeAdaptive", "NonRealtime"):
            scenarios = []
            for name in ("idle", "listening", "thinking", "speaking"):
                relative = f"captures/{mode.lower()}--{name}.png"
                quilt = self.root / relative
                write_fixture_quilt(quilt, 4092, 4092, f"{mode}-{name}")
                attestation = fixture_attestation(
                    quilt, uri=relative, run_id="fixture-run-0001",
                    mode=mode)
                scenarios.append(scenario(name, attestation))
                evidence_items.append(attestation["captureEvidence"])
            profiles.append({"id": mode.lower(), "mode": mode, "views": 66,
                             "quiltWidth": 4092, "quiltHeight": 4092,
                             "scenarios": scenarios})
        self.payload = {
            "schemaVersion": 1, "latencyBoundary": "physical-presentation-v1",
            "measurementRunId": "fixture-run-0001", "status": "passed",
            "platform": {"os": "Win64", "gpu": "GTX 1660 Ti", "driver": "fixture"},
            "display": {"model": "Looking Glass Go", "serialHash": "a" * 64},
            "software": {"unreal": "5.6", "plugin": "2.1.1", "bridge": "2.6.3"},
            "profiles": profiles,
            "evidence": evidence_items,
        }
        self.manifest = self.root / "acceptance.json"

    def tearDown(self):
        self.temporary.cleanup()

    def write(self):
        self.manifest.write_text(json.dumps(self.payload))

    def test_passing_realtime_profile_is_accepted(self):
        self.write()
        self.assertEqual(["realtime", "realtimeadaptive"],
                         verify(self.manifest, require_passed=True)["passingRealtimeProfiles"])

    def test_slow_realtime_profiles_cannot_pass_via_nonrealtime(self):
        for profile in self.payload["profiles"][:2]:
            profile["scenarios"][0]["p95FrameMs"] = 40
        self.write()
        with self.assertRaisesRegex(ValueError, "realtime profile"):
            verify(self.manifest, require_passed=True)

    def test_tampered_evidence_is_rejected(self):
        self.write()
        (self.root / "raw.csv").write_text("tampered")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            verify(self.manifest)

    def test_audio_stop_uses_existing_150ms_budget(self):
        for profile in self.payload["profiles"]:
            for item in profile["scenarios"]:
                item["bargeInToAudioStopP95Ms"] = 149
        self.write()
        self.assertEqual("passed", verify(self.manifest, require_passed=True)["status"])
        for profile in self.payload["profiles"][:2]:
            for item in profile["scenarios"]:
                item["bargeInToAudioStopP95Ms"] = 151
        self.write()
        with self.assertRaisesRegex(ValueError, "realtime profile"):
            verify(self.manifest, require_passed=True)

    def test_false_runtime_attestation_is_rejected(self):
        self.payload["profiles"][0]["scenarios"][0][
            "presentationAttestation"]["physicalDeviceActive"] = False
        self.write()
        with self.assertRaises(Exception):
            verify(self.manifest, require_passed=True)


if __name__ == "__main__":
    unittest.main()
