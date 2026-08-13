# Unreal Stage 준비 상태

확인일: 2026-08-11

## 준비 완료

- Core/Application과 Presentation Client 책임 경계
- WebSocket JSON v1 envelope와 durable/command/ephemeral 구분
- Reflex/Behavior/Cognition 비차단 실행 규칙
- intent generation, priority, expiry와 stale-result 폐기 참조 구현
- 지연·단절·barge-in acceptance 기준
- Desktop의 전체 semantic SSE event 구독
- Core event → Unreal v1 mapper와 session/world scoped replay service
- Backend/Unreal 공용 canonical protocol fixture
- 조건부 WebSocket endpoint, hello/welcome/ack/heartbeat와 backpressure
- Actuator `unrealRuntime` TTS/batch STT/선택적 streaming STT readiness indicator
- bounded Cognition executor, generation admission과 command idempotency
- 엔진 독립 C++20 RuntimeCore, MPSC mailbox와 VAD/barge-in coordinator
- Cognition/TTS worker 분리, first-segment `speech.prepared`와 TTL audio cache
- streaming conversation 경계, 문장 단위 조기 TTS 준비와 first-delta/first-sentence metric
- opt-in 실제 ChatModel token streaming, tool-step 음성 차단과 계약 위반 circuit breaker
- barge-in generation 변경 시 queued/running stale cognition Future 취소
- barge-in generation 변경 시 queued/running stale TTS Future 취소와 미게시 audio cache 회수
- 마지막 session WebSocket 종료 시 Cognition/TTS 작업 취소와 admission/lifecycle 상태 해제
- utterance/segment 이중 순서와 `speech.sequence.ended` 기반 전체 발화 종료
- superseded cognition을 provider failure가 아닌 cancellation event/ledger 상태로 분리
- generation-aware ordered C++ speech queue
- audio-device callback 기반 C++ SpeechPlaybackCoordinator와 gap/barge-in 테스트
- 32 MiB 제한·PCM16 검증·late callback 무효화를 갖춘 UE HTTP/AudioComponent source adapter
- Game/PIE 전용 단일 transient presentation host 자동 bootstrap
- semantic character presentation component와 DataAsset 기반 local gesture/montage profile
- C++/TypeScript local VAD hysteresis와 backend-independent barge-in controller
- session-wide perception generation watermark와 final transcript 단일 Cognition 제출
- local Thinking watchdog과 VAD/timeout 즉시 Backend Cognition cancellation
- seeded C++ AmbientMotionRuntime과 30분 offline 무정지/범위 검증
- eye-first/head-follow C++ AttentionRuntime과 stale tracking ambient fade
- replay 시 intent/speech를 함께 전진시키는 C++ ProtocolEventRuntime generation 수렴
- scope gap·duplicate·disconnect를 처리하는 C++ ReplayCursorRuntime
- hello authoritative World snapshot provider와 revision-aware C++ WorldStateRuntime
- reconnect→snapshot 2초 budget을 측정하는 C++ ConnectionConvergenceRuntime
- 수동 종료와 구분되는 bounded exponential WebSocket reconnect/backoff + jitter
- audio-position timed viseme + RMS fallback C++ LipSyncRuntime
- PCM WAV duration + 한국어 모음군 heuristic viseme fallback과 exact provider 교체 포트
- PCM16 WAV 사전 검증과 단일 authoritative failed speech-sequence 종료 경계
- provider viseme cue가 실제 WAV duration을 넘을 때 timeline 전체를 거부하는 경계
- TTS provider 교체용 Backend UnrealVisemeTimelinePort와 strict speech schema
- audio SHA 결합·bounded timeout을 사용하는 exact forced-aligner HTTP adapter
- multidimensional EmotionRuntime과 data-driven deterministic GestureRuntime
- bounded p50/p95/p99/worst C++ LatencyTrace와 실제 presentation callback span 경계
- revision-guarded World action target, local navigation/interaction, idempotent completion과
  Core/Renderer 단일-claim commit 경계, Headless executor, PostgreSQL action ledger,
  재시작 복구 및 독립 timeout 정리
