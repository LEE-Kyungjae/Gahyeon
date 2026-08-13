#!/usr/bin/env python3
"""Resumable, provider-free acoustic gate for Voicebox teacher WAV files."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import tempfile
import wave
from array import array
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path


def dbfs(value: float) -> float:
    return -120.0 if value <= 0 else 20.0 * math.log10(value / 32768.0)


def quiet_runs(samples: array, channels: int, sample_rate: int, threshold: int = 260) -> tuple[float, float, float]:
    window_frames = max(1, sample_rate // 50)
    quiet: list[bool] = []
    total_frames = len(samples) // channels
    for start in range(0, total_frames, window_frames):
        end = min(total_frames, start + window_frames)
        begin_sample = start * channels
        end_sample = end * channels
        peak = max((abs(value) for value in samples[begin_sample:end_sample]), default=0)
        quiet.append(peak < threshold)
    seconds_per_window = window_frames / sample_rate
    leading = 0
    while leading < len(quiet) and quiet[leading]:
        leading += 1
    trailing = 0
    while trailing < len(quiet) and quiet[len(quiet) - 1 - trailing]:
        trailing += 1
    longest_internal = 0
    run = 0
    for is_quiet in quiet[leading : len(quiet) - trailing if trailing else len(quiet)]:
        if is_quiet:
            run += 1
            longest_internal = max(longest_internal, run)
        else:
            run = 0
    return (
        round(leading * seconds_per_window, 3),
        round(trailing * seconds_per_window, 3),
        round(longest_internal * seconds_per_window, 3),
    )


def inspect(record: dict, output_root: Path) -> dict:
    audio = Path(record["audio"])
    if not audio.is_absolute():
        audio = output_root / audio
    try:
        stat = audio.stat()
        with wave.open(str(audio), "rb") as wav:
            channels = wav.getnchannels()
            sample_width = wav.getsampwidth()
            sample_rate = wav.getframerate()
            frame_count = wav.getnframes()
            raw = wav.readframes(frame_count)
    except (OSError, EOFError, wave.Error) as error:
        return {
            "index": int(record["index"]),
            "text": record["text"],
            "audio": str(audio.resolve()),
            "size": audio.stat().st_size if audio.exists() else -1,
            "mtimeNs": audio.stat().st_mtime_ns if audio.exists() else -1,
            "pcmSha256": None,
            "sampleRate": 0,
            "channels": 0,
            "duration": 0.0,
            "peakDbfs": -120.0,
            "rmsDbfs": -120.0,
            "clippingRatio": 0.0,
            "leadingSilence": 0.0,
            "trailingSilence": 0.0,
            "maxInternalSilence": 0.0,
            "accepted": False,
            "reasons": ["unreadable_wav"],
            "error": f"{type(error).__name__}: {error}",
        }
    reasons: list[str] = []
    if sample_width != 2:
        reasons.append("not_pcm16")
        samples = array("h")
    else:
        samples = array("h")
        samples.frombytes(raw)
        if os.sys.byteorder != "little":
            samples.byteswap()
    duration = frame_count / sample_rate if sample_rate > 0 else 0.0
    pcm_identity = hashlib.sha256(
        f"pcm16|{channels}|{sample_rate}|{sample_width}|".encode() + raw
    ).hexdigest()
    peak = max((abs(value) for value in samples), default=0)
    square_sum = sum(value * value for value in samples)
    rms = math.sqrt(square_sum / max(1, len(samples)))
    clipped = sum(abs(value) >= 32700 for value in samples)
    clipping_ratio = clipped / max(1, len(samples))
    leading, trailing, internal = quiet_runs(samples, max(1, channels), max(1, sample_rate))
    if not 0.4 <= duration <= 20.0:
        reasons.append("duration")
    if channels not in (1, 2):
        reasons.append("channels")
    if not 8_000 <= sample_rate <= 96_000:
        reasons.append("sample_rate")
    if dbfs(rms) < -45.0:
        reasons.append("too_quiet")
    if clipping_ratio > 0.001:
        reasons.append("clipping")
    if leading > 0.8:
        reasons.append("leading_silence")
    if trailing > 0.8:
        reasons.append("trailing_silence")
    if internal > 1.5:
        reasons.append("internal_silence")
    return {
        "index": int(record["index"]),
        "text": record["text"],
        "audio": str(audio.resolve()),
        "size": stat.st_size,
        "mtimeNs": stat.st_mtime_ns,
        "pcmSha256": pcm_identity,
        "sampleRate": sample_rate,
        "channels": channels,
        "duration": round(duration, 3),
        "peakDbfs": round(dbfs(peak), 2),
        "rmsDbfs": round(dbfs(rms), 2),
        "clippingRatio": round(clipping_ratio, 7),
        "leadingSilence": leading,
        "trailingSilence": trailing,
        "maxInternalSilence": internal,
        "accepted": not reasons,
        "reasons": reasons,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--workers", type=int, default=min(8, max(1, os.cpu_count() or 1)))
    parser.add_argument("--require-count", type=int)
    parser.add_argument("--require-unique-audio", action="store_true")
    args = parser.parse_args()
    manifest = args.root / "manifest.jsonl"
    records = [json.loads(line) for line in manifest.read_text(encoding="utf-8").splitlines() if line]
    if args.require_count is not None and len(records) != args.require_count:
        raise RuntimeError(f"expected {args.require_count} records, found {len(records)}")

    result_path = args.root / "acoustic_qc.jsonl"
    cached: dict[int, dict] = {}
    if result_path.exists():
        for line in result_path.read_text(encoding="utf-8").splitlines():
            if line:
                item = json.loads(line)
                cached[int(item["index"])] = item
    pending = []
    results = []
    for record in records:
        audio = Path(record["audio"])
        if not audio.is_absolute():
            audio = args.root / audio
        try:
            stat = audio.stat()
        except OSError:
            pending.append(record)
            continue
        prior = cached.get(int(record["index"]))
        if prior and prior.get("pcmSha256") and prior.get("size") == stat.st_size \
                and prior.get("mtimeNs") == stat.st_mtime_ns \
                and prior.get("text") == record["text"]:
            results.append(prior)
        else:
            pending.append(record)
    with ThreadPoolExecutor(max_workers=max(1, args.workers)) as executor:
        results.extend(executor.map(lambda row: inspect(row, args.root), pending))
    results.sort(key=lambda row: row["index"])

    by_pcm: dict[str, list[dict]] = {}
    for item in results:
        item["reasons"] = [reason for reason in item["reasons"] if reason != "duplicate_audio"]
        item["accepted"] = not item["reasons"]
        if item.get("pcmSha256"):
            by_pcm.setdefault(item["pcmSha256"], []).append(item)
    duplicate_groups = [group for group in by_pcm.values() if len(group) > 1]
    for group in duplicate_groups:
        for item in group:
            if "duplicate_audio" not in item["reasons"]:
                item["reasons"].append("duplicate_audio")
            item["accepted"] = False

    result_path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=result_path.parent, delete=False) as handle:
        temporary = Path(handle.name)
        for item in results:
            handle.write(json.dumps(item, ensure_ascii=False) + "\n")
    temporary.replace(result_path)

    accepted = [item for item in results if item["accepted"]]
    metadata = args.root / "metadata_acoustic_accepted.csv"
    metadata.write_text(
        "".join(
            f'{Path(item["audio"]).resolve().relative_to(args.root.resolve())}|'
            f'{item["text"].replace("|", " ")}\n'
            for item in accepted
        ),
        encoding="utf-8",
    )
    reason_counts: dict[str, int] = {}
    for item in results:
        for reason in item["reasons"]:
            reason_counts[reason] = reason_counts.get(reason, 0) + 1
    summary = {
        "total": len(results),
        "analyzed": len(pending),
        "reused": len(results) - len(pending),
        "accepted": len(accepted),
        "rejected": len(results) - len(accepted),
        "acceptedSeconds": round(sum(item["duration"] for item in accepted), 2),
        "duplicateAudioGroups": len(duplicate_groups),
        "duplicateAudioClips": sum(len(group) for group in duplicate_groups),
        "uniqueAudio": not duplicate_groups,
        "reasons": reason_counts,
        "result": str(result_path),
        "metadata": str(metadata),
    }
    (args.root / "acoustic_qc_summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(summary, ensure_ascii=False))
    if args.require_unique_audio and duplicate_groups:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
