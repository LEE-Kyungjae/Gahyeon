# Gahyeon

[한국어](README.md) · [English](README.en.md) · [日本語](README.ja.md)

[![Build](https://github.com/LEE-Kyungjae/Gahyeon/actions/workflows/build-test.yml/badge.svg)](https://github.com/LEE-Kyungjae/Gahyeon/actions/workflows/build-test.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Gahyeon은 기억하고, 듣고, 말하고, 스스로 행동하는 실시간 AI 캐릭터를 만드는
오픈소스 프로젝트입니다. 대화와 기억을 담당하는 독립적인 Core를 중심에 두고,
Discord·Desktop·Unreal을 서로 교체할 수 있는 접점으로 연결합니다.

이 프로젝트의 목표는 Discord 봇에 3D 화면을 덧붙이는 것이 아닙니다. 같은 Gahyeon이
Discord에서는 음성 비서로, Desktop에서는 생활형 캐릭터로, Unreal에서는 고품질 실시간
캐릭터로 나타날 수 있는 구조를 만드는 것입니다.

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

Core는 무엇을 말하고 기억할지, 어떤 감정과 행동을 선택할지 결정합니다. 각 클라이언트는
그 결과를 음성·표정·립싱크·애니메이션·화면으로 표현합니다.

## 현재 상태

| 영역 | 구현 및 검증 상태 |
|---|---|
| Core/Application | 대화·세션·음성·이벤트·기억·World/Behavior를 Discord에서 분리 |
| Headless | Discord 없이 API와 지속 World를 실행할 수 있음. 실제 대화에는 별도 LLM 설정 필요 |
| Discord Adapter | 기존 슬래시 명령어, 텍스트·음성 대화, 음악 및 운영 기능 유지 |
| Desktop Client | Electron/Vue/Three.js 기반 텍스트·마이크·오디오·VRM·World 흐름 구현 |
| Unreal 연결 | WebSocket v1, 재연결, 이벤트 재생, snapshot, streaming speech 구현 |
| 실시간 RuntimeCore | 엔진과 분리된 C++20 Reflex/Behavior/Cognition, VAD, 음성, viseme, World 테스트 구현 |
| Unreal Stage | UE 5.6 소스 프로젝트와 진단용 Pawn·카메라 구현. 실제 MetaHuman/패키징 검증은 남음 |
| Looking Glass | Desktop WebXR 및 Unreal 어댑터 구현. 실제 Go 장치 검증은 남음 |
| 음성 제작 | 중복을 줄인 5,000문장 생성 중. 완료 후 QC와 Piper 학습·청취 평가로 자동 인계 |
| 캐릭터 제작 | SDXL LoRA 비교와 원본 기반 identity 기준 수립 완료. 최종 hero mesh는 제작 중 |

테스트용 RuntimeCore 통과와 실제 Unreal 환경 통과는 구분합니다. RT-01~RT-13의 자동화
검증 결과와 실제 장비에서 확인해야 할 항목은
[Acceptance 상태표](docs/unreal/ACCEPTANCE_STATUS.md)에 있습니다.

## 설계 원칙

- Discord, Desktop, Unreal은 모두 Core에 연결되는 클라이언트입니다.
- Core 영역은 JDA, Electron, Unreal, Spring Web이나 특정 AI 제공자의 타입에 의존하지 않습니다.
- LLM은 행동의 의도만 정하며, 프레임별 좌표나 애니메이션 파일을 직접 고르지 않습니다.
- Reflex, Behavior, Cognition은 서로 다른 시간축에서 병렬로 동작합니다.
- 렌더러가 없어도 Core의 World와 자율 행동은 계속됩니다.
- 네트워크 콜백은 Unreal Game Thread의 상태를 직접 바꾸지 않습니다.
- 이벤트 커서와 행동 결과는 저장이 성공한 뒤에만 확인 응답을 보냅니다.
- Memory는 과거의 기억을, World State는 현재 위치와 행동을 담당합니다.

## 요구 환경

- Java 21
- Node.js 20 이상과 npm
- 운영 환경: PostgreSQL 16
- 로컬 테스트 기본값: PostgreSQL 호환 모드의 인메모리 H2
- Unreal Stage 개발: Unreal Engine 5.6 및 호환되는 MetaHuman 플러그인

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

Headless Core는 Discord·Spotify·OpenAI 인증 정보 없이 실행할 수 있습니다.
각 어댑터나 AI 제공자를 사용할 때만 해당 인증 정보를 설정하면 됩니다.

```bash
BOT_ENABLED=false \
WEATHER_PREFETCH_ENABLED=false \
GAHYEON_HEADLESS_ENABLED=true \
GAHYEON_BEHAVIOR_ENABLED=true \
TTS_ENABLED=false \
./gradlew bootRun
```

Discord를 끈 상태의 무자격 기동과 health·World revision 점검은
`./scripts/smoke_headless_core.sh`로 한 번에 재현할 수 있습니다.
이 점검에서는 LLM을 설정하지 않았으므로 Conversation 상태가 의도대로 `DOWN`이며,
DB와 World 동작만 검증합니다.
배포 JAR까지 검증하려면 `GAHYEON_HEADLESS_SMOKE_MODE=jar ./scripts/smoke_headless_core.sh`를
사용합니다.
느린 개발 머신에서는 `GAHYEON_HEADLESS_SMOKE_STARTUP_TIMEOUT`으로 기동 제한 시간을 조정할 수
있으며 허용 범위는 30~900초입니다.
실제 Docker 이미지 경계까지 검증하려면 `./scripts/smoke_headless_container.sh`를 실행합니다.
이 검사는 임시 컨테이너에서 Discord를 비활성화하고 health와 World revision 변경을 확인한 뒤
자신이 만든 컨테이너만 정리합니다.

기본 API root는 `http://127.0.0.1:8080/api`입니다. 토큰이 없으면 Client API는
loopback 요청만 허용합니다.

### 3. Desktop 개발 클라이언트 실행

다른 터미널에서:

```bash
cd desktop
npm ci
GAHYEON_CORE_API_URL=http://127.0.0.1:8080/api npm run dev
```

원격 Core에 연결할 때는 양쪽에 충분히 긴 동일한 `GAHYEON_CLIENT_TOKEN`을 설정하세요.
VRM/VRMA와 환경 자산 설정은 [`desktop/.env.example`](desktop/.env.example)을 참고합니다.

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

사용할 AI 제공자와 모델이 도구 호출과 발화문을 안전하게 구분하는지 검증하기 전에는
`GAHYEON_AGENT_TOOL_SAFE_STREAMING_ENABLED`를 켜지 마세요.

## Discord 어댑터

```bash
BOT_ENABLED=true \
TOKEN='<discord-token>' \
APPLICATION_ID='<application-id>' \
GAHYEON_AGENT_PROVIDER=openai \
AGENT_API_KEY='<key>' \
./gradlew bootRun
```

기존 `/설정`, `/가현아`, 나가기·음악·운영 슬래시 명령어를 유지합니다. 음성 대화는
`TEN VAD → STT → Conversation → TTS` 순서로 처리하며, 대화와 음성 영역은 Discord 객체를
직접 참조하지 않습니다.

`BOT_ENABLED=true`이면 Discord token 누락·거부 또는 초기화 실패가 애플리케이션 프로세스를
종료하지는 않지만 `/api/health`와 Actuator Discord health는 `FAILED`/DOWN으로 닫힙니다.
Blue/Green follower가 PostgreSQL advisory lock을 정상적으로 기다리는 경우만 `STANDBY`/UP이며,
`BOT_ENABLED=false`는 명시적인 `DISABLED`/UP 상태입니다.

## Unreal Stage

Backend WebSocket 어댑터와 C++20 RuntimeCore는 구현되어 있지만 기본값은 꺼져 있습니다.
UE 5.6 Editor와 패키지 빌드를 실제로 검증하기 전에는 운영에서 활성화하지 않습니다.

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
- AAA Character Pipeline *(캐릭터 제작 트랙에서 작성 중)*
- Character 품질 Gate *(캐릭터 제작 트랙에서 작성 중)*
- `docs/GAHYEON_G1_MODELING_HANDOFF.md` *(캐릭터 제작 트랙에서 작성 중)*
- [Unreal Acceptance 상태](docs/unreal/ACCEPTANCE_STATUS.md)
- [Looking Glass](docs/LOOKING_GLASS.md)
- [음성](docs/CUSTOM_VOICE_TTS.md)
- [배포](docs/DEPLOYMENT.md)

## 보안과 자산

비밀키, 원본 음성, 학습 체크포인트, 라이선스가 있는 VRM/VRMA·MetaHuman·환경 자산은
Git이나 컨테이너 이미지에 넣지 마세요. 배포 환경의 Secret과 별도 아티팩트 저장소를 사용합니다.

SDXL 결과와 생성 초안은 캐릭터 얼굴의 최종 기준이 아닙니다. 캐릭터 identity의 기준은
checksum으로 고정한 사용자 원본 묶음이며, 생성 이미지의 추정 영역과 승인 상태는 별도
manifest로 관리합니다.

## 라이선스

프로젝트의 자체 소스 코드는 [MIT License](LICENSE)로 배포됩니다. 외부 모델, 음성 데이터,
MetaHuman, Looking Glass SDK와 기타 제3자 asset에는 각각의 별도 라이선스가 적용됩니다.
