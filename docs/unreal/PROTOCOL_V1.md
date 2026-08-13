# Backend ↔ Unreal Protocol v1

클라이언트의 `sessionId`는 transport-local opaque ID다. Backend는 Core에 전달하기 전에
`unreal:` namespace를 붙여 Desktop/Headless의 같은 문자열과 격리한다. WebSocket 응답은
클라이언트 ID를 유지하며 durable replay는 새 namespace와 기존 prefix 없는 이벤트를 모두
수용한다.

## 원칙

- 초기 전송은 WebSocket, payload는 UTF-8 JSON이다.
- 연결 단위 protocol version과 메시지 단위 schema version을 모두 검증한다.
- 프레임 단위 bone/transform/amplitude를 영속 이벤트로 보내지 않는다.
- audio binary는 JSON에 base64로 넣지 않는다. 초기 PoC는 HTTP URL과 timeline을
  참조하고, 필요해질 때 WebSocket binary channel을 추가한다.
- 기존 `GahyeonEvent.sequence`는 durable server event의 resume cursor로 재사용한다.

`speech.prepared`는 실제 오디오 cache 수명과 결합된 ephemeral event다. 연결이 끊긴
동안의 오래된 음성을 replay하지 않는다. 여러 segment는 최신 상태로 덮지 않고 client의
generation-aware speech queue에 순서대로 넣는다.

## 공통 envelope

```json
{
  "protocol": "gahyeon.unreal.v1",
  "schemaVersion": 1,
  "messageId": "01J...",
  "type": "character.state.target",
  "sentAt": "2026-08-11T03:00:00Z",
  "sessionId": "desktop:local-user",
  "correlationId": "conversation:01J...",
  "delivery": "durable",
  "sequence": 1842,
  "payload": {
    "generation": 13,
    "state": "thinking",
    "priority": 60
  }
}
```

`delivery`는 `durable`, `command`, `ephemeral`이다. `durable` server message에는
`sequence`가 필수이고 재전송할 수 있다. `command`는 `messageId`를 idempotency key로
사용하는 client 요청이다. `ephemeral`은 최신 값만 의미하며 cursor에 포함하지 않는다.

Ingress 문자열은 WebSocket 전체 프레임 상한과 별도로 필드별 제한을 적용한다. Envelope의
`messageId`는 80자, `type`은 100자, `correlationId`는 120자까지다. `client.hello`의
`sessionId`와 `worldId`는 180자, `installationId`는 200자, `displayName`은 100자까지이며,
conversation `text`는 UTF-16 기준 16,384자까지다. 초과 입력은 `invalid_field`로 격리하고
Cognition queue나 session identity registry에 입장시키지 않는다.
문자열 필드는 JSON number/boolean을 문자열로 암묵 변환하지 않으며 `sentAt`은 ISO-8601
instant여야 한다. 유효하지 않거나 과도한 `correlationId`는 오류 응답에서 `protocol`로
대체해 공격자 입력을 그대로 반사하지 않는다.
고빈도 `perception.transcript.partial`은 8,192자까지이며 `generation`, `text`, 선택적
`stability`만 저장한다. `perception.user.pose`도 정의된 position/confidence만 정규화해 latest
state에 보관한다. Action completion의 `actionId`는 80자, 실패 `reason`은 512자까지다.
정의되지 않은 payload 필드는 상태 admission 전에 `invalid_field`로 거부한다.

## 연결 순서

Endpoint는 `/api/gahyeon/unreal/v1`이다. 활성화에는
`GAHYEON_HEADLESS_ENABLED=true`와 `GAHYEON_UNREAL_WEBSOCKET_ENABLED=true`가 모두
필요하다. 원격 연결은 기존 `GAHYEON_CLIENT_TOKEN` Bearer 인증을 그대로 사용한다.

```text
Unreal                              Backend
  ├─ client.hello(lastSequence) ──────►
  ◄──────── server.welcome(capabilities)
  ◄──────── world.snapshot
  ◄──────── durable events after cursor
  ├─ client.ack(sequence) ────────────►
  └─ ping/pong, normal messages
```

