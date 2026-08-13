#!/usr/bin/env python3

import json
import tempfile
import unittest
from pathlib import Path

from build_desktop_realtime_acceptance import build
from verify_desktop_realtime_acceptance import verify


class DesktopRealtimeAcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.raw = self.root / "raw.json"
        self.output = self.root / "acceptance.json"
        self.payload = {
            "schemaVersion": 1,
            "measurementRunId": "desktop-run-0001",
            "renderer": "desktop-single-view",
            "latencyBoundary": "physical-presentation-v1",
            "durationSeconds": 600,
            "frameMs": [16.7] * 6000,
            "reflexUpdates": 12000,
            "behaviorUpdates": 3000,
            "maxReflexGapMs": 60,
            "maxBehaviorGapMs": 220,
            "vadToListeningMs": [70] * 20,
            "bargeInToAudioStopMs": [90] * 20,
            "audioToVisemeMs": [45] * 20,
            "os": "Windows 11",
            "gpu": "GTX 1660 Ti",
        }

    def tearDown(self):
        self.temporary.cleanup()

    def write(self):
        self.raw.write_text(json.dumps(self.payload))

    def test_complete_physical_run_passes(self):
        self.write()
        result = build(self.raw, self.output)
        self.assertEqual("passed", result["status"])
        self.assertTrue(result["cadencePassed"])
        self.assertEqual(45, result["audioToVisemeP95Ms"])
        self.assertEqual("passed", verify(self.output, require_passed=True)["status"])

    def test_missing_interactions_remains_measured_not_passed(self):
        self.payload["audioToVisemeMs"] = []
        self.write()
        result = build(self.raw, self.output)
        self.assertEqual("measured", result["status"])
        self.assertFalse(result["interactionLatencyMeasured"])

    def test_frozen_behavior_fails_cadence(self):
        self.payload["maxBehaviorGapMs"] = 900
        self.write()
        result = build(self.raw, self.output)
        self.assertFalse(result["cadencePassed"])
        self.assertEqual("measured", result["status"])

    def test_cursor_only_latency_is_rejected(self):
        del self.payload["latencyBoundary"]
        self.write()
        with self.assertRaisesRegex(ValueError, "physical presentation"):
            build(self.raw, self.output)

    def test_short_or_sparse_run_is_rejected(self):
        self.payload["durationSeconds"] = 599
        self.write()
        with self.assertRaisesRegex(ValueError, "10 to 60"):
            build(self.raw, self.output)
        self.payload["durationSeconds"] = 600
        self.payload["frameMs"] = [16.7] * 5999
        self.write()
        with self.assertRaisesRegex(ValueError, "at least 6000"):
            build(self.raw, self.output)

    def test_tampered_raw_evidence_is_rejected(self):
        self.write()
        build(self.raw, self.output)
        self.raw.write_text("tampered")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            verify(self.output)

    def test_forged_pass_status_is_rejected(self):
        self.payload["audioToVisemeMs"] = []
        self.write()
        build(self.raw, self.output)
        result = json.loads(self.output.read_text())
        result["status"] = "passed"
        self.output.write_text(json.dumps(result))
        with self.assertRaisesRegex(ValueError, "status does not match"):
            verify(self.output)

    def test_forged_frame_summary_is_rejected_against_raw(self):
        self.write()
        build(self.raw, self.output)
        result = json.loads(self.output.read_text())
        result["p95FrameMs"] = 1.0
        result["p99FrameMs"] = 1.0
        self.output.write_text(json.dumps(result))
        with self.assertRaisesRegex(ValueError, "does not match raw evidence: p95FrameMs"):
            verify(self.output)

    def test_forged_latency_summary_is_rejected_against_raw(self):
        self.write()
        build(self.raw, self.output)
        result = json.loads(self.output.read_text())
        result["audioToVisemeP95Ms"] = 1.0
        self.output.write_text(json.dumps(result))
        with self.assertRaisesRegex(ValueError, "does not match raw evidence: audioToVisemeP95Ms"):
            verify(self.output)


if __name__ == "__main__":
    unittest.main()
