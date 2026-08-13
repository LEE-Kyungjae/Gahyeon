# Gahyeon

[한국어](README.md) · [English](README.en.md) · [日本語](README.ja.md)

Gahyeon은 지속 기억, 음성, 자율 행동과 영속 World를 갖는 모듈형 embodied AI
agent입니다. Discord Bot에 화면을 덧붙이는 프로젝트가 아니라, 하나의 독립 Core를
여러 Client/Adapter가 사용하는 구조를 지향합니다.

> 우선순위는 그래픽 데모가 아니라 저지연 실시간 AI 캐릭터 아키텍처입니다.
> LLM 응답 중에도 Reflex, Behavior, Cognition은 서로를 막지 않고 계속 동작해야 합니다.

```text
                         Gahyeon Core
 Conversation · Memory · STT/TTS · Tools · Session
        Emotion · Behavior · Persistent World
                              │
                   Event · HTTP · WebSocket
          ┌───────────────────┼───────────────────┐
          ▼                   ▼                   ▼
 Discord Adapter     Desktop Compatibility    Unreal Stage
                      Three.js / VRM          AAA target
                                                  │
                                      Monitor · Looking Glass
```

Core가 무엇을 말하고 기억하며, 어떤 감정·행동·이동을 선택할지 결정합니다.
Presentation은 음성, 표정, 립싱크, 애니메이션과 렌더링으로 이를 표현합니다.

## 현재 상태

| 영역 | 현재 증거 |
|---|---|
| Core/Application | Discord와 분리된 Conversation, Session, Speech port, Event, World/Behavior 경계 구현 |
| Headless | Discord·LLM provider 없이 API와 영속 World 실행 가능 |
| Discord Adapter | 기존 Slash Command, 텍스트·음성 대화, 음악·운영 기능 유지 |
| Desktop 호환 Client | Electron/Vue/Three.js 기반 텍스트·마이크·오디오·VRM·World 경로 구현 |
| Unreal Backend Adapter | 조건부 WebSocket v1, replay/cursor/snapshot, streaming speech 구현 |
| Unreal RuntimeCore | 엔진 비종속 C++20 Reflex/Behavior/Cognition, VAD, speech, viseme, World와 저장/재연결 harness 구현 |
| Unreal Stage | UE 5.6 source project, native runtime/ingress, asset-free 진단 Pawn·카메라 구현; Editor build, MetaHuman, NavMesh와 packaged build는 미검증 |
| Looking Glass | Desktop WebXR 경로 구현, 실제 Go 장치 합격 검증은 남음 |
| Voice 제작 | 중복 제거된 5,000문장 Voicebox teacher corpus 생성 중; 완료 후 음향/STT/화자 QC, Piper 단계별 학습과 blind review까지 자동 인계 |
| Character 제작 | SDXL LoRA 학습·비교 완료; 사용자 원본을 canonical identity로 고정한 G0 pack과 G1 modeling handoff/draft 준비, 최종 hero mesh는 미완성 |

Reference runtime 통과와 실제 Unreal 합격은 구분합니다. 느린 보조 renderer 격리까지
포함한 RT-01~RT-13의 현재 증거와 실기기 미검증 항목은
[Acceptance 상태표](docs/unreal/ACCEPTANCE_STATUS.md)에 있습니다.

## 설계 원칙

- Discord, Desktop, Unreal은 Core의 Client/Adapter입니다.
- Core domain은 JDA, Electron, Unreal, Spring Web 또는 특정 AI provider 타입을 참조하지 않습니다.
- LLM은 semantic intent를 만들며 frame 단위 transform이나 animation asset ID를 선택하지 않습니다.
- Reflex, Behavior, Cognition은 서로 다른 시간축에서 병렬로 동작합니다.
- Renderer가 없어도 Core World와 Headless Behavior는 진행됩니다.
- 네트워크 callback은 Game Thread 상태를 직접 수정하지 않습니다.
- durable cursor와 action outbox는 저장 성공 후에만 ACK/송신합니다.
- Memory는 무엇을 기억하는지, World State는 현재 어디서 무엇을 하는지 담당합니다.