한 connection에서는 `client.hello`를 정확히 한 번만 허용한다. 동일 socket의 두 번째 hello는
`hello_already_completed` 오류이며 기존 구독과 실행 중 작업을 건드리지 않는다. Backend는
welcome/snapshot을 쓰기 전에 session 구독 lease를 먼저 등록한다. 따라서 Desktop Renderer가
끊기는 순간 Looking Glass Renderer의 hello 응답이 진행 중이어도 세션을 마지막 구독자 종료로
오판해 Cognition/TTS를 취소하지 않는다. Hello 응답이 실패하면 해당 lease만 원자적으로
rollback한다.

Renderer hello admission은 기본적으로 Backend 인스턴스 전체 64개, 동일 session 4개로 제한한다.
초과 hello는 `renderer_capacity`로 거부하며 connection map, perception state, outbound queue를
할당하지 않는다. 정상 종료·transport 오류·hello rollback은 slot을 반납한다. 운영 상한은
`GAHYEON_UNREAL_MAXIMUM_RENDERER_CONNECTIONS`와
`GAHYEON_UNREAL_MAXIMUM_RENDERER_CONNECTIONS_PER_SESSION`으로 조정한다.
전역 slot은 WebSocket established 시점에 먼저 예약하므로 hello를 보내지 않는 연결도 동일한
64개 상한에 포함된다. 전역 상한을 넘는 물리 socket은 close code `1013`과
`renderer_capacity` reason으로 즉시 닫는다.
`client.hello`가 기본 10초 안에 도착하지 않으면 close code `1008`과 `hello_timeout` reason으로
연결을 닫고 slot을 회수한다. 이 deadline은
`GAHYEON_UNREAL_RENDERER_HELLO_TIMEOUT_SECONDS`로 1~60초 범위에서 조정할 수 있다.
Hello 이후에는 client가 welcome의 10초 주기에 맞춰 ping 또는 정상 protocol message를 보내야
한다. 기본 30초 동안 유효한 client activity가 없으면 `1008 / heartbeat_timeout`으로 해당
renderer만 닫고 lease를 회수한다. 다른 renderer가 같은 session에 남아 있으면 공유
Cognition/TTS/Perception은 유지한다. timeout은
`GAHYEON_UNREAL_RENDERER_HEARTBEAT_TIMEOUT_SECONDS`로 15~120초 범위에서 조정한다.
`server.welcome`의 `heartbeatIntervalMs`는 1,000~60,000 범위의 정수이며 필수다. Stage는
범위 밖·누락·소수 값이면 해당 연결을 `invalid heartbeat contract`로 닫고 reconnect한다.
Stage는 ping correlation과 monotonic 전송 시각을 한 개만 보유한다. 일치하는
`server.pong`은 RuntimeCore mailbox로 전달하지 않고 Transport에서 소비해 RTT를 갱신한다.
이전 correlation의 지연 pong은 현재 heartbeat를 완료시키지 않으며, 다음 heartbeat 주기까지
현재 pong이 도착하지 않으면 half-open 연결로 판단해 새 hello/cursor로 reconnect한다. Pong의
delivery 또는 timestamp payload가 계약과 다르면 `invalid heartbeat pong`으로 연결을 닫는다.

여러 renderer는 같은 `sessionId`를 공유할 수 있지만 각 WebSocket은 독립적인 durable cursor와
ACK 범위를 가진다. 한 renderer의 ACK나 연결 종료가 다른 renderer의 replay cursor를 대신
전진시키지 않는다. 공유 세션의 Cognition/TTS/admission 상태는 마지막 renderer가 떠난 뒤에만
해제된다.

