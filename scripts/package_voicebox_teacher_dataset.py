#!/usr/bin/env python3
"""Package QC-selected Voicebox teacher clips as a Piper dataset."""

from __future__ import annotations

import csv
import argparse
import gzip
import hashlib
import json
import os
import re
import subprocess
import tarfile
import tempfile
import unicodedata
from pathlib import Path


parser = argparse.ArgumentParser()
parser.add_argument("--root", type=Path, default=Path("artifacts/voicebox-teacher"))
parser.add_argument("--archive-name", default="voicebox-teacher-piper-dataset.tar.gz")
parser.add_argument("--selection", type=Path, help="STT QC JSONL; defaults to root/stt_qc.jsonl")
parser.add_argument("--min-clips", type=int, default=0)
parser.add_argument("--require-audio-identity", action="store_true")
args = parser.parse_args()


def training_text_quality(text: str) -> tuple[str, str | None]:
    """Return Piper metadata text and an optional rejection reason."""
    value = unicodedata.normalize("NFC", str(text)).strip().replace("|", " ")
    if not value:
        return value, "empty"
    # News-source corpora often contain `.”.` / `?”.`: retain the punctuation
    # spoken inside the quote and remove only the redundant outer terminator.
    value = re.sub(r'([.!?])([”’"])\s*[.!?]\s*$', r'\1\2', value)
    # Canonicalize malformed two-or-more ASCII full stops as one ellipsis.
    value = re.sub(r'\.{2,}\s*$', '...', value)
    tokens = re.findall(r"[0-9A-Za-z가-힣]+", value.casefold())
    # Reject adjacent repeated clauses (>=3 tokens), while retaining natural
    # one-word emphasis such as "정말 정말".
    for width in range(3, len(tokens) // 2 + 1):
        for start in range(0, len(tokens) - width * 2 + 1):
            if tokens[start:start + width] == tokens[start + width:start + width * 2]:
                return value, "adjacent_repeated_clause"
    return value, None


def file_sha256(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()

root = args.root
package = root / "piper_dataset"
wav_dir = package / "wav"
wav_dir.mkdir(parents=True, exist_ok=True)
for previous in wav_dir.glob("teacher_*_p*.wav"):
    previous.unlink()

selection = args.selection or (root / "stt_qc.jsonl")
if selection.exists():
    rows = [json.loads(line) for line in selection.read_text(encoding="utf-8").splitlines() if line]
    selected = [item for item in rows if item.get("accepted")]
else:
    # Compatibility with the older all-in-one QC artifact.
    selected = json.loads((root / "qc.json").read_text(encoding="utf-8"))["selected"]
prepared = []
text_rejections = []
text_normalized = 0
for item in selected:
    normalized_text, rejection = training_text_quality(item.get("text", ""))
    if rejection is not None:
        text_rejections.append({"index": item.get("index"), "reason": rejection})
        continue
    candidate = dict(item)
    candidate["trainingText"] = normalized_text
    candidate["textNormalized"] = normalized_text != str(item.get("text", ""))
    text_normalized += candidate["textNormalized"]
    prepared.append(candidate)
selected = prepared
if len(selected) < args.min_clips:
    raise SystemExit(
        f"Text+audio QC selected {len(selected)} clips, below required minimum {args.min_clips}"
    )
if args.require_audio_identity:
    identities = [str(item.get("pcmSha256", "")) for item in selected]
    if any(not re.fullmatch(r"[0-9a-f]{64}", identity) for identity in identities):
        raise SystemExit("selected clips are missing valid PCM identities")
    if len(set(identities)) != len(identities):
        raise SystemExit("selected clips contain duplicate PCM identities")
metadata = []
manifest = []
for sequence, item in enumerate(selected, 1):
    source = Path(item["audio"])
    profile = item.get("profile", "teacher")
    filename = f"teacher_{sequence:04d}_p{profile}.wav"
    destination = wav_dir / filename
    subprocess.run(
        [
            "ffmpeg", "-y", "-loglevel", "error", "-i", str(source),
            "-ar", "22050", "-ac", "1", "-sample_fmt", "s16", str(destination),
        ],
        check=True,
    )
    digest = hashlib.sha256(destination.read_bytes()).hexdigest()
    metadata.append((filename, item["trainingText"]))
    manifest.append(
        {
            "file": filename,
            "text": item["trainingText"],
            "sourceText": item["text"],
            "textNormalized": item["textNormalized"],
            "teacher_profile": profile,
            "cer": item["cer"],
            "duration": item["duration"],
            "sha256": digest,
            "sourcePcmSha256": item.get("pcmSha256"),
        }
    )

with (package / "metadata.csv").open("w", encoding="utf-8", newline="") as handle:
    csv.writer(handle, delimiter="|", lineterminator="\n").writerows(metadata)
(package / "manifest.json").write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2),
    encoding="utf-8",
)

archive = root / args.archive_name
descriptor, temporary_name = tempfile.mkstemp(prefix=f".{archive.name}.", dir=root)
os.close(descriptor)
temporary = Path(temporary_name)
try:
    with temporary.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode="w") as handle:
                for source in sorted(path for path in package.rglob("*") if path.is_file()):
                    relative = source.relative_to(package).as_posix()
                    info = tarfile.TarInfo(f"piper_dataset/{relative}")
                    info.size = source.stat().st_size
                    info.mtime = 0
                    info.mode = 0o644
                    info.uid = info.gid = 0
                    info.uname = info.gname = ""
                    with source.open("rb") as payload:
                        handle.addfile(info, payload)
        raw.flush()
        os.fsync(raw.fileno())
    os.replace(temporary, archive)
finally:
    temporary.unlink(missing_ok=True)

print(json.dumps(
    {
        "clips": len(metadata),
        "text_normalized": text_normalized,
        "text_rejected": len(text_rejections),
        "text_rejections": text_rejections,
        "duration_seconds": round(sum(item["duration"] for item in selected), 3),
        "archive": str(archive),
        "archive_bytes": archive.stat().st_size,
        "archive_sha256": file_sha256(archive),
    },
    ensure_ascii=False,
))