## 요구 환경

- Java 21
- Node.js 20 이상과 npm
- 운영 환경: PostgreSQL 16 권장
- 개발 기본값: PostgreSQL compatibility mode의 in-memory H2
- Unreal Stage 개발 시: Unreal Engine 5.6과 호환 MetaHuman plugin

## 빠른 시작

### 1. 검증

```bash
./gradlew test
python3 scripts/verify_core_platform_boundaries.py
./scripts/test_unreal_runtime_core.sh
./scripts/verify_unreal_stage_scaffold.sh
./scripts/verify_unreal_protocol_contract.sh
./scripts/test_run_unreal_engine_gate.sh
./scripts/test_smoke_headless_core.sh

cd desktop
npm ci
npm test
npm run build
```

### 2. Headless Core 실행

Headless Core는 Discord/Spotify/OpenAI credential 없이 독립적으로 실행됩니다.
해당 Adapter나 Provider를 활성화할 때만 필요한 값을 설정합니다.

```bash
BOT_ENABLED=false \
WEATHER_PREFETCH_ENABLED=false \
GAHYEON_HEADLESS_ENABLED=true \
GAHYEON_BEHAVIOR_ENABLED=true \
TTS_ENABLED=false \
./gradlew bootRun
```

실제 Discord 비활성 무자격 기동과 health·World revision HTTP smoke는
`./scripts/smoke_headless_core.sh`로 한 번에 재현할 수 있습니다.
이 무자격 smoke에서 Conversation readiness는 의도대로 `DOWN`이고 DB·World 동작은 검증됩니다.
배포 JAR까지 검증하려면 `GAHYEON_HEADLESS_SMOKE_MODE=jar ./scripts/smoke_headless_core.sh`를
사용합니다.
느린 개발 머신에서는 `GAHYEON_HEADLESS_SMOKE_STARTUP_TIMEOUT`으로 기동 제한 시간을 조정할 수
있으며 허용 범위는 30~900초입니다.
실제 Docker 이미지 경계까지 검증하려면 `./scripts/smoke_headless_container.sh`를 실행합니다.
이 검사는 임시 컨테이너에서 Discord를 비활성화하고 health와 World revision 변경을 확인한 뒤
자신이 만든 컨테이너만 정리합니다.

기본 API root는 `http://127.0.0.1:8080/api`입니다. 토큰이 없으면 Client API는
loopback 요청만 허용합니다.

### 3. Desktop 개발 Client 실행

다른 터미널에서:

```bash
cd desktop
npm ci
GAHYEON_CORE_API_URL=http://127.0.0.1:8080/api npm run dev
```

원격 Core를 사용할 때는 양쪽에 같은 고엔트로피 `GAHYEON_CLIENT_TOKEN`을 설정하세요.
VRM/VRMA와 환경 asset 설정은 [`desktop/.env.example`](desktop/.env.example)을 참고합니다.

### 4. LLM 대화 활성화

```bash
GAHYEON_AGENT_PROVIDER=openai \
AGENT_API_KEY='<key>' \
AGENT_BASE_URL='https://openrouter.ai/api' \
AGENT_MODEL='<model>' \
GAHYEON_HEADLESS_ENABLED=true \
BOT_ENABLED=false \
./gradlew bootRun
```

provider/model이 tool call과 발화 텍스트를 안전하게 분리하는지 검증하기 전에는
`GAHYEON_AGENT_TOOL_SAFE_STREAMING_ENABLED`를 켜지 마세요.

## Discord 호환 Adapter

```bash
BOT_ENABLED=true \
TOKEN='<discord-token>' \
APPLICATION_ID='<application-id>' \
GAHYEON_AGENT_PROVIDER=openai \
AGENT_API_KEY='<key>' \
./gradlew bootRun
```

