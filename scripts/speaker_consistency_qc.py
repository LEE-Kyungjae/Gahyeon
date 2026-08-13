#!/usr/bin/env python3
"""Resumable speaker-identity gate for a Piper dataset (run on the GPU worker)."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
from pathlib import Path


def percentile(values: list[float], proportion: float) -> float:
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int((len(ordered) - 1) * proportion)))
    return ordered[index]


def summarize(similarities: list[float], accepted: int, minimum_retained: int) -> dict:
    if not similarities:
        raise RuntimeError("speaker QC has no similarities")
    rejected = len(similarities) - accepted
    rejected_ratio = rejected / len(similarities)
    p05 = percentile(similarities, 0.05)
    median = percentile(similarities, 0.50)
    report = {
        "total": len(similarities), "accepted": accepted, "rejected": rejected,
        "rejectedRatio": round(rejected_ratio, 6),
        "minimum": round(min(similarities), 4),
        "p01": round(percentile(similarities, 0.01), 4),
        "p05": round(p05, 4),
        "median": round(median, 4),
        "mean": round(sum(similarities) / len(similarities), 4),
    }
    violations = []
    if accepted < minimum_retained: violations.append("retained_clips")
    if rejected_ratio > 0.02: violations.append("speaker_outlier_ratio")
    if p05 < 0.90: violations.append("speaker_similarity_p05")
    if median < 0.93: violations.append("speaker_similarity_median")
    report["violations"] = violations
    report["ready"] = not violations
    return report


def atomic_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as handle:
        temporary = Path(handle.name)
        json.dump(payload, handle, ensure_ascii=False)
        handle.write("\n"); handle.flush(); os.fsync(handle.fileno())
    temporary.replace(path)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--audio-dir", type=Path, required=True)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--output-root", type=Path, required=True)
    parser.add_argument("--min-similarity", type=float, default=0.85)
    parser.add_argument("--min-retained", type=int, default=4000)
    parser.add_argument("--device", default="cuda")
    args = parser.parse_args()

    if not args.reference.is_file():
        raise RuntimeError(f"speaker reference does not exist: {args.reference}")
    reference_sha256 = sha256(args.reference)
    encoder_id = "resemblyzer-voiceencoder-v1"
    metadata = []
    filenames = set()
    for line in args.metadata.read_text(encoding="utf-8").splitlines():
        filename, separator, text = line.partition("|")
        if not separator or not text.strip() or Path(filename).name != filename:
            raise RuntimeError(f"invalid Piper metadata row: {line!r}")
        if filename in filenames:
            raise RuntimeError(f"duplicate Piper metadata filename: {filename}")
        filenames.add(filename)
        metadata.append((filename, text))
    if not metadata:
        raise RuntimeError("Piper metadata is empty")
    args.output_root.mkdir(parents=True, exist_ok=True)
    checkpoint_dir = args.output_root / "checkpoints"
    cached = {}
    if checkpoint_dir.exists():
        for checkpoint in checkpoint_dir.glob("*.json"):
            payload = json.loads(checkpoint.read_text(encoding="utf-8"))
            cached[payload["file"]] = payload
    pending = []
    results = {}
    for filename, text in metadata:
        audio = args.audio_dir / filename
        stat = audio.stat()
        prior = cached.get(filename)
        if (prior and prior.get("size") == stat.st_size
                and prior.get("mtimeNs") == stat.st_mtime_ns and prior.get("text") == text
                and prior.get("referenceSha256") == reference_sha256
                and prior.get("encoderId") == encoder_id):
            prior["accepted"] = float(prior["similarity"]) >= args.min_similarity
            results[filename] = prior
        else:
            pending.append((filename, text, audio, stat))

    if pending:
        import numpy as np
        from resemblyzer import VoiceEncoder, preprocess_wav
        encoder = VoiceEncoder(device=args.device)
        reference_embedding = encoder.embed_utterance(preprocess_wav(args.reference))
        for filename, text, audio, stat in pending:
            embedding = encoder.embed_utterance(preprocess_wav(audio))
            similarity = float(np.dot(reference_embedding, embedding))
            payload = {
                "file": filename, "text": text, "size": stat.st_size,
                "mtimeNs": stat.st_mtime_ns, "similarity": round(similarity, 6),
                "accepted": similarity >= args.min_similarity,
                "referenceSha256": reference_sha256, "encoderId": encoder_id,
            }
            results[filename] = payload
            atomic_json(checkpoint_dir / f"{Path(filename).stem}.json", payload)
            print(json.dumps(payload, ensure_ascii=False), flush=True)

    ordered = [results[filename] for filename, _ in metadata]
    accepted_rows = [row for row in ordered if row["accepted"]]
    output_metadata = args.output_root / "metadata-speaker-accepted.csv"
    output_metadata.write_text(
        "".join(f'{row["file"]}|{row["text"]}\n' for row in accepted_rows), encoding="utf-8")
    result_path = args.output_root / "speaker-consistency.jsonl"
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=args.output_root, delete=False) as handle:
        temporary = Path(handle.name)
        for row in ordered:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
        handle.flush(); os.fsync(handle.fileno())
    temporary.replace(result_path)
    summary = summarize(
        [float(row["similarity"]) for row in ordered], len(accepted_rows), args.min_retained)
    summary.update({
        "minimumSimilarity": args.min_similarity, "reference": str(args.reference),
        "referenceSha256": reference_sha256, "encoderId": encoder_id,
        "result": str(result_path), "metadata": str(output_metadata),
        "analyzed": len(pending), "reused": len(ordered) - len(pending),
    })
    atomic_json(args.output_root / "speaker-consistency-summary.json", summary)
    print(json.dumps(summary, ensure_ascii=False))
    if not summary["ready"]:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
