# Gahyeon 아키텍처 문서

## 시스템 개요

Gahyeon은 Java 21와 Spring Boot 3 기반의 독립형 AI Agent입니다. Conversation, Session, STT, TTS, Event와 World/Behavior 기능은 Core/Application 경계에 있으며, JDA 기반 Discord 기능과 Electron 기반 Desktop은 Adapter/Client로 연결됩니다. 기존 예약·음악·서버 관리 기능은 Discord Adapter에 남겨 점진적으로 호환합니다.

## 기술 스택

### Core
- **Java**: 21 (Eclipse Temurin)
- **Spring Boot**: 3.5.6 LTS
- **Build Tool**: Gradle 8.13

### Discord & Audio
- **JDA**: 5.2.1 (Discord API 클라이언트)
- **Lavaplayer**: 2.2.2 (음악 재생 엔진)
- **Spotify Web API**: 9.1.1

### AI & Database
- **Spring AI**: 1.0.1 GA (OpenAI 통합)
- **PostgreSQL**: 16.x (운영 환경)
- **H2**: 인메모리 DB (테스트 환경)
- **Flyway**: DB 마이그레이션

### Infrastructure
- **Docker**: 컨테이너화
- **GitHub Actions**: CI/CD
- **Blue/Green Deployment**: 무중단 배포

## 아키텍처 다이어그램

현재 목표 및 구현 경계는 다음과 같습니다.

```text
Discord Adapter ──────┐
Desktop/Headless API ─┼─ Application Use Cases ─ Core Domain
                      │                         ├─ Conversation / Session
                      │                         ├─ Speech ports
                      │                         ├─ Events
                      │                         └─ World / Behavior
                      └─ Presentation Events
                                  │
                         Desktop Three.js Scene
                             ├─ Monitor renderer
                             └─ Looking Glass WebXR renderer
```

Core 타입은 JDA, Electron, Spring Web 또는 음성 제공자 타입을 참조하지 않습니다. Discord의 음악·관리·예약 기능은 기존 호환 기능으로 Adapter 측에 남아 있습니다. 아래 다이어그램과 레이어 설명은 이 Discord 호환 영역의 상세 구조를 설명합니다.

```
┌─────────────────────────────────────────────────────────────┐
│                      Discord Platform                        │
└────────────────────┬────────────────────────────────────────┘
                     │ WebSocket (JDA)
                     ▼
┌─────────────────────────────────────────────────────────────┐
│                Gahyeon Discord Compatibility                 │
│  ┌────────────────────────────────────────────────────────┐ │
│  │              Listeners & Event Handlers                 │ │
│  │  - CommandManager (슬래시 명령어)                       │ │
│  │  - MessageListener (메시지 이벤트)                      │ │
│  │  - MemberJoinListener (멤버 입장)                       │ │
│  │  - UserStatusUpdateListener (상태 변경)                 │ │
│  └────────────────┬───────────────────────────────────────┘ │
│                   │                                          │
│  ┌────────────────▼───────────────────────────────────────┐ │
│  │                 Command Layer                           │ │
│  │  ┌─────────────┬──────────────┬──────────────────────┐ │ │
│  │  │  General    │   Music      │   Moderation         │ │ │
│  │  │  - Gahyeona │   - Add      │   - KickUser         │ │ │
│  │  │  - Info     │   - Skip     │   - KickAllBots      │ │ │
│  │  │  - Clean    │   - Pause    │   - ListKicks        │ │ │
│  │  │  - Allhere  │   - Resume   │   - CancelKick       │ │ │
│  │  └─────────────┴──────────────┴──────────────────────┘ │ │
│  └────────────────┬───────────────────────────────────────┘ │
│                   │                                          │
│  ┌────────────────▼───────────────────────────────────────┐ │
│  │                 Service Layer                           │ │
│  │  ┌──────────────────┬────────────────┬────────────────┐ │ │
│  │  │  AI Services     │ Music Services │  Moderation    │ │ │
│  │  │  - OpenAiService │ - MusicService │  - BotManager  │ │ │
│  │  │                  │ - Spotify      │  - MessageClean│ │ │
│  │  └──────────────────┴────────────────┴────────────────┘ │ │
│  └────────────────┬───────────────────────────────────────┘ │
│                   │                                          │
│  ┌────────────────▼───────────────────────────────────────┐ │
│  │              Core Components                            │ │
│  │  - AudioManager (음악 재생 관리)                         │ │
│  │  - TrackScheduler (재생 큐 관리)                         │ │
│  │  - LeaveSchedulerManager (예약 관리)                     │ │
│  │  - CommandRegistry (명령어 등록)                         │ │
│  └────────────────┬───────────────────────────────────────┘ │
│                   │                                          │
│  ┌────────────────▼───────────────────────────────────────┐ │
│  │            Repository Layer (JPA)                       │ │
│  │  - OpenAiUsageRepository                                │ │
│  └────────────────┬───────────────────────────────────────┘ │
└───────────────────┼──────────────────────────────────────────┘
                    │
        ┌───────────┴───────────┬─────────────────┐
        ▼                       ▼                 ▼
┌───────────────┐    ┌────────────────┐   ┌──────────────┐
│  PostgreSQL   │    │  OpenAI API    │   │ Spotify API  │
│   Database    │    │   GPT-4o-mini  │   │              │
└───────────────┘    └────────────────┘   └──────────────┘
```

