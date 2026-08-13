#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import io
import sys
import unittest
import urllib.request
import wave
from email.message import Message
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("verify_piper_runtime_smoke.py")
SPEC = importlib.util.spec_from_file_location("verify_piper_runtime_smoke", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def wav_bytes(rate: int = 22_050, seconds: float = 0.5) -> bytes:
    output = io.BytesIO()
    with wave.open(output, "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(rate)
        audio.writeframes(b"\0\0" * int(rate * seconds))
    return output.getvalue()


class FakeResponse:
    def __init__(self, body: bytes, *, alias: str = "voice", sha: str = "a" * 64,
                 config_sha: str = "c" * 64, rtf: str = "0.25"):
        self.body = body
        self.headers = Message()
        self.headers["X-Piper-Model"] = alias
        self.headers["X-Piper-Model-SHA256"] = sha
        self.headers["X-Piper-Config-SHA256"] = config_sha
        self.headers["X-Realtime-Factor"] = rtf

    def __enter__(self): return self
    def __exit__(self, *_): return False
    def read(self, limit: int) -> bytes: return self.body[:limit]


class PiperRuntimeSmokeTest(unittest.TestCase):
    def test_accepts_identity_bound_realtime_pcm_wav(self) -> None:
        with patch.object(urllib.request, "urlopen", return_value=FakeResponse(wav_bytes())):
            result = MODULE.probe(
                "http://runtime/synthesize", "voice", "a" * 64, "c" * 64, 1.0, 3)
        self.assertTrue(result["ready"])
        self.assertEqual(result["sampleRate"], 22_050)

    def test_rejects_wrong_digest_or_slow_model(self) -> None:
        with patch.object(urllib.request, "urlopen", return_value=FakeResponse(wav_bytes(), sha="b" * 64)):
            with self.assertRaisesRegex(RuntimeError, "wrong model digest"):
                MODULE.probe(
                    "http://runtime/synthesize", "voice", "a" * 64, "c" * 64, 1.0, 3)
        with patch.object(urllib.request, "urlopen", return_value=FakeResponse(wav_bytes(), rtf="1.2")):
            with self.assertRaisesRegex(RuntimeError, "exceeds deployment gate"):
                MODULE.probe(
                    "http://runtime/synthesize", "voice", "a" * 64, "c" * 64, 1.0, 3)

    def test_rejects_invalid_or_too_short_wav(self) -> None:
        with patch.object(urllib.request, "urlopen", return_value=FakeResponse(b"not-wav")):
            with self.assertRaisesRegex(RuntimeError, "not a valid WAV"):
                MODULE.probe(
                    "http://runtime/synthesize", "voice", "a" * 64, "c" * 64, 1.0, 3)
        with patch.object(urllib.request, "urlopen", return_value=FakeResponse(wav_bytes(seconds=0.1))):
            with self.assertRaisesRegex(RuntimeError, "format/duration is invalid"):
                MODULE.probe(
                    "http://runtime/synthesize", "voice", "a" * 64, "c" * 64, 1.0, 3)


if __name__ == "__main__":
    unittest.main()
