#!/usr/bin/env python3
"""Resumable transcript gate for acoustically accepted Voicebox teacher clips."""

from __future__ import annotations

import argparse
import json
import os
import tempfile
import unicodedata
import urllib.error
import urllib.request
import uuid
from pathlib import Path


def normalized(text: str) -> str:
    text = unicodedata.normalize("NFKC", text).lower()
    return "".join(character for character in text if character.isalnum())


def edit_distance(left: str, right: str) -> int:
    previous = list(range(len(right) + 1))
    for row, left_char in enumerate(left, 1):
        current = [row]
        for column, right_char in enumerate(right, 1):
            current.append(min(
                current[-1] + 1,
                previous[column] + 1,
                previous[column - 1] + (left_char != right_char),
            ))
        previous = current
    return previous[-1]


def transcribe(base_url: str, audio: Path, timeout: float) -> str:
    boundary = f"----gahyeon-{uuid.uuid4().hex}"
    parts: list[bytes] = []
    for name, value in (("language", "ko"), ("model", "base")):
        parts.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n'
            f'{value}\r\n'.encode()
        )
    parts.append(
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
        f'filename="{audio.name}"\r\nContent-Type: audio/wav\r\n\r\n'.encode()
        + audio.read_bytes() + b"\r\n"
    )
    parts.append(f"--{boundary}--\r\n".encode())
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/transcribe",
        data=b"".join(parts),
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        payload = json.load(response)
    transcript = payload.get("text")
    if not isinstance(transcript, str):
        raise RuntimeError("transcription response has no text")
    return transcript


def atomic_jsonl(path: Path, rows: list[dict]) -> None:
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as handle:
        temporary = Path(handle.name)
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
        handle.flush()
        os.fsync(handle.fileno())
    temporary.replace(path)


def atomic_json(path: Path, row: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as handle:
        temporary = Path(handle.name)
        json.dump(row, handle, ensure_ascii=False)
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
    temporary.replace(path)


def load_rows(path: Path) -> list[dict]:
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--base-url", default="http://127.0.0.1:17493")
    parser.add_argument("--cer-threshold", type=float, default=0.12)
    parser.add_argument("--timeout", type=float, default=600.0)
    parser.add_argument("--require-count", type=int)
    parser.add_argument("--retry-failures", action="store_true")
    parser.add_argument("--limit", type=int, help="development/testing limit; omitted for full QC")
    args = parser.parse_args()

    acoustic_path = args.root / "acoustic_qc.jsonl"
    acoustic = load_rows(acoustic_path)
    if args.require_count is not None and len(acoustic) != args.require_count:
        raise RuntimeError(f"expected {args.require_count} acoustic rows, found {len(acoustic)}")
    candidates = [row for row in acoustic if row.get("accepted")]
    result_path = args.root / "stt_qc.jsonl"
    cached = {int(row["index"]): row for row in load_rows(result_path)}
    checkpoint_dir = args.root / "stt_qc_checkpoint"
    if checkpoint_dir.exists():
        for path in checkpoint_dir.glob("*.json"):
            row = json.loads(path.read_text(encoding="utf-8"))
            cached[int(row["index"])] = row
    results: dict[int, dict] = {}
    pending: list[dict] = []
    for row in candidates:
        index = int(row["index"])
        prior = cached.get(index)
        unchanged = (
            prior is not None
            and prior.get("size") == row.get("size")
            and prior.get("mtimeNs") == row.get("mtimeNs")
            and prior.get("pcmSha256") == row.get("pcmSha256")
            and prior.get("text") == row.get("text")
        )
        if unchanged and (prior.get("status") == "ok" or not args.retry_failures):
            results[index] = prior
        else:
            pending.append(row)
    if args.limit is not None:
        pending = pending[:max(0, args.limit)]

    analyzed = 0
    for row in pending:
        index = int(row["index"])
        audio = Path(row["audio"])
        try:
            transcript = transcribe(args.base_url, audio, args.timeout)
            expected = normalized(row["text"])
            actual = normalized(transcript)
            cer = edit_distance(expected, actual) / max(1, len(expected))
            result = {
                "index": index,
                "text": row["text"],
                "audio": str(audio.resolve()),
                "size": row["size"],
                "mtimeNs": row["mtimeNs"],
                "pcmSha256": row.get("pcmSha256"),
                "duration": row["duration"],
                "transcript": transcript,
                "cer": round(cer, 4),
                "accepted": cer <= args.cer_threshold,
                "status": "ok",
            }
        except (OSError, RuntimeError, ValueError, urllib.error.URLError) as error:
            result = {
                "index": index,
                "text": row["text"],
                "audio": str(audio.resolve()),
                "size": row["size"],
                "mtimeNs": row["mtimeNs"],
                "pcmSha256": row.get("pcmSha256"),
                "duration": row["duration"],
                "accepted": False,
                "status": "error",
                "error": f"{type(error).__name__}: {error}",
            }
        results[index] = result
        analyzed += 1
        atomic_json(checkpoint_dir / f"{index:04d}.json", result)
        print(json.dumps(result, ensure_ascii=False), flush=True)

    rows = [results[key] for key in sorted(results)]
    atomic_jsonl(result_path, rows)
    accepted = [row for row in rows if row.get("accepted")]
    metadata = args.root / "metadata_selected.csv"
    metadata.write_text(
        "".join(
            f'{Path(row["audio"]).resolve().relative_to(args.root.resolve())}|'
            f'{row["text"].replace("|", " ")}\n'
            for row in accepted
        ),
        encoding="utf-8",
    )
    errors = sum(row.get("status") == "error" for row in rows)
    summary = {
        "acousticAccepted": len(candidates),
        "completed": len(rows),
        "analyzed": analyzed,
        "reused": len(rows) - analyzed,
        "accepted": len(accepted),
        "rejected": len(rows) - len(accepted) - errors,
        "errors": errors,
        "acceptedSeconds": round(sum(float(row["duration"]) for row in accepted), 2),
        "ready": len(rows) == len(candidates) and errors == 0,
        "result": str(result_path),
        "metadata": str(metadata),
    }
    (args.root / "stt_qc_summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
