#!/usr/bin/env python3

from __future__ import annotations

import json
import math
import struct
import subprocess
import tempfile
import unittest
import wave
from pathlib import Path


SCRIPT = Path(__file__).with_name("acoustic_qc_voicebox_teacher.py")


class AcousticQcTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def wav(self, name: str, amplitude: float, seconds: float = 1.0) -> Path:
        path = self.root / name
        rate = 16_000
        values = [
            int(amplitude * 32767 * math.sin(2 * math.pi * 220 * index / rate))
            for index in range(int(rate * seconds))
        ]
        with wave.open(str(path), "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(rate)
            wav.writeframes(struct.pack(f"<{len(values)}h", *values))
        return path

    def run_qc(self) -> dict:
        completed = subprocess.run(
            ["python3", str(SCRIPT), "--root", str(self.root), "--require-count", "2"],
            text=True,
            capture_output=True,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        return json.loads(completed.stdout)

    def test_rejects_clipping_and_reuses_unchanged_results(self) -> None:
        clean = self.wav("clean.wav", 0.25)
        clipped = self.root / "clipped.wav"
        with wave.open(str(clipped), "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(16_000)
            wav.writeframes(struct.pack("<16000h", *([32767] * 16_000)))
        manifest = [
            {"index": 1, "text": "정상 문장", "audio": str(clean)},
            {"index": 2, "text": "클리핑 문장", "audio": str(clipped)},
        ]
        (self.root / "manifest.jsonl").write_text(
            "".join(json.dumps(item, ensure_ascii=False) + "\n" for item in manifest),
            encoding="utf-8",
        )
        first = self.run_qc()
        self.assertEqual((first["accepted"], first["rejected"], first["analyzed"]), (1, 1, 2))
        second = self.run_qc()
        self.assertEqual((second["reused"], second["analyzed"]), (2, 0))
        metadata = (self.root / "metadata_acoustic_accepted.csv").read_text(encoding="utf-8")
        self.assertEqual(metadata, "clean.wav|정상 문장\n")

    def test_unreadable_wav_is_rejected_without_aborting_batch(self) -> None:
        clean = self.wav("clean.wav", 0.25)
        broken = self.root / "broken.wav"
        broken.write_bytes(b"not a wav")
        manifest = [
            {"index": 1, "text": "정상 문장", "audio": str(clean)},
            {"index": 2, "text": "손상 문장", "audio": str(broken)},
        ]
        (self.root / "manifest.jsonl").write_text(
            "".join(json.dumps(item, ensure_ascii=False) + "\n" for item in manifest),
            encoding="utf-8",
        )
        result = self.run_qc()
        self.assertEqual((result["accepted"], result["rejected"]), (1, 1))
        rows = [json.loads(line) for line in (self.root / "acoustic_qc.jsonl").read_text().splitlines()]
        self.assertEqual(rows[1]["reasons"], ["unreadable_wav"])

    def test_exact_pcm_duplicate_is_a_hard_gate_and_recovers_after_replacement(self) -> None:
        first = self.wav("first.wav", 0.25)
        second = self.root / "second.wav"
        second.write_bytes(first.read_bytes())
        manifest = [
            {"index": 1, "text": "첫 번째 문장", "audio": str(first)},
            {"index": 2, "text": "두 번째 문장", "audio": str(second)},
        ]
        (self.root / "manifest.jsonl").write_text(
            "".join(json.dumps(item, ensure_ascii=False) + "\n" for item in manifest),
            encoding="utf-8",
        )
        command = [
            "python3", str(SCRIPT), "--root", str(self.root), "--require-count", "2",
            "--require-unique-audio",
        ]
        duplicate = subprocess.run(command, text=True, capture_output=True)
        self.assertEqual(duplicate.returncode, 2)
        summary = json.loads(duplicate.stdout)
        self.assertEqual((summary["duplicateAudioGroups"], summary["duplicateAudioClips"]), (1, 2))
        self.assertEqual(summary["accepted"], 0)

        self.wav("second.wav", 0.30)
        recovered = subprocess.run(command, text=True, capture_output=True)
        self.assertEqual(recovered.returncode, 0, recovered.stderr)
        summary = json.loads(recovered.stdout)
        self.assertTrue(summary["uniqueAudio"])
        self.assertEqual(summary["accepted"], 2)


if __name__ == "__main__":
    unittest.main()
