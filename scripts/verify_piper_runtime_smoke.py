#!/usr/bin/env python3
"""Synthesize and validate one identity-bound Piper runtime response."""

from __future__ import annotations

import argparse
import io
import json
import urllib.request
import wave


def probe(endpoint: str, alias: str, model_sha256: str, config_sha256: str,
          max_rtf: float, timeout: float) -> dict:
    request = urllib.request.Request(
        endpoint,
        data=json.dumps({
            "text": "가현의 새 음성 모델 배포 상태를 확인하고 있어요.",
            "model": alias,
            "format": "wav",
        }, ensure_ascii=False).encode("utf-8"),
        headers={"content-type": "application/json", "accept": "audio/wav"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        payload = response.read(32 * 1024 * 1024 + 1)
        if len(payload) > 32 * 1024 * 1024:
            raise RuntimeError("Piper smoke response exceeds 32 MiB")
        headers = response.headers
    if headers.get("X-Piper-Model") != alias:
        raise RuntimeError("Piper smoke response has the wrong model alias")
    if headers.get("X-Piper-Model-SHA256") != model_sha256:
        raise RuntimeError("Piper smoke response has the wrong model digest")
    if headers.get("X-Piper-Config-SHA256") != config_sha256:
        raise RuntimeError("Piper smoke response has the wrong config digest")
    try:
        rtf = float(headers["X-Realtime-Factor"])
    except (KeyError, TypeError, ValueError) as error:
        raise RuntimeError("Piper smoke response has no valid realtime factor") from error
    if not 0 <= rtf <= max_rtf:
        raise RuntimeError(f"Piper realtime factor {rtf:.3f} exceeds deployment gate {max_rtf:.3f}")
    try:
        with wave.open(io.BytesIO(payload), "rb") as audio:
            channels = audio.getnchannels()
            sample_width = audio.getsampwidth()
            sample_rate = audio.getframerate()
            frames = audio.getnframes()
    except (EOFError, wave.Error) as error:
        raise RuntimeError("Piper smoke response is not a valid WAV") from error
    duration = frames / sample_rate if sample_rate else 0.0
    if channels != 1 or sample_width != 2 or sample_rate < 16_000 or duration < 0.25:
        raise RuntimeError(
            "Piper smoke WAV format/duration is invalid: "
            f"channels={channels} width={sample_width} rate={sample_rate} duration={duration:.3f}"
        )
    return {
        "ready": True,
        "model": alias,
        "modelSha256": model_sha256,
        "configSha256": config_sha256,
        "realtimeFactor": rtf,
        "audioSeconds": round(duration, 4),
        "sampleRate": sample_rate,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--model-sha256", required=True)
    parser.add_argument("--config-sha256", required=True)
    parser.add_argument("--max-rtf", type=float, default=1.0)
    parser.add_argument("--timeout", type=float, default=30.0)
    args = parser.parse_args()
    print(json.dumps(probe(
        args.endpoint, args.model, args.model_sha256, args.config_sha256,
        args.max_rtf, args.timeout
    ), ensure_ascii=False))


if __name__ == "__main__":
    main()
