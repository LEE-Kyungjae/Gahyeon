# Gahyeon

[한국어](README.md) | [English](README.en.md)

Gahyeon은 지속적인 기억, 음성, 자율 행동과 3D World를 갖도록 확장 중인 독립형 AI Agent입니다. Discord와 Desktop은 동일한 Gahyeon Core에 접근하는 Client/Adapter이며, 대화·기억·음성·행동 결정은 특정 UI나 플랫폼에 종속되지 않습니다.

```text
Discord Adapter ─┐
                 ├─ Gahyeon Core ─ Event/API ─ Desktop Client ─ Renderer
Headless API ────┘
```

현재 Discord의 서버 관리·음악·예약 기능은 호환 Adapter로 계속 제공됩니다. Desktop은 텍스트·마이크·스피커·VRM Avatar·World를 제공하며, Looking Glass Go는 같은 World를 표시하는 선택적 Renderer입니다.

## 주요 기능

- **AI 에이전트**: `/가현아` 또는 전용 채팅 채널의 일반 메시지를 AI 에이전트로 전달합니다.
- **독립형 AI Core**: Discord 없이도 Headless/Desktop API를 통해 Conversation, Session, STT, TTS와 World State를 실행합니다.
- **Desktop Client**: 텍스트·음성 대화, VRM Avatar, 표정·립싱크·활동 애니메이션과 이동 가능한 World를 제공합니다.
- **지속형 World**: 위치·방·활동·감정·상호작용 대상을 저장하고, 결정론적 Behavior가 대화하지 않는 동안의 행동을 선택합니다.
- **서버별 비서 채널**: `/설정`으로 기존 `가현봇-채팅`과 `가현봇-비서` 채널을 만들고 연결합니다. 채널명은 기존 서버 호환을 위해 유지합니다.
- **음성 비서**: 전용 음성 채널 입장을 감지해 봇이 자동 참여하고 STT → AI → TTS 파이프라인으로 응답합니다.
- **안전한 턴 감지**: TEN VAD와 연속 무음 기준으로 발화 종료를 판단해 짧은 쉼마다 API를 호출하지 않습니다.
- **대화 문맥 유지**: 사용자와 비서 메시지의 역할 경계를 보존해 후속 질문을 처리합니다.
- **다중 TTS 제공자**: Voicebox, Edge TTS, 범용 HTTP 커스텀 TTS를 지원하며 실패 시 Edge로 전환할 수 있습니다.
- **음악과 음성 채널 관리**: 재생·일시정지·스킵·큐 관리와 예약 나가기 기능을 제공합니다.
- **뉴스레터 및 서버 관리**: 구독형 DM 콘텐츠와 관리 명령을 제공합니다.

## 비서 사용법

### 서버 설정

관리자가 Discord에서 다음 명령을 한 번 실행합니다.

```text
/설정
```

Gahyeon Discord Adapter는 전용 채팅 채널과 음성 채널을 생성하거나 기존 설정을 복구합니다.

- 전용 채팅 채널의 일반 메시지는 `/가현아`와 동일하게 처리됩니다.
- 전용 음성 채널에 입장하면 음성 비서가 자동으로 참여합니다.
- 수동 제어가 필요하면 `/비서 action:시작`, `종료`, `상태`를 사용합니다.

### 음성 처리 흐름

```text
Discord 음성 → TEN VAD → STT → AI 에이전트/OpenRouter → TTS → Discord 음성
```

발화는 설정된 최소 음성 길이와 연속 무음 시간을 충족할 때 한 번만 확정됩니다. OpenRouter 응답은 한 턴당 하나의 스트리밍 요청으로 수신합니다.

## 기술 스택

- Java 21, Spring Boot 3
- JDA 5, LavaPlayer
- PostgreSQL / H2, Flyway
- OpenRouter 기반 에이전트 런타임
- TEN VAD와 교체 가능한 HTTP STT
- Voicebox, Edge TTS, 범용 커스텀 TTS
- Docker, GitHub Actions, Blue/Green 배포

자세한 구조는 [Core 마이그레이션 문서](docs/GAHYEON_CORE_MIGRATION.md), [Desktop 문서](desktop/README.md), [아키텍처 문서](docs/ARCHITECTURE.md)를 참고하세요.

## 요구 환경

- Java 21
- 프로젝트에 포함된 Gradle Wrapper
- Discord Bot Token 및 Application ID
- AI 비서를 사용할 경우 OpenRouter API 키
- 음악 기능을 사용할 경우 Spotify API 자격 증명
- 운영 환경의 PostgreSQL
- 음성 비서를 사용할 경우 접근 가능한 STT/TTS 서비스

## 주요 환경 변수

### 기본 및 AI

| 변수 | 설명 |
| --- | --- |
| `TOKEN` | Discord 봇 토큰 |
| `APPLICATION_ID` | Discord 애플리케이션 ID |
| `BOT_ENABLED` | Discord 연결 활성화 여부 |
| `GAHYEON_AGENT_PROVIDER` | LLM Agent provider. `openai`로 설정하면 OpenAI/OpenRouter Adapter 활성화 |
| `ASSISTANT_ENABLED` | 음성 비서 활성화 |
| `ASSISTANT_OPENROUTER_ENABLED` | OpenRouter AI 제공자 활성화 |
| `OPENROUTER_API_KEY` | OpenRouter API 키 |
| `OPENROUTER_MODEL` | 사용할 OpenRouter 모델 ID |
| `SPOTIFY_CLIENT_ID` | Spotify Client ID |
| `SPOTIFY_CLIENT_SECRET` | Spotify Client Secret |