같은 세션을 공유하는 renderer는 `worldId`와 `installationId`가 일치해야 한다. 이는 Desktop이
소유한 동일 설치 identity 아래에서 Monitor/Looking Glass 표시 경로만 늘어난다는 뜻이다.
이미 활성인 세션과 다른 identity를 주장하는 hello는 `incompatible_session_identity`로
구독 전에 거부하므로 transient speech나 sibling STT 권한을 받을 수 없다.

Ephemeral outbound 전송이 실패하면 broker는 실패한 renderer 구독을 먼저 원자적으로 제거하고
그 connection lease를 즉시 정리한다. Container의 WebSocket close callback을 기다리지 않는다.
같은 세션의 다른 renderer에는 해당 event 전달을 계속하며, 실패한 renderer가 마지막
구독자였을 때만 공유 Cognition/TTS/admission을 해제한다. 뒤늦은 close callback은 idempotent한
no-op이다.
전송 실패가 TTS/Cognition worker 내부 publish에서 동기적으로 감지될 수 있으므로 session
release는 현재 실행 중인 자기 Future를 interrupt하지 않는다. 작업은 cancelled admission으로
표시하되 cleanup callback과 건강한 renderer 전달 루프를 끝낸 뒤 반환한다. 다른 thread에서
실행 중인 stale task와 아직 queue에 있는 task는 기존대로 interrupt/remove한다.

각 renderer의 ephemeral outbound와 durable replay/cursor는 같은 독립적인 bounded serial
queue를 사용한다. 따라서
Looking Glass의 socket write가 느리거나 멈춰도 Desktop 전달과 Cognition/TTS worker는 그
write를 기다리지 않는다. renderer 내부 event 순서는 유지하며, renderer별 queue 포화 또는
outbound executor 거부가 발생하면 해당 renderer만 구독 해제·연결 정리한다. 같은 session의
건강한 renderer는 계속 전달받고, 실패한 renderer가 마지막 구독자일 때만 공유 작업을 해제한다.
기본 queue 한도는 renderer당 64개이고 운영 환경에서
`GAHYEON_UNREAL_OUTBOUND_PER_RENDERER_QUEUE_CAPACITY`로 조정한다.

확정 대화 command에는 `generation`을 포함한다. Backend는 이를 durable event의
correlation ID와 payload에 보존한다. 따라서 재접속/replay 후에도 Unreal은 현재보다
낮은 generation의 LLM/TTS 결과를 폐기할 수 있다.

Backend admission도 session별 최신 generation보다 오래된 queued command를 실행하지
않는다. 같은 `messageId` 재전송은 `duplicate=true`로 승인 응답만 반복하고 Cognition을
다시 호출하지 않는다.

Unreal은 durable replay를 단순히 intent mailbox에 넣어서는 안 된다. 알려진
authoritative type의 `generation`이 로컬보다 높으면 먼저 local intent watermark와
speech queue를 함께 전진시키고, 기존 active audio를 중단한 다음 message를 적용한다.
로컬보다 낮은 generation은 폐기한다. 알 수 없는 type은 cursor에는 반영하지만 그
payload의 generation만으로 cancellation watermark를 변경하지 않는다.

Client cursor는 WebSocket 수명과 분리한다. durable message의 격리된 처리가 끝나면
알려진/알 수 없는 type 모두 sequence를 완료하고, `stream.cursor.scannedThrough`는
scope 밖 event까지 처리된 것으로 간주해 안전 cursor를 전진시킨다. 이 값을 내구성
있게 저장한 뒤 `client.ack`를 보내며, 재접속 `lastSequence`에는 마지막 전송 ack가
아니라 마지막으로 저장된 안전 cursor를 사용한다. 중복 sequence는 다시 적용하지 않고
감소하는 cursor나 안전 cursor를 넘는 ack는 protocol 오류로 취급한다.

## Unreal → Backend

