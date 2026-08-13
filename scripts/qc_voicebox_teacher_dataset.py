#!/usr/bin/env python3
"""Transcribe and score matched Voicebox teacher clips, then select Piper data."""

from __future__ import annotations

import argparse
import csv
import json
import re
import subprocess
import unicodedata
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
            current.append(
                min(
                    current[-1] + 1,
                    previous[column] + 1,
                    previous[column - 1] + (left_char != right_char),
                )
            )
        previous = current
    return previous[-1]


def transcribe(base_url: str, audio: Path) -> str:
    boundary = f"----voicebox-{uuid.uuid4().hex}"
    parts = []
    for name, value in (("language", "ko"), ("model", "base")):
        parts.append(
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n"
            f"{value}\r\n".encode()
        )
    parts.append(
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
        f"filename=\"{audio.name}\"\r\nContent-Type: audio/wav\r\n\r\n".encode()
        + audio.read_bytes()
        + b"\r\n"
    )
    parts.append(f"--{boundary}--\r\n".encode())
    request = urllib.request.Request(
        f"{base_url}/transcribe",
        data=b"".join(parts),
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=600) as response:
        return json.load(response)["text"]


def audio_metrics(audio: Path) -> dict:
    probe = subprocess.run(
        [
            "ffprobe", "-v", "error", "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1", str(audio),
        ],
        check=True, capture_output=True, text=True,
    )
    duration = float(probe.stdout.strip())
    detected = subprocess.run(
        ["ffmpeg", "-hide_banner", "-i", str(audio), "-af", "silencedetect=n=-42dB:d=0.18",
         "-f", "null", "-"],
        capture_output=True, text=True,
    )
    starts = [float(value) for value in re.findall(r"silence_start: ([0-9.]+)", detected.stderr)]
    ends = [float(value) for value in re.findall(r"silence_end: ([0-9.]+)", detected.stderr)]
    silences = []
    for index, start in enumerate(starts):
        end = ends[index] if index < len(ends) else duration
        # Leading/trailing quiet is harmless; pauses inside speech affect rhythm.
        if start > 0.12 and end < duration - 0.12:
            silences.append(end - start)
    return {
        "duration": round(duration, 3),
        "max_internal_silence": round(max(silences, default=0.0), 3),
        "internal_silence_count": len(silences),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("artifacts/voicebox-teacher"))
    parser.add_argument("--base-url", default="http://127.0.0.1:17493")
    parser.add_argument("--profile-6-trim-seconds", type=float, default=1.6)
    args = parser.parse_args()

    records = [
        json.loads(line)
        for line in (args.root / "manifest.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    scores = []
    for item in records:
        audio = Path(item["audio"])
        if not audio.exists():
            audio = args.root / f"profile_{item['profile']}" / audio.name
        if item["profile"] == "6" and args.profile_6_trim_seconds > 0:
            repaired_dir = args.root / "profile_6_repaired"
            repaired_dir.mkdir(exist_ok=True)
            repaired = repaired_dir / audio.name
            subprocess.run(
                [
                    "ffmpeg", "-y", "-loglevel", "error",
                    "-ss", str(args.profile_6_trim_seconds), "-i", str(audio), str(repaired),
                ],
                check=True,
            )
            audio = repaired
        transcript = transcribe(args.base_url, audio)
        expected_norm = normalized(item["text"])
        actual_norm = normalized(transcript)
        metrics = audio_metrics(audio)
        score = {
            **item,
            "audio": str(audio),
            **metrics,
            "transcript": transcript,
            "cer": round(edit_distance(expected_norm, actual_norm) / max(1, len(expected_norm)), 4),
        }
        scores.append(score)
        print(json.dumps(score, ensure_ascii=False), flush=True)

    by_index: dict[int, list[dict]] = {}
    for score in scores:
        by_index.setdefault(score["index"], []).append(score)

    selected = []
    for index in sorted(by_index):
        candidates = by_index[index]
        passing = [
            item for item in candidates
            if item["cer"] <= 0.12 and item["max_internal_silence"] <= 0.55
        ]
        if not passing:
            continue
        profile_9 = next((item for item in passing if item["profile"] == "9"), None)
        profile_6 = next((item for item in passing if item["profile"] == "6"), None)
        if profile_9 is not None:
            selected.append(profile_9)
        if profile_6 is not None and (profile_9 is None or index % 4 == 0):
            selected.append(profile_6)

    (args.root / "qc.json").write_text(
        json.dumps({"scores": scores, "selected": selected}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    with (args.root / "metadata_selected.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, delimiter="|", lineterminator="\n")
        for item in selected:
            writer.writerow([str(Path(item["audio"]).resolve()), item["text"]])
    print(json.dumps(
        {
            "selected": len(selected),
            "profile_6": sum(item["profile"] == "6" for item in selected),
            "profile_9": sum(item["profile"] == "9" for item in selected),
        },
        ensure_ascii=False,
    ))


if __name__ == "__main__":
    main()
