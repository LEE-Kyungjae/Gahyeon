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

Conversation admission은 API key를 직접 검사하지 않고 `AgentRuntime.isReady()`만 사용한다.
`DisabledAgentRuntime`은 false를 반환하며, 로컬 또는 다른 인증 방식의 runtime은 자체
준비 조건을 구현할 수 있다. 이 값은 시작 시 캐시하지 않고 readiness 조회와 새 request
admission 때마다 다시 평가하므로 provider 장애와 복구가 프로세스 재시작 없이 반영된다.
`DefaultAgentRuntime`의 모델 전송/추론 호출이 실패하면 기본 5초 동안 readiness를 내리고
`gahyeonbot.agent.provider.failures`를 증가시킨다. cooldown 뒤 첫 admission이 recovery
probe가 되며 원자적 half-open lease 때문에 동시에 하나만 provider를 호출한다. 나머지는
모델 호출 전에 거절되고 `gahyeonbot.agent.provider.circuit.rejections`가 증가한다. 모델
응답을 받으면 즉시 circuit을 닫고 probe 실패 시 cooldown을 다시 시작한다. Tool 실행·renderer
observer 실패와 tool/text streaming 계약 위반은 provider 가용성 실패로 분류하지 않는다.

Content Safety는 Cognition 앞의 동기 gate이므로 무제한 HTTP 대기를 허용하지 않는다. OpenAI
adapter의 connect/read 기본값은 각각 300/700ms이고 각 값은 100~5000ms로 강제된다. timeout과
provider 오류는 local deterministic policy로 fallback하며
`gahyeonbot.content.safety.latency{outcome=safe|unsafe|unavailable|failure}`로 관측한다.
첫 HTTP 실패 뒤에는 기본 30초 동안 network 호출 없이 즉시 `UNAVAILABLE`을 반환한다.
cooldown 뒤에는 atomic half-open lease를 가진 요청 하나만 recovery probe가 되고 나머지는
계속 즉시 fallback한다. 이 circuit은 Cognition provider availability와 독립적이다.

## 실행 상태

`QUEUED → RUNNING → SUCCEEDED | FAILED | CANCELLED`

쓰기 도구가 추가되면 `RUNNING → WAITING_APPROVAL → RUNNING` 흐름을 사용한다.
장시간 작업은 `WAITING_BACKGROUND` 상태와 `agent_background_jobs` 영속 큐를 사용한다.

동일 actor의 새 interactive run은 DB에 저장된 `created_at`이 더 이른 `RUNNING` 및
`WAITING_APPROVAL` run을 취소한다. `WAITING_BACKGROUND`는 이 규칙에서 제외된다. 이 비교는
오래된 request ID의 idempotent retry가 더 최신 run을 취소하지 않도록 strict earlier-than을
사용한다. 따라서 서로 다른 인스턴스가 DB timestamp 정밀도 안에서 정확히 같은 시각에 run을
생성한 경우의 완전한 선형화는 현재 보장하지 않는다. 이를 제거하려면 후속으로 actor-scoped DB
lease 또는 단조 증가 admission ordinal을 도입해야 한다.
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

## Token streaming 계약 검증

Tool을 쓰는 모델은 한 assistant step에서 사용자에게 말할 text와 tool call을 섞지
않는 것이 확인된 경우에만 token streaming을 켠다. 다음 probe는 direct-text,
forced single-tool, required parallel two-tool 요청뿐 아니라 각 tool result 뒤의 최종
text-only streaming까지 총 다섯 요청으로 검사한다. 이 마지막 두 단계는 도구 호출 자체는
정상이지만 후속 답변에서 text/tool chunk를 섞거나 최종 음성을 잃는 모델도 거부한다. 응답
내용이나 API key를 출력하지 않고 chunk 종류, 고유 tool-call index 수와 finish reason만 보고한다.

```bash
OPENROUTER_API_KEY='<secret>' \
OPENROUTER_MODEL='<provider/model>' \
python3 scripts/verify_agent_streaming_provider.py
```

별도 endpoint를 쓰면 `--base-url`, key 환경변수 이름이 다르면 `--api-key-env`를
지정한다. 결과가 `ready: true`일 때만 다음 플래그를 설정한다.

```bash
GAHYEON_AGENT_TOOL_SAFE_STREAMING_ENABLED=true
GAHYEON_AGENT_STREAMING_VERIFIED_BASE_URL='<same AGENT_BASE_URL>'
GAHYEON_AGENT_STREAMING_VERIFIED_MODEL='<same provider/model ID>'
```

세 값 중 하나가 없거나 `AGENT_BASE_URL`/`AGENT_MODEL`과 검증한 base URL/model 조합이
정확히 일치하지 않으면 동기 경로를 유지한다. URL의 마지막 `/`만 동일하게 취급한다.
따라서 provider endpoint, model alias 또는 revision을 바꾼 뒤에는 probe도 다시 실행해야 한다.

실행 중 provider가 이후 계약을 위반하면 해당 요청을 실패시키고 violation metric을
증가시킨 뒤 프로세스 수명 동안 동기 fallback으로 돌아간다. Probe parser와 runtime
stream/tool 경계는 CI에서 함께 검증하지만 실제 provider 호출은 secret과 비용이 필요한
운영 전 acceptance 단계로 남긴다.

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

프로세스 내부 generation lease는 취소와 새 도구 호출 시작을 선형화한다. 여러 Pod가 같은
DB를 공유할 때 run/event row lock은 늦은 원장 commit을 막지만, 이미 시작된 외부 도구 호출을
되돌리거나 다른 Pod의 process-local lease를 취소하지는 못한다. 쓰기 도구를 다중 Pod에서
정확히 한 번 실행해야 한다면 tool-call별 durable idempotency key와 distributed fencing 또는
transactional outbox가 추가로 필요하다.
