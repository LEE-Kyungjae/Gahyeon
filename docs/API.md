# Gahyeon Client API

이 문서는 현재 Controller 구현을 기준으로 한 Client/Adapter API 계약이다. Discord Slash
Command는 호환 Adapter 인터페이스이며 아래 HTTP API의 식별자나 domain model이 아니다.

## 기본 조건

- 기본 HTTP root: `http://127.0.0.1:8080/api`
- `GAHYEON_HEADLESS_ENABLED=true`일 때 `/api/gahyeon/**` REST/SSE API가 활성화된다.
- Unreal WebSocket/음성 API는 추가로 `GAHYEON_UNREAL_WEBSOCKET_ENABLED=true`가 필요하다.
- JSON은 별도 표기가 없으면 `Content-Type: application/json`을 사용한다.

## 인증

`/api/gahyeon/**` 전체는 `GahyeonClientAuthenticationFilter`가 보호한다.

- `GAHYEON_CLIENT_TOKEN`이 비어 있으면 loopback(`127.0.0.1`, `::1`) 요청만 허용한다.
- token이 설정되면 local/remote 구분 없이 다음 header가 필요하다.

```http
Authorization: Bearer <GAHYEON_CLIENT_TOKEN>
```

- token 없이 remote에서 접근: `403 Forbidden`
- token이 틀리거나 누락됨: `401 Unauthorized`와 `WWW-Authenticate: Bearer`

운영에서 reverse proxy의 주소를 loopback으로 신뢰하는 방식으로 우회하지 않는다. Core와 Client
양쪽에 같은 고엔트로피 token을 설정한다.

## Session과 Actor identity

Client가 보내는 `sessionId`와 사용자 식별자는 **외부 ID**다. Core는 source namespace를 붙인
내부 `ConversationSessionId`와 `ActorId`로 변환한다.

```text
headless session abc → headless:abc
desktop session abc  → desktop:abc
unreal session abc   → unreal:abc
```

따라서 서로 다른 Client가 같은 문자열을 사용해도 Session이 충돌하지 않는다. Discord의
`guild_id`, `channel_id`, `user_id`를 Desktop/Headless API에 전달하거나 내부 DB ID로 간주하지
않는다.

Client 입력은 Core가 감당할 수 있는 공통 상한에서 거부한다. `sessionId`는 180자,
`requestId`는 120자, `displayName`은 100자, 사용자 메시지는 UTF-16 기준 16,384자까지다.
Desktop `installationId`와 Headless `externalActorId`는 200자까지 허용한다. Bean Validation을
통과하지 못한 HTTP 요청은 conversation/identity 자원을 할당하기 전에 `400 Bad Request`로
종료한다.

## Health와 운영

| Method | Path | 설명 |
|---|---|---|
| `GET` | `/api/health` | DB, 선택적 Discord, 필수 Conversation Runtime readiness. 준비 전 `503` |
| `GET` | `/api/actuator/health` | Spring Actuator health 상세 |
| `GET` | `/api/actuator/metrics` | metric 이름 목록 |
| `GET` | `/api/actuator/metrics/{name}` | 개별 metric |
| `GET` | `/api/actuator/prometheus` | Prometheus scrape |

Actuator health details are hidden by default. Set
`MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always` only on a trusted internal network or for a
bounded diagnostic run; the headless smoke scripts enable it explicitly for contract assertions.

`/api/health`의 Weather timestamp/error는 관측 정보이며 readiness를 차단하지 않는다. Headless에서
Discord가 비활성화된 경우 `bot`은 `DISABLED`가 될 수 있다.
Headless 또는 Unreal WebSocket이 활성화되면 `conversationRequired=true`이며
`AgentRuntime.isReady()`가 false이거나 예외를 던질 때 `conversation=DOWN`과 HTTP 503을 반환한다.
같은 readiness는 새 conversation admission마다 재평가되어 provider 복구 후 재시작 없이
요청을 다시 받을 수 있다.
모델 provider 호출 실패 직후에는 bounded cooldown 동안 `conversation=DOWN`이며, cooldown
후 새 요청 하나만 half-open recovery probe를 획득한다. 동시에 들어온 다른 요청은 provider를
호출하지 않으며, probe가 성공하면 즉시 `UP`으로 복귀하고 실패하면 cooldown을 다시 시작한다.
둘 다 비활성인 Discord 전용 배포에서는 `conversation=OPTIONAL_DISABLED`가 관측되지만 다른
기능의 readiness를 차단하지 않는다. Actuator의 `agentRuntime` component도 같은 정책을 사용한다.
Headless와 Unreal WebSocket을 함께 활성화하면 Actuator의 `unrealRuntime` component가
assistant TTS, batch STT, 그리고 명시적으로 켠 경우 streaming STT provider readiness를
검사한다. `unavailable`에는 `tts`, `batch_stt`, `streaming_stt`,
`streaming_stt_provider_missing` 중 실제 실패 사유만 들어간다.

