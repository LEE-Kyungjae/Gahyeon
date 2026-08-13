#!/usr/bin/env python3
"""Generate a diverse profile-9 Voicebox teacher corpus for Piper distillation."""

from __future__ import annotations

import argparse
import json
import random
import subprocess
import time
import urllib.request
import wave
from pathlib import Path


PROFILE_9 = "1df376d5-c74d-415c-a2f0-fdb1654f7331"


def sync_progress(output: Path, total: int) -> None:
    manifest = output / "manifest.jsonl"
    items = [json.loads(line) for line in manifest.read_text(encoding="utf-8").splitlines() if line] if manifest.exists() else []
    seconds = 0.0
    for item in items:
        try:
            with wave.open(item["audio"], "rb") as handle:
                seconds += handle.getnframes() / handle.getframerate()
        except (FileNotFoundError, wave.Error):
            pass
    average = sum(float(x["generation_seconds"]) for x in items) / len(items) if items else 0
    remaining = max(0, total - len(items)) * average
    status = (
        "===== Voicebox → Piper 교사 코퍼스 =====\n"
        f"  생성: {len(items)}/{total} ({len(items) / total * 100:.1f}%)\n"
        f"  확보 음성: {seconds / 60:.1f}분   평균 생성: {average:.1f}초/문장\n"
        f"  예상 완료까지: {remaining / 60:.1f}분\n"
    )
    try:
        subprocess.run(
            ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=4", "land", "tee /home/ubuntu/voicebox-distill.status"],
            input=status,
            text=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=10,
            check=False,
        )
    except subprocess.TimeoutExpired:
        # Progress mirroring is optional and must never stop corpus generation.
        pass


