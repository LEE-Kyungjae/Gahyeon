#!/usr/bin/env python3
"""Generate matched Voicebox teacher corpora for Piper distillation."""

from __future__ import annotations

import argparse
import csv
import json
import time
import urllib.request
from pathlib import Path


PROFILES = {
    "6": "1b577d8f-56aa-49f7-b76c-81f720c78d78",
    "9": "1df376d5-c74d-415c-a2f0-fdb1654f7331",
}

SENTENCES = [
    "안녕하세요. 오늘 처리할 업무를 말씀해 주세요.",
    "요청하신 내용을 확인하고 바로 정리하겠습니다.",
    "일정과 우선순위를 검토해서 알려드리겠습니다.",
    "회의 자료에서 핵심 쟁점을 먼저 찾아보겠습니다.",
    "관련 문서를 검색하고 정확한 근거를 확인하겠습니다.",
    "중요한 변경 사항이 생기면 바로 알려드리겠습니다.",
    "오늘 오후 세 시에 예정된 회의를 확인했습니다.",
    "내일 오전까지 보고서 초안을 준비하겠습니다.",
    "이번 주에 처리해야 할 항목은 모두 다섯 개입니다.",
    "작업이 완료되면 결과와 남은 문제를 함께 보고하겠습니다.",
    "현재 서버 상태는 정상이며 특이 사항은 없습니다.",
    "오류가 발생한 구간을 찾아 원인을 분석하겠습니다.",
    "배포 전에 설정값과 비밀키를 다시 확인하겠습니다.",
    "사용자에게 영향을 주는 변경은 신중하게 적용하겠습니다.",
    "문서 임베딩 작업은 백그라운드에서 계속 진행 중입니다.",
    "검색 결과를 바탕으로 필요한 정보만 간결하게 요약했습니다.",
    "질문의 의도를 파악한 뒤 적절한 도구를 선택하겠습니다.",
    "확실하지 않은 내용은 추측하지 않고 다시 검증하겠습니다.",
    "요청이 길어도 끝까지 듣고 순서대로 처리하겠습니다.",
    "답변을 생성하는 동안 음성 채널 연결을 유지하겠습니다.",
    "발화가 끝난 것을 확인한 뒤 한 번만 요청을 보내겠습니다.",
    "짧은 침묵 때문에 문장이 중간에 끊기지 않도록 하겠습니다.",
    "이전 대화 내용을 참고해서 자연스럽게 이어서 답변하겠습니다.",
    "필요하면 세부 작업을 나누고 진행 상황을 알려드리겠습니다.",
    "파일 이름과 저장 위치를 확인한 후 작업을 시작하겠습니다.",
    "데이터가 손상되지 않도록 원본은 그대로 보관하겠습니다.",
    "모델의 응답 속도와 정확도를 각각 측정하겠습니다.",
    "음성 품질이 기준에 미달하면 다음 단계로 넘어가지 않겠습니다.",
    "발음이 틀린 문장은 학습 데이터에서 제외하겠습니다.",
    "단어 사이에 불필요한 침묵이 있는지도 검사하겠습니다.",
    "숫자와 영문 약어는 문맥에 맞게 또렷하게 읽겠습니다.",
    "인공지능 모델과 실시간으로 대화를 이어가겠습니다.",
    "한국어 문장의 억양과 호흡을 자연스럽게 유지하겠습니다.",
    "결과가 준비되는 대로 음성 예시를 재생하겠습니다.",
    "첫 번째 후보와 두 번째 후보를 같은 문장으로 비교하겠습니다.",
    "속도가 빨라도 목소리와 발음이 정확해야 사용할 수 있습니다.",
    "업무 비서는 질문에 맞는 답을 구체적으로 제공해야 합니다.",
    "무엇을 도와줄지만 반복해서 묻지 않도록 대화를 기억하겠습니다.",
    "긴 문장도 생략하지 않고 문장 단위로 나누어 읽겠습니다.",
    "최종 모델은 디스코드 음성 채널에서 빠르게 응답해야 합니다.",
]