## Headless Conversation

### `POST /api/gahyeon/conversations/{sessionId}/messages`

동기 text conversation 진입점이다.

```json
{
  "requestId": "client-request-018",
  "externalActorId": "local-user-1",
  "displayName": "User",
  "message": "지금 무엇을 하고 있었어?"
}
```

`requestId`가 비어 있으면 서버가 생성한다. `externalActorId`는 필수다. 과거 호환용 양수
`actorId`도 받지만 `legacy-numeric:<value>`라는 외부 key로 다시 resolve하며 내부 Actor ID로
신뢰하지 않는다.

응답:

```json
{
  "runId": "agent-run-id",
  "content": "응답 텍스트"
}
```

## Durable Event 조회

### `GET /api/gahyeon/events?afterSequence=0&limit=100`

```json
{
  "events": [],
  "nextSequence": 0
}
```

`afterSequence`보다 큰 durable event를 sequence 순으로 반환한다. 다음 요청에는 응답의
`nextSequence`를 사용한다. Event에는 `schemaVersion`, `eventId`, `sequence`, semantic `type`,
`scope`, namespaced `sessionId`, `correlationId`, `occurredAt`, `payload`가 포함된다.

## Desktop Conversation과 SSE

### `POST /api/gahyeon/desktop/identity/link`

Discord의 `/데스크톱연결` 명령으로 발급한 128-bit 일회성 코드를 Desktop installation에
소비한다. 원문 코드는 DB에 저장하지 않고 SHA-256만 저장하며 10분 뒤 만료되고 한 번만
사용할 수 있다.

- `/데스크톱연결 action:연결 코드 발급`
- `/데스크톱연결 action:연결 기기 목록`
- `/데스크톱연결 action:기기 이름 변경 device:<기기 ID> label:<새 이름>`
- `/데스크톱연결 action:기기 연결 폐기 device:<기기 ID>`

목록과 폐기 응답은 Discord ephemeral reply로만 전달된다. 다른 Principal의 device ID는
폐기할 수 없다. 폐기 즉시 해당 credential 인증은 실패하며, 같은 설치는 새 일회성 코드로
재연결해 복구한다.
Principal당 활성 기기는 최대 10개이며 목록은 그 전체를 최근 등록 순으로 표시한다.

```json
{
  "code": "discord-issued-one-time-code",
  "installationId": "stable-installation-id",
  "displayName": "User"
}
```

처음 사용하는 installation은 Discord와 동일한 내부 Principal에 바로 결합한다. 기존
Desktop identity가 있으면 conversation, usage, agent ledger를 Discord Principal로
transactional하게 이전하고 external identity를 재결합한다. Discord 전용 DM 구독/전송
기록은 플랫폼 독립 데이터가 아니므로 변경하지 않는다.

성공 응답에는 `linked=true`와 256-bit installation credential이 한 번만 포함된다. 서버는
credential 원문을 저장하지 않고 SHA-256만 저장한다. Electron Client는 이를 OS
`safeStorage`로 암호화해 user-data 디렉터리에 mode `0600`으로 저장하고 이후 Desktop
요청에 `X-Gahyeon-Account-Token`으로 전송한다. 재연결하면 기존 credential은 폐기되고
새 credential만 유효하다. 이 credential은 `/gahyeon/desktop/**`에서만 인증 수단으로
받으며 Headless·Unreal API에는 사용할 수 없다.
기본 기기 이름은 installation suffix로 만들며 Discord에서 100자 이내로 변경할 수 있다.
인증 성공 시 `last_used_at`은 요청마다 쓰지 않고 최소 5분 간격으로만 갱신한다.
Credential은 발급 후 90일에 만료된다. 만료된 credential은 인증, 활성 기기 목록과 10기기
한도 계산에서 즉시 제외되며 같은 installation은 새 일회성 코드를 소비해 회전할 수 있다.

### `GET /api/gahyeon/desktop/identity/status?installationId={id}`

