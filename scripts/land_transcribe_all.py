import json
import time
from pathlib import Path

from faster_whisper import WhisperModel


root = Path("/home/ubuntu/piper-voice")
inputs = sorted((root / "input").glob("*.m4a"))
output_dir = root / "output" / "transcripts"
output_dir.mkdir(parents=True, exist_ok=True)

started = time.perf_counter()
model = WhisperModel("small", device="cuda", compute_type="float16")
loaded = time.perf_counter()
summary = []

for audio_path in inputs:
    file_started = time.perf_counter()
    segments, info = model.transcribe(
        str(audio_path),
        language="ko",
        beam_size=1,
        vad_filter=True,
        word_timestamps=True,
    )
    rows = [
        {
            "start": round(segment.start, 3),
            "end": round(segment.end, 3),
            "text": segment.text.strip(),
        }
        for segment in segments
    ]
    file_finished = time.perf_counter()
    audio_seconds = max((row["end"] for row in rows), default=0.0)
    result = {
        "source": audio_path.name,
        "language": info.language,
        "wall_seconds": round(file_finished - file_started, 3),
        "audio_seconds": round(audio_seconds, 3),
        "rtf": round((file_finished - file_started) / audio_seconds, 4)
        if audio_seconds
        else None,
        "segments": rows,
    }
    output_path = output_dir / f"{audio_path.stem}.json"
    output_path.write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    summary.append({key: value for key, value in result.items() if key != "segments"})
    print(json.dumps(summary[-1], ensure_ascii=False), flush=True)

finished = time.perf_counter()
(output_dir / "summary.json").write_text(
    json.dumps(
        {
            "model_load_seconds": round(loaded - started, 3),
            "total_seconds": round(finished - started, 3),
            "files": summary,
        },
        ensure_ascii=False,
        indent=2,
    ),
    encoding="utf-8",
)
