#!/usr/bin/env python3

from __future__ import annotations

import json
import hashlib
import subprocess
import tempfile
import unittest
import wave
from pathlib import Path


SCRIPT = Path(__file__).with_name("package_voicebox_teacher_dataset.py")


class PackageVoiceboxTeacherTest(unittest.TestCase):
    def test_packages_only_accepted_rows_and_is_idempotent(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            audio = root / "source.wav"
            with wave.open(str(audio), "wb") as wav:
                wav.setnchannels(1)
                wav.setsampwidth(2)
                wav.setframerate(16_000)
                wav.writeframes(b"\0\0" * 16_000)
            rows = [
                {
                    "index": 1, "audio": str(audio), "text": "정상 문장", "cer": 0.0,
                    "duration": 1.0, "accepted": True,
                },
                {
                    "index": 2, "audio": str(audio), "text": "탈락 문장", "cer": 1.0,
                    "duration": 1.0, "accepted": False,
                },
            ]
            (root / "stt_qc.jsonl").write_text(
                "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8"
            )
            archive_digest = None
            for _ in range(2):
                completed = subprocess.run(
                    ["python3", str(SCRIPT), "--root", str(root)],
                    text=True, capture_output=True,
                )
                self.assertEqual(completed.returncode, 0, completed.stderr)
                report = json.loads(completed.stdout)
                self.assertEqual(report["clips"], 1)
                self.assertEqual(report["archive_bytes"], Path(report["archive"]).stat().st_size)
                current_digest = hashlib.sha256(Path(report["archive"]).read_bytes()).hexdigest()
                self.assertEqual(report["archive_sha256"], current_digest)
                if archive_digest is not None:
                    self.assertEqual(archive_digest, current_digest)
                archive_digest = current_digest
            self.assertEqual(len(list((root / "piper_dataset/wav").glob("*.wav"))), 1)
            self.assertEqual(
                (root / "piper_dataset/metadata.csv").read_text(encoding="utf-8"),
                "teacher_0001_pteacher.wav|정상 문장\n",
            )

    def test_rejects_a_selection_below_the_training_minimum(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "stt_qc.jsonl").write_text(
                json.dumps({
                    "index": 1, "audio": "missing.wav", "text": "한 문장",
                    "cer": 0.0, "duration": 1.0, "accepted": True,
                }, ensure_ascii=False) + "\n",
                encoding="utf-8",
            )

            completed = subprocess.run(
                ["python3", str(SCRIPT), "--root", str(root), "--min-clips", "2"],
                text=True, capture_output=True,
            )

            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("below required minimum 2", completed.stderr)
            self.assertFalse((root / "voicebox-teacher-piper-dataset.tar.gz").exists())

    def test_rejects_duplicate_pcm_identity_before_conversion(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            rows = [{
                "index": index, "audio": "missing.wav", "text": f"문장 {index}",
                "cer": 0.0, "duration": 1.0, "accepted": True,
                "pcmSha256": "a" * 64,
            } for index in (1, 2)]
            (root / "stt_qc.jsonl").write_text(
                "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
                encoding="utf-8",
            )
            completed = subprocess.run([
                "python3", str(SCRIPT), "--root", str(root), "--require-audio-identity",
            ], text=True, capture_output=True)
            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("duplicate PCM identities", completed.stderr)
            self.assertFalse((root / "voicebox-teacher-piper-dataset.tar.gz").exists())

    def test_normalizes_redundant_terminal_punctuation_and_rejects_repeated_clause(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            audio = root / "source.wav"
            with wave.open(str(audio), "wb") as wav:
                wav.setnchannels(1)
                wav.setsampwidth(2)
                wav.setframerate(16_000)
                wav.writeframes(b"\0\0" * 8_000)
            rows = [
                {
                    "index": 1, "audio": str(audio),
                    "text": "그는 준비됐다고 말했다.”.", "cer": 0.0,
                    "duration": 0.5, "accepted": True,
                },
                {
                    "index": 2, "audio": str(audio),
                    "text": "같은 구절 세 단어 같은 구절 세 단어", "cer": 0.0,
                    "duration": 0.5, "accepted": True,
                },
            ]
            (root / "stt_qc.jsonl").write_text(
                "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
                encoding="utf-8",
            )

            completed = subprocess.run(
                ["python3", str(SCRIPT), "--root", str(root), "--min-clips", "1"],
                text=True, capture_output=True,
            )

            self.assertEqual(completed.returncode, 0, completed.stderr)
            report = json.loads(completed.stdout)
            self.assertEqual(report["clips"], 1)
            self.assertEqual(report["text_normalized"], 1)
            self.assertEqual(report["text_rejected"], 1)
            self.assertEqual(
                (root / "piper_dataset/metadata.csv").read_text(encoding="utf-8"),
                "teacher_0001_pteacher.wav|그는 준비됐다고 말했다.”\n",
            )
            manifest = json.loads(
                (root / "piper_dataset/manifest.json").read_text(encoding="utf-8")
            )
            self.assertTrue(manifest[0]["textNormalized"])
            self.assertEqual(manifest[0]["sourceText"], "그는 준비됐다고 말했다.”.")


if __name__ == "__main__":
    unittest.main()
