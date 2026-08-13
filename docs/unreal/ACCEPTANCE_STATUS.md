# Realtime Acceptance Evidence Matrix

확인일: 2026-08-12

`Reference verified`는 엔진 비종속 RuntimeCore와 Backend에서 결정론적으로 검증했다는
뜻이다. `UE pending`은 실제 Anim/Audio/WebSocket callback과 target GPU 계측이 남았다는
뜻이다. reference 통과를 packaged Unreal 합격으로 간주하지 않는다.

| ID | Reference evidence | 상태 | UE/실기기에서 남은 증거 |
|---|---|---|---|
| RT-01 | `AmbientMotionRemainsFiniteAndAliveForThirtyOfflineMinutes` | Reference verified | Anim graph 30분 capture, frozen-frame 0 |
| RT-02 | `LatencyTraceComputesBoundedAcceptancePercentiles`, generation/runtime-epoch-bound explicit `ConfirmConversationPoseApplied`; state 관측만으로 latency를 자동 종료하지 않음 | Reference verified / UE pending | Anim Blueprint/Control Rig가 실제 pose 적용 직후 confirmation한 VAD→Listening p95 |
| RT-03 | `TenSecondCognitionHarnessKeepsBehaviorAndReflexCadence`, bounded `MockCognitionRuntime`의 0.5초 실패/10초 지연/순서 역전, `Gahyeon.Runtime.MockCognitionDelayFailureAndReordering` Automation source | Reference verified / UE pending | Automation 실행과 packaged build frame/update counter |
| RT-04 | `BargeInRejectsLateCognition`, `StaleReplayCannotRewindGenerationOrReplacePresentation`, SaveGame v2 generation restore/migration, RuntimeCore epoch 기반 STT/TTS callback 폐기, 실제 STT socket에서 generation 교체 후 취소된 provider의 늦은 transcript 폐기, Desktop durable terminal correlation으로 orphan stream 종결·HTTP/SSE terminal 중복 차단·failed/cancelled 이후 늦은 HTTP/TTS 부활 차단 | Backend/Desktop transport verified / UE pending | LLM/TTS provider 포함 E2E 순서 역전 |
| RT-05 | RuntimeCore barge-in tests + `UGahyeonSpeechAudioComponent` stop boundary | Source connected / UE pending | 실제 device stop과 pose p95 |
| RT-06 | reconnect/convergence tests, Stage 2초 timeout→transport reconnect, connection-generation + runtime-epoch callback fence Automation source, old-socket mailbox reset, bounded dispatcher, disconnect/restart SaveGame harness, 실제 STT socket 재연결 후 이전 provider callback 폐기, Desktop SSE의 Electron+Renderer 이중 durable cursor fence와 EOF 포함 bounded reconnect backoff, Backend durable replay send 실패 즉시 session/transport lease 회수, pre-hello/heartbeat timeout의 renderer별 lease 회수 | Backend/Desktop reconnect verified / UE pending | 실제 TLS 단절·SaveGame restore 경합 중 Game Thread stall 0 및 stale callback 0 |
| RT-07 | `MalformedDurableEventIsIsolatedAndFollowingEventStillApplies`, unknown event test, UE parser malformed→valid recovery Automation source, oversized STT frame 연결 격리 후 새 socket 정상 frame 수신 통합 테스트, Desktop SSE 65,536자 block bound와 다중 bounded event chunk 테스트 | Backend/Desktop transport verified / UE pending | UE Automation 실행과 실제 client malformed→valid 연속 수신 |
| RT-08 | `TenMinuteVisemeLoopStaysWithinTheEightyMillisecondSyncBudget`, PCM WAV duration/한국어 모음 heuristic fallback, `speech.prepared` audio URL과 embedded HTTP WAV/no-store/404 전송 통합 테스트, timeline 관측과 실제 morph/Control Rig 적용 확인 분리, AnimInstance의 generation/runtime-epoch-bound `ConfirmCurrentVisemeApplied`, Looking Glass 전 증거 경로의 `physical-presentation-v1` 강제 | Backend audio transport verified / UE pending | exact phoneme timing을 사용한 Piper audio + MetaHuman curve p95; heuristic과 cursor-only 표본은 합격 증거 제외 |
| RT-09 | local watchdog tests, Stage Behavior tick 연결과 Backend generation cancellation tests, Desktop Browser/Electron 10초 POST deadline·fetch abort·Core active-generation DELETE·Idle 복귀, TTS sequence stop/cancel/replacement의 transport abort 및 failed/cancelled terminal의 mouth-state 원자 초기화 | Backend/Desktop verified / UE pending | 실제 provider Future cancellation E2E |
| RT-10 | Attention eye-first/head-follow/stale fade tests | Reference verified / UE pending | Control Rig 각도·보간 capture |
| RT-11 | generation-bound batch STT worker, VAD-end latency span, 8초 provider timeout, 즉시 Idle cancellation, RuntimeCore epoch 기반 HTTP/PCM callback 폐기, 별도 streaming WebSocket sink와 connection-generation callback fence, connection monitor를 놓은 뒤 state machine을 호출하는 단방향 lock order, embedded Tomcat 최대 128KiB PCM frame·양방향 lifecycle·barge-in/reconnect stale callback 전송 통합 테스트, production WebSocket E2E WAV evaluator와 nearest-rank suite acceptance | Backend transport verified / provider+UE pending | 실제 Korean microphone/provider 20회 이상 p95·CER 및 stale 0회 |
| RT-12 | World action arrival/interaction, timeout, generation cancellation과 durable outbox tests + runtime-epoch-bound `UGahyeonWorldActionComponent` | Source connected / UE pending | 실제 NavMesh 도착·실패, montage notify, meter↔centimeter 왕복 |
| RT-13 | ephemeral과 durable replay를 함께 처리하는 renderer별 bounded serial outbound queue, slow-renderer 격리·순서·포화 cleanup 테스트, bounded detach reason metric | Backend verified / UE pending | 실제 Desktop+Looking Glass 동시 연결에서 slow display가 Desktop latency에 영향 0 |

