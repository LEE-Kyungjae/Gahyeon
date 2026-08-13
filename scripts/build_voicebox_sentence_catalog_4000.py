#!/usr/bin/env python3
"""Build a deterministic 4,000-sentence Korean Voicebox teacher catalog."""

from __future__ import annotations

import argparse
import csv
import json
import random
import re
import unicodedata
from pathlib import Path

from build_voicebox_teacher_corpus_v2 import build_sentences


def key(text: str) -> str:
    normalized = unicodedata.normalize("NFKC", text).lower()
    return re.sub(r"[^0-9a-z가-힣]", "", normalized)


def additional_sentences():
    subjects = [
        "서버 상태", "배포 일정", "고객 요청", "회의 안건", "시장 동향",
        "예산 현황", "보안 설정", "검색 결과", "모델 성능", "데이터 품질",
        "재고 수량", "배송 일정", "오류 기록", "사용자 의견", "작업 목록",
        "문서 내용", "운영 지표", "백업 상태", "네트워크 지연", "결제 내역",
        "주간 계획", "분기 실적", "개발 진척도", "서비스 사용량", "알림 설정",
        "환율 변화", "날씨 예보", "교통 상황", "예약 내역", "장비 온도",
    ]
    actions = [
        "확인", "분석", "검토", "정리", "비교", "점검", "측정", "요약",
        "기록", "공유", "분류", "갱신",
    ]
    reasons = [
        "잘못된 판단을 피하기 위해", "중요한 변화를 놓치지 않도록",
        "다음 작업을 안전하게 시작하려고", "사용자에게 정확히 설명하기 위해",
        "문제의 재발 가능성을 낮추려고", "우선순위를 올바르게 정하기 위해",
        "예상 비용과 시간을 계산하려고", "이전 결과와 차이를 파악하기 위해",
    ]
    outcomes = [
        "핵심 내용을 세 문장으로 알려드리겠습니다",
        "확인된 사실과 추정 내용을 나누어 설명하겠습니다",
        "가장 중요한 항목부터 차례대로 처리하겠습니다",
        "필요한 후속 조치와 담당자를 함께 정리하겠습니다",
        "수치와 근거를 보기 쉽게 표로 정리하겠습니다",
        "문제가 발견되면 즉시 작업을 중단하고 보고하겠습니다",
        "이상이 없으면 다음 단계로 넘어가겠습니다",
        "변경된 부분만 골라 간결하게 말씀드리겠습니다",
    ]
    openings = ["먼저", "지금부터", "요청하신 대로", "작업을 시작하기 전에"]

    for subject in subjects:
        for reason in reasons:
            for outcome in outcomes:
                yield f"{reason} {subject} 관련 자료를 살펴보고 {outcome}."

    for subject in subjects:
        for action in actions:
            for opening in openings:
                yield f"{opening} {subject} 관련 내용을 {action}한 뒤 결과를 정확하게 알려드리겠습니다."

    places = [
        "서울", "부산", "인천", "대전", "대구", "광주", "울산", "제주",
        "강릉", "전주", "수원", "춘천", "세종", "창원", "포항",
    ]
    times = [
        "오전 여덟 시", "오전 아홉 시 반", "오전 열한 시", "오후 한 시",
        "오후 두 시 삼십 분", "오후 네 시", "저녁 여섯 시", "저녁 여덟 시",
    ]
    activities = [
        "회의", "점검", "통화", "발표", "배포", "면담", "배송", "예약 확인",
    ]
    for place in places:
        for clock in times:
            for activity in activities:
                yield f"{place}에서 진행할 {activity} 일정은 {clock}이며 십 분 전에 다시 알려드리겠습니다."

    questions = [
        "가장 먼저 확인해야 할 항목은 무엇인가요",
        "어제와 비교해 달라진 수치는 얼마나 되나요",
        "지금 바로 적용해도 안전한 변경인가요",
        "예상보다 시간이 오래 걸리는 이유가 무엇인가요",
        "비용을 줄이면서 품질을 유지할 방법이 있나요",
        "추가로 준비해야 할 자료가 남아 있나요",
        "사용자에게 미치는 영향은 어느 정도인가요",
        "같은 문제가 다시 발생할 가능성이 있나요",
    ]
    contexts = [
        "현재 상황에서", "최종 결정을 내리기 전에", "작업을 계속 진행하려면",
        "새로운 요청을 처리하면서", "결과를 공유하기 전에", "다음 회의가 열리기 전에",
    ]
    for context in contexts:
        for subject in subjects:
            for question in questions:
                yield f"{context} {subject} 관련해서 {question}?"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--count", type=int, default=4000)
    parser.add_argument("--existing-manifest", type=Path)
    parser.add_argument("--source-csv", type=Path, action="append", default=[])
    parser.add_argument("--source-lines", type=Path, action="append", default=[])
    args = parser.parse_args()

    catalog = []
    seen = set()

    # Preserve already synthesized clips so completed work can be reused.
    required = []
    if args.existing_manifest and args.existing_manifest.exists():
        required.extend(
            json.loads(line)["text"]
            for line in args.existing_manifest.read_text(encoding="utf-8").splitlines()
            if line
        )

    # User-owned recording transcripts contribute real Korean vocabulary and syntax;
    # only their text is reused, never their audio.
    sources = []
    for csv_path in args.source_csv:
        with csv_path.open(encoding="utf-8", newline="") as handle:
            for row in csv.reader(handle, delimiter="|"):
                if len(row) >= 2:
                    sources.append(row[-1].strip())

    licensed_sources = []
    for text_path in args.source_lines:
        for raw_text in text_path.read_text(encoding="utf-8", errors="ignore").splitlines():
            text = re.sub(r"\s+", " ", raw_text).strip()
            hangul = len(re.findall(r"[가-힣]", text))
            visible = len(re.findall(r"[0-9A-Za-z가-힣]", text))
            if not (15 <= len(text) <= 85):
                continue
            if visible == 0 or hangul / visible < 0.8:
                continue
            if re.search(r"https?://|www\.|[@#{}<>\\]|\d", text):
                continue
            if text[-1] not in ".?!":
                text += "."
            licensed_sources.append(text)
    random.Random(20260810).shuffle(licensed_sources)

    generated = [*build_sentences(), *additional_sentences()]
    random.Random(20260809).shuffle(generated)

    # Keep one representative when punctuation-only variants normalize equally.
    for text in [*required, *sources, *licensed_sources, *generated]:
        normalized = key(text)
        if not normalized or normalized in seen:
            continue
        seen.add(normalized)
        catalog.append(text)
        if len(catalog) >= args.count:
            break

    if len(catalog) != args.count:
        raise RuntimeError(f"Only built {len(catalog)} unique sentences")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as handle:
        for index, text in enumerate(catalog, 1):
            handle.write(json.dumps({"index": index, "text": text}, ensure_ascii=False) + "\n")
    print(json.dumps({"sentences": len(catalog), "unique_keys": len(seen), "output": str(args.output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