기존 `/설정`, `/가현아`와 나가기·음악·운영 Slash Command 경로를 유지합니다.
음성 대화는 `TEN VAD → STT → Conversation → TTS`를 사용하며 Conversation과 Speech
domain은 Discord 객체를 직접 참조하지 않습니다.

`BOT_ENABLED=true`이면 Discord token 누락·거부 또는 초기화 실패가 애플리케이션 프로세스를
종료하지는 않지만 `/api/health`와 Actuator Discord health는 `FAILED`/DOWN으로 닫힙니다.
Blue/Green follower가 PostgreSQL advisory lock을 정상적으로 기다리는 경우만 `STANDBY`/UP이며,
`BOT_ENABLED=false`는 명시적인 `DISABLED`/UP 상태입니다.

## Unreal Stage

Backend WebSocket Adapter와 C++20 RuntimeCore는 준비되어 있지만 기본값은 비활성화되어
있습니다. UE 5.6 Editor/packaged build 검증 전에는 운영에서 켜지 않습니다.

UE 5.6이 설치된 개발 장비의 실제 gate:

```bash
GAHYEON_UE_ROOT="/path/to/UE_5.6" ./scripts/run_unreal_engine_gate.sh
```

GTX 1660 Ti Windows 제작 장비에서는 PowerShell로 canonical Stage를 먼저 검증합니다.

```powershell
.\scripts\run_unreal_engine_gate.ps1 -UnrealRoot "C:\Program Files\Epic Games\UE_5.6"
```

Editor 검증 후 packaged Development까지 생성·봉인하려면 `-Package`를 추가합니다.
생성된 packaged 폴더의 10분 실측은 다음 runner가 실행·집계·검증합니다.

```powershell
.\scripts\run_desktop_realtime_acceptance.ps1 `
  -PackagedRoot "C:\gahyeon-package" `
  -EvidenceRoot "C:\gahyeon-evidence\desktop-0001"
```

- [Unreal 아키텍처](docs/unreal/ARCHITECTURE.md)
- [Protocol v1](docs/unreal/PROTOCOL_V1.md)
- [Adapter 통합 계약](docs/unreal/ADAPTER_INTEGRATION.md)
- [Vertical Slice 순서](docs/unreal/VERTICAL_SLICE.md)
- [실시간 Acceptance](docs/unreal/REALTIME_ACCEPTANCE.md)
- [개발 환경 준비 상태](docs/unreal/READINESS.md)

## 주요 설정

| 변수 | 의미 | 기본값 |
|---|---|---|
| `BOT_ENABLED` | Discord Adapter 연결 | `true` |
| `GAHYEON_HEADLESS_ENABLED` | Headless/Desktop API | `false` |
| `GAHYEON_CLIENT_TOKEN` | 원격 Client bearer 인증 | 없음; loopback만 허용 |
| `GAHYEON_BEHAVIOR_ENABLED` | Core 자율 행동 scheduler | `false` |
| `GAHYEON_UNREAL_WEBSOCKET_ENABLED` | Unreal WebSocket endpoint | `false` |
| `GAHYEON_UNREAL_COGNITION_*` | Unreal Cognition worker/queue 상한 | 작은 bounded pool |
| `GAHYEON_UNREAL_TTS_*` | Unreal TTS worker/queue 상한 | 작은 bounded pool |
| `GAHYEON_UNREAL_VISEME_ALIGNER_*` | exact lip-sync HTTP aligner, 250ms playback deadline와 전용 bounded pool | 비활성화 |
| `GAHYEON_UNREAL_SPEECH_SEGMENT_MAX_CHARACTERS` | streaming TTS 문장 분할 상한 | `120` |
| `GAHYEON_AGENT_PROVIDER` | Spring AI chat provider | `none` |
| `GAHYEON_AGENT_PROVIDER_FAILURE_COOLDOWN_MILLIS` | 모델 provider 실패 후 recovery probe 대기 | `5000` |
| `GAHYEON_CONTENT_SAFETY_PROVIDER` | 교체 가능한 입력 안전성 Adapter (`openai`, `none`) | `openai` |
| `GAHYEON_CONTENT_SAFETY_CONNECT_TIMEOUT_MILLIS` / `READ_TIMEOUT_MILLIS` | 입력 안전성 provider 지연 상한(각 100~5000ms) | `300` / `700` |
| `GAHYEON_CONTENT_SAFETY_FAILURE_COOLDOWN_MILLIS` | 안전성 provider 실패 후 단일 recovery probe 대기 | `30000` |
| `GAHYEON_AGENT_TOOL_SAFE_STREAMING_ENABLED` | 검증된 provider의 token streaming | `false` |
| `GAHYEON_AGENT_STREAMING_VERIFIED_BASE_URL` | streaming probe를 통과한 정확한 provider base URL | 없음 |
| `GAHYEON_AGENT_STREAMING_VERIFIED_MODEL` | streaming probe를 통과한 정확한 model ID | 없음 |
| `AGENT_API_KEY`, `AGENT_BASE_URL`, `AGENT_MODEL` | LLM endpoint | provider별 설정 |
| `ASSISTANT_STT_*`, `ASSISTANT_VAD_*` | Discord 음성 인식과 VAD | 환경별 설정 |
| `TTS_PROVIDER` | `voicebox`, `edge`, `custom` | `voicebox` |

