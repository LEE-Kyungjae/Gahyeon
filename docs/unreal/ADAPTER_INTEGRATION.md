# Unreal Adapter Integration Contract

이 문서는 Unreal 버전을 확정한 뒤 `GahyeonCharacterNetwork` 모듈이 RuntimeCore를 감싸는
순서와 thread ownership을 고정한다. UE 전용 타입은 이 경계 밖으로 나오지 않는다.

## 시작 순서

1. `USaveGame`에서 schema version, durable sequence, action outbox와 dead-letter를 읽는다.
2. `ClientRuntimeSaveStateCodec::Restore`로 검증한다. 미래 schema나 손상 데이터는
   연결을 시작하지 않고 debug overlay에 표시한다.
3. 복원 sequence로 `ReplayCursorRuntime`을 만들고 `BeginConnection()`을 호출한다.
4. WebSocket open 뒤 `client.hello.lastSequence`에는 복원된 sequence만 사용한다.
5. `server.welcome`을 받은 뒤 cursor의 `Welcome`을 완료한다.
6. ephemeral/durable snapshot이 Game Thread에서 적용되면 reconnect convergence를 닫는다.

각 WebSocket은 `ConnectionGeneration`뿐 아니라 연결 직전 RuntimeCore의 `RuntimeEpoch`도
캡처한다. SaveGame restore로 RuntimeCore owner가 교체된 뒤 도착한 이전 runtime callback은
새 inbound queue에 들어갈 수 없다. Epoch 불일치가 발견되면 현재 socket을 닫고 저장된
cursor로 재연결하여 replay/snapshot 수렴을 다시 시작한다. 연결 완료 callback도 동일 epoch를
확인하기 전에는 hello를 보내지 않는다.
이때 old connection-generation callback은 새 socket을 닫지 않고 조용히 폐기한다. 반대로
connection generation은 현재지만 RuntimeEpoch가 바뀐 socket은 replay가 필요한 현재 연결이므로
명시적으로 닫아 reconnect한다. Runtime 교체와 `OnConnected` 사이에 경합이 생겨도 hello 없는
좀비 socket을 남기지 않는다.

## 수신 경계

```text
WebSocket callback thread
→ UE JSON schema/finite-number 검증
→ ProtocolMessage/WorldStateSnapshot 정규화
→ ProtocolNetworkIngressAdapter::OnEvent
→ bounded ProtocolIngressMailbox
→ Game Thread ProtocolGameThreadDispatcher::Drain
→ ProtocolEventRuntime / WorldStateRuntime
```

- callback thread는 Actor, UObject, Animation Instance와 RuntimeCore state를 만지지 않는다.
- `ReconnectRequired`가 반환되면 연결을 닫는다. persisted cursor 이후 replay가 누락
  durable event를 복구하므로 socket thread에서 기다리거나 무한 queue를 만들지 않는다.
- ephemeral overflow는 metric을 올리고 폐기한다.
- Game Thread는 frame마다 최대 처리 개수를 제한한다. `Backpressured` event는 mailbox
  선두로 돌아가고 durable cursor는 전진하지 않는다.

## 송신과 저장 순서

```text
Game Thread state changed
→ ClientRuntimeSaveStateCodec::Capture
→ AsyncSaveGameToSlot
→ completion callback on Game Thread
→ PersistenceConfirmed / ActionPersistenceConfirmed
→ ProtocolNetworkEgressRuntime::Next
→ socket thread non-blocking send
→ MarkSent
```

저장 성공 전에는 `client.ack`도 `character.action.completed`도 송신하지 않는다. 이 순서를
뒤집으면 crash 직후 Backend는 ACK된 event를 replay하지 않고 client는 완료 command를
잃을 수 있다. `MarkSent`는 제거가 아니라 retry deadline 설정이다.

terminal accepted/duplicate ACK만 outbox를 제거한다. terminal rejection은 bounded
dead-letter로 이동하고 debug overlay에 action ID와 Backend result를 표시한다.

## World action 연결

- `world.transition.target` → `UNavigationSystemV1` async move 요청
- move completion → `WorldActionRuntime::NavigationArrived`
- interaction montage notify → `WorldActionRuntime::Complete`
- 반환 completion → `WorldActionCommandBridge::Queue` 후 SaveGame
- `character.action.result` → `ResolveAuthoritatively`; Core Headless executor가 먼저 끝난
  경우 local move/montage를 blend-out한다.
- durable `world.snapshot` → `WorldStateRuntime` atomic swap 후 revision-aware 보간

## 종료/단절

- WebSocket 단절은 ambient/reflex/animation tick을 중단하지 않는다.
- 정상 동작은 send-before-save를 금지하므로 종료 hook 하나에 의존하지 않는다.
- 재연결 backoff와 DNS/TLS/socket 작업은 Game Thread 밖에서 수행한다.

