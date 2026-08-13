#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_piper_release import REQUIRED, verify


class VerifyPiperReleaseTest(unittest.TestCase):
    def make_release(self, root: Path) -> Path:
        release = root / "release"
        release.mkdir()
        identities = {}
        for name in REQUIRED:
            path = release / name
            path.parent.mkdir(parents=True, exist_ok=True)
            content = f"fixture:{name}".encode()
            path.write_bytes(content)
            identities[name] = {"bytes": len(content),
                                "sha256": hashlib.sha256(content).hexdigest()}
        (release / "release.json").write_text(json.dumps({
            "schemaVersion": 2, "status": "approved", "modelAlias": "gahyeon-test",
            "modelSha256": identities["voice.onnx"]["sha256"],
            "configSha256": identities["voice.onnx.json"]["sha256"],
            "listeningDecisionSha256": identities["listening-decision.json"]["sha256"],
            "artifacts": identities,
        }), encoding="utf-8")
        return release

    def test_accepts_complete_sealed_release(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            payload = verify(self.make_release(Path(temporary)))
            self.assertEqual("gahyeon-test", payload["modelAlias"])

    def test_rejects_tampered_evidence_and_undeclared_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            release = self.make_release(Path(temporary))
            (release / "evaluation-suite.json").write_bytes(b"tampered")
            with self.assertRaisesRegex(ValueError, "byte size mismatch|checksum mismatch"):
                verify(release)
        with tempfile.TemporaryDirectory() as temporary:
            release = self.make_release(Path(temporary))
            (release / "surprise.bin").write_bytes(b"surprise")
            with self.assertRaisesRegex(ValueError, "files do not match"):
                verify(release)

    def test_rejects_identity_inconsistency(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            release = self.make_release(Path(temporary))
            manifest = json.loads((release / "release.json").read_text())
            manifest["modelSha256"] = "0" * 64
            (release / "release.json").write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, "model identity"):
                verify(release)


if __name__ == "__main__":
    unittest.main()
