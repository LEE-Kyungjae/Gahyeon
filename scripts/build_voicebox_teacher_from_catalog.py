#!/usr/bin/env python3
"""Generate Voicebox teacher audio from a fixed JSONL sentence catalog."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import tempfile
import time
import unicodedata
import urllib.request
from pathlib import Path
from typing import Callable

from build_voicebox_teacher_corpus_v2 import PROFILE_9, request_json, sync_progress
from voicebox_audio_contract import MAX_AUDIO_BYTES, validate_voicebox_wav


def key(text: str) -> str:
    normalized = unicodedata.normalize("NFKC", text).lower()
    return re.sub(r"[^0-9a-z가-힣]", "", normalized)


def append_manifest_record(path: Path, record: dict) -> None:
    """Commit one complete JSONL record without exposing a torn final line."""
    existing = path.read_bytes() if path.exists() else b""
    if existing and not existing.endswith(b"\n"):
        raise ValueError("manifest has an incomplete final line")
    encoded = (json.dumps(record, ensure_ascii=False) + "\n").encode("utf-8")
    descriptor, temporary_name = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.")
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(existing)
            handle.write(encoded)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        temporary.unlink(missing_ok=True)


def download_audio_atomic(
    url: str,
    destination: Path,
    *,
    maximum_bytes: int = MAX_AUDIO_BYTES,
    opener: Callable = urllib.request.urlopen,
) -> int:
    """Stream one bounded response and expose it only after durable completion."""
    if maximum_bytes < 1:
        raise ValueError("maximum_bytes must be positive")
    descriptor, temporary_name = tempfile.mkstemp(
        dir=destination.parent, prefix=f".{destination.name}.")
    temporary = Path(temporary_name)
    total = 0
    try:
        with opener(url, timeout=600) as response, os.fdopen(descriptor, "wb") as handle:
            declared = response.headers.get("Content-Length")
            if declared is not None:
                try:
                    declared_bytes = int(declared)
                except ValueError as error:
                    raise ValueError("invalid Voicebox audio Content-Length") from error
                if declared_bytes < 1 or declared_bytes > maximum_bytes:
                    raise ValueError("Voicebox audio response exceeds size limit")
            while True:
                chunk = response.read(min(1024 * 1024, maximum_bytes - total + 1))
                if not chunk:
                    break
                total += len(chunk)
                if total > maximum_bytes:
                    raise ValueError("Voicebox audio response exceeds size limit")
                handle.write(chunk)
            if total == 0:
                raise ValueError("Voicebox audio response is empty")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, destination)
        directory = os.open(destination.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
        return total
    finally:
        try:
            os.close(descriptor)
        except OSError:
            pass
        temporary.unlink(missing_ok=True)


def wait_for_generation(
    base_url: str,
    generation_id: str,
    timeout_seconds: float,
    *,
    poll_interval_seconds: float = 0.25,
    request: Callable[[str], dict] = request_json,
    monotonic: Callable[[], float] = time.monotonic,
    sleep: Callable[[float], None] = time.sleep,
) -> dict:
    """Wait for one Voicebox job without allowing a permanent pending state."""
    deadline = monotonic() + timeout_seconds
    while True:
        status = request(f"{base_url}/history/{generation_id}")
        state = status.get("status")
        if state == "completed":
            return status
        if state == "failed":
            raise RuntimeError(status.get("error", "Voicebox generation failed"))
        if monotonic() >= deadline:
            raise TimeoutError(
                f"Voicebox generation {generation_id} did not complete within "
                f"{timeout_seconds:g} seconds (last status: {state or 'unknown'})"
            )
        sleep(poll_interval_seconds)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--reuse-manifest", type=Path)
    parser.add_argument("--base-url", default="http://127.0.0.1:17493")
    parser.add_argument("--limit", type=int, default=1000)
    parser.add_argument(
        "--generation-timeout",
        type=float,
        default=1800,
        help="Maximum seconds to wait for one Voicebox history job (default: 1800)",
    )
    args = parser.parse_args()

    catalog = [
        item for item in (
            json.loads(line) for line in args.catalog.read_text(encoding="utf-8").splitlines() if line
        ) if item["index"] <= args.limit
    ]
    args.output.mkdir(parents=True, exist_ok=True)
    audio_dir = args.output / "profile_9"
    audio_dir.mkdir(exist_ok=True)
    manifest_path = args.output / "manifest.jsonl"

    completed = {}
    if manifest_path.exists():
        for line in manifest_path.read_text(encoding="utf-8").splitlines():
            if line:
                item = json.loads(line)
                completed[item["index"]] = item

    reusable = {}
    if args.reuse_manifest and args.reuse_manifest.exists():
        for line in args.reuse_manifest.read_text(encoding="utf-8").splitlines():
            if line:
                item = json.loads(line)
                reusable.setdefault(key(item["text"]), item)

    for item in catalog:
        index = item["index"]
        text = item["text"]
        if index in completed:
            continue
        destination = audio_dir / f"teacher_{index:04d}.wav"
        old = reusable.get(key(text))
        if old and Path(old["audio"]).exists():
            shutil.copy2(old["audio"], destination)
            try:
                validate_voicebox_wav(destination)
            except ValueError:
                destination.unlink(missing_ok=True)
                old = None
        if old and destination.exists():
            record = {
                "profile": "9", "profile_id": PROFILE_9, "index": index,
                "text": text, "audio": str(destination.resolve()),
                "generation_id": old.get("generation_id"),
                "generation_seconds": old.get("generation_seconds", 0), "reused": True,
            }
        else:
            started = time.monotonic()
            generation = request_json(
                f"{args.base_url}/generate", "POST",
                {"profile_id": PROFILE_9, "text": text, "language": "ko", "engine": "qwen", "model_size": "0.6B", "normalize": True},
            )
            generation_id = generation["id"]
            wait_for_generation(
                args.base_url,
                generation_id,
                args.generation_timeout,
            )
            download_audio_atomic(
                f"{args.base_url}/audio/{generation_id}", destination)
            try:
                validate_voicebox_wav(destination)
            except ValueError:
                destination.unlink(missing_ok=True)
                raise
            record = {
                "profile": "9", "profile_id": PROFILE_9, "index": index,
                "text": text, "audio": str(destination.resolve()),
                "generation_id": generation_id,
                "generation_seconds": round(time.monotonic() - started, 3), "reused": False,
            }
        append_manifest_record(manifest_path, record)
        completed[index] = record
        sync_progress(args.output, len(catalog))
        print(json.dumps(record, ensure_ascii=False), flush=True)

    print(json.dumps({"completed": len(completed), "output": str(args.output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
