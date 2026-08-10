# Gahyeon

[한국어](README.md) · [English](README.en.md)

Gahyeon은 지속적인 기억, 음성, 자율 행동과 살아 있는 3D World를 가진
모듈형 embodied AI agent입니다. Discord Bot에 Desktop을 덧붙인 구조가
아닙니다. 하나의 Gahyeon Core가 판단하고 Discord와 Desktop은 서로 다른
입출력 Adapter로 동작합니다.

```text
                       Gahyeon Core
 Conversation · Memory · STT/TTS · Tools · Session
          Emotion · Behavior · Persistent World
                             │
                       Event / HTTP API
                  ┌──────────┴──────────┐
                  ▼                     ▼
          Discord Adapter        Desktop Client
                                      │
                            Desktop / Looking Glass
```

## 현재 구현

| 영역 | 상태 |
| --- | --- |
| 독립 Headless Core | Discord와 LLM 공급자 없이 기동 가능 |
| Discord | 텍스트, Slash Command, 음성, 음악 및 운영 기능을 Adapter로 유지 |
| Desktop | 한국어/영어 UI, 텍스트·마이크·스피커, VRM, 표정, 립싱크, 이동 World |
| 행동과 World | 결정론적 FSM, Interaction Point, 위치·방·활동·감정 영속화 |
| 렌더링 | 일반 모니터 기본, 동일 World State를 사용하는 Looking Glass WebXR 선택 지원 |
| 음성 | 교체 가능한 STT 및 Voicebox/Edge/custom TTS, Piper 증류 연구 도구 |

실제 캐릭터 VRM/VRMA와 완성형 환경 에셋은 라이선스가 있는 파일을 별도로
공급해야 합니다. 에셋이 없어도 절차형 캐릭터와 World fallback으로 전체
흐름을 실행할 수 있습니다.

## 빠른 시작: Core + Desktop

요구 환경은 Java 21 이상, Node.js 20 이상입니다.

```bash
./gradlew clean test
GAHYEON_HEADLESS_ENABLED=true \
GAHYEON_BEHAVIOR_ENABLED=true \
BOT_ENABLED=false \
./gradlew bootRun
```

다른 터미널에서:

```bash
cd desktop
npm install
npm test
npm run dev
```

원격 연결은 Core와 Desktop 양쪽에 동일한 고엔트로피
`GAHYEON_CLIENT_TOKEN`을 설정해야 합니다. 토큰이 없으면 Gahyeon API는
loopback 요청만 허용합니다.

LLM 대화를 켜려면:

```bash
GAHYEON_AGENT_PROVIDER=openai \
AGENT_API_KEY='<key>' \
AGENT_BASE_URL='https://openrouter.ai/api' \
AGENT_MODEL='<model>' \
GAHYEON_HEADLESS_ENABLED=true BOT_ENABLED=false ./gradlew bootRun
```

공급자를 설정하지 않아도 World, Event, Desktop과 음성 readiness API는
정상적으로 기동합니다.

## Discord Adapter 실행

```bash
BOT_ENABLED=true \
TOKEN='<discord-token>' \
APPLICATION_ID='<application-id>' \
GAHYEON_AGENT_PROVIDER=openai \
AGENT_API_KEY='<key>' \
./gradlew bootRun
```

`/설정`은 기존 호환 채팅·음성 채널을 구성합니다. `/가현아`와 전용 채팅
메시지는 동일한 `ConversationUseCase`를 호출하며, 음성은
`TEN VAD → STT → Conversation → TTS` 경로를 사용합니다.

## Desktop 배포 빌드

```bash
cd desktop
npm run package   # 현재 OS용 unpacked app
npm run dist      # 설치 배포물
```

출력은 `desktop/release/`에 생성됩니다. 운영 배포의 코드 서명과 공증
인증서는 저장소가 아닌 release 환경에서 주입합니다.

## 핵심 설정

| 변수 | 용도 | 기본값 |
| --- | --- | --- |
| `BOT_ENABLED` | Discord 연결 | `true` |
| `GAHYEON_HEADLESS_ENABLED` | Headless/Desktop HTTP Adapter | `false` |
| `GAHYEON_CLIENT_TOKEN` | 원격 Client Bearer 인증 | 없음, loopback만 허용 |
| `GAHYEON_BEHAVIOR_ENABLED` | 자율 행동 coordinator | `false` |
| `GAHYEON_AGENT_PROVIDER` | `openai` 호환 Agent Adapter | `none` |
| `AGENT_API_KEY`, `AGENT_BASE_URL`, `AGENT_MODEL` | LLM 공급자 | 공급자별 설정 |
| `WEATHER_PREFETCH_ENABLED` | 날씨 워밍업과 주기 갱신 | `BOT_ENABLED` 값 |
| `ASSISTANT_STT_*`, `ASSISTANT_VAD_*` | STT와 발화 검출 | 환경별 설정 |
| `TTS_PROVIDER` | `voicebox`, `edge`, `custom` | 설정 참조 |

전체 음성 설정은 [Custom Voice TTS](docs/CUSTOM_VOICE_TTS.md)를 참고하세요.
비밀키, 음성 원본, 모델 파일은 저장소나 컨테이너 이미지에 포함하지 마세요.

## 구조와 코드 위치

- `src/main/java/com/gahyeonbot/core`: 프레임워크·플랫폼 독립 타입과 정책
- `src/main/java/com/gahyeonbot/application`: Use Case와 orchestration
- `src/main/java/com/gahyeonbot/adapters`: Discord, Desktop, Headless 및 공급자 경계
- `desktop/electron`: native lifecycle, 인증된 transport와 좁은 preload bridge
- `desktop/src/stage`: renderer-neutral 상태와 Three/VRM 표현
- `desktop/src/audio`: 녹음, 재생과 presentation-only lip sync

Core가 말·기억·감정·행동·이동을 결정하고 Presentation은 이를 표현합니다.
Core dependency 검사는 JDA, Spring Web 및 공급자 import가 Core로 역류하는
것을 막습니다.

## 검증

```bash
./gradlew clean test
cd desktop && npm test && npm run build
```

## 문서

- [Core 개편과 구현 현황](docs/GAHYEON_CORE_MIGRATION.md)
- [아키텍처](docs/ARCHITECTURE.md)
- [Desktop](desktop/README.md)
- [AIRI 분석](docs/AIRI_DESKTOP_ANALYSIS.md)
- [VRM 애니메이션](docs/VRM_ANIMATION.md)
- [Looking Glass](docs/LOOKING_GLASS.md)
- [API](docs/API.md)
- [배포](docs/DEPLOYMENT.md)

프로젝트는 [MIT License](LICENSE)를 따릅니다.
