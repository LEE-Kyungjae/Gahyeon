#!/usr/bin/env python3

import json
import tempfile
import unittest
from pathlib import Path

from verify_looking_glass_unreal_profile import DEFAULT_BASE, DEFAULT_GO, verify


class LookingGlassUnrealProfileTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.base = self.root / "base.uproject"
        self.go = self.root / "go.uproject"
        self.base.write_bytes(DEFAULT_BASE.read_bytes())
        self.go.write_bytes(DEFAULT_GO.read_bytes())

    def tearDown(self):
        self.temporary.cleanup()

    def mutate(self, path, callback):
        payload = json.loads(path.read_text())
        callback(payload)
        path.write_text(json.dumps(payload))

    def test_current_profiles_are_isolated(self):
        self.assertEqual("Win64", verify(self.base, self.go)["platform"])

    def test_go_cannot_change_core_plugin_set(self):
        self.mutate(self.go, lambda payload: payload["Plugins"].remove(
            next(item for item in payload["Plugins"] if item["Name"] == "WebSockets")))
        with self.assertRaisesRegex(ValueError, "non-Looking-Glass plugin"):
            verify(self.base, self.go)

    def test_canonical_project_cannot_enable_looking_glass(self):
        self.mutate(self.base, lambda payload: payload["Plugins"].append(
            {"Name": "LookingGlass", "Enabled": True}))
        with self.assertRaisesRegex(ValueError, "canonical Stage"):
            verify(self.base, self.go)

    def test_canonical_project_cannot_load_adapter(self):
        adapter = next(item for item in json.loads(self.go.read_text())["Modules"]
                       if item["Name"] == "GahyeonLookingGlassAdapter")
        self.mutate(self.base, lambda payload: payload["Modules"].append(adapter))
        with self.assertRaisesRegex(ValueError, "canonical Stage"):
            verify(self.base, self.go)

    def test_adapter_must_remain_win64_only(self):
        self.mutate(self.go, lambda payload: next(
            item for item in payload["Modules"]
            if item["Name"] == "GahyeonLookingGlassAdapter").update(
                {"PlatformAllowList": ["Win64", "Mac"]}))
        with self.assertRaisesRegex(ValueError, "Win64-only"):
            verify(self.base, self.go)


if __name__ == "__main__":
    unittest.main()