## 레이어별 상세 설명

### 1. Listener Layer
**위치**: `src/main/java/com/gahyeonbot/listeners/`

**역할**:
- Discord 이벤트 수신 및 처리
- 슬래시 명령어 라우팅
- 사용자 이벤트 모니터링

**주요 컴포넌트**:
- `CommandManager`: 슬래시 명령어 이벤트 처리
- `MessageListener`: 텍스트 메시지 이벤트 처리
- `MemberJoinListener`: 새 멤버 입장 환영 메시지
- `UserStatusUpdateListener`: 사용자 상태 변경 감지 (예약 기능 연동)

### 2. Command Layer
**위치**: `src/main/java/com/gahyeonbot/commands/`

**역할**:
- 비즈니스 로직과 Discord 이벤트 연결
- 입력 검증 및 권한 확인
- 응답 포매팅

**패키지 구조**:
```
commands/
├── general/       # 일반 명령어
│   ├── Gahyeona   # AI 대화
│   ├── Info       # 봇 정보
│   ├── Clean      # 메시지 삭제
│   └── Allhere    # 음성 채널 알림
├── music/         # 음악 관련 명령어
│   ├── Add        # 음악 추가
│   ├── Skip       # 스킵
│   ├── Pause      # 일시 정지
│   ├── Resume     # 재개
│   ├── Queue      # 재생 목록
│   └── Clear      # 큐 삭제
├── moderation/    # 관리 명령어
│   ├── KickUser   # 유저 추방
│   ├── KickAllBots    # 봇 일괄 추방
│   ├── KickAllUsers   # 유저 일괄 추방
│   ├── ListKicks      # 추방 목록
│   └── CancelKick     # 추방 취소
└── util/          # 유틸리티
    ├── AbstractCommand    # 명령어 베이스 클래스
    ├── ICommand          # 명령어 인터페이스
    ├── ResponseUtil      # 응답 헬퍼
    ├── PermissionUtil    # 권한 체크
    └── EmbedUtil         # Embed 메시지 빌더
```

### 3. Service Layer
**위치**: `src/main/java/com/gahyeonbot/services/`

**역할**:
- 핵심 비즈니스 로직 구현
- 외부 API 통합
- 데이터 처리 및 검증

**주요 서비스**:

#### OpenAiService (`services/ai/`)
- **기능**: OpenAI GPT-4o-mini API 통합
- **보호 메커니즘**:
  - Rate Limiting (시간당 3회, 일일 10회 개인 / 일일 1000회 전체)
  - Adversarial Prompt 필터링
  - 응답 캐싱 (5분 TTL)
  - OpenAI Moderation API 연동
  - 중복 요청 방지 (30초 윈도우)
