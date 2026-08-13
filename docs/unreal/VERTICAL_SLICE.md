# Unreal Vertical Slice 실행 순서

## 먼저 확정할 것

1. Protocol v1 envelope와 최소 message set을 Core/Desktop 기존 이벤트와 대조한다.
2. intent mailbox, layer arbiter, cancellation generation과 monotonic clock을 고정한다.
3. Reflex/Behavior/Cognition별 latency budget과 계측 항목을 고정한다.
4. C++ domain model과 최소 DataAsset schema를 고정한다.

MetaHuman 선정과 G0/G1 identity gate는 실시간 런타임 검증 뒤에 진행한다. 위 네 항목이
끝나기 전에는 고급 animation graph나 대규모 Blueprint를 만들지 않는다.

## Dependency 순서

| 단계 | 산출물 | 합격 기준 |
|---:|---|---|
| VS-0 | headless C++ concurrency model + deterministic tests | 세 계층 독립 실행, stale result 폐기 |
| VS-1 | protocol parser와 event replay harness | fixture 검증, unknown type 안전 처리 |
| VS-2 | 빈 Unreal project, 모듈, CI smoke | editor/build target 재현 가능 |
| VS-3 | placeholder + state/latency overlay | Backend 없이 ambient/reflex 무기한 실행 |
| VS-4 | local VAD/reflex + Behavior layers | VAD 시작 100ms 내 반응, Cognition 중에도 motion 지속 |
| VS-5 | mock Cognition 지연/실패 harness | 0.5~10초 지연·timeout·순서 역전에도 멈춤 없음 |
| VS-6 | Backend WebSocket adapter | 비차단 I/O, reconnect/cursor/snapshot 수렴 |
| VS-7 | text/STT/TTS end-to-end | barge-in, cancellation, stale audio 차단 |
| VS-8 | viseme + layered animation | audio sync와 병렬 emotion/attention 합성 |
| VS-9 | MetaHuman Gahyeon swap | runtime 로직 변경 없이 자산 교체 |
| VS-10 | one-room world + interaction point | look-at, chair/desk action 완료 회신 |
| VS-11 | 성능·품질 acceptance | latency/frame time/sync/identity gate 통과 |

현재 VS-2에는 UE 5.6 `.uproject`, Game/Editor target, native runtime module과 정적 검증이
준비되어 있다. 실제 UE 5.6 Development Editor 컴파일과 Editor open 증거가 없으므로
VS-2를 완료로 간주하지 않는다. `AGahyeonCharacterPawn` source shell과
`UGahyeonRuntimeDebugComponent`가 offline cadence/queue/action 상태를 노출하고 source-only
GameMode/Pawn이 Engine basic-shape 진단 형상과 follow camera를 자동 생성한다. 그러나 실제
UE 5.6 compile/PIE 장시간 증거가 없으므로 VS-3도 아직 완료가 아니다.
기본 WebSocket/hello와 versioned async SaveGame source도 추가됐지만, 실제 Engine compile과
typed durable event 적용, reconnect 및 save-confirmed egress의 실제 Engine 검증 전에는
VS-6 완료로 간주하지 않는다.

현재 canonical fixture의 World/Emotion/Action/Cognition durable 종류는 typed 적용까지
source로 연결됐다. fixture에
새 durable type이 추가되면 Stage 검증기가 decoder 누락을 실패시킨다. Invalid durable은
ACK하지 않고 reconnect를 요구하며, Backpressure는 event를 폐기하지 않는다. source
scaffold에는 최대 30초의 bounded exponential reconnect/backoff와 jitter도 구현되어 있다.
남은 VS-6 gate는 UE 5.6 Editor/packaged 환경의 실제 socket·SaveGame·재연결 검증이다.

VS-5의 엔진 비종속 `MockCognitionRuntime`은 bounded queue와 monotonic clock으로 0.5~10초
지연, 명시적 실패, 요청 순서 역전을 재현한다. mock completion도 실제 generation admission을
통과해야 하므로 늦은 이전 응답이 Speaking을 되살릴 수 없다. UE Automation/PIE에서는 이
동일 harness를 사용하되, 실제 Anim/Presentation frame counter 증거가 나오기 전에는 VS-5 전체를
완료로 승격하지 않는다. Engine gate는
`Gahyeon.Runtime.MockCognitionDelayFailureAndReordering`의 명시적 성공 결과가 없으면 실패하므로,
Automation source가 빌드나 test discovery에서 빠진 상태를 통과로 오인하지 않는다.
같은 Engine gate는 VS-8의
`Gahyeon.Presentation.FacialCurveBindingsAreDataDrivenAndBounded`도 필수 성공으로 요구한다.
manifest v2는 두 test 이름과 봉인된 Automation log를 함께 검증하므로 test source가 컴파일에서
빠졌거나 manifest에 이름만 적은 경우에도 통과하지 않는다.

VS-10의 source 경계에는 `UGahyeonWorldActionComponent`가 추가되어 semantic target을
NavMesh 요청으로 바꾸고 실제 acceptance radius 도착, path failure, interaction animation
완료를 RuntimeCore에 보고한다. Core의 미터 좌표는 adapter에서 Unreal 센티미터로 왕복
변환한다. Engine 기본 cube만 사용하는 `AGahyeonPrototypeRoom`도 GameMode가 자동 생성하며,
16m x 12m 충돌 바닥·벽과 Core Home World와 동일한 `bed`, `desk`, `bookshelf`, `chair`,
`window`, `room-center` semantic interaction point를 제공한다. ID·허용 activity·변환된
좌표는 cross-language contract 검사로 고정한다. 이는 행동 통합용 fixture이지 최종 환경
그래픽이 아니다. 실제 NavMesh
bounds/bake, interaction montage notify와 PIE 캡처가 없으므로 VS-10을 완료로 간주하지 않는다.

