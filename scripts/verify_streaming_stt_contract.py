#!/usr/bin/env python3
"""Validate canonical Streaming STT v1 controls and fail-closed schema behavior."""

from __future__ import annotations

import json
from pathlib import Path

import jsonschema


ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT / "docs/contracts/gahyeon-streaming-stt-v1.schema.json"


def fixtures() -> list[dict]:
    common = {"schemaVersion": 1, "sessionId": "session-1", "streamId": "stream-9", "generation": 4}
    controls = [
        {**common, "type": "stt.stream.start", "observedAtMs": 100,
         "format": {"encoding": "float32le", "sampleRate": 48000,
                    "channels": 1, "framesPerChunk": 480}},
        {**common, "type": "stt.stream.end", "observedAtMs": 900,
         "lastAudioSequence": 79},
        {**common, "type": "stt.transcript.partial", "resultSequence": 2,
         "text": "지금 확인", "stability": 0.7},
        {**common, "type": "stt.transcript.final", "resultSequence": 3,
         "text": "지금 확인해 줘.", "language": "ko-KR"},
        {**common, "type": "stt.stream.error", "code": "provider_timeout",
         "recoverable": True},
    ]
    controls.extend(
        {**common, "type": "stt.stream.cancel", "reason": reason}
        for reason in ("barge_in", "client_reset", "backpressure", "timeout", "capture_error")
    )
    return controls


def verify() -> dict:
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    validator = jsonschema.Draft202012Validator(schema)
    values = fixtures()
    for value in values:
        validator.validate(value)
    rejected = 0
    invalid = [
        {**values[0], "extra": True},
        {**values[0], "format": {**values[0]["format"], "encoding": "pcm16le"}},
        {**values[2], "stability": 1.1},
        {**values[3], "generation": -1},
        {**values[4], "code": "unknown"},
        {**values[5], "reason": "generic"},
    ]
    for value in invalid:
        try:
            validator.validate(value)
        except jsonschema.ValidationError:
            rejected += 1
    if rejected != len(invalid):
        raise ValueError("Streaming STT schema accepted an invalid canonical case")
    return {"valid": True, "controls": len(values), "invalidRejected": rejected}


if __name__ == "__main__":
    print(json.dumps(verify(), ensure_ascii=False))