- **데이터**: `OpenAiUsageRepository`로 사용 기록 저장

#### MusicService (`services/music/`)
- **기능**: 음악 재생 관리
- **지원 소스**:
  - YouTube (직접 URL, 검색)
  - Spotify (트랙, 플레이리스트)
  - SoundCloud
- **기술**: Lavaplayer 기반 오디오 스트리밍

#### SpotifySearchService (`services/streaming/`)
- **기능**: Spotify 트랙 검색 및 메타데이터 조회
- **인증**: Client Credentials Flow

#### BotManagerService & MessageCleanService (`services/moderation/`)
- **기능**: 서버 관리 및 메시지 청소

### 4. Core Layer
**위치**: `src/main/java/com/gahyeonbot/core/`

**AudioManager** (`core/audio/`):
- `GuildMusicManager`: 길드별 음악 재생 상태 관리
- `TrackScheduler`: 재생 큐 및 반복 재생 관리
- `TrackQueue`: 재생 목록 자료구조
- `AudioPlayerSendHandler`: Discord 음성 채널로 오디오 전송

**Scheduler** (`core/scheduler/`):
- `LeaveSchedulerManager`: 예약된 음성 채널 나가기 관리

**Command** (`core/command/`):
- `CommandRegistry`: 명령어 등록 및 관리

**Initialization** (`core/`):
- `BotInitializer`: JDA 초기화 및 리스너 등록
- `BotInitializerRunner`: Spring Boot 시작 시 봇 초기화

### 5. Repository Layer
**위치**: `src/main/java/com/gahyeonbot/repository/`

**구현**:
- `OpenAiUsageRepository`: Spring Data JPA 인터페이스
- OpenAI API 사용 기록 저장 및 조회
- Rate Limiting을 위한 카운트 쿼리

### 6. Entity Layer
**위치**: `src/main/java/com/gahyeonbot/entity/`

**주요 엔티티**:
- `OpenAiUsage`: OpenAI API 호출 기록
  - `interactionId`: Discord Interaction 고유 ID (중복 방지)
  - `userId`, `username`: 사용자 정보
  - `guildId`: 서버 ID
  - `prompt`, `response`: 질문/응답 내용
  - `success`: 성공 여부
  - `errorMessage`: 에러 발생 시 메시지
  - `createdAt`: 생성 시간 (Rate Limiting 기준)

## 데이터 흐름

### AI 대화 요청 흐름
```
1. User: /가현아 질문:"안녕하세요?"
   ↓
2. CommandManager: SlashCommandInteractionEvent 수신
   ↓
3. Gahyeona Command:
   - 옵션 파싱 ("질문" 파라미터)
   - OpenAiService.isEnabled() 확인
   ↓
4. OpenAiService.chat():
   - Rate Limiting 검증
   - Adversarial Prompt 검사
   - 캐시 확인
   - OpenAI API 호출
   - 사용 기록 저장 (OpenAiUsageRepository)
   ↓
5. Gahyeona Command: Discord Embed 응답 전송
   ↓
6. User: AI 응답 수신
```

### 음악 재생 요청 흐름
```
1. User: /add url:"https://youtube.com/..."
   ↓
2. Add Command:
   - 사용자 음성 채널 확인
   - URL 검증
   ↓
3. MusicService.loadAndPlay():
   - URL 타입 감지 (YouTube/Spotify/검색어)
   - Spotify URL → SpotifySearchService로 메타데이터 조회
   - YouTube 검색 변환
   ↓
4. AudioManager:
   - GuildMusicManager 획득/생성
   - TrackScheduler에 트랙 추가
   - 음성 채널 연결
   ↓
5. TrackScheduler:
   - 재생 큐 관리
   - 자동 재생 시작
   ↓
6. AudioPlayerSendHandler:
   - Lavaplayer로 오디오 디코딩
   - Discord 음성 채널로 스트리밍
```