### STT와 발화 감지

| 변수 | 설명 |
| --- | --- |
| `ASSISTANT_STT_ENABLED` | STT 활성화 |
| `ASSISTANT_STT_BASE_URL` | STT 서버 기본 URL |
| `ASSISTANT_STT_ENDPOINT` | 전사 API 경로 |
| `ASSISTANT_STT_API_KEY_REQUIRED` | STT 인증 필요 여부 |
| `ASSISTANT_STT_API_KEY` | STT API 키 |
| `ASSISTANT_STT_MODEL` | STT 모델 이름 |
| `ASSISTANT_VAD_ENABLED` | TEN VAD 활성화 |
| `ASSISTANT_VAD_THRESHOLD` | 음성 판정 임계값 |
| `ASSISTANT_VAD_END_SILENCE_MILLIS` | 한 턴을 확정할 연속 무음 시간 |

### TTS

| 변수 | 설명 |
| --- | --- |
| `TTS_PROVIDER` | `voicebox`, `edge`, `custom` |
| `TTS_FALLBACK_TO_EDGE` | 기본 TTS 실패 시 Edge 사용 |
| `VOICEBOX_BASE_URL` | Voicebox 서버 URL |
| `VOICEBOX_PROFILE_ID` | 사용할 Voicebox 프로필 ID |
| `VOICEBOX_PROFILE_NAME` | ID가 없을 때 검색할 프로필 이름 |
| `VOICEBOX_MODEL_SIZE` | Voicebox 모델 크기 |
| `VOICEBOX_TIMEOUT_SECONDS` | Voicebox 요청 제한 시간 |
| `CUSTOM_TTS_ENDPOINT` | 커스텀 합성 HTTP 엔드포인트 |
| `CUSTOM_TTS_API_KEY` | 커스텀 TTS Bearer 토큰 |
| `CUSTOM_TTS_MODEL` | 추론 서버의 모델 별칭 |
| `CUSTOM_TTS_SPEAKER_ID` | 추론 서버의 화자 ID |
| `CUSTOM_TTS_FORMAT` | `wav` 또는 `mp3` |

전체 설정과 HTTP 계약은 [커스텀 음성 TTS 문서](docs/CUSTOM_VOICE_TTS.md)를 참고하세요. 비밀키는 저장소나 이미지에 넣지 말고 환경 변수 또는 GitHub Actions Secrets로 주입하세요.

## 로컬 실행

```bash
git clone https://github.com/LEE-Kyungjae/gahyeonbot.git
cd gahyeonbot
./gradlew clean test
./gradlew bootRun
```

Discord 연결 없이 Gahyeon Core와 Headless/Desktop API만 실행하려면:

```bash
BOT_ENABLED=false ./gradlew bootRun
```

기본 개발 프로필은 별도 DB 설정이 없으면 인메모리 H2를 사용합니다. PostgreSQL을 사용하려면 관련 `POSTGRES_DEV_*` 변수와 `FLYWAY_ENABLED=true`를 설정하세요.

## Docker

```bash
docker build -t gahyeonbot:latest .
docker run --rm \
  -e TOKEN \
  -e APPLICATION_ID \
  -e OPENROUTER_API_KEY \
  gahyeonbot:latest
```

컨테이너에서 `127.0.0.1`은 컨테이너 자신을 가리킵니다. STT, Voicebox 또는 커스텀 TTS가 다른 호스트에 있다면 컨테이너에서 접근 가능한 주소를 지정해야 합니다.

## 커스텀 음성 연구 상태

- **Voicebox**: 녹음 기반 프로필을 통한 음성 복제를 지원합니다.
- **Piper 증류**: Voicebox 교사 음성을 경량 Piper 모델로 증류하는 실험 도구가 `scripts/`에 있습니다. 현재 연구 단계이며 기본 Discord TTS로 간주하지 않습니다.
- 생성 음성의 사용 권한과 화자 동의를 확인하고, 모델·원본 녹음·프로필 ID를 비밀정보에 준해 관리하세요.

## 테스트와 배포

```bash
./gradlew clean test
./gradlew clean shadowJar
```

GitHub Actions는 PR과 `main` 푸시에서 테스트를 실행하고, 버전 이미지 생성 및 승인된 Blue/Green 배포를 수행합니다. 자세한 절차는 [배포 문서](docs/DEPLOYMENT.md)를 참고하세요.

## 문서

- [API](docs/API.md)
- [Core 마이그레이션](docs/GAHYEON_CORE_MIGRATION.md)
- [Desktop Client](desktop/README.md)
- [VRM 애니메이션](docs/VRM_ANIMATION.md)
- [Looking Glass](docs/LOOKING_GLASS.md)
- [아키텍처](docs/ARCHITECTURE.md)
- [에이전트 런타임](docs/agent-runtime.md)
- [커스텀 음성 TTS](docs/CUSTOM_VOICE_TTS.md)
- [배포](docs/DEPLOYMENT.md)

## 기여 및 라이선스

이슈나 Pull Request를 환영합니다. 프로젝트는 [MIT License](LICENSE)를 따릅니다.

## 문의

- [LEE-Kyungjae](https://github.com/LEE-Kyungjae)
- ze2@kakao.com
