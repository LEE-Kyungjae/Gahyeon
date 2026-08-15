#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import hashlib
import io
import os
import sys
import tempfile
import types
import unittest
import wave
from pathlib import Path
from unittest import mock


SERVER = Path(__file__).parents[1] / "infra/images/piper/server.py"


class FakeHttpException(Exception):
    def __init__(self, status_code: int, detail: str):
        super().__init__(detail)
        self.status_code = status_code
        self.detail = detail


class FakeResponse:
    def __init__(self, *, content: bytes, media_type: str, headers: dict[str, str]):
        self.body = content
        self.media_type = media_type
        self.headers = headers


class FakeFastApi:
    def __init__(self, **_: object): pass
    def get(self, _: str): return lambda function: function
    def post(self, _: str): return lambda function: function


class FakePiperVoice:
    @classmethod
    def load(cls, *_: object, **__: object):
        return cls()

    def synthesize(self, text: str, syn_config=None):
        self.last_synthesis_config = syn_config
        sentence_count = 2 if "." in text else 1
        for _ in range(sentence_count):
            yield types.SimpleNamespace(
                sample_channels=1,
                sample_width=2,
                sample_rate=16_000,
                audio_int16_bytes=b"\0\0" * 1_600,
            )


class FakeBaseModel:
    def __init__(self, **values: object):
        for name in self.__class__.__annotations__:
            setattr(self, name, values.get(name, getattr(self.__class__, name, None)))


def load_server():
    fastapi = types.ModuleType("fastapi")
    fastapi.FastAPI = FakeFastApi
    fastapi.Header = lambda default=None: default
    fastapi.HTTPException = FakeHttpException
    responses = types.ModuleType("fastapi.responses")
    responses.Response = FakeResponse
    piper = types.ModuleType("piper")
    piper.PiperVoice = FakePiperVoice
    piper.SynthesisConfig = lambda **values: types.SimpleNamespace(**values)
    pydantic = types.ModuleType("pydantic")
    pydantic.BaseModel = FakeBaseModel
    pydantic.Field = lambda **_: None
    sys.modules["fastapi"] = fastapi
    sys.modules["fastapi.responses"] = responses
    sys.modules["piper"] = piper
    sys.modules["pydantic"] = pydantic
    os.environ["PIPER_MODEL_ALIAS"] = "test-voice"
    os.environ["PIPER_MODEL_SHA256"] = "a" * 64
    os.environ["PIPER_CONFIG_SHA256"] = "c" * 64
    os.environ["PIPER_ADMISSION_TIMEOUT_SECONDS"] = "0"
    specification = importlib.util.spec_from_file_location("gahyeon_piper_server_test", SERVER)
    module = importlib.util.module_from_spec(specification)
    assert specification.loader is not None
    specification.loader.exec_module(module)
    return module


class PiperRuntimeServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.server = load_server()

    def setUp(self) -> None:
        self.server.voice = FakePiperVoice()

    def test_returns_valid_wav_and_runtime_identity_headers(self) -> None:
        response = self.server.synthesize(
            self.server.SynthesisRequest(text="안녕하세요", model="test-voice", format="wav"),
            authorization=None,
        )
        self.assertEqual(response.media_type, "audio/wav")
        self.assertEqual(response.body[:4], b"RIFF")
        self.assertEqual(response.headers["X-Piper-Model"], "test-voice")
        self.assertEqual(response.headers["X-Piper-Model-SHA256"], "a" * 64)
        self.assertEqual(response.headers["X-Piper-Config-SHA256"], "c" * 64)
        self.assertGreater(float(response.headers["X-Audio-Seconds"]), 0)
        self.assertGreaterEqual(float(response.headers["X-Realtime-Factor"]), 0)
        self.assertEqual(response.headers["X-Sentence-Silence-Seconds"], "0.280")
        self.assertEqual(response.headers["X-Audio-Denoised"], "true")

    def test_inserts_silence_between_sentence_chunks(self) -> None:
        payload = self.server.synthesize_payload("첫 문장. 두 번째 문장")
        with wave.open(io.BytesIO(payload), "rb") as wav_file:
            expected_frames = 3_200 + round(16_000 * 0.28)
            self.assertEqual(wav_file.getnframes(), expected_frames)

    def test_uses_listener_selected_prosody(self) -> None:
        self.server.synthesize_payload("억양 설정 확인")
        config = self.server.voice.last_synthesis_config
        self.assertEqual(config.length_scale, 1.03)
        self.assertEqual(config.noise_scale, 0.85)
        self.assertEqual(config.noise_w_scale, 0.95)

    def test_github_pronunciation_survives_transliterator_failure(self) -> None:
        prepared, mode = self.server.prepare_synthesis_text("GitHub 확인")
        self.assertEqual(prepared, "[[ɡithʌbɯ]] 확인")
        self.assertEqual(mode, "pronunciation-dictionary-fallback")

    def test_uses_approved_medium_noise_filter(self) -> None:
        payload = self.server.synthesize_payload("필터 확인")
        completed = types.SimpleNamespace(stdout=b"\0\0" * 1_600, stderr=b"")
        with mock.patch.object(self.server.subprocess, "run", return_value=completed) as run:
            self.server.normalize_loudness(payload, 16_000)
        command = run.call_args.args[0]
        self.assertEqual(
            command[command.index("-af") + 1],
            "highpass=f=80,afftdn=nr=10:nf=-32:tn=1,loudnorm=I=-12:TP=-1:LRA=7",
        )

    def test_health_binds_alias_sha_and_readiness(self) -> None:
        self.assertEqual(self.server.health(), {
            "status": "healthy", "provider": "piper", "model": "test-voice",
            "modelSha256": "a" * 64, "configSha256": "c" * 64,
            "cuda": False, "ready": True,
        })

    def test_startup_recomputes_model_and_config_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            model = root / "voice.onnx"
            config = root / "voice.onnx.json"
            model.write_bytes(b"model")
            config.write_bytes(b"config")
            original = (
                self.server.MODEL_PATH, self.server.CONFIG_PATH,
                self.server.MODEL_SHA256, self.server.CONFIG_SHA256,
            )
            try:
                self.server.MODEL_PATH = model
                self.server.CONFIG_PATH = config
                self.server.MODEL_SHA256 = hashlib.sha256(model.read_bytes()).hexdigest()
                self.server.CONFIG_SHA256 = hashlib.sha256(config.read_bytes()).hexdigest()
                self.server.verify_runtime_identity()
                self.server.MODEL_SHA256 = "0" * 64
                with self.assertRaisesRegex(RuntimeError, "model digest"):
                    self.server.verify_runtime_identity()
            finally:
                (self.server.MODEL_PATH, self.server.CONFIG_PATH,
                 self.server.MODEL_SHA256, self.server.CONFIG_SHA256) = original

    def test_busy_runtime_rejects_instead_of_building_an_unbounded_queue(self) -> None:
        self.assertTrue(self.server.SYNTHESIS_SLOT.acquire(blocking=False))
        try:
            with self.assertRaises(FakeHttpException) as captured:
                self.server.synthesize(
                    self.server.SynthesisRequest(text="대기하지 않을 문장"), authorization=None)
            self.assertEqual(captured.exception.status_code, 429)
        finally:
            self.server.SYNTHESIS_SLOT.release()

    def test_synthesis_failure_releases_the_admission_slot(self) -> None:
        class BrokenVoice:
            def synthesize(self, _: str, syn_config=None):
                raise RuntimeError("broken model")
                yield
        self.server.voice = BrokenVoice()
        with self.assertRaises(FakeHttpException) as captured:
            self.server.synthesize(
                self.server.SynthesisRequest(text="실패할 문장"), authorization=None)
        self.assertEqual(captured.exception.status_code, 422)
        self.assertTrue(self.server.SYNTHESIS_SLOT.acquire(blocking=False))
        self.server.SYNTHESIS_SLOT.release()

    def test_rejects_wrong_model_format_and_bearer_token(self) -> None:
        with self.assertRaises(FakeHttpException) as wrong_model:
            self.server.synthesize(
                self.server.SynthesisRequest(text="문장", model="another-model"), None)
        self.assertEqual(wrong_model.exception.status_code, 404)
        with self.assertRaises(FakeHttpException) as wrong_format:
            self.server.synthesize(
                self.server.SynthesisRequest(text="문장", format="mp3"), None)
        self.assertEqual(wrong_format.exception.status_code, 400)
        self.server.API_KEY = "secret"
        try:
            with self.assertRaises(FakeHttpException) as unauthorized:
                self.server.synthesize(self.server.SynthesisRequest(text="문장"), None)
            self.assertEqual(unauthorized.exception.status_code, 401)
            response = self.server.synthesize(
                self.server.SynthesisRequest(text="문장"), "Bearer secret")
            self.assertEqual(response.body[:4], b"RIFF")
        finally:
            self.server.API_KEY = ""


if __name__ == "__main__":
    unittest.main()
