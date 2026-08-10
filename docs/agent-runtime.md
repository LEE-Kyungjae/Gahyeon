# Gahyeon Agent Runtime

텍스트 명령과 Discord 음성 비서는 동일한 에이전트 런타임을 사용한다.

```text
Discord text / voice
        │
        ▼
Admission (rate limit, moderation, VAD/STT)
        │
        ▼
AgentRuntime
  ├─ persistent session/run/event ledger
  ├─ bounded model/tool loop
  ├─ tool allow/approval/deny policy
  ├─ conversation memory
  └─ metrics
        │
        ├─ weather
        ├─ GitHub knowledge
        ├─ paper knowledge
        └─ knowledge freshness
```

## 실행 상태

`QUEUED → RUNNING → SUCCEEDED | FAILED | CANCELLED`

쓰기 도구가 추가되면 `RUNNING → WAITING_APPROVAL → RUNNING` 흐름을 사용한다.
장시간 작업은 `WAITING_BACKGROUND` 상태와 `agent_background_jobs` 영속 큐를 사용한다.
워커가 결과를 받으면 동일 run을 다시 깨워 최종 응답을 생성하며, Pod 재시작으로
남은 claim은 복구된다.

모든 실행에는 외부 요청 ID와 내부 run ID가 있으며, 모델 호출과 도구 호출은
`agent_run_events`에 순서대로 기록된다. 동일한 외부 요청 ID는 새 실행을 만들지
않으므로 Discord 재전송이나 다중 인스턴스 경쟁에도 중복 실행을 막는다.

## 모델 설정

기본 모델 백엔드는 OpenAI 호환 OpenRouter API다.

- `AGENT_API_KEY` 또는 `OPENROUTER_API_KEY` (`OPEN_ROUTER`도 이전 Secret 이름으로 지원)
- `AGENT_BASE_URL` 기본값: `https://openrouter.ai/api`
- `AGENT_MODEL` 또는 `OPENROUTER_MODEL`

음성 비서를 켜려면 기존 `ASSISTANT_ENABLED`,
`ASSISTANT_OPENROUTER_ENABLED` 설정도 활성화해야 한다.

## 안전 한계

- 한 실행의 모델 단계는 기본 8회로 제한한다.
- 동일한 이름과 인자의 도구 호출이 3회 반복되면 루프로 판단해 실패시킨다.
- 등록되지 않은 도구는 기본 거부한다.
- 읽기 도구만 자동 실행한다. 쓰기 도구는 승인, 파괴적 도구는 거부가 기본이다.

## Discord 제어면

`/에이전트` 명령으로 자신의 실행만 조회·승인·거부·취소할 수 있다.

- `상태`: 최근 run 또는 지정한 run의 상태, 단계, 승인 ID 확인
- `승인하고 재개`: 승인 ID를 1회 소비하고 동일 run 재개
- `거부`: 승인 요청 거부 후 run 취소
- `취소`: 실행 소유자가 대기·실행 중인 run 취소

승인은 도구명과 인자 SHA-256 조합에만 유효하며 다른 인자나 다른 사용자의
실행에는 재사용할 수 없다.