## Adapter safety evidence

- embedded server Bearer integration: unauthenticated STT upgrade와 speech HTTP는 401,
  동일 token을 사용한 WebSocket/HTTP는 정상 처리
- `ProtocolIngressMailboxProtectsDurableReplayUnderSaturation`
- `GameThreadDispatcherRetriesBackpressureBeforeAdvancingDurableCursor`
- `GameThreadDispatcherBoundsPerFrameReplayWork`
- `NetworkBridgeRequiresSaveBeforeAckAndKeepsActionUntilTerminalAck`
- `DisconnectRestartHarnessReplaysCursorAndDeliversSavedCompletion`
- `DurableWorldSnapshotFlowsFromSocketMailboxToGameThreadAtomically`
- Backend multi-renderer hello lease/identity conflict/outbound failure lifecycle tests

검증 명령:

```bash
./scripts/test_unreal_runtime_core.sh
./gradlew test
cd desktop && npm test -- --run && npm run build
```

## 현재 판정

- VS-0과 엔진 비종속 VS-1 reference gate는 통과했다.
- UE minor version은 5.6으로 고정했다. VS-2 이후의 project/editor/package 증거는
  실제 Engine이 설치된 개발 머신에서 `scripts/run_unreal_engine_gate.sh`를 통과해야 한다.
  성공 gate는 `build.log`, `automation.log`와 두 로그·uproject의 SHA-256, UE 버전, platform,
  필수 VS-5 test 이름을 묶은 원자적 `manifest.json`을 남긴다. 실패 실행은 passed manifest를
  만들지 않는다.
  현재 머신에는 UnrealEditor가 없어 source/static 검증을 실제 Engine 합격으로 승격하지 않는다.
- VS-3~VS-8의 domain/runtime 규칙은 상당 부분 reference verified이고 AudioComponent source
  boundary와 asset-free diagnostic Pawn/camera entrypoint도 연결됐지만, UE compile·실기기
  재생과 Anim Blueprint 증거 전에는 완료로 표시하지 않는다.
- VS-9의 canonical G0 원본 패키지는 준비됐지만 MetaHuman 조형/교체는 미착수다.
  VS-10 World action adapter source는 연결됐고 실제 Map/NavMesh/Montage 증거는 남아 있다.