| type | delivery | 목적 |
|---|---|---|
| `client.hello` | ephemeral | handshake와 resume |
| `client.ack` | ephemeral | 마지막 적용 durable sequence |
| `client.ping` | ephemeral | 연결 생존과 왕복 지연 측정 |
| `interaction.text.submitted` | command | 텍스트 대화 입력 |
| `interaction.generation.advanced` | ephemeral | timeout/reset/STT 실패/마이크 중단으로 새 generation 확정 및 이전 Cognition·TTS 취소 |
| `perception.voice.started` | ephemeral | 즉시 Listening 반응 공유 |
| `perception.voice.ended` | ephemeral | Thinking 전환 힌트 |
| `perception.transcript.partial` | ephemeral | 부분 transcript와 안정도 |
| `perception.transcript.final` | command | 확정 발화 |
| `perception.user.pose` | ephemeral | 사용자/얼굴 방향 관측 |
| `character.action.completed` | command | 이동·gesture 실행 결과 |

`client.ping` payload는 반드시 빈 객체다. Heartbeat는 command queue나 durable cursor를
사용하지 않으며 Backend는 다른 delivery 또는 임의 payload field를 protocol error로 거부한다.

마이크 PCM은 PoC에서 `POST /gahyeon/unreal/speech/transcriptions`에 `audio/wav`로 보내고
WebSocket에는 VAD와 transcript event만 보낸다. 이 endpoint도 Desktop controller를
호출하지 않고 동일한 Core `TranscriptionUseCase` port를 사용한다. 장기적으로 streaming
STT ingress를 추가해도 이 semantic 계약은 유지한다.

`perception.user.pose.position`의 `x/y/z`는 World 좌표 mirror다. Unreal은 전송 전에
같은 관측을 character-local forward/right/up으로 변환해 로컬 `AttentionRuntime`에
즉시 적용한다. 따라서 WebSocket 지연이나 단절이 Eye/Head LookAt을 멈추지 않는다.

프로토콜의 World 위치는 **미터 단위 semantic 좌표**이며 `X/Z`가 수평면, `Y`가
elevation이다. Unreal은 `X/Y`가 수평면이고 `Z`가 elevation이므로 Stage adapter는
`Core(x,y,z) → Unreal(x×100,z×100,y×100)`으로 변환한다. 완료 위치는 역변환해
`Unreal(X,Y,Z) → Core(X÷100,Z÷100,Y÷100)`으로 보낸다. 이 축·단위 변환은
`FGahyeonWorldCoordinateAdapter` 한곳에서만 수행하며 Core나 Blueprint가 반복하지 않는다.

`perception.voice.started/ended`, `perception.transcript.partial`,
`perception.user.pose`는 event store에 쓰지 않는다. Backend는 session/type별 최신값만
메모리에 10초간 유지한다. session 전체 generation watermark가 올라가면 다른 type에
남아 있던 낮은 generation 관측도 즉시 숨긴다.

음성 generation의 정상 순서는 `voice.started → partial* → voice.ended? → final`이다.
partial은 voice가 active일 때만 받고, final은 generation마다 한 번만 Cognition에
제출한다. 동일 final 재전송과 stale generation은 `perception.ignored`로 응답하며,
voice start 없이 온 partial/final은 `invalid_perception_lifecycle`로 거부한다. 새 text
command generation도 이전 voice lifecycle을 무효화한다.

## Backend → Unreal

