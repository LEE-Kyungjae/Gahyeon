#!/usr/bin/env python3
"""Evaluate several TTS samples with one Whisper/voice-encoder load."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import librosa
import numpy as np
from faster_whisper import WhisperModel
from resemblyzer import VoiceEncoder, preprocess_wav


def normalize(text: str) -> str:
    return "".join(character for character in text if character.isalnum()).lower()


def edit_distance(left: str, right: str) -> int:
    previous = list(range(len(right) + 1))
    for row, left_character in enumerate(left, 1):
        current = [row]
        for column, right_character in enumerate(right, 1):
            current.append(min(
                current[-1] + 1,
                previous[column] + 1,
                previous[column - 1] + (left_character != right_character),
            ))
        previous = current
    return previous[-1]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--suite", type=Path, required=True)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    cases = [json.loads(line) for line in args.suite.read_text(encoding="utf-8").splitlines() if line]
    whisper = WhisperModel("small", device="cuda", compute_type="float16")
    encoder = VoiceEncoder(device="cuda")
    reference_embedding = encoder.embed_utterance(preprocess_wav(args.reference))
    results = []
    for case in cases:
        audio_path = Path(case["audio"])
        audio, sample_rate = librosa.load(audio_path, sr=None, mono=True)
        rms = librosa.feature.rms(y=audio)[0]
        segments, _ = whisper.transcribe(str(audio_path), language="ko", beam_size=1, vad_filter=True)
        transcript = " ".join(segment.text.strip() for segment in segments)
        expected_norm = normalize(case["text"])
        transcript_norm = normalize(transcript)
        candidate_embedding = encoder.embed_utterance(preprocess_wav(audio_path))
        result = {
            "id": case["id"],
            "audio": str(audio_path),
            "expected": case["text"],
            "transcript": transcript,
            "cer": round(edit_distance(expected_norm, transcript_norm) / max(1, len(expected_norm)), 4),
            "speakerSimilarity": round(float(np.dot(reference_embedding, candidate_embedding)), 4),
            "durationSeconds": round(len(audio) / sample_rate, 3),
            "activeRatio": round(float(np.mean(rms > max(0.005, np.max(rms) * 0.08))), 4),
            "clippingRatio": round(float(np.mean(np.abs(audio) >= 0.99)), 6),
            "spectralFlatness": round(float(np.mean(librosa.feature.spectral_flatness(y=audio))), 6),
        }
        result["hardPass"] = (
            result["cer"] <= 0.45
            and result["activeRatio"] >= 0.25
            and result["clippingRatio"] <= 0.01
        )
        results.append(result)

    summary = {
        "cases": len(results),
        "hardPasses": sum(item["hardPass"] for item in results),
        "meanCer": round(float(np.mean([item["cer"] for item in results])), 4),
        "maxCer": round(max(item["cer"] for item in results), 4),
        "meanSpeakerSimilarity": round(float(np.mean([item["speakerSimilarity"] for item in results])), 4),
        "minSpeakerSimilarity": round(min(item["speakerSimilarity"] for item in results), 4),
        "maxClippingRatio": max(item["clippingRatio"] for item in results),
    }
    summary["hardPass"] = summary["hardPasses"] == summary["cases"]
    payload = {"summary": summary, "results": results}
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(payload, ensure_ascii=False))


if __name__ == "__main__":
    main()