음성 설정과 fallback은 [Custom Voice TTS](docs/CUSTOM_VOICE_TTS.md)를 참고하세요.

## 저장소 구조

```text
src/main/java/com/gahyeonbot/
├─ core/          framework/platform 독립 domain
├─ application/   use case, port, orchestration
└─ adapters/      Discord, Desktop, Headless, Unreal, provider 구현

desktop/          Electron/Vue/Three.js 호환 Presentation Client
unreal/RuntimeCore/ 엔진 비종속 C++20 실시간 reference runtime
unreal/GahyeonStage/ UE 5.6 source-only Stage project와 native module
docs/unreal/      Unreal architecture, protocol, acceptance와 integration 계약
scripts/          Voice/Piper, SDXL asset pipeline과 운영 보조 도구
```

## 명칭과 호환성

제품·캐릭터·아키텍처의 공식 명칭은 **Gahyeon**입니다. `com.gahyeonbot` Java package,
기존 database/container 이름, GHCR 경로와 일부 service file의 `gahyeonbot` 문자열은 운영
마이그레이션을 깨뜨리지 않기 위한 legacy identifier입니다. 이 값은 새 제품명을 의미하지 않으며,
저장소·배포 경로를 함께 이전하는 별도 migration 전까지 임의로 일괄 변경하지 않습니다.

## 문서

- [전체 아키텍처](docs/ARCHITECTURE.md)
- [Core 분리 진행 기록](docs/GAHYEON_CORE_MIGRATION.md)
- [API](docs/API.md)
- [Desktop](desktop/README.md)
- [AIRI 분석](docs/AIRI_DESKTOP_ANALYSIS.md)
- [AAA Character Pipeline](docs/AAA_CHARACTER_PIPELINE.md)
- [Character 품질 Gate](docs/GAHYEON_CHARACTER_QUALITY_GATES.md)
- [G1 Modeling Handoff](docs/GAHYEON_G1_MODELING_HANDOFF.md)
- [Unreal Acceptance 상태](docs/unreal/ACCEPTANCE_STATUS.md)
- [Looking Glass](docs/LOOKING_GLASS.md)
- [음성](docs/CUSTOM_VOICE_TTS.md)
- [배포](docs/DEPLOYMENT.md)

## 보안과 asset

비밀키, 원본 음성, 학습 checkpoint, 라이선스가 있는 VRM/VRMA/MetaHuman/환경 asset은
Git이나 container image에 포함하지 마세요. 배포 환경의 secret과 별도 artifact storage를
사용합니다.

SDXL/생성 draft는 canonical 얼굴 근거가 아닙니다. 캐릭터 identity authority는 checksum으로
고정된 사용자 원본 pack이며, 생성된 G1 시트의 추정 영역과 승인 상태는 별도 manifest로 관리합니다.