| type | delivery | 목적 |
|---|---|---|
| `server.welcome` | ephemeral | 협상 결과 |
| `server.pong` | ephemeral | client timestamp를 반사하는 heartbeat 응답 |
| `stream.cursor` | ephemeral | scope 밖 이벤트를 포함해 안전하게 ack할 scan cursor |
| `world.snapshot` | ephemeral/durable | hello 직후 또는 revision 변경 시 World 정본 전체 |
| `cognition.request.started` | durable | 비동기 Cognition 요청 시작 |
| `cognition.request.cancelled` | durable | 새 generation에 의해 대체된 요청의 종료 기록 |
| `cognition.response.completed` | durable | 응답 텍스트·도구·처리시간 |
| `cognition.response.failed` | durable | 실패 정보와 로컬 복귀 힌트 |
| `character.state.target` | durable | Listening/Thinking/Speaking 등 목표 |
| `emotion.target` | durable | 다차원 감정 목표와 blend 시간 |
| `attention.target` | ephemeral/durable | 시선 대상의 의미/위치 |
| `gesture.intent` | ephemeral | asset ID가 아닌 의미·강도·제약 |
| `speech.prepared` | ephemeral | TTL audio URL과 segment/viseme timeline |
| `speech.sequence.ended` | ephemeral | 전체 streaming 응답의 utterance 생산 종료와 결과 |
| `speech.started/stopped` | ephemeral | 실제 local playback 상태와 interruption |
| `world.transition.target` | durable | room/activity/interaction 목표 |
| `character.action.result` | durable | Core/Renderer 경쟁의 authoritative action 종결 |
| `character.action.acknowledged` | ephemeral | completion terminal/accepted/duplicate 결과 |
| `protocol.error` | ephemeral | 해당 메시지 오류 |
| `perception.ignored` | ephemeral | duplicate/stale perception을 적용하지 않았다는 확인 |
| `generation.advanced` | ephemeral | Backend cancellation watermark 반영 확인 |

`character.state.target`는 필수 `generation`과 `state`, 선택적인 `priority`와
`expiresAfterMs`만 받는다. `state`는 `idle`, `listening`, `thinking`, `speaking`,
`reacting`, `executing_action` 중 하나이며 UE adapter가 이를 `conversation.phase`
intent로 변환한다. `speaking`의 실제 시작 ownership은 이 목표가 아니라 audio device의
playback-start callback에 있다.

Cognition executor 포화는 연결 오류가 아니다. Backend는 해당 요청에만
`protocol.error{code=cognition_queue_full}`를 응답하고 WebSocket과 renderer lease는 유지한다.
Voice final admission은 rollback되어 같은 generation을 안전하게 재시도할 수 있으며, dispatcher
metric에는 `commands{result=backpressure}`로 별도 기록된다.

`interaction.generation.advanced.payload.reason`은
`cognition_timeout`, `client_reset`, `stt_failed`, `microphone_capture_aborted` 중 하나다.
Backend는 reason과 무관하게 generation watermark를 Cognition과 TTS admission에 원자적으로
적용하고, 같은 generation의 늦은 결과를 publish하지 않는다.

`emotion.target`의 `dimensions`는 1~16개 semantic과 0..1 weight를 보존한다. 선택적인
valence/arousal/dominance는 -1..1이며, `blendSeconds`는 0..5,
`holdSeconds`는 0..600이다. Unreal JSON adapter는 이를 monotonic millisecond로
정규화해 `EmotionRuntime`에 전달하며 대화 phase와 별도 facial layer로 합성한다.

`gesture.intent`는 `semantic`, 0..1 `intensity`, 선택적인 generation/priority/expiry,
hand/duration hint만 전달한다. 현재 posture는 wire가 결정하지 않고 Unreal local state가
normalized message에 붙인다. `GestureRuntime`이 DataAsset 정의의 조건과 cooldown을
검사해 variant를 결정하므로 Backend는 animation montage/sequence ID를 보내지 않는다.

World 이동은 `world.transition.target` 생성만으로 정본 상태를 선반영하지 않는다.
Core는 source/target 거리로 계산한 execution due를 영속화하고 Headless에서도 자체
Behavior executor가 action을 완료할 수 있다. Unreal은 같은 `actionId`를 idempotent하게
로컬 navigation/interaction으로 표현하며 실제 도착이 더 빠르면
`character.action.completed` command로 조기 완료를 알린다. Core executor와 Renderer
완료는 같은 DB claim을 경쟁하므로 하나만 World State를 commit한다. Renderer는 이후
durable event/`world.snapshot`으로 정본에 수렴한다. 따라서 Desktop이 꺼져 있어도
Gahyeon World는 살아 있고, 중복 replay·늦은 완료도 위치를 두 번 변경하지 않는다.
Backend는 action ID와 활성 world slot을 영속 ledger에 저장한다. 완료 처리는 DB에서
`PENDING → PROCESSING`을 원자적으로 claim한 한 인스턴스만 수행하며 World transition,
terminal ledger 상태, 결과 event는 같은 transaction에 참여한다. 재시작 후에도 기존
pending target을 재사용하고 terminal action 재전송은 duplicate로 응답한다.