- bounded C++ action completion outbox, ack-only removal, offline backoff와 SaveGame snapshot
- bounded protocol ingress, Game Thread dispatcher, save-before-ack egress와 versioned client
  SaveGame contract
- RT-01~RT-10 reference evidence matrix와 UE 실기기 미검증 항목 분리
- Desktop 테스트와 production build
- UE 5.6 source-only `GahyeonStage.uproject`, Development/Editor target와 native module 골격
- Backend 없이 진행되는 Unreal GameInstance runtime cadence와 bounded MPSC ingress 경계
- Unreal 설치 없이 실행 가능한 Stage scaffold 정적 검증기
- UE protocol envelope parser, callback→Game Thread bounded ingress와 `client.hello` transport 골격
- versioned `USaveGame` cursor/action schema와 직렬화된 async save boundary
- CMake/UBT 단일 source-of-truth `GahyeonRuntimeCore` module과 SaveGame 왕복 mapper
- save-confirmed cursor ACK, action completion retry와 terminal acknowledgement persistence 경로
- 네 durable fixture의 strict UE payload decoder와 RuntimeCore typed apply→persist→ACK 경계
- Blueprint용 authoritative World/action/phase/dominant-emotion Game Thread snapshot
- Core `X/Z` 수평·`Y` 높이 미터↔Unreal `X/Y` 수평·`Z` 높이 센티미터 좌표 adapter와
  Pawn용 `UGahyeonWorldActionComponent`
- 자동 NavMesh 요청, 실제 도착 판정, path failure, generation 취소와 interaction notify 경계
- DataAsset 기반 activity/target→posture/Montage 매핑과 non-blocking async preload/playback
- 교체 가능한 `AGahyeonCharacterPawn` source shell과 저주기 stall/status debug component
- Engine Entry map에서도 자동 생성되는 source-only GameMode, 진단 Pawn 형상, follow camera와
  avatar 지정 시 진단 형상 자동 비활성화
- GameMode가 자동 생성하는 16m x 12m source-only prototype room과 Core Home World의
  bed/desk/bookshelf/chair/window/room-center semantic interaction point
  (최종 environment asset이 아닌 VS-10 행동 fixture)
- Debug overlay의 자동 navigation readiness와 등록된 semantic point 수 상시 노출
- 실제 adapter callback 기반 bounded latency p95/worst/budget-violation Snapshot
- local VAD edge 및 partial/final transcript의 Unreal→Backend protocol 송신 경계
- 현재 generation의 partial이 Backend 왕복 전에 user Attention TTL을 갱신하고 VAD end/stale
  generation 이후에는 거부되는 RuntimeCore 계약
- capture timestamp를 보존하는 bounded MPSC VoiceInput mailbox와 Presentation 선행 tick
- AudioCaptureCore float PCM/RMS callback, non-blocking STT audio sink와 backpressure 계측
- Core TranscriptionUseCase를 사용하는 Unreal 전용 WAV STT endpoint
- 300ms pre-roll/30초 상한 PCM16 WAV batch STT worker와 generation-bound stale 차단
- capture callback 무할당·무잠금 고정 SPSC PCM ring과 timestamp lifecycle 병합
- 별도 인증 WebSocket으로 연결된 renderer streaming STT sink/provider session과
  connection-generation 기반 stale socket callback 차단
- VAD end→batch STT final 3초 p95 budget과 Backend provider 처리시간 metric
- 원본 18장(얼굴 12/전신 6) canonical G0 identity pack과 G1 modeling handoff
- Hero manifest v2 lifecycle과 선택적 UE build-time G5/source/evidence/package SHA gate

## 개발 환경 확인

- 개발 머신: Apple M3 MacBook Air, 16GB, arm64
- Xcode와 Apple Clang: 설치됨
- Epic Games Launcher: 설치되지 않음
- Unreal Engine: 설치 흔적 없음

이 머신에서는 Unreal project를 컴파일하거나 Editor로 열 수 없다. 다만 MetaHuman 제작이
에디터에 통합된 첫 버전인 UE 5.6을 초기 지원 기준으로 고정하고, source-only project와
C++ runtime module을 생성했다. `scripts/verify_unreal_stage_scaffold.sh`는 구조와 필수 plugin,
Game Thread/MPSC 경계를 정적으로 검사한다. 이는 UE 컴파일 증거를 대신하지 않는다.

