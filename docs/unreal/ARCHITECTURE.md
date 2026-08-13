# Gahyeon Unreal Stage 아키텍처

> 최우선 목표는 그래픽 데모가 아니라 저지연 실시간 AI 캐릭터 런타임이다.
> 캐릭터는 Cognition 응답을 기다리며 멈추지 않고 Reflex, Behavior, Cognition을
> 독립적으로 동시에 실행해야 한다.

## 결정 요약

Unreal Stage는 Gahyeon Core를 대체하지 않는 Presentation Client다. Conversation,
Memory, STT, TTS, Session, Emotion의 장기 상태와 World State의 정본은 Backend에
남는다. Unreal은 캐릭터 표현, 로컬 반사 행동, 애니메이션 합성, 렌더링과 입력 수집을
담당한다.

MetaHuman과 hero asset은 이 아키텍처를 표현하는 교체 가능한 자산이다. 고품질
캐릭터를 먼저 조립한 뒤 행동을 붙이는 순서로 개발하지 않는다. 테스트 humanoid로
동시성, interruption, timeout, reconnect, latency budget을 먼저 통과시킨다.

```text
Discord Adapter ─┐
Desktop Client ──┼── Gahyeon Core/Application ── durable event store
Unreal Stage ────┘             │
        ▲                      │
        └──── WebSocket JSON v1┘

Unreal Stage
├─ Network: 명령·이벤트·snapshot·재연결
├─ Character Core: 표현용 상태 모델
├─ Behavior: Reflex와 상태/제스처 선택
├─ Animation: body/face/look-at/lip-sync 합성
├─ Presentation: MetaHuman, camera, lighting, UI
└─ Debug: 상태 overlay, event log, latency
```

기존 HTTP/SSE Desktop 경로는 그대로 유지한다. Unreal용 WebSocket Adapter는 기존
Application use case와 `GahyeonEvent`를 감싸며 Core 도메인에 Unreal 타입을 넣지
않는다.

## 책임 경계

| 영역 | Backend/Core | Unreal Stage |
|---|---|---|
| 대화·기억 | 정본, 저장, 검색, LLM 호출 | 텍스트 입력과 결과 표현 |
| 음성 | STT/TTS provider 선택과 실행 | 마이크 캡처, 재생, viseme 적용 |
| 감정·의도 | 의미와 장기 상태 결정 | 표정·자세·동작으로 혼합 |
| 행동 | 고수준 activity/intent와 Headless 실행 due 결정 | 후보 애니메이션 선택과 자연스러운 전환 |
| World | 영속 위치·방·활동 및 action ledger 정본 | nav/IK/충돌, 프레임 이동과 조기 완료 관측 |
| Reflex | 관측 정책과 제한 | 16~100ms 로컬 반응, offline에서도 동작 |

`ISTTProvider`, `ITTSProvider`, `ILLMProvider`는 Backend port다. Unreal에 필요한
핵심 인터페이스는 `ICharacterTransport`, `ICharacterBehaviorController`,
`IFacialController`, `ILipSyncController`, `IAttentionController`다.

## 독립 실행 모델

세 계층은 하나의 직렬 상태머신이 아니라 서로 다른 시간축에서 동작한다.

```text
Game Thread / render cadence
├─ Reflex evaluator       16~100ms     local observation → overlay
├─ Behavior scheduler     100~500ms    phase/intent → pose/gesture blend
└─ Presentation update    every frame  interpolation/IK/face/secondary motion

Backend async tasks
└─ Cognition              500ms~수초   transcript/memory/LLM → semantic target
```

- Network callback이나 LLM future를 Game Thread에서 기다리지 않는다.
- 각 계층은 timestamp, priority, expiry를 가진 immutable intent를 mailbox에 게시한다.
- Presentation은 매 frame 현재 유효한 intent를 합성하고 마지막 안정 상태를 유지한다.
- 새 Reflex가 Cognition을 취소하지 않으며, 짧은 overlay로 표현을 선점할 수 있다.
- 같은 표현 channel에서는 `Reflex > Behavior > Cognition` 계층 순서를 먼저 적용하고,
  priority/timestamp/ID는 같은 계층 안에서만 승자를 정한다. 따라서 Backend가 부여한 큰
  priority가 로컬 Reflex를 가릴 수 없고, Reflex TTL이 끝나면 대기 중이던 Behavior 또는
  Cognition 표현이 다시 드러난다.
- Cognition 결과가 늦게 도착하면 correlation과 generation을 검사해 오래된 결과를
  버린다.