## 보안 및 성능 최적화

### OpenAI API 보호
1. **Rate Limiting**:
   - 사용자당 시간당 3회, 일일 10회
   - 전체 일일 1000회, 월간 20,000회
   - PostgreSQL 타임스탬프 기반 카운팅

2. **Adversarial Prompt 필터링**:
   ```java
   List<String> ADVERSARIAL_KEYWORDS = List.of(
       "ignore", "system prompt", "previous instructions",
       "developer mode", "jailbreak", "DAN mode"
   );
   ```

3. **응답 캐싱**:
   - Caffeine Cache (5분 TTL)
   - 동일 질문에 대한 반복 API 호출 방지

4. **중복 요청 방지**:
   - `interactionId` 기반 30초 윈도우 중복 체크

### 음악 재생 최적화
- 길드별 독립적인 `GuildMusicManager`
- 오디오 버퍼링 및 스트리밍
- 재생 큐 메모리 효율적 관리

## 배포 아키텍처

### CI/CD Pipeline
```
GitHub Push (main)
  ↓
GitHub Actions:
  1. Build & Test (./gradlew clean test)
  2. Generate Version Tag (v1.x.x)
  3. Build Docker Image (multi-arch: amd64, arm64)
  4. Push to GHCR (ghcr.io/lee-kyungjae/gahyeonbot)
  ↓
Production Deployment:
  5. SSH to Server
  6. Blue/Green Deployment
     - 현재 환경 확인 (Blue or Green)
     - 새 환경에 컨테이너 시작
     - Health Check (/api/health)
     - 이전 환경 컨테이너 정리
  ↓
Create GitHub Release Tag
```

### Blue/Green 배포
```
┌─────────────┐
│   Traefik   │  (Reverse Proxy)
└──────┬──────┘
       │
   ┌───┴────┐
   │        │
   ▼        ▼
┌──────┐ ┌──────┐
│ Blue │ │Green │  (Docker Containers)
│:8080 │ │:8081 │
└──────┘ └──────┘

배포 과정:
1. 현재: Blue 활성 (8080)
2. Green 컨테이너 시작 (8081)
3. Health Check 통과
4. Traffic → Green으로 전환
5. Blue 컨테이너 정리
```

### 환경 분리
- **Dev**: H2 인메모리 DB, 로컬 개발
- **Prod**: PostgreSQL, Docker 컨테이너, Traefik 프록시

## 확장 포인트

### 새 명령어 추가
1. `commands/` 패키지에 클래스 생성
2. `AbstractCommand` 상속 또는 `ICommand` 구현
3. `@Component` 애노테이션 추가
4. `CommandRegistry`가 자동 등록

### 새 서비스 추가
1. `services/` 패키지에 서비스 클래스 생성
2. `@Service` 애노테이션 추가
3. 필요 시 Repository 생성
4. Flyway 마이그레이션으로 스키마 추가

### 새 외부 API 통합
1. `services/` 하위에 전용 패키지 생성
2. API 클라이언트 구현
3. `AppCredentialsConfig`에 인증 정보 추가
4. `application.yml`에 설정 추가

## 모니터링 및 로깅

### Health Check
- **Endpoint**: `/api/health`
- **Actuator**: Spring Boot Actuator 활성화
- **메트릭**: health, info, metrics

### 로깅
- **Framework**: Logback
- **레벨**: INFO (운영), DEBUG (개발)
- **출력**:
  - 콘솔 (표준 출력)
  - 파일 (선택적, 향후 확장)

### 추천 확장
- Prometheus + Grafana 메트릭 수집
- Sentry 에러 추적
- ELK Stack 로그 수집

## 참고 문서
- [API.md](./API.md) - REST API 엔드포인트 문서
- [DEPLOYMENT.md](./DEPLOYMENT.md) - 배포 가이드
- [README.md](../README.md) - 프로젝트 개요 및 시작 가이드