# Deterministic, natural business-assistant coverage. Keeping the corpus generated
# from reviewed templates makes it easy to resume without changing sentence IDs.
SUBJECTS = [
    "프로젝트 일정", "회의 안건", "시장 동향", "서버 상태", "배포 계획",
    "고객 요청", "분석 보고서", "작업 목록", "검색 결과", "운영 지표",
    "보안 설정", "문서 내용", "데이터 품질", "모델 성능", "예산 현황",
]
ACTIONS = [
    "다시 확인해서 핵심 내용을 알려드리겠습니다",
    "우선순위에 따라 정리해서 보고하겠습니다",
    "관련 자료와 비교해서 정확하게 설명하겠습니다",
    "문제가 있는 부분을 찾아 개선 방안을 제안하겠습니다",
    "변경된 내용을 반영해서 최신 상태로 갱신하겠습니다",
    "필요한 항목만 골라 이해하기 쉽게 요약하겠습니다",
    "수치와 근거를 검토한 뒤 결과를 공유하겠습니다",
    "진행 상황을 추적하고 완료 여부를 확인하겠습니다",
]
CONDITIONS = [
    "오전 업무를 시작하기 전에",
    "다음 회의가 열리기 전에",
    "최종 결정을 내리기 전에",
    "새로운 요청을 처리하기 전에",
    "오늘 업무가 끝나기 전에",
]

for subject in SUBJECTS:
    for action in ACTIONS:
        SENTENCES.append(f"{subject}을 확인한 뒤 {action}.")
for condition in CONDITIONS:
    for subject in SUBJECTS:
        SENTENCES.append(f"{condition} {subject}을 검토하고 필요한 사항을 알려드리겠습니다.")


def request_json(url: str, method: str = "GET", body: dict | None = None) -> dict:
    data = None if body is None else json.dumps(body).encode("utf-8")
    headers = {} if data is None else {"Content-Type": "application/json"}
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=600) as response:
        return json.load(response)


def download(url: str, destination: Path) -> None:
    with urllib.request.urlopen(url, timeout=600) as response:
        destination.write_bytes(response.read())


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:17493")
    parser.add_argument("--output", type=Path, default=Path("artifacts/voicebox-teacher"))
    parser.add_argument("--limit", type=int, default=len(SENTENCES))
    parser.add_argument("--profiles", nargs="+", choices=sorted(PROFILES), default=sorted(PROFILES))
    args = parser.parse_args()

    args.output.mkdir(parents=True, exist_ok=True)
    manifest_path = args.output / "manifest.jsonl"
    completed = set()
    if manifest_path.exists():
        for line in manifest_path.read_text(encoding="utf-8").splitlines():
            item = json.loads(line)
            completed.add((item["profile"], item["index"]))

    for profile in args.profiles:
        profile_id = PROFILES[profile]
        audio_dir = args.output / f"profile_{profile}"
        audio_dir.mkdir(exist_ok=True)
        for index, text in enumerate(SENTENCES[: args.limit], 1):
            if (profile, index) in completed:
                continue
            started = time.monotonic()
            generation = request_json(
                f"{args.base_url}/generate",
                "POST",
                {
                    "profile_id": profile_id,
                    "text": text,
                    "language": "ko",
                    "engine": "qwen",
                    "model_size": "0.6B",
                    "normalize": True,
                },
            )
            generation_id = generation["id"]
            while True:
                status = request_json(f"{args.base_url}/history/{generation_id}")
                state = status.get("status")
                if state == "completed":
                    break
                if state == "failed":
                    raise RuntimeError(status.get("error", "Voicebox generation failed"))
                time.sleep(0.25)
            audio_path = audio_dir / f"p{profile}_{index:03d}.wav"
            download(f"{args.base_url}/audio/{generation_id}", audio_path)
            item = {
                "profile": profile,
                "profile_id": profile_id,
                "index": index,
                "text": text,
                "audio": str(audio_path),
                "generation_id": generation_id,
                "generation_seconds": round(time.monotonic() - started, 3),
            }
            with manifest_path.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(item, ensure_ascii=False) + "\n")
            print(json.dumps(item, ensure_ascii=False), flush=True)

    with (args.output / "metadata_all.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, delimiter="|", lineterminator="\n")
        for line in manifest_path.read_text(encoding="utf-8").splitlines():
            item = json.loads(line)
            writer.writerow([Path(item["audio"]).name, item["text"], item["profile"]])


if __name__ == "__main__":
    main()
