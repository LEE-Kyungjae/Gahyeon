#!/usr/bin/env python3

import json
import tempfile
import unittest
from pathlib import Path

from verify_looking_glass_integration import DEFAULT_LOCK, DEFAULT_PROJECT, verify


class LookingGlassIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.lock = self.root / "lock.json"
        self.project = self.root / "project.uproject"
        self.lock.write_bytes(DEFAULT_LOCK.read_bytes())
        self.project.write_bytes(DEFAULT_PROJECT.read_bytes())

    def tearDown(self):
        self.temp.cleanup()

    def mutate(self, path, callback):
        payload = json.loads(path.read_text(encoding="utf-8"))
        callback(payload)
        path.write_text(json.dumps(payload), encoding="utf-8")

    def test_current_optional_integration_contract_is_valid(self):
        self.assertEqual("2.1.1", verify(self.lock, self.project)["release"])

    def test_enabling_plugin_before_acceptance_is_rejected(self):
        self.mutate(self.project, lambda payload: payload["Plugins"].append(
            {"Name": "LookingGlass", "Enabled": True}))
        with self.assertRaisesRegex(ValueError, "cannot be enabled"):
            verify(self.lock, self.project)

    def test_claiming_upstream_recommends_realtime_production_is_rejected(self):
        self.mutate(self.lock, lambda payload: payload["compatibility"].update(
            {"upstreamRecommendsRealtimeProduction": True}))
        with self.assertRaisesRegex(ValueError, "compatibility facts"):
            verify(self.lock, self.project)


if __name__ == "__main__":
    unittest.main()
