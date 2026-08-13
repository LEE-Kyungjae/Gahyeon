#!/usr/bin/env python3

from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
import wave
from pathlib import Path


SCRIPT = Path(__file__).with_name("check_voicebox_teacher_progress.py")


class VoiceboxTeacherProgressTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.output = self.root / "output"
        self.audio = self.output / "profile_9"
        self.audio.mkdir(parents=True)
        self.catalog = self.root / "catalog.jsonl"
        self.catalog.write_text(
            "".join(
                json.dumps({"index": index, "text": f"문장 {index}"}, ensure_ascii=False) + "\n"
                for index in range(1, 4)
            ),
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write_wav(self, index: int) -> Path:
        path = self.audio / f"teacher_{index:04d}.wav"
        with wave.open(str(path), "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(22050)
            wav.writeframes(b"\x00\x00" * 2205)
        return path

    def write_manifest(self, indices: list[int]) -> None:
        rows = []
        for index in indices:
            path = self.write_wav(index)
            rows.append({"index": index, "text": f"문장 {index}", "audio": str(path)})
        (self.output / "manifest.jsonl").write_text(
            "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
            encoding="utf-8",
        )

    def run_checker(self, *extra: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                "python3", str(SCRIPT), "--catalog", str(self.catalog),
                "--output", str(self.output), "--target", "3", *extra,
            ],
            text=True,
            capture_output=True,
        )

    def test_accepts_contiguous_complete_manifest_and_probes_wav(self) -> None:
        self.write_manifest([1, 2, 3])
        result = self.run_checker("--probe-wav", "--require-complete")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(json.loads(result.stdout)["ready"])

    def test_reports_valid_incomplete_progress(self) -> None:
        self.write_manifest([1, 2])
        result = self.run_checker("--field", "remaining")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "1")

    def test_rejects_gap_duplicate_and_escaped_audio(self) -> None:
        self.write_manifest([1, 3])
        self.assertNotEqual(self.run_checker().returncode, 0)

        path = self.write_wav(1)
        duplicate = {"index": 1, "text": "문장 1", "audio": str(path)}
        (self.output / "manifest.jsonl").write_text(
            json.dumps(duplicate, ensure_ascii=False) + "\n"
            + json.dumps(duplicate, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
        self.assertNotEqual(self.run_checker().returncode, 0)

        outside = self.root / "outside.wav"
        outside.write_bytes(path.read_bytes())
        escaped = {"index": 1, "text": "문장 1", "audio": str(outside)}
        (self.output / "manifest.jsonl").write_text(
            json.dumps(escaped, ensure_ascii=False) + "\n", encoding="utf-8"
        )
        self.assertNotEqual(self.run_checker().returncode, 0)

    def test_rejects_catalog_text_duplicated_only_by_punctuation(self) -> None:
        self.catalog.write_text(
            "".join(
                json.dumps(row, ensure_ascii=False) + "\n"
                for row in (
                    {"index": 1, "text": "같은 문장."},
                    {"index": 2, "text": "같은 문장!"},
                    {"index": 3, "text": "다른 문장."},
                )
            ),
            encoding="utf-8",
        )
        result = self.run_checker()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("duplicate text", result.stderr)

    def test_probe_rejects_readable_but_non_training_wav(self) -> None:
        path = self.audio / "teacher_0001.wav"
        with wave.open(str(path), "wb") as wav:
            wav.setnchannels(2)
            wav.setsampwidth(2)
            wav.setframerate(24000)
            wav.writeframes(b"\0\0\0\0" * 2400)
        (self.output / "manifest.jsonl").write_text(
            json.dumps({"index": 1, "text": "문장 1", "audio": str(path)}, ensure_ascii=False)
            + "\n",
            encoding="utf-8",
        )

        result = self.run_checker("--probe-wav")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("mono PCM16", result.stderr)


if __name__ == "__main__":
    unittest.main()
