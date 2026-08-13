#!/usr/bin/env python3
"""Single authoritative PCM WAV contract for Voicebox teacher artifacts."""

from __future__ import annotations

import wave
from pathlib import Path


MAX_AUDIO_BYTES = 32 * 1024 * 1024


def validate_voicebox_wav(path: Path) -> dict:
    """Reject mislabeled, unsupported, empty or truncated teacher audio."""
    if not path.is_file() or path.stat().st_size > MAX_AUDIO_BYTES:
        raise ValueError("Voicebox audio file is missing or exceeds size limit")
    try:
        with wave.open(str(path), "rb") as audio:
            channels = audio.getnchannels()
            sample_width = audio.getsampwidth()
            sample_rate = audio.getframerate()
            frames = audio.getnframes()
            compression = audio.getcomptype()
            if channels != 1 or sample_width != 2 or compression != "NONE":
                raise ValueError("Voicebox audio must be mono PCM16 WAV")
            if not 16000 <= sample_rate <= 48000:
                raise ValueError("Voicebox audio sample rate is outside 16-48 kHz")
            if frames < sample_rate // 10 or frames > sample_rate * 120:
                raise ValueError("Voicebox audio duration is outside 0.1-120 seconds")
            payload = audio.readframes(frames)
            if len(payload) != frames * channels * sample_width:
                raise ValueError("Voicebox WAV payload is truncated")
            return {
                "channels": channels,
                "sampleWidth": sample_width,
                "sampleRate": sample_rate,
                "frames": frames,
            }
    except (wave.Error, EOFError) as error:
        raise ValueError("Voicebox audio is not a readable WAV") from error