## VS-0 착수 전에 필요한 결정

1. 실제 Unreal 개발/실행 머신을 정한다. M3 16GB는 headless/runtime 구조와 경량
   placeholder 검증에는 사용할 수 있지만 AAA MetaHuman 품질의 주 목표 머신으로
   간주하지 않는다.
2. 해당 머신에 기준 버전 Unreal Engine 5.6과 C++ toolchain을 설치한다.
3. UE 5.6의 MetaHuman, Control Rig, Full Body IK, WebSocket plugin을 실제 Editor에서
   로드하고 Development Editor target을 컴파일한다.
4. Development Editor와 packaged Development build의 목표 OS를 정한다.
5. Unreal binary asset은 Git LFS 또는 별도 artifact storage 중 하나를 선택한다.
6. 생성물(`Binaries`, `DerivedDataCache`, `Intermediate`, `Saved`) 제외 규칙은 준비됐으며,
   첫 Editor build 뒤 누락 항목을 재검사한다.

Windows/GTX 1660 Ti 머신에서는 다음 명령이 canonical 일반 모니터 Stage를 Win64 Development로
빌드하고 VS-5/VS-8 Automation을 실행한 뒤 checksum-bound evidence manifest v2를 남긴다.

```powershell
.\scripts\run_unreal_engine_gate.ps1 -UnrealRoot "C:\Program Files\Epic Games\UE_5.6"
```

`-Package`를 추가하면 UAT `BuildCookRun`으로 Win64 Development를 cook/stage/pak/archive한다.
gate는 출력 파일 전체의 상대경로·byte size·SHA-256 inventory를 만들고 verifier가 누락·추가·변조
파일을 다시 검사한다. 이는 packaged 산출물 재현 증거이며 실제 프레임/마이크/MetaHuman 품질
합격을 대신하지 않는다.

Looking Glass 전용 gate는 이 일반 Stage gate가 통과한 다음 별도로 실행한다.

## 다음 작업

Backend WebSocket Adapter는 구현되어 있지만 기본값은 비활성화되어 있다. Unreal
환경이 준비되기 전에는 운영에서 활성화하지 않는다. 실제 Stage source 골격은
`unreal/GahyeonStage/`에 있으며, 현재 VS-2는 정적 구조만 통과하고 Editor build/open
증거가 없으므로 진행 중이다. World action 실행 경계는 엔진
독립 RuntimeCore/Backend와 Pawn용 NavMesh source adapter까지 연결됐다. 실제 Map/NavMesh와
interaction montage asset을 이용한 PIE 증거는 VS-10 작업으로 남아 있다. VAD/partial transcript는
ephemeral latest-state 경로로 분리했고 Unreal 송신 API와 latency metric도 추가했다.
실제 low-latency streaming STT provider와 target device 실측은 아직 남아 있다. batch WAV
fallback은 구현됐지만 Unreal Editor compile/PIE 증거가 필요하다. 실제 token streaming
구현도 준비되어 있으나 기본값은 안전한 동기 fallback이다. 정적 probe는 direct/tool/multi-tool과
단일·병렬 tool result 이후 최종 text-only stream까지 검증하도록 준비됐다. 다음 선행 작업은
선택한 OpenRouter provider/model에 대해 이 다섯 live fixture를 실행한 뒤 opt-in 플래그를
활성화하는 것이다.

Streaming STT 구현 방향은
[`../adr/0009-core-owned-streaming-stt.md`](../adr/0009-core-owned-streaming-stt.md)에 고정했다.
STT provider와 credential은 Core가 소유하고 Unreal은 별도 bounded duplex channel로 PCM만
운반한다. `IGahyeonStreamingSttAudioSink`에서 별도 인증 WebSocket과 Backend provider
session까지 source 연결되어 있으며, 재접속 이전 socket의 connected/message/closed callback은
connection generation으로 폐기한다. 다만 실제 UE 5.6 compile/PIE와 한국어 microphone provider
평가 전에는 batch fallback을 운영 기본값으로 유지한다.