Unreal은 `character.action.completed`를 송신 직전에 제거하지 않는다. bounded local
outbox에 넣고 `character.action.acknowledged`를 받을 때만 제거하며, 단절 중에는
250ms부터 최대 5초까지 backoff해 같은 action ID를 재전송한다. outbox snapshot은 절대
monotonic timestamp 대신 남은 retry delay를 저장하므로 SaveGame 복원 뒤에도 안전하다.
`terminal=false` ACK는 command를 유지한다. terminal rejection(`stale`, `conflict`)은
무한 재시도하지 않고 bounded dead-letter에 원본 completion과 함께 격리해 overlay/log 및
수동 복구에서 확인할 수 있게 한다.

## 예시

```json
{
  "protocol": "gahyeon.unreal.v1",
  "schemaVersion": 1,
  "messageId": "01J5EMOTION",
  "type": "emotion.target",
  "sentAt": "2026-08-11T03:00:00Z",
  "correlationId": "conversation:01J5",
  "delivery": "durable",
  "sequence": 1842,
  "payload": {
    "dimensions": {"curiosity": 0.7, "amusement": 0.2},
    "valence": 0.35,
    "arousal": 0.42,
    "dominance": 0.1,
    "blendSeconds": 0.35,
    "holdSeconds": 2.0
  }
}
```

```json
{
  "protocol": "gahyeon.unreal.v1",
  "schemaVersion": 1,
  "messageId": "01J5SPEECH",
  "type": "speech.prepared",
  "sentAt": "2026-08-11T03:00:01Z",
  "sessionId": "desktop-local-user",
  "correlationId": "unreal:g12:client-message-1",
  "delivery": "ephemeral",
  "payload": {
    "generation": 12,
    "utteranceId": "550e8400-e29b-41d4-a716-446655440000",
    "utteranceIndex": 0,
    "segmentIndex": 0,
    "segmentCount": 2,
    "finalSegment": false,
    "audio": {
      "url": "/api/gahyeon/unreal/speech/audio/550e8400-e29b-41d4-a716-446655440000",
      "mimeType": "audio/wav"
    },
    "visemes": []
  }
}
```

각 `finalSegment`는 전체 답변이 아니라 해당 `utteranceIndex` 안에서 마지막 TTS
segment라는 뜻이다. 전체 streaming 응답이 더 이상 utterance를 만들지 않는 시점에는
Backend가 같은 generation/correlation로 다음 ephemeral event를 보낸다.

```json
{
  "type": "speech.sequence.ended",
  "payload": {
    "generation": 12,
    "utteranceCount": 2,
    "outcome": "completed"
  }
}
```

Renderer는 이 event가 오기 전에는 일시적으로 audio queue가 비었더라도 전체 발화가
끝났다고 판단하지 않는다. `outcome`은 `completed` 또는 `failed`이며, barge-in은 새
generation 자체가 이전 queue와 sequence marker를 폐기한다.
Backend TTS 중 하나가 실패하면 별도 비표준 failure event를 보내지 않고, 실패 전에 실제로
준비된 연속 utterance 수와 `outcome=failed`를 담은 sequence marker 하나로 종료한다. 따라서
Renderer는 준비된 앞부분만 재생한 뒤 Idle로 복귀하며 watchdog까지 Thinking에 머물지 않는다.