## UE 버전 확정 후 생성할 최소 클래스

```text
FGahyeonCharacterNetworkModule
UGahyeonTransportSubsystem       WebSocket lifecycle, JSON normalization
UGahyeonRuntimeSaveGame          versioned cursor/outbox fields
UGahyeonRuntimeDispatcher        Game Thread drain and egress scheduling
UGahyeonWorldActionComponent     NavMesh/montage callbacks
UGahyeonRuntimeDebugComponent    overflow, dead-letter, latency overlay
```

현재 source scaffold에는 `UGahyeonTransportSubsystem`, `UGahyeonRuntimeSaveGame`,
`UGahyeonRuntimePersistenceSubsystem`, `UGahyeonRuntimeSubsystem`과 strict envelope parser가
생성되어 있다. Transport는 save 복원이 성공한 뒤에만 socket을 열고 `client.hello`를
전송하며, message callback은 parser 이후 bounded mailbox enqueue를 Game Thread에 예약한다.

`GahyeonRuntimeCore`는 CMake와 UBT가 같은 26개 구현 파일을 소비하는 실제 Unreal module로
구성했다. `ClientRuntimeSaveStateCodec`과 UE SaveGame의 양방향 mapper, persisted cursor
이후 `client.ack`, action outbox 저장 이후 `character.action.completed`, terminal
acknowledgement 이후 outbox/dead-letter 재저장 경로도 source 수준에서 연결했다. 재시작한
outbox 항목은 이미 저장이 확인된 것으로 복원하며 retry cadence를 다시 시작한다.

durable semantic event는 strict typed handler가 성공한 뒤에만 `CompleteDurableEvent`를
호출한다. 현재 fixture의 World/Emotion/Action 네 종류와 Cognition lifecycle 네 종류는
허용 필드, 필수 필드, JSON-safe 정수, 유한 좌표와 값 범위를 검증한 뒤
`ProtocolEventRuntime`에 적용한다. Applied/Ignored/Stale만
저장·ACK하며 Invalid는 연결을 닫고 persisted cursor부터 다시 수렴한다. Backpressure event는
별도 슬롯에 보존해 다음 frame에 재시도한다. 알 수 없는 확장 event는 payload를 해석하지
않되 cursor를 완료해 무한 replay를 방지한다.

`speech.prepared`, `speech.sequence.ended`, `generation.advanced`, `gesture.intent`,
`attention.target`도 strict typed decoder를 거친다. Audio URL/MIME/viseme timeline은
`AcquireNextSpeechSegment`로 presentation에 전달하고, 실제 device start/finish/failure가
RuntimeCore의 Speaking ownership을 결정한다. 새 generation이 active utterance를 폐기하면
`OnAudioInterruptRequested`가 즉시 broadcast된다.

`UGahyeonSpeechAudioComponent`는 최대 32 MiB HTTP 응답만 받고 PCM16 mono/stereo WAV를
검증해 `USoundWaveProcedural`에 전달한다. audio device가 실제 재생을 시작한 뒤에만
Speaking ownership을 보고하며 완료/실패를 다시 RuntimeCore로 돌려준다. 늦은 HTTP
callback은 request serial과 generation interruption으로 무효화한다. Backend가 보내는
상대 audio URL은 WebSocket의 `ws→http`, `wss→https` 동일 origin으로 해석하고 같은 Bearer
credential을 사용하므로 별도 Blueprint secret 설정은 필요 없다.
Backend transport 테스트는 `speech.prepared`가 게시한 `/api/gahyeon/unreal/speech/audio/{id}`
경로에서 실제 embedded HTTP GET으로 동일 WAV, `audio/wav`, `Cache-Control: no-store`를 받고,
cache discard 뒤 같은 URL이 404가 되는 것까지 검증한다.
오디오 캐시는 5분 TTL, 256 entries, entry당 32 MiB, 총 256 MiB로 제한하며 초과 시
가장 오래된 항목을 먼저 회수한다.
procedural FIFO가 플랫폼별로 finish delegate를 놓치는 경우에는 PCM 길이와 500ms margin의
monotonic deadline이 queue deadlock을 막는다.

로컬 마이크 adapter는 매 audio callback의 정규화 level을
`ObserveMicrophoneLevel`에 전달한다. VAD edge는 Backend 왕복 전에 Listening/barge-in을
적용하고 동시에 `perception.voice.started/ended`를 비차단 송신한다. Streaming STT는
`SubmitPartialTranscript`, 확정 STT는 `SubmitFinalTranscript`만 호출한다. final command가
실제로 socket send queue에 들어간 경우에만 transcript→Thinking/first-audio span을
시작한다. 연결이 끊겨 송신하지 못해도 local Reflex와 audio stop은 계속 동작하며,
실패한 입력을 성공 latency sample로 기록하지 않는다.