- generation advance는 실행 중 Cognition Future를 interrupt 취소하고, 아직 Spring
  executor queue에 있는 FutureTask는 즉시 제거해 replacement 요청이 stale 작업 뒤에서
  거부되지 않게 한다.
- `Speaking` 중 사용자 발화가 시작되면 로컬 Listening 반응을 먼저 표현하고,
  Backend에는 interruption을 비동기로 통지한다.
- Backend가 없어도 ambient Behavior와 Reflex는 무기한 실행 가능해야 한다.

Desktop과 Looking Glass는 동일 세션/World를 구독하는 독립 renderer다. 각 renderer의 transport
cursor와 연결 수명은 분리하되 Cognition/TTS는 session 단위로 공유한다. 새 renderer는 welcome을
받기 전에 session lease를 획득하고, 마지막 lease가 사라진 경우에만 Backend가 session 작업을
취소한다. 따라서 renderer handoff 경합이 캐릭터의 사고나 발화를 중간에 끊지 않는다.
공유 renderer는 같은 `worldId`와 `installationId`를 사용해야 하며, 충돌 identity는 outbound
구독 전에 거부한다. Looking Glass는 별도 Gahyeon 인스턴스나 별도 사용자 세션이 아니라 같은
Desktop 설치가 소유한 추가 표시 surface다.

Looking Glass의 구체적인 채택 경계는
[`LOOKING_GLASS_INTEGRATION.md`](LOOKING_GLASS_INTEGRATION.md)와
[`ADR-0010`](../adr/0010-looking-glass-is-an-optional-renderer.md)을 따른다. 공식 UE 5.6
Plugin은 실시간 렌더링 경로를 구현하지만 upstream이 실시간 제품 용도로 권장하지 않으므로
기본 plugin으로 활성화하지 않는다. 실기기에서 latency/frame pacing gate를 통과하기 전까지는
선택형 prototype 출력일 뿐이다.

TTS executor는 서로 다른 session을 병렬 처리하지만 같은 session의 utterance와
`speech.sequence.ended`는 제출 순서대로 직렬 실행한다. session별 대기는 최대 64개로
제한하고 sequence terminal용 슬롯을 별도로 보존한다. LLM response도 최대 64개 utterance로
제한하며 문장부호·공백이 없는 텍스트는 설정된 문장 길이의 2배에서 강제 분할한다. 초과 시
해당 sequence를 실패 처리한다. 따라서 thread 수를
늘려도 한 캐릭터의 `utteranceIndex`가 역전되지 않고, 다른 session의 느린 합성이 전체를
막지 않는다.

반대로 Renderer가 없어도 Core World/Behavior는 진행되어야 한다. Core는 action target의
source/target 거리로 execution due를 계산해 영속화하고 Headless executor가 완료한다.
해당 World에 Unreal renderer가 연결된 동안에는 renderer가 실행 ownership을 가지므로
Headless executor가 예상 도착 시간만으로 먼저 완료하지 않는다. renderer가 끊기면 다음
execution tick에서 Headless가 같은 영속 action을 이어받는다. 사용자 대화가 시작되면 진행 중인
자율 action을 먼저 `cancelled`로 종결하고 Conversation이 World activity를 선점한다. 모든 경로는
같은 action ID claim을 사용하므로 World transition은 정확히 한 번만 일어난다. 따라서 Unreal은
고품질 표현과 물리 관측을 제공하지만 Gahyeon의 존재 여부를 결정하는 authority가 아니다.

마이크 RMS frame은 로컬 `VoiceActivityDetector`로 먼저 들어간다. hysteresis와
attack/release window로 짧은 잡음을 거르고, voice start가 확정되면 Backend 왕복 전에
새 generation·Listening·현재 audio stop을 같은 Game Thread 단계에서 수행한다. STT와
`perception.voice.started` 전송은 그 뒤의 비동기 작업이다.

항상 존재하는 secondary motion은 `AmbientMotionRuntime`의 정규화 신호로 생성한다.
호흡, blink, saccade, micro-head, weight shift는 seed와 monotonic time만 사용하므로
Backend 연결과 무관하며 테스트에서 재현 가능하다. Anim/Control Rig는 이 값을 낮은
가중치 base layer로 소비하고 LookAt, gesture, emotion, lip-sync가 필요한 채널만 덮는다.

사용자 위치는 먼저 character-local `forward/right/up`으로 변환해 `AttentionRuntime`에
직접 넣는다. 눈은 약 60ms, 머리는 약 180ms response로 따라가며 confidence와 freshness가
낮아지면 ambient saccade로 fade한다. Backend의 `perception.user.pose`는 기억/고수준
행동용 mirror일 뿐 로컬 LookAt의 선행 조건이 아니다.