정확한 phoneme/viseme provider가 있으면 해당 timeline을 사용한다. 없을 때 Backend는 PCM
WAV duration과 한국어 모음군을 사용한 명시적인 heuristic timeline을 만들며, WAV 해석이
불가능하면 `visemes`를 빈 배열로 두고 audio amplitude 기반 mouth motion으로 fallback한다.
heuristic은 정확한 forced alignment 합격 증거로 취급하지 않는다. 향후 Piper/aligner가 실제
timing을 제공하는 `UnrealVisemeTimelinePort` bean을 등록하면 fallback bean은 자동 비활성화된다.
현재 `land`에 설치된 Piper CLI의 출력 옵션은 WAV/raw audio뿐이며 phoneme duration 또는
alignment timeline을 제공하지 않는 것을 확인했다. 따라서 최종 Piper 배포에서는 별도의
forced aligner가 이 port를 구현해야 하며, 단순 phoneme 순서를 균등 배분한 값은 `provider`
표본으로 승격하지 않는다.

Backend의 exact aligner HTTP adapter는 다음 환경변수로 활성화한다.

```env
GAHYEON_UNREAL_VISEME_ALIGNER_ENABLED=true
GAHYEON_UNREAL_VISEME_ALIGNER_ENDPOINT=http://aligner:18768/align
GAHYEON_UNREAL_VISEME_ALIGNER_TIMEOUT_MILLIS=1500
GAHYEON_UNREAL_VISEME_ALIGNER_PLAYBACK_DEADLINE_MILLIS=250
GAHYEON_UNREAL_VISEME_ALIGNER_THREADS=2
GAHYEON_UNREAL_VISEME_ALIGNER_QUEUE_CAPACITY=8
GAHYEON_UNREAL_VISEME_ALIGNER_MAX_AUDIO_BYTES=2000000
GAHYEON_UNREAL_VISEME_ALIGNER_MAX_RESPONSE_BYTES=131072
```

요청은 `text`, base64 PCM WAV, `mediaType`, `audioSha256`을 포함하고 응답은 같은
`audioSha256`과 `cues`를 돌려준다. digest가 다르거나 timeout/HTTP/형식 오류가 발생하면
다른 발화의 timeline을 적용하지 않고 기존 fallback으로 내려간다. API key가 설정되면
Bearer 인증을 사용한다. 이 endpoint의 cue도 실제 WAV duration 경계를 다시 통과해야 한다.
Timeout은 100~5,000ms, 요청 audio는 최대 32MiB, 응답은 1KiB~1MiB 범위 안에서만 설정할
수 있다. 기본 응답 상한은 128KiB이며 Content-Length와 streaming body 양쪽에 적용해
오작동한 aligner가 TTS worker 메모리나 지연 예산을 무제한 점유하지 못하게 한다.
HTTP aligner는 TTS executor와 분리된 bounded pool에서 실행한다. 음성 게시 경로는 기본
250ms playback deadline까지만 exact timeline을 기다리며, 초과·queue 포화·실패 시 해당
segment를 amplitude fallback으로 즉시 게시한다. 취소된 HTTP 호출이 read timeout까지 남더라도
aligner 전용 thread/queue 상한 안에 격리되어 후속 합성이나 Cognition executor를 점유하지 않는다.
정렬과 cache 삽입이 끝난 뒤의 `speech.prepared` broker admission은 speech session의 내부
generation과 dispatcher가 소유한 external generation token을 같은 session monitor 안에서
재검증한 뒤, bounded renderer queue 삽입까지 선형화한다. 따라서 마지막 health check 직후
generation이 바뀌더라도 이전 generation audio가 새로 outbound queue에 들어갈 수 없으며,
게시되지 않은 cache entry는 즉시 회수한다. 이 임계 구역에는 queue capacity 확인과 단일
enqueue만 포함된다. executor scheduling, 실제 socket write, renderer callback과 장치 playback은
monitor 밖에서 계속 비동기로 실행되고 renderer도 동일 generation fence를 다시 적용한다.

비어 있지 않은 `visemes` 항목 형식은 다음과 같다.

```json
{"semantic":"aa","atMs":0,"durationMs":90,"weight":1.0}
```