def build_sentences() -> list[str]:
    sentences = [
        "네, 확인했습니다.",
        "잠시만 기다려 주세요.",
        "바로 처리하겠습니다.",
        "어떤 부분부터 살펴볼까요?",
        "조금 전에 말씀하신 내용을 다시 확인해 보겠습니다.",
        "오늘 서울의 날씨와 미세먼지 정보를 함께 알려드릴까요?",
        "회의가 끝난 뒤 참석자에게 요약 메일을 보내겠습니다.",
        "현재 상황에서는 두 번째 방안이 비용과 위험을 모두 줄일 수 있습니다.",
        "결론부터 말씀드리면 서비스는 정상이며 추가 조치는 필요하지 않습니다.",
        "정확한 답을 위해 최신 자료와 내부 문서를 함께 검색하겠습니다.",
        "전쟁 프리미엄이 걷히는 자리를 광산 하나가 채우고 있습니다.",
        "기니 시만두의 공급 충격이 철광석 가격과 한국 철강의 마진을 흔들고 있습니다.",
        "인도네시아 정부는 팜유와 석탄, 니켈의 수출 관리 정책을 발표했습니다.",
        "단기 변동성과 장기 구조 변화는 구분해서 해석할 필요가 있습니다.",
        "오전 아홉 시 삼십 분에 시작한 작업은 오후 두 시쯤 완료될 예정입니다.",
        "전체 용량 이 테라바이트 가운데 남은 공간은 사십삼 기가바이트입니다.",
        "응답 시간은 평균 영 점 팔 초이고 성공률은 구십구 점 구 퍼센트입니다.",
        "첫 번째 서버는 정상이고 두 번째 서버에서만 연결 오류가 발생했습니다.",
        "깃허브 저장소의 최근 변경 사항과 실패한 작업 기록을 확인하겠습니다.",
        "에이 피 아이 요청은 발화가 완전히 끝난 뒤 한 번만 전송합니다.",
    ]

    topics = [
        "국내 주식 시장", "원달러 환율", "철광석 가격", "팜유 공급망",
        "반도체 수출", "인공지능 산업", "클라우드 비용", "프로젝트 일정",
        "고객 문의", "서버 장애", "보안 경고", "문서 검색", "회의 결과",
        "분기 실적", "재고 현황", "물류 일정", "정책 변화", "경쟁사 동향",
        "모델 정확도", "음성 인식 품질",
    ]
    conclusions = [
        "핵심 원인과 영향을 세 문장으로 정리하겠습니다",
        "어제 자료와 비교해 달라진 수치를 알려드리겠습니다",
        "근거가 확인된 내용과 추정 내용을 구분해서 설명하겠습니다",
        "중요도와 긴급도를 기준으로 우선순위를 정하겠습니다",
        "실행 가능한 다음 조치를 제안하겠습니다",
    ]
    for topic in topics:
        for conclusion in conclusions:
            sentences.append(f"{topic}에 관한 최신 자료를 확인하고 {conclusion}.")

    systems = [
        "데이터베이스", "벡터 검색 서버", "음성 인식 서비스", "텍스트 생성 모델",
        "디스코드 봇", "배포 파이프라인", "백업 저장소", "그래픽 처리 장치",
        "중앙 처리 장치", "네트워크 연결",
    ]
    checks = [
        "응답 지연과 오류율을 측정하겠습니다",
        "최근 한 시간의 로그에서 이상 징후를 찾겠습니다",
        "메모리와 저장 공간이 충분한지 확인하겠습니다",
        "중단 없이 재시작할 수 있는 절차를 준비하겠습니다",
        "사용자에게 미치는 영향을 먼저 검토하겠습니다",
    ]
    for system in systems:
        for check in checks:
            sentences.append(f"{system}의 현재 상태를 점검하고 {check}.")

    questions = [
        "오늘 가장 먼저 처리해야 할 업무는 무엇인가요?",
        "이 보고서에서 가장 중요한 위험 요인은 무엇인가요?",
        "현재 서버가 느려진 원인을 확인할 수 있나요?",
        "다음 회의까지 준비해야 할 자료가 남아 있나요?",
        "비용을 줄이면서 품질을 유지할 방법이 있을까요?",
        "이 변경 사항을 지금 배포해도 안전한가요?",
        "검색 결과에 신뢰할 만한 근거가 포함되어 있나요?",
        "음성이 중간에 끊긴 이유를 로그에서 찾았나요?",
        "어제와 비교해서 처리 속도가 얼마나 빨라졌나요?",
        "추가 정보가 필요하면 어떤 내용을 알려드려야 하나요?",
    ]
    sentences.extend(questions)

    times = ["오전 여덟 시", "오전 열한 시 반", "오후 한 시", "오후 네 시 삼십 분", "저녁 일곱 시"]
    events = ["시장 보고서 검토", "개발팀 회의", "서비스 배포", "고객 통화", "주간 업무 정리"]
    for event in events:
        for clock in times:
            sentences.append(f"{event} 일정은 {clock}로 등록하고 십 분 전에 알려드리겠습니다.")

    # Broader natural Korean coverage for a pure Voicebox teacher corpus.
    # Keep several sentence shapes so Piper does not learn one repeated cadence.
    subjects = [
        "새로 접수된 고객 문의", "이번 주 프로젝트 일정", "어제 발생한 서버 오류",
        "오늘 아침 시장 동향", "다음 달 예산 계획", "회의에서 결정된 사항",
        "최근 변경된 보안 정책", "현재 진행 중인 배포 작업", "지난 분기 매출 자료",
        "사용자가 남긴 개선 의견", "백업 저장소의 남은 용량", "음성 모델의 평가 결과",
        "개발팀이 제출한 코드 변경", "오후에 예정된 화상 회의", "새로 수집한 학습 데이터",
        "모바일 앱의 응답 속도", "국내외 주요 경제 지표", "배송이 지연된 주문 목록",
        "이번 업데이트의 핵심 기능", "장애 복구 과정에서 남은 문제",
        "데이터베이스의 느린 질의", "고객 지원팀의 처리 현황", "내일 발표할 보고서 초안",
        "실시간 알림 서비스의 상태", "최근 일주일 동안의 사용량",
    ]
    actions = [
        "관련 기록과 비교해 정확한 원인을 확인했습니다",
        "중요한 항목부터 차례대로 정리해 알려드리겠습니다",
        "수치가 달라진 구간을 찾아 근거와 함께 설명하겠습니다",
        "누락된 내용이 없는지 다시 한번 꼼꼼하게 검토하겠습니다",
        "문제가 재발하지 않도록 필요한 조치를 준비하겠습니다",
        "담당자가 바로 판단할 수 있도록 짧고 명확하게 요약하겠습니다",
        "예상되는 위험과 가능한 해결 방법을 함께 제안하겠습니다",
        "이전 결과와 나란히 놓고 변화한 부분을 살펴보겠습니다",
        "완료 여부를 확인한 뒤 다음 작업으로 안전하게 넘어가겠습니다",
        "사용자에게 미치는 영향을 기준으로 우선순위를 결정하겠습니다",
    ]
    openings = [
        "먼저", "지금부터", "요청하신 대로", "결론을 말씀드리기 전에",
    ]
    for subject in subjects:
        for action in actions:
            for opening in openings:
                sentences.append(f"{opening} {subject} 관련 내용을 확인하고 {action}.")

    everyday = [
        ("창문을 열기 전에", "밖의 기온과 공기 질을 확인해 주세요"),
        ("집을 나서기 전에", "우산과 휴대전화를 챙겼는지 살펴보세요"),
        ("물을 끓이는 동안", "식탁 위에 컵과 찻잎을 준비해 둘게요"),
        ("저녁 식사가 끝나면", "남은 음식을 정리하고 설거지를 시작하겠습니다"),
        ("주말 여행을 떠나기 전에", "교통 상황과 숙소 예약을 다시 확인할까요"),
        ("운동을 시작하기 전에", "가볍게 몸을 풀고 호흡을 천천히 가다듬으세요"),
        ("책을 읽다가 궁금한 점이 생기면", "표시해 두었다가 함께 이야기해 봅시다"),
        ("비가 그친 오후에는", "가까운 공원을 천천히 걸어도 좋겠습니다"),
        ("잠들기 한 시간 전에는", "밝은 화면을 줄이고 방을 조용하게 만들어 보세요"),
        ("아침 식사를 준비하면서", "오늘 해야 할 일을 세 가지만 정해 보겠습니다"),
    ]
    tones = [".", "!", "?", ", 괜찮으신가요?"]
    for prefix, clause in everyday:
        for tone in tones:
            sentences.append(f"{prefix} {clause}{tone}")

    number_sentences = [
        "회의는 오전 아홉 시 십오 분에 시작해서 열한 시에 끝날 예정입니다.",
        "전체 백 개 항목 가운데 아흔일곱 개가 정상적으로 처리되었습니다.",
        "응답 시간은 일 점 이 초에서 영 점 칠 초로 약 사십 퍼센트 줄었습니다.",
        "첫째 항목은 품질, 둘째 항목은 속도, 셋째 항목은 안정성입니다.",
        "저장 공간 이 테라바이트 중에서 약 육백오십 기가바이트가 남았습니다.",
        "다음 점검은 팔월 십일일 화요일 오후 세 시로 예약하겠습니다.",
        "오류는 열 번의 요청 중 한 번이 아니라 천 번 중 두 번 발생했습니다.",
        "예상 비용은 삼십만 원에서 사십오만 원 사이로 계산됩니다.",
        "현재 온도는 이십칠 도이고 체감 온도는 삼십 도에 가깝습니다.",
        "파일 세 개의 크기는 각각 이십, 사십, 육십 메가바이트입니다.",
    ]
    sentences.extend(number_sentences)

    unique = list(dict.fromkeys(sentences))
    random.Random(20260808).shuffle(unique)
    return unique


