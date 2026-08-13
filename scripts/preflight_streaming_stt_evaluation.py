#!/usr/bin/env python3
"""Fail-closed readiness check for the real Korean Streaming STT evaluation."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path


REQUIRED_TRUE = (
    "ASSISTANT_ENABLED",
    "ASSISTANT_STT_ENABLED",
    "ASSISTANT_STT_REALTIME_ENABLED",
    "GAHYEON_HEADLESS_ENABLED",
    "GAHYEON_UNREAL_WEBSOCKET_ENABLED",
    "GAHYEON_UNREAL_STREAMING_STT_ENABLED",
)


def enabled(value: str | None) -> bool:
    return value is not None and value.strip().lower() in {"1", "true", "yes", "on"}


def check(suite_path: Path) -> dict:
    missing_flags = [name for name in REQUIRED_TRUE if not enabled(os.getenv(name))]
    api_key = os.getenv("ASSISTANT_STT_API_KEY") or os.getenv("OPENAI_API_KEY") or ""
    cases: list[dict] = []
    errors: list[str] = []
    identities: set[str] = set()
    if not suite_path.is_file():
        errors.append("suite_missing")
    else:
        for line_number, raw in enumerate(suite_path.read_text(encoding="utf-8").splitlines(), 1):
            if not raw.strip():
                continue
            try:
                item = json.loads(raw)
            except json.JSONDecodeError:
                errors.append(f"suite_json_invalid:{line_number}")
                continue
            if set(item) != {"id", "wav", "expected", "repeats"}:
                errors.append(f"suite_fields_invalid:{line_number}")
                continue
            identity = item.get("id")
            wav = (suite_path.parent / str(item.get("wav", ""))).resolve()
            if not isinstance(identity, str) or not identity or identity in identities:
                errors.append(f"suite_id_invalid:{line_number}")
            elif not wav.is_file() or wav.stat().st_size <= 44:
                errors.append(f"suite_wav_invalid:{line_number}")
            elif not isinstance(item.get("expected"), str) or not item["expected"].strip():
                errors.append(f"suite_expected_invalid:{line_number}")
            elif not isinstance(item.get("repeats"), int) or not 1 <= item["repeats"] <= 20:
                errors.append(f"suite_repeats_invalid:{line_number}")
            else:
                identities.add(identity)
                cases.append(item)
    trials = sum(item["repeats"] for item in cases)
    if len(cases) < 10:
        errors.append("suite_requires_10_unique_wavs")
    if trials < 20:
        errors.append("suite_requires_20_trials")
    return {
        "schemaVersion": 1,
        "ready": not missing_flags and len(api_key.strip()) >= 20 and not errors,
        "providerCredential": "configured" if len(api_key.strip()) >= 20 else "missing",
        "missingEnabledFlags": missing_flags,
        "suite": str(suite_path),
        "uniqueWavs": len(cases),
        "trials": trials,
        "errors": errors,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--suite", type=Path, required=True)
    args = parser.parse_args()
    result = check(args.suite.resolve())
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result["ready"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