Capture/STT provider callback은 UObject/Runtime/WebSocket을 직접 호출하지 않고
`UGahyeonVoiceInputComponent`의 bounded MPSC mailbox에 immutable observation만 넣는다.
오디오 observation은 capture 시점의 monotonic timestamp를 보존하므로 여러 callback이
한 frame에 drain되어도 VAD attack/release 시간이 압축되지 않는다. mailbox는 최대 512개,
frame당 drain은 128개로 제한한다. provider는 component teardown 전에 callback을
detach하고 worker를 join해야 하며, callback에서는 weak lifetime guard가 유효할 때만 enqueue한다.
Pawn에서는 VoiceInput tick을 Presentation보다 먼저 실행해 새 Listening state를 같은 frame에
표현할 수 있게 한다.

`AudioCaptureCore::FAudioCapture` callback은 float PCM의 RMS만 계산하고 shared mailbox에
넣는다. 동시에 설치된 `IGahyeonStreamingSttAudioSink`가 있으면 같은 PCM view를 전달한다.
sink는 callback 안에서 대기하거나 UObject를 접근해서는 안 되며 자체 bounded queue에
복사하거나 즉시 거부해야 한다. 거부는 `SttBackpressureCount`, capture device overflow는
별도 `CaptureOverflowCount`로 노출한다. batch PoC provider는 VAD로 확정한 구간을 WAV로
만들어 `/gahyeon/unreal/speech/transcriptions`로 전송하고, 향후 streaming provider는 이
sink 구현만 교체한다. 교체 구현은 provider에 직접 연결하지 않고 ADR-0009의 Core-owned
duplex STT endpoint에 연결하며, control/event WebSocket과 PCM queue를 분리한다.

기본 batch sink는 시작 시 256개 PCM slot과 sample storage를 한 번만 할당한다. capture
callback은 SPSC ring에 `memcpy`하고 semaphore를 signal할 뿐 동적 할당·mutex 대기·WAV
encoding을 하지 않는다. lifecycle event는 별도 작은 queue로 전달하고 capture timestamp를
기준으로 PCM과 합친다. 전용 worker의 `PcmUtteranceBuffer`가 300ms pre-roll을 포함하되 한 발화를
최대 30초로 제한하고 PCM16 WAV encoding까지 수행한다. lifecycle event는 포화 시 오래된
PCM 하나를 버려서라도 보존하며, 완료 WAV는 최대 4개만 유지한다. Game Thread는 완료 WAV를
최대 2개의 비동기 HTTP request로 제출한다. 응답 transcript에는 VAD 시작 generation을
그대로 붙여, 그 사이 barge-in이 일어났으면 Cognition에 제출하지 않고 stale count만 올린다.
PCM ring drop, lifecycle drop, 완료 utterance drop, HTTP/STT failure와 stale result는 서로 다른 원인으로
계측한다. lifecycle queue가 비정상적으로 포화되면 edge를 임의로 이어 붙이지 않고 queued
edge와 worker utterance를 함께 초기화해 손상된 WAV가 STT로 제출되는 것을 막는다.
장치 format 변경이나 빈 PCM처럼 worker가 WAV를 만들지 못한 경우에도 실패 generation을
Game Thread로 돌려보내 VAD end→STT span을 즉시 취소한다.

Game/PIE World가 준비되면 `UGahyeonRuntimeSubsystem`이 transient
`AGahyeonPresentationHost` 하나를 자동 생성해 speech adapter를 활성화한다. 이미 같은
host가 배치돼 있으면 재사용하며, dedicated server에는 생성하지 않는다. Core의 headless
실행과 Stage의 speaker 출력 수명은 이 조건으로 분리된다.

가시 캐릭터에는 `UGahyeonCharacterPresentationComponent`만 붙인다. Anim Blueprint는 frame의
breath/blink, ambient eye/micro-head/weight-shift, tracked eye/head, phase, emotion, gesture
semantic/variant를 읽되 행동을 다시 판단하지 않는다. camera/tracker의 World target은 이
component가 character-local 좌표로 바꿔 `AttentionRuntime`에 직접 넣으므로 Backend/LLM
지연과 무관하게 eye-first/head-follow가 동작한다.

`UGahyeonCharacterPresentationProfile` DataAsset은 캐릭터별 motion scale과 semantic gesture
규칙, local variant→soft montage mapping을 소유한다. Backend는 semantic만 보내며
RuntimeCore가 local rule로 variant를 선택하고 Presentation만 montage object를 안다. profile이
아직 없거나 비어 있어도 Stage는 정상 기동하고 gesture만 `NoCandidate`가 된다.

