# Gahyeon 아키텍처

## 목적

Gahyeon은 Discord Bot이 아니라 지속 기억, 음성, 자율행동과 World를 가진 독립 AI
Agent다. Discord, Desktop, Headless API와 Unreal Stage는 같은 Core에 접근하는
Client/Adapter이며 어느 플랫폼도 Conversation·Memory·World의 정본이 아니다.

최우선 품질 기준은 그래픽 데모가 아니라 저지연 실시간 동작이다. Reflex, Behavior,
Cognition은 서로 다른 시간 규모로 병렬 진행하며 LLM이 늦거나 연결이 끊겨도 캐릭터의
시선·호흡·미세 움직임과 로컬 안전 동작은 멈추지 않는다.

## 시스템 경계

```text
 Discord I/O ─ Discord Adapter ─┐
 Desktop UI ─ Desktop Adapter ──┼── Application Use Cases ── Core Domain
 Headless API ─ Headless Adapter┤             │
 Unreal Stage ⇄ WS/HTTP Adapter ┘             ├─ Conversation / Session / Identity
                                              ├─ Memory / Event
                                              ├─ STT / TTS ports
                                              ├─ Emotion / Behavior
                                              └─ Persistent World State

 Unreal Stage
 ├─ Reflex and ambient motion
 ├─ Behavior presentation and navigation
 ├─ Audio / lip sync / animation
 └─ Hero renderer (MetaHuman/custom asset target)
```

Core와 Application은 JDA, Spring MVC/WebSocket, Electron, Unreal 또는 특정 STT/TTS
제공자 타입을 참조하지 않는다. 이 규칙은
`scripts/verify_core_platform_boundaries.py`와 CI에서 검사한다.

## 소스 구조

| 영역 | 위치 | 책임 |
|---|---|---|
| Core Domain | `src/main/java/com/gahyeonbot/core/` | 플랫폼 중립 타입, 정책, 포트 |
| Application | `src/main/java/com/gahyeonbot/application/` | Conversation·Identity·World·Behavior 유스케이스 조정 |
| Adapters | `src/main/java/com/gahyeonbot/adapters/` | Desktop, Headless, Discord, Unreal, DB, Speech 연결 |
| Legacy compatibility | `commands/`, `listeners/`, 일부 `services/` | 기존 Discord 명령 호환; 신규 도메인 정본이 아님 |
| Desktop | `desktop/` | Electron/Vue UI, 마이크·스피커, 경량 3D 호환 renderer |
| Unreal Stage | `unreal/GahyeonStage/` | 고품질 실시간 Presentation Client |
| RuntimeCore | `unreal/GahyeonStage/Source/GahyeonRuntimeCore/` | 엔진 독립 C++20 실시간 상태기 정본 |
| Contracts | `docs/contracts/` | JSON Schema와 canonical fixture |

기존 Java package 이름 `com.gahyeonbot`, DB/GHCR/컨테이너 식별자 일부는 호환 마이그레이션이
끝날 때까지 유지한다. 공개 제품명과 서비스명은 `Gahyeon`이다.

## Core와 Application

### Conversation과 Session

`ConversationSession`은 `sessionId`, 내부 `actorId`, `ClientSource`, modality, context를
가진다. Discord의 guild/channel/user ID는 Adapter가 외부 identity로 해석하며 Conversation
식별자 자체가 되지 않는다. Desktop, Headless와 Unreal도 같은
`IdentityResolutionUseCase`와 `ConversationUseCase`를 사용한다.

Conversation 순서는 다음 불변식을 지킨다.

1. World Presence가 진행 중 자율행동을 취소하고 Conversation ownership을 얻는다.
2. `conversation.started`를 발행한다.
3. Cognition을 실행하며 가능한 경우 최종 안전 텍스트만 streaming한다.
4. completed/failed/cancelled terminal event를 정확히 한 번 남긴다.
5. lease를 닫고 마지막 동시 대화가 끝났을 때 이전 World activity를 복원한다.

Unreal command는 session별 generation으로 admission한다. 새 generation은 queued/running
Cognition과 TTS를 취소하며 이전 provider callback, HTTP 응답과 audio segment는 다시
Presentation을 되살릴 수 없다.

입력 안전성도 `ContentSafetyPort`로 분리한다. Conversation admission은 `SAFE`, `UNSAFE`,
`UNAVAILABLE` 결정만 소비하며 OpenAI Moderation의 HTTP/auth/JSON은 Adapter 내부에 남는다.
외부 안전성 provider가 없거나 실패해도 결정론적 local policy는 계속 적용된다.
대화 활성 여부는 credential 문자열이 아니라 `AgentRuntime.isReady()`가 결정한다. 따라서
로컬 모델이나 다른 인증 방식을 추가해도 admission 코드는 provider 설정을 해석하지 않는다.

### Speech

Core는 `TranscriptionUseCase`, `StreamingTranscriptionPort`, `SpeechSynthesisUseCase` 같은
교체 가능한 경계를 소유한다. Discord와 Unreal은 PCM/WAV 또는 텍스트만 전달하며 제공자
객체를 Core로 넘기지 않는다.

