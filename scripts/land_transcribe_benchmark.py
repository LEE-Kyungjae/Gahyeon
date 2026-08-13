import json
import sys
import time
from pathlib import Path

from faster_whisper import WhisperModel


audio_path = Path(sys.argv[1])
duration = float(sys.argv[2]) if len(sys.argv) > 2 else None
model_name = sys.argv[3] if len(sys.argv) > 3 else "small"
beam_size = int(sys.argv[4]) if len(sys.argv) > 4 else 5

started = time.perf_counter()
model = WhisperModel(model_name, device="cuda", compute_type="float16")
loaded = time.perf_counter()
segments, info = model.transcribe(
    str(audio_path),
    language="ko",
    beam_size=beam_size,
    vad_filter=True,
    word_timestamps=True,
    clip_timestamps=f"0,{duration}" if duration else None,
)
rows = []
for segment in segments:
    rows.append(
        {
            "start": round(segment.start, 3),
            "end": round(segment.end, 3),
            "text": segment.text.strip(),
        }
    )
finished = time.perf_counter()
audio_seconds = max((row["end"] for row in rows), default=0.0)
result = {
    "model": model_name,
    "beam_size": beam_size,
    "language": info.language,
    "model_load_seconds": round(loaded - started, 3),
    "transcribe_seconds": round(finished - loaded, 3),
    "wall_seconds": round(finished - started, 3),
    "audio_seconds_processed": round(audio_seconds, 3),
    "processing_rtf": round((finished - loaded) / audio_seconds, 4)
    if audio_seconds
    else None,
    "segments": len(rows),
    "items": rows,
}
print(json.dumps(result, ensure_ascii=False))
