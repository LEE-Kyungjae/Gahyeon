#!/usr/bin/env python3
"""Validate resumable Voicebox teacher progress without trusting manifest line count."""

from __future__ import annotations

import argparse
import json
import unicodedata
from pathlib import Path

from voicebox_audio_contract import validate_voicebox_wav


def fail(message: str) -> None:
    raise RuntimeError(message)


def normalized_text(text: str) -> str:
    return "".join(
        character for character in unicodedata.normalize("NFKC", text).lower()
        if character.isalnum()
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--target", type=int, default=5000)
    parser.add_argument("--field", choices=("completed", "remaining", "ready"))
    parser.add_argument("--require-complete", action="store_true")
    parser.add_argument("--probe-wav", action="store_true")
    args = parser.parse_args()

    if args.target <= 0:
        fail("target must be positive")
    catalog_rows = [
        json.loads(line)
        for line in args.catalog.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    expected = {int(row["index"]): str(row["text"]) for row in catalog_rows}
    if len(expected) != len(catalog_rows) or len(expected) < args.target:
        fail("catalog indices are duplicated or shorter than target")
    normalized_catalog = [normalized_text(expected[index]) for index in sorted(expected)[:args.target]]
    if any(not text for text in normalized_catalog):
        fail("catalog contains a sentence with no letters or numbers")
    if len(set(normalized_catalog)) != len(normalized_catalog):
        fail("catalog contains duplicate text after punctuation-insensitive normalization")

    manifest = args.output / "manifest.jsonl"
    records: dict[int, dict] = {}
    if manifest.exists():
        for line_number, line in enumerate(manifest.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as error:
                fail(f"manifest line {line_number} is invalid JSON: {error}")
            index = int(record.get("index", -1))
            if index in records:
                fail(f"manifest contains duplicate index {index}")
            if index < 1 or index > args.target or index not in expected:
                fail(f"manifest index {index} is outside the target catalog")
            if record.get("text") != expected[index]:
                fail(f"manifest text differs from catalog at index {index}")
            audio = Path(str(record.get("audio", "")))
            if not audio.is_absolute():
                audio = args.output / audio
            try:
                audio.resolve().relative_to(args.output.resolve())
            except ValueError:
                fail(f"audio for index {index} escapes output root")
            if not audio.is_file() or audio.stat().st_size <= 44:
                fail(f"audio for index {index} is missing or empty")
            if args.probe_wav:
                try:
                    validate_voicebox_wav(audio)
                except ValueError as error:
                    fail(f"audio for index {index} violates the Voicebox WAV contract: {error}")
            records[index] = record

    contiguous = 0
    while contiguous + 1 in records:
        contiguous += 1
    if records and contiguous != len(records):
        fail(f"manifest has an index gap after {contiguous}")

    completed = len(records)
    result = {
        "valid": True,
        "completed": completed,
        "target": args.target,
        "remaining": args.target - completed,
        "contiguousThrough": contiguous,
        "ready": completed == args.target,
        "manifest": str(manifest),
    }
    if args.require_complete and not result["ready"]:
        print(json.dumps(result, ensure_ascii=False))
        raise SystemExit(1)
    if args.field:
        value = result[args.field]
        print(str(value).lower() if isinstance(value, bool) else value)
    else:
        print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