Unreal 음성 경로는 다음 두 STT 모드를 가진다.

- 별도 인증 WebSocket을 사용하는 bounded streaming STT
- 300ms pre-roll과 30초 상한을 가진 batch WAV fallback

TTS는 문장 단위로 먼저 준비해 LLM 전체 응답을 기다리지 않는다. Audio와 viseme timeline은
분리되며 exact aligner가 deadline 안에 응답하지 않으면 RMS/한국어 모음 heuristic fallback을
사용한다. exact timing이 아닌 fallback 결과는 RT-08 최종 합격 증거로 사용하지 않는다.

### Memory, Event와 Persistence

Memory는 가현이 무엇을 기억하는지 담당하고 World State는 현재 어디서 무엇을 하는지
담당한다. 둘은 같은 개념으로 합치지 않고 event를 통해 협력한다.

대화 종료 시 원문 저장은 Cognition 호출 스레드의 트랜잭션 안에서 먼저 완료한다. 오래된
대화 압축은 그 트랜잭션의 commit 이후에만 전용 단일 worker에 예약하며, 외부 요약 provider
호출은 응답·TTS 경로에서 실행하지 않는다. worker queue와 회당 batch는
`GAHYEON_MEMORY_COMPACTION_QUEUE_CAPACITY`, `GAHYEON_MEMORY_COMPACTION_BATCH_SIZE`로 제한한다.
같은 actor의 대기 요청은 합치고 queue 포화나 provider 실패 시 레코드를 미요약 상태로 남겨
다음 대화가 다시 예약할 수 있게 한다. 따라서 선택적 장기 압축의 지연·실패가 저장된 대화나
사용자 응답 전달을 되돌리지 않는다. 요약 모델은 `MemorySummarizationPort` 뒤에서 교체한다.

PostgreSQL/Flyway 경계에는 identity, conversation/agent ledger, durable event,
World snapshot과 World action ledger가 있다. durable event는 session/world scope와 증가하는
sequence를 가지며 renderer는 로컬 저장 완료 후에만 ACK한다.

Backend 재시작 시 in-memory lease가 사라진 `CONVERSATION`과 `ATTENTION`은 영속 활동으로
간주하지 않는다. Behavior가 활성화된 시작 단계에서 이 두 orphan 상태만 `IDLE`로 revision을
전진시키고 `world.state.restored` snapshot을 발행한다. Work/Read/Sleep 같은 생활 상태는
그대로 보존한다. 복구가 끝날 때까지 Behavior와 Headless action scheduler는 공통 readiness
gate에서 fail-closed하며 Actuator의 `worldRuntime` health가 `OUT_OF_SERVICE`로 이를 노출한다.

### Behavior와 World Action

`DeterministicBehaviorPolicy`가 시간, 현재 activity와 World의 semantic interaction point를
기준으로 Idle/Work/Read/Sleep/Relax 등의 고수준 결정을 만든다. LLM은 프레임 단위 좌표나
animation asset ID를 선택하지 않는다.

World action의 authority는 다음과 같이 나뉜다.

- Core: 목표, revision, timeout, idempotency와 최종 World commit
- Renderer: 실제 NavMesh 도착, path failure, interaction montage 관측
- Headless executor: 해당 World에 renderer가 없을 때 지속성 유지

Renderer 연결 중에는 Headless가 예상 시간만으로 먼저 완료하지 않는다. 연결이 끊기면
영속 pending action을 이어받는다. 사용자 대화는 pending 자율행동을 먼저 취소하며 늦은
renderer callback은 terminal action ID/revision으로 격리된다. Application의
`WorldActionPresentationPresence` 포트가 이 World별 ownership을 Adapter로부터 전달한다.

## 실시간 계층

| 계층 | 목표 지연 | LLM 의존 | 예 |
|---|---:|---|---|
| Reflex | 16–100ms | 없음 | VAD start, eye/head LookAt, barge-in, audio stop |
| Behavior | 100–500ms | 없음 | Listening/Thinking pose, gesture, posture, navigation |
| Cognition | 500ms–수초 | 있음 | LLM, Memory 검색, tool, 장기 결정 |

RuntimeCore의 mailbox, generation arbiter와 monotonic clock은 Game Thread 단일 소유권을
유지한다. network/audio/provider thread는 bounded queue에만 기록한다. 포화 시 durable
event를 조용히 버리지 않고 backpressure 또는 reconnect를 요청한다.

상시 secondary motion은 breathing, blink, saccade, micro-head와 weight shift를 seed 기반으로
생성한다. Backend가 없어도 계속 동작하고 emotion, gesture, attention, lip sync가 필요한
채널만 layer로 덮는다.

## Unreal Presentation Client

Unreal Stage는 UE 5.6 기준 source project다. AI 판단을 포함하지 않고 semantic event를
Control Rig/Animation/Audio/World 표현으로 변환한다.

```text
Base locomotion
+ posture
+ upper-body gesture
+ head / eyes
+ emotion / face
+ lip sync
+ procedural secondary motion
```