첫 구현에서 필요한 런타임 primitive는 `IntentMailbox`, `LayerArbiter`,
`MonotonicCharacterClock`, `CancellationGeneration`, `LatencyTrace`다. 이들은 캐릭터
그래픽 자산과 독립적으로 자동 테스트한다.

`LatencyTrace`는 metric별 고정 크기 ring과 bounded pending span만 유지한다. VAD 감지
함수 반환 시점을 Listening 완료로 간주하지 않고 Anim/Presentation bridge가 실제 pose를
적용한 callback에서 span을 닫는다. audio stop도 stop 요청이 아니라 audio device 확인
callback에서 닫는다. reconnect는 snapshot 원자 적용, viseme은 audio playback cursor에서
기록한다. Debug overlay는 retained p50/p95/p99/worst와 전체 budget violation 수를 읽는다.

`IntentMailbox`, generation arbiter와 기본 coordinator의 엔진 독립 C++20 구현은
`unreal/GahyeonStage/Source/GahyeonRuntimeCore`의 UBT module이 정본이며
[`../../unreal/RuntimeCore/`](../../unreal/RuntimeCore/) CMake harness가 같은 소스를
검증한다. Network/STT/Cognition
thread는 bounded MPSC mailbox에만 쓰고, Game Thread가 drain하여 arbiter의 유일한
소유자가 된다. 포화 시 Reflex는 낮은 계층을 선점할 수 있지만 Cognition은 Reflex를
밀어낼 수 없다.

오디오 표현도 event 수신과 실제 재생을 분리한다. `SpeechPlaybackCoordinator`가
prepared queue, sequence end, audio-device callback을 조정하며 `PlaybackStarted`가
들어온 순간에만 Speaking intent를 게시한다. barge-in generation 변경 시 반환되는
active utterance ID를 Unreal audio component가 즉시 정지한다.

같은 callback에서 `LipSyncRuntime`도 시작한다. timeline이 있으면 audio device의 실제
segment playback position을 사용해 최대 두 semantic viseme을 병렬 blend하고, timeline이
비어 있으면 RMS noise gate와 attack/release smoothing으로 jaw fallback을 만든다. 따라서
Piper가 alignment를 아직 제공하지 않아도 입은 움직이며, 향후 timing provider를 붙여도
Animation/Control Rig 계약은 바뀌지 않는다. 완료/barge-in은 speech와 mouth ownership을
함께 해제하고 동일하거나 낮은 generation 재설정은 재생을 끊지 않는다.
viseme timeline을 sample한 것만으로 지연 합격을 기록하지 않는다. Presentation이 profile에
묶인 morph target을 실제 설정했거나 Control Rig/Anim Blueprint가 `ConfirmVisemeApplied`를
호출해야만 audio→mouth onset 표본이 생긴다. 이 확인은 generation과 runtime epoch를 함께
검사하므로 이전 발화의 늦은 얼굴 callback은 새 발화의 측정값이 될 수 없다.

표정은 `EmotionRuntime`이 dimensions와 valence/arousal/dominance를 그대로 보존해
blend/hold/release하며 Speaking·Thinking phase와 독립적으로 계속 평가한다. 제스처는
Backend가 semantic/intensity만 보내고 `GestureRuntime`이 local GestureDefinition에서
posture, intensity, cooldown, interruptible 조건을 검사해 seeded variant를 선택한다.
따라서 LLM이나 wire payload에는 animation asset ID가 등장하지 않는다.

재접속 replay의 적용은 `ProtocolEventRuntime` 한 경계에서 수행한다. Backend의 알려진
authoritative event generation이 로컬보다 앞서면 `IntentRuntime` watermark와
`SpeechPlaybackCoordinator` queue를 같은 Game Thread 단계에서 전진시킨 뒤 event를
적용한다. 이때 반환되는 이전 utterance ID는 audio component가 즉시 중단한다. 더 낮은
generation은 state/audio 모두 적용하지 않으며, 알 수 없는 확장 event가 임의로
generation을 전진시키는 것도 허용하지 않는다.

`ReplayCursorRuntime`은 WebSocket 객체와 분리된 client resume 상태기다. durable event는
해당 event의 격리된 처리(적용·무시·해당 event만의 오류)가 끝난 뒤 sequence를 완료한다.
알 수 없는 event도 완료하여 재접속 때 무한 replay되지 않게 한다. `stream.cursor`는
session/world scope 밖 event를 건너뛸 수 있으며, 안전한 cursor를 로컬에 먼저 저장한
뒤 `client.ack`를 전송한다. 연결 객체가 사라져도 이 저장값은 유지한다.

