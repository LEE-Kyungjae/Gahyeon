#!/usr/bin/env python3
"""Tests for bounded Voicebox teacher generation polling."""

from __future__ import annotations

import unittest
import sys
import json
import tempfile
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_voicebox_teacher_from_catalog import (
    append_manifest_record,
    download_audio_atomic,
    validate_voicebox_wav,
    wait_for_generation,
)


class FakeResponse:
    def __init__(self, chunks: list[bytes], content_length: str | None = None) -> None:
        self.chunks = iter(chunks)
        self.headers = {} if content_length is None else {"Content-Length": content_length}

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self, _size: int) -> bytes:
        return next(self.chunks, b"")


class WaitForGenerationTest(unittest.TestCase):
    def test_voicebox_wav_validation_accepts_mono_pcm16_and_rejects_truncation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            valid = Path(directory) / "valid.wav"
            with wave.open(str(valid), "wb") as audio:
                audio.setnchannels(1)
                audio.setsampwidth(2)
                audio.setframerate(24000)
                audio.writeframes(b"\0\0" * 2400)
            self.assertEqual(24000, validate_voicebox_wav(valid)["sampleRate"])

            truncated = Path(directory) / "truncated.wav"
            truncated.write_bytes(valid.read_bytes()[:-8])
            with self.assertRaisesRegex(ValueError, "truncated"):
                validate_voicebox_wav(truncated)

    def test_voicebox_wav_validation_rejects_non_pcm16_or_implausible_duration(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            stereo = Path(directory) / "stereo.wav"
            with wave.open(str(stereo), "wb") as audio:
                audio.setnchannels(2)
                audio.setsampwidth(2)
                audio.setframerate(24000)
                audio.writeframes(b"\0\0\0\0" * 2400)
            with self.assertRaisesRegex(ValueError, "mono PCM16"):
                validate_voicebox_wav(stereo)

            short = Path(directory) / "short.wav"
            with wave.open(str(short), "wb") as audio:
                audio.setnchannels(1)
                audio.setsampwidth(2)
                audio.setframerate(24000)
                audio.writeframes(b"\0\0" * 100)
            with self.assertRaisesRegex(ValueError, "duration"):
                validate_voicebox_wav(short)

    def test_audio_download_is_bounded_and_atomically_installed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "teacher.wav"
            size = download_audio_atomic(
                "http://voicebox/audio/job",
                destination,
                maximum_bytes=6,
                opener=lambda *_args, **_kwargs: FakeResponse([b"RI", b"FF"], "4"),
            )
            self.assertEqual(4, size)
            self.assertEqual(b"RIFF", destination.read_bytes())
            self.assertEqual([], list(Path(directory).glob(".teacher.wav.*")))

    def test_audio_download_rejects_declared_and_chunked_oversize_without_partial_file(self) -> None:
        for response in (FakeResponse([b"ignored"], "7"), FakeResponse([b"1234", b"567"])):
            with self.subTest(headers=response.headers), tempfile.TemporaryDirectory() as directory:
                destination = Path(directory) / "teacher.wav"
                with self.assertRaisesRegex(ValueError, "exceeds size limit"):
                    download_audio_atomic(
                        "http://voicebox/audio/job",
                        destination,
                        maximum_bytes=6,
                        opener=lambda *_args, response=response, **_kwargs: response,
                    )
                self.assertFalse(destination.exists())
                self.assertEqual([], list(Path(directory).glob(".teacher.wav.*")))

    def test_manifest_records_are_committed_as_complete_jsonl(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "manifest.jsonl"
            append_manifest_record(manifest, {"index": 1, "text": "첫 문장"})
            append_manifest_record(manifest, {"index": 2, "text": "둘째 문장"})
            self.assertTrue(manifest.read_bytes().endswith(b"\n"))
            self.assertEqual(
                [1, 2],
                [json.loads(line)["index"] for line in manifest.read_text().splitlines()],
            )
            self.assertEqual([], list(Path(directory).glob(".manifest.jsonl.*")))

    def test_manifest_append_rejects_an_existing_torn_line(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "manifest.jsonl"
            manifest.write_bytes(b'{"index":1')
            with self.assertRaisesRegex(ValueError, "incomplete final line"):
                append_manifest_record(manifest, {"index": 2})
            self.assertEqual(b'{"index":1', manifest.read_bytes())

    def test_returns_completed_status(self) -> None:
        statuses = iter([{"status": "pending"}, {"status": "completed", "id": "job-1"}])
        clock = iter([0.0, 0.1])

        result = wait_for_generation(
            "http://voicebox",
            "job-1",
            10,
            request=lambda _url: next(statuses),
            monotonic=lambda: next(clock),
            sleep=lambda _seconds: None,
        )

        self.assertEqual("completed", result["status"])

    def test_propagates_failed_job_error(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "model crashed"):
            wait_for_generation(
                "http://voicebox",
                "job-2",
                10,
                request=lambda _url: {"status": "failed", "error": "model crashed"},
                monotonic=lambda: 0.0,
                sleep=lambda _seconds: None,
            )

    def test_times_out_pending_job(self) -> None:
        clock = iter([0.0, 11.0])

        with self.assertRaisesRegex(TimeoutError, "job-3.*10 seconds.*pending"):
            wait_for_generation(
                "http://voicebox",
                "job-3",
                10,
                request=lambda _url: {"status": "pending"},
                monotonic=lambda: next(clock),
                sleep=lambda _seconds: None,
            )


if __name__ == "__main__":
    unittest.main()
