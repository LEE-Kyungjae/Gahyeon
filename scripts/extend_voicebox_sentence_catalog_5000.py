#!/usr/bin/env python3
"""Extend the frozen 4k Voicebox catalog with 1k low-overlap coverage prompts."""

from __future__ import annotations

import argparse
import json
import random
import re
import unicodedata
from collections import defaultdict
from pathlib import Path

from build_voicebox_sentence_catalog_4000 import additional_sentences
from build_voicebox_teacher_corpus_v2 import build_sentences


def normalize(text: str) -> str:
    text = unicodedata.normalize("NFKC", text).lower()
    return re.sub(r"[^0-9a-z가-힣]", "", text)


def grams(text: str, size: int = 4) -> set[str]:
    value = normalize(text)
    return {value[i : i + size] for i in range(max(1, len(value) - size + 1))}


def coverage_sentences():
    # Natural, useful TTS domains that are underrepresented in the news-heavy 4k set.
    pairs = [
        ("현관 앞에 놓인 작은 상자를 안으로 옮기고", "송장에 적힌 이름을 확인해 주세요"),
        ("냉장고에 남은 채소를 먼저 꺼내서", "오늘 저녁에 만들 수 있는 요리를 골라 봅시다"),
        ("산책을 나가기 전에 운동화 끈을 단단히 묶고", "물 한 병도 가방에 챙겨 두세요"),
        ("창가에 햇빛이 너무 강하게 들어오면", "얇은 커튼을 절반 정도만 내려 주세요"),
        ("세탁이 끝났다는 알림이 울렸으니", "옷이 구겨지기 전에 바로 널어 두겠습니다"),
        ("지하철이 평소보다 혼잡하다고 하니", "한 정거장 먼저 내려서 천천히 걸어갈까요"),
        ("약속 장소가 처음 가는 골목 안쪽이라서", "출발 전에 지도와 건물 번호를 저장했습니다"),
        ("따뜻한 차가 조금 식을 때까지", "읽던 책의 다음 장을 마저 살펴보겠습니다"),
        ("반려동물이 낯선 소리에 놀라지 않도록", "창문을 닫고 조용한 음악을 틀어 주세요"),
        ("화분의 흙이 아직 촉촉한 편이므로", "오늘은 물을 주지 않아도 괜찮겠습니다"),
        ("사진 속 글자가 흐릿하게 보여서", "밝기와 대비를 높인 뒤 다시 읽어 보겠습니다"),
        ("택배 도착 시간이 저녁으로 바뀌었으니", "외출 일정을 삼십 분 정도 늦추겠습니다"),
        ("빵이 타지 않도록 오븐 온도를 낮추고", "남은 시간을 오 분으로 다시 맞춰 주세요"),
        ("회의실 화면에 자료가 잘리지 않게", "발표 비율을 십육 대 구로 변경하겠습니다"),
        ("휴대전화 배터리가 십오 퍼센트 남아서", "절전 모드를 켜고 충전기를 찾아보겠습니다"),
        ("비밀번호를 세 번 잘못 입력했으므로", "잠금이 풀릴 때까지 잠시 기다려야 합니다"),
        ("파일 이름에 날짜와 버전을 함께 적으면", "나중에 최신 문서를 찾기가 쉬워집니다"),
        ("이어폰의 왼쪽 소리만 작게 들리니", "균형 설정과 연결 상태부터 점검해 보세요"),
        ("알림이 너무 자주 울리지 않도록", "긴급한 항목만 남기고 나머지는 묶어 두겠습니다"),
        ("검색어가 지나치게 길어서 결과가 적다면", "핵심 명사 두세 개만 남겨 다시 찾아보세요"),
    ]
    endings = [".", "!", "?", ", 괜찮을까요?", ", 그렇게 진행하겠습니다."]
    for left, right in pairs:
        for ending in endings:
            yield f"{left} {right}{ending}"

    people = ["민서", "지우", "서준", "하린", "도윤", "예린", "현우", "수아", "유진", "정민"]
    places = ["서울역", "광화문", "성수동", "해운대", "제주공항", "대전역", "수원시청", "전주한옥마을"]
    dates = ["일월 십오일", "삼월 이십일일", "오월 오일", "칠월 십팔일", "구월 이십구일", "십이월 삼십일일"]
    clocks = ["오전 여덟 시 십 분", "오전 열 시 반", "오후 한 시 사십오 분", "오후 다섯 시", "저녁 여덟 시 이십 분"]
    tasks = ["건강 검진", "기차 예매", "자료 제출", "고객 상담", "장비 점검", "온라인 면접"]
    for person in people:
        for place, date, clock, task in zip(places * 4, dates * 6, clocks * 8, tasks * 7):
            yield f"{person} 님의 {task} 일정은 {date} {clock}이며 장소는 {place}입니다."
            yield f"{date} {clock}에 {place}에서 {person} 님을 만나기로 했는데, 일정을 그대로 둘까요?"

    quantities = [
        ("삼 점 오 킬로그램", "이 점 칠 킬로그램"), ("이백사십 밀리리터", "백팔십 밀리리터"),
        ("십이만 오천 원", "구만 팔천 원"), ("팔십칠 점 삼 퍼센트", "구십이 점 일 퍼센트"),
        ("이십사 기가바이트", "십육 기가바이트"), ("일 점 이 킬로미터", "팔백오십 미터"),
        ("섭씨 삼십이 도", "섭씨 이십육 도"), ("오백육십 밀리초", "삼백십 밀리초"),
    ]
    metrics = ["무게", "용량", "가격", "성공률", "메모리 사용량", "이동 거리", "온도", "응답 시간"]
    for metric in metrics:
        for before, after in quantities:
            yield f"{metric} 수치가 {before}에서 {after}로 바뀌었는지 다시 계산해 주세요."
            yield f"측정된 {metric}은 {before}이고 목표값은 {after}이므로 차이를 기록하겠습니다."

    acronyms = ["API", "CPU", "GPU", "HTTP", "JSON", "SQL", "USB", "PDF", "Wi-Fi", "GPS"]
    operations = ["연결 상태", "처리 속도", "오류 기록", "버전 정보", "접근 권한", "응답 형식"]
    for acronym in acronyms:
        for operation in operations:
            yield f"{acronym}의 {operation}을 확인한 다음 결과를 한국어로 또박또박 읽어 주세요."
            yield f"현재 {acronym} {operation}에는 이상이 없지만 변경 이력은 별도로 저장하겠습니다."

    moods = ["차분하게", "밝고 자연스럽게", "조금 천천히", "또렷하고 간결하게", "부드러운 어조로"]
    messages = [
        "오늘도 수고 많으셨습니다", "걱정하지 않으셔도 됩니다", "준비가 끝나면 알려 주세요",
        "지금부터 하나씩 해결해 봅시다", "필요하면 언제든 다시 말씀해 주세요",
        "결과가 예상과 달라도 괜찮습니다", "잠깐 쉬었다가 다시 시작할까요",
        "좋은 아침입니다", "편안한 밤 보내세요", "안전하게 다녀오세요",
    ]
    for mood in moods:
        for message in messages:
            yield f"{mood} 말씀드리겠습니다. {message}."
            yield f"{message}. 이번 안내는 {mood} 전달해 주세요."

    objects = ["파란 우산", "작은 열쇠", "검은 안경", "노란 공책", "무선 충전기", "유리 물병", "회색 목도리", "교통 카드"]
    locations = ["책상 아래", "두 번째 서랍", "현관 신발장", "침대 옆", "자동차 뒷좌석", "주방 선반", "가방 안쪽", "회의실 입구"]
    for obj in objects:
        for location in locations:
            yield f"혹시 {location}에 둔 {obj}을 보셨나요? 아무리 찾아도 보이지 않습니다."
            yield f"잊어버리지 않도록 {obj}은 {location}에 보관했다고 메모해 두겠습니다."

    conditions = ["비가 갑자기 많이 오면", "도로가 얼어 있다면", "바람이 강하게 불면", "기차가 늦게 도착하면", "예약이 취소된다면", "인터넷 연결이 끊기면", "몸 상태가 좋지 않으면", "재료가 부족하다면"]
    responses = ["가까운 실내에서 기다리세요", "다른 이동 경로를 찾아보겠습니다", "일정을 내일로 미루는 편이 좋겠습니다", "담당자에게 먼저 연락해 주세요", "저장된 자료로 작업을 계속하겠습니다", "무리하지 말고 충분히 쉬세요", "대체할 수 있는 방법을 제안하겠습니다", "안전을 우선해서 판단하겠습니다"]
    for condition in conditions:
        for response in responses:
            yield f"{condition} {response}. 상황이 달라지면 바로 알려 주세요."


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--count", type=int, default=5000)
    parser.add_argument("--max-jaccard", type=float, default=0.64)
    args = parser.parse_args()

    rows = [json.loads(line) for line in args.input.read_text(encoding="utf-8").splitlines() if line]
    if len(rows) != 4000:
        raise RuntimeError(f"Expected frozen 4,000-row catalog, got {len(rows)}")
    texts = [row["text"] for row in rows]
    seen = {normalize(text) for text in texts}
    accepted_grams = [grams(text) for text in texts]
    postings: dict[str, set[int]] = defaultdict(set)
    for index, item_grams in enumerate(accepted_grams):
        for gram in item_grams:
            postings[gram].add(index)

    candidates = [*coverage_sentences(), *build_sentences(), *additional_sentences()]
    random.Random(20260809).shuffle(candidates)
    for text in candidates:
        cleaned = re.sub(r"\s+", " ", text).strip()
        key = normalize(cleaned)
        if not key or key in seen or not 8 <= len(cleaned) <= 100:
            continue
        candidate_grams = grams(cleaned)
        # A pair with non-zero Jaccard overlap must share at least one gram.
        # The inverted index avoids comparing every candidate with all 4k rows.
        possible = set()
        for gram in candidate_grams:
            possible.update(postings.get(gram, ()))
        too_close = False
        for existing_index in possible:
            existing = accepted_grams[existing_index]
            union = len(candidate_grams | existing)
            if union and len(candidate_grams & existing) / union > args.max_jaccard:
                too_close = True
                break
        if too_close:
            continue
        texts.append(cleaned)
        seen.add(key)
        accepted_grams.append(candidate_grams)
        new_index = len(accepted_grams) - 1
        for gram in candidate_grams:
            postings[gram].add(new_index)
        if len(texts) == args.count:
            break

    if len(texts) != args.count:
        raise RuntimeError(f"Only assembled {len(texts)} low-overlap sentences")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as handle:
        for index, text in enumerate(texts, 1):
            handle.write(json.dumps({"index": index, "text": text}, ensure_ascii=False) + "\n")
    print(json.dumps({"sentences": len(texts), "added": len(texts) - len(rows), "output": str(args.output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