hello 직후 Backend는 `WorldStateUseCase.current(worldId)`의 정본 전체를 ephemeral
`world.snapshot`으로 보낸다. `WorldStateRuntime`은 모든 필드와 좌표를 먼저 검증하고
revision 단위로 한 번에 교체한다. 낮은 revision은 stale, 동일 revision의 다른 내용은
conflict로 분류하므로 snapshot 뒤에 replay되는 과거 World event가 화면을 되돌리지
못한다. `ConnectionConvergenceRuntime`은 reconnect 시작부터 snapshot 적용까지를 local
monotonic clock으로 측정하며 2초 초과를 명시적 timeout으로 노출한다.

## Unreal 권장 모듈

```text
Source/GahyeonStage/
├─ GahyeonCharacterCore        # Unreal 비종속 값/상태/규칙
├─ GahyeonCharacterNetwork     # WebSocket, schema, cursor, reconnect
├─ GahyeonCharacterBehavior    # state layers, reflex, gesture selector
├─ GahyeonCharacterAnimation   # face, viseme, look-at, Control Rig bridge
├─ GahyeonCharacterPresentation# MetaHuman actor/component, camera, audio
└─ GahyeonCharacterDebug       # overlay, tracing, replay

Content/Gahyeon/
├─ Characters/Gahyeon
├─ Animation/{Base,Posture,Gesture,Face,Secondary}
├─ Behavior/{Profiles,Gestures,Emotions}
├─ ControlRig
├─ Audio
├─ Maps/VerticalSlice
└─ UI/Debug
```

초기에는 하나의 Unreal plugin 또는 project 안에서 위 모듈을 구성한다. 실제 재사용
필요가 생기기 전에는 plugin을 여러 개로 쪼개지 않는다.

## C++와 Blueprint

### C++

- 프로토콜 타입, 검증, 재연결, sequence/correlation 처리
- 상태 전이 규칙, 우선순위, timeout, interruption
- Reflex/Behavior scheduler와 deterministic gesture selection
- Animation/Facial/LipSync/Attention interface와 component
- 자동화 테스트, event replay, 성능 계측

### Blueprint, Anim Blueprint, Control Rig, DataAsset

- MetaHuman 및 레벨 조립, 카메라·조명 튜닝
- Animation Blueprint의 layered blend와 transition 표현
- Control Rig/IK/LookAt의 authoring 값
- Gesture/Emotion/Character profile 데이터
- 디버그 UI와 빠른 연출 반복

Blueprint는 상태의 정본이나 네트워크 parser가 되지 않는다. C++가 요청한 semantic
상태를 어떻게 보일지 편집하는 역할을 맡는다.

## 애니메이션 합성 순서

```text
Base locomotion
→ posture
→ upper-body gesture
→ head/eyes attention
→ facial emotion
→ lip sync
→ breathing/blink/saccade/micro motion
→ groom/cloth/secondary physics
```

Speech, emotion, attention, gesture는 서로 독립적인 layer다. 감정 하나를 animation
asset 하나와 1:1로 연결하지 않는다.

## 실패 시 동작

- Backend 연결이 끊겨도 blink, breathing, saccade, idle, local look-at은 계속된다.
- 재연결 시 마지막 durable `sequence`를 보내 누락 이벤트를 재생한 뒤 snapshot으로
  수렴한다.
- replay event 적용 시 generation synchronization과 이전 audio 폐기는 원자적인
  Game Thread 작업이어야 한다.
- 오래된 ephemeral event는 재생하지 않는다.
- 알 수 없는 메시지 타입은 로그 후 무시하고 연결은 유지한다.
- 유효하지 않은 payload는 해당 메시지만 거부하고 `protocol.error`를 회신한다.

관련 결정은 [`../adr/`](../adr/)에, 메시지 계약은
[`PROTOCOL_V1.md`](PROTOCOL_V1.md)에 기록한다.
첫 Unreal 기준 버전은 [`ADR-0007`](../adr/0007-unreal-5-6-baseline.md)에 따라 UE 5.6으로
고정한다.
UE adapter의 thread·SaveGame·송수신 순서는
[`ADAPTER_INTEGRATION.md`](ADAPTER_INTEGRATION.md)를 따른다.
현재 개발 환경과 착수 조건은 [`READINESS.md`](READINESS.md)에 기록한다.