audio device가 재생 중인 동안 component는 monotonic playback cursor와 envelope amplitude를
RuntimeCore `LipSyncRuntime`에 공급한다. provider viseme timeline이 있으면 primary/secondary
viseme weight를, 없으면 envelope 기반 jaw 값을 frame에 노출한다. Presentation profile의
semantic→curve mapping만 MetaHuman/커스텀 rig 이름을 알고 Core는 curve 이름을 모른다.
직접 morph 적용 모드에서는 실제 `SetMorphTarget` 호출 뒤 viseme 적용을 확인한다. MetaHuman
Control Rig처럼 별도 graph가 얼굴을 소유하면 graph가 값을 적용한 뒤
AnimInstance의 `ConfirmCurrentVisemeApplied()`를 호출한다. bridge가 semantic, generation,
runtime epoch를 보존하므로 Blueprint가 토큰을 조립하지 않는다. 내부 frame에 viseme가
보였다는 사실만으로는 latency acceptance를 통과하지 않는다.

실제 NavMesh/montage asset callback과 UE compiler/실기기 audio 증거는 아직 없다. 따라서 audio
interruption acceptance나 VS-6 전체를 통과한 것으로 보지 않는다.

Blueprint는 URL, profile/DataAsset와 debug 표시만 설정한다. cursor, retry, generation,
action claim 로직은 Blueprint에 두지 않는다.
## World action execution

`world.transition.target` 수신 후 `FGahyeonRuntimeFrameSnapshot`에서 다음 값을 읽는다.

- `ActiveWorldActionId`, `WorldActionPhase`
- `WorldActionTargetRoom`, `WorldActionTargetPosition`
- `WorldActionTargetActivity`, `WorldActionInteractionTarget`
- `WorldActionExpectedRevision`

Presentation의 NavMesh/path-following은 목표에 실제 도착한 뒤
`UGahyeonCharacterPresentationComponent::NotifyWorldNavigationArrived()`를 호출한다.
의자 앉기·책 읽기 같은 상호작용 애니메이션이 실제로 끝난 뒤에만
`NotifyWorldActionFinished("completed", "", ActualPosition)`를 호출한다. 경로 실패나
애니메이션 실패는 각각 `failed`와 이유를 전달한다. Backend snapshot은 이 완료가
커밋되어 다시 재생되기 전까지 미리 변경하지 않는다.

새 cognition generation이 도착하면 generation-bound 이동은 즉시 취소된다. Runtime은
이를 `cancelled/superseded_generation` 완료 명령으로 durable outbox에 넣으므로 캐릭터가
이전 대화의 이동을 계속하지 않는다. 타임아웃은 Behavior cadence에서 검사하며 렌더
프레임이나 네트워크 콜백을 막지 않는다.

기본 Pawn에는 `UGahyeonCharacterPresentationComponent`와
`UGahyeonWorldActionComponent`를 함께 붙인다. WorldAction component는 Presentation
뒤에 tick하도록 prerequisite를 설정하며 Core 미터 좌표가 변환된 Unreal 센티미터 목표로
`SimpleMoveToLocation`을 요청한다. acceptance radius에 실제 진입해야 arrival을 보고하고,
path following이 도착 전 Idle로 끝나면 `failed/navigation_failed`를 보고한다. 커스텀
locomotion을 쓰면 `bAutoNavigate=false`로 두고 `OnNavigationRequested`에서 이동한 뒤
`MarkNavigationArrived()`를 호출한다. `OnInteractionRequested`는 DataAsset이 선택한 결과
posture를 함께 노출한다. 등록된 Montage는 비동기로 preload/재생하며 정상 종료는 자동
완료된다. custom sequence는 animation notify에서 `FinishCurrentAction()`을 호출할 수 있다.
새 generation이나 authoritative stop은 현재 component가 소유한 Montage만 짧게 blend-out한다.

자동 이동을 선택했지만 controller 또는 baked navigation data가 없다면 명령을 대기 상태로
방치하지 않고 각각 `navigation_controller_unavailable`, `navigation_data_unavailable`로 즉시
실패시킨다. 커스텀 locomotion 경로는 navigation data를 요구하지 않는다.

의자·책상·침대 Actor에는 정확한 root alignment 위치에
`UGahyeonInteractionPointComponent`를 놓고 stable semantic ID와 허용 activity를 지정한다.
World registry는 ID를 유일하게 유지한다. 각 point는 Core와 같은 stable room ID도 가진다.
action의 `interactionTarget`이 registry에 없거나 target room이 다르거나 activity가 허용되지
않으면 fallback 좌표로 억지 실행하지 않고 명시적으로 실패한다.