`linked`와, account credential이 해당 installation을 소유한 경우에만
`credentialExpiresAt`을 반환한다. credential 원문, 내부 Actor ID나 Discord ID는 노출하지
않는다. Desktop은 시작할 때 이 서버 권위 상태를 조회하며 localStorage 표식을 연결 증거로
신뢰하지 않는다. 만료 7일 전부터 한국어·영어·일본어 갱신 경고를 표시한다.
Account credential이 없거나 만료·폐기·타 installation 소유이면 external identity가 남아
있더라도 `linked=false`다. OS `safeStorage`가 없는 Browser fallback은 일회성 코드를 소비하거나
상태/해제 API를 호출하지 않으며 native Desktop 필요 안내만 표시한다.

### `DELETE /api/gahyeon/desktop/identity/current?installationId={id}`

현재 요청의 account credential로 인증된 Principal이 해당 installation을 소유할 때만 `204`로
폐기한다. Deployment Bearer나 loopback만으로는 실행할 수 없다. Electron은 성공 응답을 받은
뒤 OS `safeStorage`의 암호화 credential 파일도 삭제하며 UI를 미연결 상태로 되돌린다.

### `POST /api/gahyeon/desktop/conversations/{sessionId}/messages`

```json
{
  "requestId": "desktop-request-018",
  "installationId": "stable-installation-id",
  "displayName": "User",
  "message": "오늘 일정 정리해 줘."
}
```

`installationId`가 Desktop Actor identity의 외부 key다. 최종 응답은 Headless와 같은
`runId/content` 형태이며, 생성 중 delta는 SSE로 동시에 전달된다.

### `DELETE /api/gahyeon/desktop/conversations/{sessionId}/active?installationId={id}`

현재 Desktop conversation generation을 취소하고 `204 No Content`를 반환한다. 늦게 도착한
LLM/TTS 결과는 새 generation에 적용되지 않는다. Session을 처음 claim한 installation과
다른 ID의 취소 요청은 `409 Conflict`로 거부한다.

### `GET /api/gahyeon/desktop/events?sessionId={id}&installationId={install}&afterSequence={n}`

`text/event-stream` 연결이다. durable replay와 현재 session의 ephemeral conversation delta를
전달한다. 재연결 시 마지막 durable sequence를 `afterSequence`로 보낸다.
최초 SSE 또는 message 요청이 session을 installation에 원자적으로 귀속시키며 이후 다른
installation이 같은 session에 attach하는 것을 거부한다. Account credential이 있는 요청은
credential의 Principal이 해당 installation을 실제 소유하는지도 추가로 검증한다.
활성 subscription은 기본적으로 전역 128개, 동일 session 4개까지 허용하며 초과 연결은
`429 Too Many Requests`로 격리한다. 정상 종료·timeout·전송 오류 시 해당 admission slot을
반납한다. 한도는 `GAHYEON_DESKTOP_MAXIMUM_EVENT_SUBSCRIPTIONS`와
`GAHYEON_DESKTOP_MAXIMUM_EVENT_SUBSCRIPTIONS_PER_SESSION`으로 조정할 수 있다.
한 번의 `conversation.delta` payload는 Core에서 UTF-16 16,384자 이하로 나눠 순서대로
전송한다. surrogate pair는 조각 경계에서 분리하지 않으므로 큰 provider chunk도 Desktop의
65,536자 SSE block/parser 한도를 반복적으로 초과하지 않는다.

## Desktop Speech

| Method | Path | Body/응답 |
|---|---|---|
| `GET` | `/api/gahyeon/desktop/speech/status` | `transcriptionReady`, `synthesisReady` |
| `POST` | `/api/gahyeon/desktop/speech/transcriptions` | `audio/wav` bytes → `{"transcript":"..."}` |
| `POST` | `/api/gahyeon/desktop/speech/segments` | `{"text":"..."}` → platform-neutral segment 배열 |
| `POST` | `/api/gahyeon/desktop/speech/synthesis` | `index`, `text`, `voiceProfile` → audio bytes |

WAV 입력 상한은 20 MiB다. STT/TTS provider가 준비되지 않으면 `503`, 비어 있거나 상한을 넘는
음성은 `413`이다. 합성 응답은 `Cache-Control: no-store`와 `X-Audio-Extension`을 포함한다.

합성 요청 예시:

```json
{
  "index": 0,
  "text": "잠시만요, 확인해 볼게요.",
  "voiceProfile": "assistant"
}
```

## Desktop World

World 변경은 optimistic revision을 사용한다. stale `expectedRevision`이면
`409 Conflict`와 `world_revision_conflict`를 반환한다.

