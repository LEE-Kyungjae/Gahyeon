import csv
import subprocess
from pathlib import Path


root = Path("/home/ubuntu/piper-voice")
source = root / "input" / "새로운 녹음 9.m4a"
dataset = root / "experiments" / "clean9" / "dataset"
audio_dir = dataset / "wav"
audio_dir.mkdir(parents=True, exist_ok=True)

segments = [
    (
        2.06,
        11.84,
        "인도네시아 정부가 팜유와 석탄, 니켈 등 전략 원자재 수출을 국영기업을 통해 관리하는 수출 일원화 정책을 발표했다.",
    ),
    (
        12.38,
        20.14,
        "이후 현지 팜열매 가격이 급락하면서 시장에서는 팜유 수출이 제한되는 것 아니냐는 우려가 확대됐다.",
    ),
    (
        20.14,
        31.82,
        "하지만 이번 정책은 생산량 자체를 줄이는 공급 규제라기보다 거래와 수출을 관리하는 공급망 정책으로 해석할 필요가 있다.",
    ),
    (
        32.66,
        47.06,
        "단기적으로는 수출 절차 변화와 팜열매 가격 변동성에 영향을 줄 수 있으나, 중장기적으로는 정부의 공급망 관리 강화가 팜유 시장의 새로운 가격 변수로 자리잡을 가능성이 있다.",
    ),
]

metadata = []
for index, (start, end, text) in enumerate(segments, 1):
    filename = f"clean9_{index:02d}.wav"
    subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-loglevel",
            "error",
            "-ss",
            str(start),
            "-to",
            str(end),
            "-i",
            str(source),
            "-ar",
            "22050",
            "-ac",
            "1",
            str(audio_dir / filename),
        ],
        check=True,
    )
    metadata.append((filename, text))

with (dataset / "metadata.csv").open("w", encoding="utf-8", newline="") as handle:
    csv.writer(handle, delimiter="|", lineterminator="\n").writerows(metadata)

print(f"created={len(metadata)} dataset={dataset}")