`atMs`와 `durationMs`는 해당 audio segment 시작 기준이며 cue는 `atMs` 오름차순이다.
최대 256개, weight는 `(0, 1]`이다. Backend의 `UnrealVisemeTimelinePort`가 provider별
phoneme/alignment 결과를 이 공통 semantic으로 변환한다. 현재 Piper처럼 timing을 주지
않거나 alignment가 실패하면 음성 자체를 실패시키지 않고 빈 배열을 보내 amplitude
fallback을 사용한다. Backend는 provider cue의 끝(`atMs + durationMs`)이 실제 PCM WAV
duration을 넘으면 timeline 전체를 폐기해 잘못된 입 모양이 다음 segment까지 누출되지 않게 한다.

Unreal은 wall clock이나 message `sentAt`이 아니라 audio component의 실제 playback
position으로 timeline을 샘플링한다. 최대 두 cue를 함께 출력해 coarticulation blend가
가능하다. 실제 audio start callback 전에는 lip-sync를 시작하지 않고, completion 또는
더 높은 generation에서는 즉시 mouth ownership을 해제한다.

`world.snapshot`은 event replay가 아니라 handshake 시점의 현재 정본이므로 sequence가
없는 ephemeral message다. Backend는 `server.welcome` 직후, durable replay 전에 이를
보낸다. Client는 payload 전체를 검증한 뒤 revision 단위로 원자 교체한다. 이후 replay된
World event의 revision이 snapshot 이하이면 폐기하므로 과거 event가 현재 방·위치·활동을
되돌릴 수 없다. 같은 revision인데 내용이 다르면 conflict로 기록하고 기존 snapshot을
유지한다.

스키마 초안은
[`../contracts/gahyeon-unreal-protocol-v1.schema.json`](../contracts/gahyeon-unreal-protocol-v1.schema.json)에 있다.
Unreal parser와 Backend가 함께 검증할 canonical JSON은
[`../contracts/fixtures/`](../contracts/fixtures/)에 둔다.

## 기존 Core event 호환 mapping

| Core event | Unreal v1 type |
|---|---|
| `conversation.started` | `cognition.request.started` |
| `conversation.completed` | `cognition.response.completed` |
| `conversation.failed` | `cognition.response.failed` |
| `avatar.expression` | `emotion.target` |
| `character.moved` | `world.position.changed` |
| `behavior.activity.changed` | `world.activity.changed` |
| `world.state.restored` | `world.snapshot` |

알 수 없는 semantic event는 type과 payload를 보존해 전달한다. Unreal parser는 알 수
없는 type을 무시하되 cursor는 적용해야 한다.

UE adapter는 canonical durable type의 payload를 envelope와 별도로 strict decode한다.
현재 `world.snapshot`, `emotion.target`, `world.transition.target`,
`character.action.result`가 대상이다. typed RuntimeCore 적용 결과가 Applied, Ignored 또는
Stale일 때만 SaveGame cursor를 전진시키고 저장 완료 뒤 ACK한다. Invalid는 ACK 없이
연결을 닫으며 Backpressured event는 Game Thread 보존 슬롯에서 재시도한다.

## 관측 지표

- `gahyeon.unreal.websocket.connections`
- `gahyeon.unreal.websocket.messages{direction,type}`
- `gahyeon.unreal.websocket.message.processing{type}`
- `gahyeon.unreal.websocket.protocol.errors{code}`
- `gahyeon.unreal.websocket.replay.messages`
- `gahyeon.unreal.cognition.commands{result}`
- `gahyeon.unreal.perception.events{type}`
- `gahyeon.unreal.tts.first.segment`
- `gahyeon.unreal.tts.segment`
- `gahyeon.unreal.tts.failures{code}`

Metric tag는 고정 allowlist로 제한한다. 네트워크 왕복 지연은 서버와 클라이언트의 시계
차이를 포함하는 `sentAt`으로 계산하지 않고 `client.ping/server.pong` 왕복을 Unreal이
monotonic clock으로 측정한다.