| Method | Path | Body |
|---|---|---|
| `GET` | `/api/gahyeon/desktop/worlds/{worldId}` | 없음 |
| `POST` | `/api/gahyeon/desktop/worlds/{worldId}/move` | `expectedRevision`, `room`, `x`, `y`, `z` |
| `POST` | `/api/gahyeon/desktop/worlds/{worldId}/activity` | `expectedRevision`, `activity`, `interactionTarget` |
| `POST` | `/api/gahyeon/desktop/worlds/{worldId}/emotion` | `expectedRevision`, `emotion`, `intensity` |
| `POST` | `/api/gahyeon/desktop/worlds/{worldId}/actions/{actionId}/complete` | `installationId`, `expectedRevision`, `x`, `y`, `z` |

`activity`는 `IDLE`, `WALK`, `SIT`, `READ`, `SLEEP`, `WORK`, `LOOK_OUTSIDE`, `RELAX`,
`ATTENTION`, `CONVERSATION` 중 하나다. World coordinate는 Core에서 `X/Z` 수평면과 `Y`
elevation을 사용하는 meter semantic이다. Unreal Adapter가 이를 Unreal의 `X/Y` 수평면,
`Z` elevation 및 centimeter 단위로 변환한다.

Desktop은 durable `world.transition.target`을 받는 즉시 화면 이동을 시작한다. 목적지에
도착하면 action completion endpoint로 실제 최종 좌표를 보고하며, Core가 revision과 목표
거리까지 검증한 뒤에만 정본 World State를 commit한다. Renderer가 종료되거나 보고가
유실되면 bounded Core executor가 동일 action을 완료하므로 자율 행동은 Headless에서도
멈추지 않는다. `character.action.result` 또는 더 높은 revision의 snapshot은 Desktop의
pending presentation target을 해제한다.

## Unreal Stage

### WebSocket

```text
ws://127.0.0.1:8080/api/gahyeon/unreal/v1
```

인증은 HTTP upgrade request의 Bearer header에 적용된다. `client.hello`, delivery class,
generation, replay/ACK, snapshot, perception, cognition, speech와 World action의 정확한 JSON
계약은 [Protocol v1](unreal/PROTOCOL_V1.md)과
[JSON Schema](contracts/gahyeon-unreal-protocol-v1.schema.json)를 따른다. frame 단위 transform이나
animation asset ID를 이 연결로 보내지 않는다.

### Unreal Speech HTTP

| Method | Path | 설명 |
|---|---|---|
| `GET` | `/api/gahyeon/unreal/speech/status` | STT readiness |
| `POST` | `/api/gahyeon/unreal/speech/transcriptions` | 최대 20 MiB `audio/wav` batch STT |
| `GET` | `/api/gahyeon/unreal/speech/audio/{audioId}` | `speech.prepared`가 참조한 TTL/no-store audio |

오디오 URL은 ephemeral cache와 결합된다. 연결 복구용 durable event가 아니며 만료 후 `404`가
정상이다.

## Discord Adapter

Discord Slash Command, message와 Voice Channel은 Core API가 아니라 Discord Adapter 입력이다.
기존 `/설정`, `/가현아`, 나가기, 음악과 운영 명령은 유지하지만 Conversation/Memory/STT/TTS는
JDA 객체를 domain 식별자로 사용하지 않는다. 실제 명령 이름과 option은 실행 중 Discord command
registration과 `src/main/java/com/gahyeonbot/commands/`가 authoritative source다.

현재 호환 surface는 18개 stable command이며 시작 시 `CommandRegistry`가 전체 존재와 한국어
localization을 fail-closed로 검증한다. 핵심 예약 명령은 `/퇴장`, `/퇴장취소`, `/퇴장조회`,
`/함께퇴장`이고 음악은 `/뮤직`의 `action` option으로 추가·대기열·일시정지·재생·다음곡·초기화를
통합한다. source에는 이전 개별 music command class가 호환 구현으로 남아 있어도 Spring Bean과
Discord 등록의 정본은 통합 `/뮤직` 하나다.

## 공통 오류 원칙

| Status | 의미 |
|---|---|
| `400` | JSON/필수 필드/semantic 값이 잘못됨 |
| `401` | 설정된 Bearer token이 없거나 불일치 |
| `403` | token 없이 remote Gahyeon API 접근 |
| `404` | endpoint 또는 만료된 ephemeral audio가 없음 |
| `409` | World revision 충돌 |
| `413` | 음성 입력 크기 초과 또는 빈 음성 |
| `503` | STT/TTS/provider 또는 readiness가 준비되지 않음 |

Core API와 Unreal protocol은 schema/version 및 semantic event를 기준으로 진화한다. 플랫폼별 ID,
렌더러 asset, 특정 LLM/STT/TTS provider 응답 타입을 공통 계약에 추가하지 않는다.