현재 source에는 WebSocket reconnect/cursor/snapshot, SaveGame, voice capture, STT/TTS,
attention, emotion, gesture, audio playback, World action과 source-only prototype room이 있다.
prototype room은 행동 통합 fixture이며 최종 환경 그래픽이 아니다. 실제 UE Editor compile,
PIE, MetaHuman/hero asset, NavMesh bake와 target GPU 성능은 별도 물리 acceptance가 필요하다.

상세 계약:

- [`unreal/ARCHITECTURE.md`](unreal/ARCHITECTURE.md)
- [`unreal/PROTOCOL_V1.md`](unreal/PROTOCOL_V1.md)
- [`unreal/REALTIME_ACCEPTANCE.md`](unreal/REALTIME_ACCEPTANCE.md)
- [`unreal/ACCEPTANCE_STATUS.md`](unreal/ACCEPTANCE_STATUS.md)

## Client별 책임

### Discord Adapter

JDA message/interaction/voice 입력을 플랫폼 중립 request/audio로 바꾸고 결과를 Discord로
출력한다. 기존 슬래시 명령, 음악, moderation과 voice-channel 호환 기능은 Adapter 아래에
유지한다. 신규 Core 코드가 JDA event, guild 또는 channel 객체를 직접 받으면 안 된다.

### Desktop Client

Electron/Vue client는 텍스트 대화, 마이크, 스피커와 경량 3D Stage를 제공한다. durable SSE
cursor, bounded reconnect, request cancellation과 stale generation fence를 가진다. Three/VRM
경로는 일반 PC 호환 client이며 최종 AAA 품질 상한이 아니다.

### Headless

UI 없이 Conversation/World/Speech 계약을 실행하고 자동화·통합 테스트에 사용한다. Renderer가
없어도 Behavior와 World 시간이 진행된다는 것을 보장한다.

### Looking Glass

별도 Gahyeon 인스턴스가 아니라 같은 World/Avatar 상태를 소비하는 추가 renderer다. Desktop
또는 Unreal의 일반 perspective 렌더링과 light-field 렌더링은 분리하며 장치가 없어도 기본
client가 정상 동작해야 한다. 물리 장치 acceptance는 아직 완료되지 않았다.

## Protocol과 안정성 불변식

- envelope: `gahyeon.unreal.v1`, schema version 1
- durable/ephemeral/command delivery를 명시적으로 구분
- durable cursor는 persist-before-ack
- session/world scope 밖 event는 scan cursor로 안전하게 건너뜀
- heartbeat는 correlation을 검증하고 half-open 연결을 재접속
- renderer별 bounded serial outbound queue로 slow display 격리
- malformed event 하나가 다음 valid event를 막지 않음
- generation 증가 후 이전 LLM/STT/TTS/audio/gesture/action callback 적용 0회
- frame-by-frame transform과 animation asset ID를 네트워크로 보내지 않음

## 기술 기준

- Java 21, Spring Boot 3.5.6, Gradle
- Spring AI 1.0.1
- PostgreSQL 42.7.4, H2 test, Flyway
- JDA 6.4.1, Lavaplayer 2.2.2
- Electron/Vue/TypeScript Desktop
- Unreal Engine 5.6 target, C++20 RuntimeCore
- Micrometer/Prometheus metrics

버전의 정본은 `build.gradle`, `desktop/package.json`과
`unreal/GahyeonStage/GahyeonStage.uproject`다.

## 검증

```bash
./gradlew test
python3 scripts/verify_core_platform_boundaries.py
python3 scripts/verify_api_documentation.py
bash scripts/test_unreal_runtime_core.sh
bash scripts/verify_unreal_stage_scaffold.sh
bash scripts/verify_unreal_protocol_contract.sh
```

정적 Stage 검증과 RuntimeCore harness 통과는 실제 UE 5.6 compile/PIE 증거를 대신하지 않는다.
실기 acceptance와 캐릭터 품질 gate는 각각 Unreal 문서와
캐릭터 제작 트랙에서 작성 중인 `GAHYEON_CHARACTER_QUALITY_GATES.md` 계약을 따른다. 해당 문서는 캐릭터 소스와 함께 별도 반영한다.

## 배포와 호환 마이그레이션

Spring profile과 feature flag로 Discord, Headless, Unreal WebSocket/streaming STT를 독립적으로
활성화한다. 새 Core 기능은 기존 Discord 명령을 한 번에 제거하지 않고 Adapter 뒤로 이동한다.
DB schema는 Flyway로 전진하며 package/database/container 식별자 변경은 배포 설정·migration·
rollback을 함께 준비한 별도 작업으로 수행한다.

## 관련 문서

- [`API.md`](API.md)
- [`GAHYEON_CORE_MIGRATION.md`](GAHYEON_CORE_MIGRATION.md)
- [`CUSTOM_VOICE_TTS.md`](CUSTOM_VOICE_TTS.md)
- `AAA_CHARACTER_PIPELINE.md` *(캐릭터 제작 트랙에서 작성 중)*
- `GAHYEON_G1_MODELING_HANDOFF.md` *(캐릭터 제작 트랙에서 작성 중)*
- [`adr/`](adr/)