VS-8의 source 경계에는 character-local `UGahyeonCharacterPresentationProfile`의 semantic
binding을 실제 Skeletal Mesh morph target으로 적용하는 경로가 추가됐다. 하나의 emotion 또는
viseme가 좌우·복수 morph를 동시에 구동할 수 있고, weight는 0..1로 제한되며 이전 frame에서만
사용된 curve는 명시적으로 0으로 복귀한다. Blink 역시 profile의 좌우 curve 이름과 scale을
사용한다. 따라서 일반 ARKit/custom morph 캐릭터는 C++ 경로만으로 얼굴 반응을 받을 수 있다.
MetaHuman Control Rig 전용 control은 동일 Snapshot/Profile을 Anim Blueprint에서 소비해야 하며,
실제 MetaHuman 얼굴에서의 curve 호환성과 audio sync는 여전히 UE 실기 검증 대상이다.

Gesture도 같은 presentation boundary를 사용한다. Core가 결정한 semantic/local variant,
posture와 intensity를 Profile에서 검증한 뒤에만 body AnimInstance의 Montage를 재생한다. Backend가
Montage 경로나 asset ID를 보내 직접 선택할 수 없으며, soft reference는 async load되어 Game
Thread를 막지 않는다. Gesture가 취소·교체되거나 Runtime epoch가 바뀌면 request generation이
증가해 늦게 도착한 asset callback이 이전 동작을 되살리지 못한다. 실제 slot layering과
MetaHuman body montage 재생 품질은 UE Editor에서 검증해야 한다.

항상 실행되는 Ambient/Attention 값은 `FGahyeonResolvedProceduralPose`로 한 번 더 정규화한다.
Profile의 눈·머리 최대 각도, 호흡·blink·micro-head·weight-shift scale을 적용하고 ambient
saccade와 user tracking을 tracking weight로 혼합한다. Anim Blueprint와 Control Rig는 이
resolved frame만 읽어 Base Locomotion 위의 Head/Eyes/Secondary Motion layer를 표현하면 된다.
같은 계산을 Blueprint마다 복제하지 않으며 C++이 bone transform을 직접 덮어쓰지도 않는다.

Listening/Thinking latency는 상태가 Presentation Component에 도착한 순간 자동 종료하지 않는다.
Anim Blueprint 또는 Control Rig가 해당 generation/runtime epoch의 포즈를 실제 적용한 다음
`ConfirmConversationPoseApplied`를 호출해야 측정된다. 현재 phase·generation·epoch가 모두 같은
경우만 수락하며 중복, 늦은 이전 turn, runtime 교체 전 confirmation은 거부한다. 따라서 C++
tick latency를 실제 시각 반응 latency로 잘못 보고하는 경로가 없다.

`UGahyeonCharacterAnimInstance`는 MetaHuman/custom Anim Blueprint용 얇은 기본 클래스다.
graph evaluation 전에 immutable runtime frame과 resolved procedural pose를 복사하고 새
Listening/Thinking token에서만 `bConversationPoseConfirmationPending`을 연다. Blueprint는
실제로 해당 state/pose layer를 소비한 뒤 `ConfirmCurrentConversationPoseApplied`를 호출한다.
단순히 Anim graph update 또는 post-evaluate가 실행됐다는 이유로 자동 확인하지 않으며,
확인이 stale 상태로 거부되면 pending을 유지해 현재 frame에서 재시도할 수 있다.
같은 bridge는 활성 primary viseme의 semantic·generation·runtime epoch를
`bVisemeConfirmationPending`으로 노출한다. MetaHuman Control Rig graph가 실제 mouth control을
소비한 뒤 `ConfirmCurrentVisemeApplied`를 호출한다. 동일 semantic cue가 연속될 수 있으므로
활성 cue 동안 매 graph update에서 확인 기회를 다시 열되 RuntimeCore가 cue당 한 표본만 받는다.

## PoC 측정값

- VAD start → Listening visual latency p95
- final transcript → Thinking transition latency
- first audio playable latency
- audio/viseme sync offset p95
- reconnect convergence time와 duplicate event 수
- idle 10분 중 frozen-frame 비율
- Cognition 10초 지연 중 Reflex/Behavior update 누락 수
- 새 turn 이후 stale LLM/TTS 결과가 적용된 횟수
- barge-in 감지 → audio 중단 및 Listening 표현 latency
- target GPU에서 game/render/RHI frame time과 VRAM

구체적인 합격 기준은 [`REALTIME_ACCEPTANCE.md`](REALTIME_ACCEPTANCE.md)를 따른다.

## 아직 하지 않는 것

- LLM의 animation asset 선택
- 네트워크 frame-by-frame transform
- Motion Matching 전체 도입
- 방 여러 개와 완전한 생활 시뮬레이션
- Looking Glass renderer
- 커스텀 MetaHuman mesh/groom 최종 제작
- 그래픽 품질만 보여주는 별도 선행 데모

Vertical Slice가 통과한 뒤 품질 작업은
[`../AAA_CHARACTER_PIPELINE.md`](../AAA_CHARACTER_PIPELINE.md)의 gate 순서로 진행한다.