def request_json(url: str, method: str = "GET", body: dict | None = None) -> dict:
    data = None if body is None else json.dumps(body).encode("utf-8")
    headers = {} if data is None else {"Content-Type": "application/json"}
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=600) as response:
        return json.load(response)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:17493")
    parser.add_argument("--output", type=Path, default=Path("artifacts/voicebox-teacher-v2"))
    parser.add_argument("--limit", type=int)
    args = parser.parse_args()
    sentences = build_sentences()
    if args.limit:
        sentences = sentences[: args.limit]
    args.output.mkdir(parents=True, exist_ok=True)
    audio_dir = args.output / "profile_9"
    audio_dir.mkdir(exist_ok=True)
    manifest_path = args.output / "manifest.jsonl"
    completed = set()
    if manifest_path.exists():
        completed = {json.loads(line)["index"] for line in manifest_path.read_text().splitlines() if line}
    sync_progress(args.output, len(sentences))

    for index, text in enumerate(sentences, 1):
        if index in completed:
            continue
        started = time.monotonic()
        generation = request_json(
            f"{args.base_url}/generate", "POST",
            {"profile_id": PROFILE_9, "text": text, "language": "ko", "engine": "qwen", "model_size": "0.6B", "normalize": True},
        )
        generation_id = generation["id"]
        while True:
            status = request_json(f"{args.base_url}/history/{generation_id}")
            if status.get("status") == "completed":
                break
            if status.get("status") == "failed":
                raise RuntimeError(status.get("error", "Voicebox generation failed"))
            time.sleep(0.25)
        destination = audio_dir / f"p9v2_{index:03d}.wav"
        with urllib.request.urlopen(f"{args.base_url}/audio/{generation_id}", timeout=600) as response:
            destination.write_bytes(response.read())
        item = {"profile": "9", "profile_id": PROFILE_9, "index": index, "text": text, "audio": str(destination), "generation_id": generation_id, "generation_seconds": round(time.monotonic() - started, 3)}
        with manifest_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(item, ensure_ascii=False) + "\n")
        sync_progress(args.output, len(sentences))
        print(json.dumps(item, ensure_ascii=False), flush=True)

    print(json.dumps({"completed": len(sentences), "output": str(args.output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
