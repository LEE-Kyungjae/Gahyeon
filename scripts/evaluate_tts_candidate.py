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
            current.append(
                min(
                    current[-1] + 1,
                    previous[column] + 1,
                    previous[column - 1] + (left_character != right_character),
                )
            )
        previous = current
    return previous[-1]


parser = argparse.ArgumentParser()
parser.add_argument("--audio", required=True)
parser.add_argument("--text", required=True)
parser.add_argument("--reference", required=True)
parser.add_argument("--output", required=True)
args = parser.parse_args()

audio_path = Path(args.audio)
audio, sample_rate = librosa.load(audio_path, sr=None, mono=True)
duration = len(audio) / sample_rate
rms = librosa.feature.rms(y=audio)[0]
active_ratio = float(np.mean(rms > max(0.005, np.max(rms) * 0.08)))
clipping_ratio = float(np.mean(np.abs(audio) >= 0.99))
spectral_flatness = float(np.mean(librosa.feature.spectral_flatness(y=audio)))

whisper = WhisperModel("small", device="cuda", compute_type="float16")
segments, _ = whisper.transcribe(
    str(audio_path), language="ko", beam_size=1, vad_filter=True
)
transcript = " ".join(segment.text.strip() for segment in segments)
expected_normalized = normalize(args.text)
transcript_normalized = normalize(transcript)
cer = edit_distance(expected_normalized, transcript_normalized) / max(
    1, len(expected_normalized)
)

encoder = VoiceEncoder(device="cuda")
reference_embedding = encoder.embed_utterance(preprocess_wav(Path(args.reference)))
candidate_embedding = encoder.embed_utterance(preprocess_wav(audio_path))
speaker_similarity = float(np.dot(reference_embedding, candidate_embedding))

result = {
    "audio": str(audio_path),
    "expected": args.text,
    "transcript": transcript,
    "cer": round(cer, 4),
    "speaker_similarity": round(speaker_similarity, 4),
    "duration_seconds": round(duration, 3),
    "active_ratio": round(active_ratio, 4),
    "clipping_ratio": round(clipping_ratio, 6),
    "spectral_flatness": round(spectral_flatness, 6),
}
result["hard_pass"] = (
    result["cer"] <= 0.45
    and result["active_ratio"] >= 0.25
    and result["clipping_ratio"] <= 0.01
)
Path(args.output).write_text(
    json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
)
print(json.dumps(result, ensure_ascii=False))
