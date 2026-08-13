from __future__ import annotations

import io
import hashlib
import logging
import os
import threading
import time
import wave
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import Response
from piper import PiperVoice
from pydantic import BaseModel, Field


logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s %(levelname)s %(message)s",
)
log = logging.getLogger("gahyeonbot-piper")

MODEL_PATH = Path(os.getenv("PIPER_MODEL_PATH", "/models/voice.onnx"))
CONFIG_PATH = Path(os.getenv("PIPER_CONFIG_PATH", f"{MODEL_PATH}.json"))
MODEL_ALIAS = os.getenv("PIPER_MODEL_ALIAS", "ze9-fp32-step5884")
API_KEY = os.getenv("PIPER_API_KEY", "")
MAX_CHARS = int(os.getenv("PIPER_MAX_CHARS", "500"))
USE_CUDA = os.getenv("PIPER_USE_CUDA", "false").lower() in {"1", "true", "yes"}
ADMISSION_TIMEOUT_SECONDS = float(os.getenv("PIPER_ADMISSION_TIMEOUT_SECONDS", "0.05"))
MODEL_SHA256 = os.getenv("PIPER_MODEL_SHA256", "")
CONFIG_SHA256 = os.getenv("PIPER_CONFIG_SHA256", "")
SYNTHESIS_SLOT = threading.BoundedSemaphore(1)
voice: PiperVoice | None = None


def file_sha256(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def verify_runtime_identity() -> None:
    if len(MODEL_SHA256) != 64 or file_sha256(MODEL_PATH) != MODEL_SHA256:
        raise RuntimeError("Piper model digest does not match the deployment identity")
    if len(CONFIG_SHA256) != 64 or file_sha256(CONFIG_PATH) != CONFIG_SHA256:
        raise RuntimeError("Piper config digest does not match the deployment identity")


class SynthesisRequest(BaseModel):
    text: str = Field(min_length=1)
    model: str | None = None
    speakerId: str | None = None
    format: str = "wav"


@asynccontextmanager
async def lifespan(_: FastAPI):
    global voice
    if not MODEL_PATH.is_file() or not CONFIG_PATH.is_file():
        raise RuntimeError(f"Piper model is missing: {MODEL_PATH} / {CONFIG_PATH}")
    verify_runtime_identity()
    started = time.perf_counter()
    voice = PiperVoice.load(MODEL_PATH, CONFIG_PATH, use_cuda=USE_CUDA)
    log.info("model_loaded alias=%s cuda=%s seconds=%.3f", MODEL_ALIAS, USE_CUDA,
             time.perf_counter() - started)
    yield
    voice = None


app = FastAPI(title="Gahyeonbot Piper TTS", version="1", lifespan=lifespan)


@app.get("/health")
def health() -> dict[str, object]:
    return {
        "status": "healthy" if voice is not None else "loading",
        "provider": "piper",
        "model": MODEL_ALIAS,
        "modelSha256": MODEL_SHA256,
        "configSha256": CONFIG_SHA256,
        "cuda": USE_CUDA,
        "ready": voice is not None,
    }


@app.post("/synthesize")
def synthesize(
    request: SynthesisRequest,
    authorization: str | None = Header(default=None),
) -> Response:
    if API_KEY and authorization != f"Bearer {API_KEY}":
        raise HTTPException(status_code=401, detail="invalid bearer token")
    if voice is None:
        raise HTTPException(status_code=503, detail="model is not ready")
    text = request.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="text is empty")
    if len(text) > MAX_CHARS:
        raise HTTPException(status_code=413, detail=f"text exceeds {MAX_CHARS} characters")
    if request.format.lower() != "wav":
        raise HTTPException(status_code=400, detail="only wav output is supported")
    if request.model and request.model != MODEL_ALIAS:
        raise HTTPException(status_code=404, detail="unknown model alias")

    started = time.perf_counter()
    output = io.BytesIO()
    admitted = SYNTHESIS_SLOT.acquire(timeout=max(0.0, ADMISSION_TIMEOUT_SECONDS))
    if not admitted:
        raise HTTPException(status_code=429, detail="synthesis is busy")
    try:
        with wave.open(output, "wb") as wav_file:
            voice.synthesize_wav(text, wav_file)
    finally:
        SYNTHESIS_SLOT.release()
    elapsed = time.perf_counter() - started
    payload = output.getvalue()
    with wave.open(io.BytesIO(payload), "rb") as wav_file:
        duration = wav_file.getnframes() / wav_file.getframerate()
    rtf = elapsed / duration if duration else 0.0
    log.info(
        "synthesized chars=%d audio_seconds=%.3f generation_seconds=%.3f rtf=%.3f",
        len(text), duration, elapsed, rtf,
    )
    return Response(
        content=payload,
        media_type="audio/wav",
        headers={
            "X-Piper-Model": MODEL_ALIAS,
            "X-Piper-Model-SHA256": MODEL_SHA256,
            "X-Piper-Config-SHA256": CONFIG_SHA256,
            "X-Generation-Seconds": f"{elapsed:.4f}",
            "X-Audio-Seconds": f"{duration:.4f}",
            "X-Realtime-Factor": f"{rtf:.4f}",
        },
    )
