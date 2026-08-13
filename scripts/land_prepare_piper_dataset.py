import json
import shutil
import subprocess
from pathlib import Path

import numpy as np
from resemblyzer import VoiceEncoder, preprocess_wav


root = Path("/home/ubuntu/piper-voice")
audio_dir = root / "dataset" / "wav"
dataset_dir = audio_dir.parent
if dataset_dir.exists():
    shutil.rmtree(dataset_dir)
audio_dir.mkdir(parents=True)

reference_path = root / "input" / "새로운 녹음 9.m4a"
reference_wav = dataset_dir / "reference9.wav"
subprocess.run(
    [
        "ffmpeg", "-y", "-loglevel", "error", "-i", str(reference_path),
        "-ar", "16000", "-ac", "1", str(reference_wav),
    ],
    check=True,
)

encoder = VoiceEncoder(device="cuda")
reference_embedding = encoder.embed_utterance(preprocess_wav(reference_wav))
candidates = []

for transcript_path in sorted((root / "output" / "transcripts").glob("새로운 녹음 *.json")):
    source_number = transcript_path.stem.rsplit(" ", 1)[-1]
    source_audio = root / "input" / f"{transcript_path.stem}.m4a"
    transcript = json.loads(transcript_path.read_text(encoding="utf-8"))
    for index, segment in enumerate(transcript["segments"]):
        duration = segment["end"] - segment["start"]
        text = segment["text"].strip()
        if duration < 1.5 or duration > 12 or len(text) < 4:
            continue
        name = f"rec{source_number}_{index:04d}.wav"
        wav_path = audio_dir / name
        subprocess.run(
            [
                "ffmpeg", "-y", "-loglevel", "error",
                "-ss", str(max(0, segment["start"] - 0.08)),
                "-to", str(segment["end"] + 0.08),
                "-i", str(source_audio),
                "-ar", "22050", "-ac", "1",
                "-af", "loudnorm=I=-20:TP=-2:LRA=7",
                str(wav_path),
            ],
            check=True,
        )
        embedding = encoder.embed_utterance(preprocess_wav(wav_path))
        similarity = float(np.dot(reference_embedding, embedding))
        candidates.append(
            {
                "wav": name,
                "text": text.replace("|", " "),
                "source": source_number,
                "duration": round(duration, 3),
                "similarity": round(similarity, 4),
            }
        )

# Recording 9 is the clean reference. For 6-8, keep only strong speaker matches.
selected = [
    row for row in candidates
    if row["source"] == "9" or row["similarity"] >= 0.70
]
selected_names = {row["wav"] for row in selected}
for wav_path in audio_dir.glob("*.wav"):
    if wav_path.name not in selected_names:
        wav_path.unlink()

(dataset_dir / "metadata.csv").write_text(
    "".join(f'{row["wav"]}|{row["text"]}\n' for row in selected),
    encoding="utf-8",
)
(dataset_dir / "selection.json").write_text(
    json.dumps(
        {
            "threshold": 0.70,
            "candidate_count": len(candidates),
            "selected_count": len(selected),
            "selected_seconds": round(sum(row["duration"] for row in selected), 2),
            "by_source": {
                source: {
                    "count": sum(row["source"] == source for row in selected),
                    "seconds": round(
                        sum(row["duration"] for row in selected if row["source"] == source),
                        2,
                    ),
                }
                for source in ("6", "7", "8", "9")
            },
            "selected": selected,
        },
        ensure_ascii=False,
        indent=2,
    ),
    encoding="utf-8",
)
print((dataset_dir / "selection.json").read_text(encoding="utf-8"))
