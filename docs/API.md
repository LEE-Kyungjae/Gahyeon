# Gahyeon API 문서

## 개요
Gahyeon은 Discord, Desktop과 Headless Client가 공유하는 HTTP API를 제공합니다. 기존 운영·모니터링 API와 함께 Conversation, Speech, Event Stream 및 World State API가 독립적인 Core 진입점으로 동작합니다.

**Base URL**: `/api`
**프로토콜**: HTTP/HTTPS
**포트**: 8080 (Blue), 8081 (Green)

## 인증
현재 REST API는 인증이 필요하지 않습니다. (내부 Health Check 용도)

향후 관리자 API 확장 시 인증 메커니즘 추가 예정.

---

## REST API Endpoints

### Health Check

#### `GET /api/health`
애플리케이션 헬스 체크 엔드포인트. Blue/Green 배포 시 활성 상태 확인에 사용됩니다.

**요청**:
```http
GET /api/health HTTP/1.1
Host: localhost:8080
```

**응답**:
```json
{
  "status": "UP",
  "timestamp": "2025-11-14T10:30:00.000+00:00"
}
```

**상태 코드**:
- `200 OK`: 애플리케이션 정상 동작
- `503 Service Unavailable`: 애플리케이션 비정상 상태

**용도**:
- Blue/Green 배포 시 컨테이너 준비 상태 확인
- 로드 밸런서 헬스 체크
- 모니터링 시스템 연동

---

### Actuator Endpoints

Spring Boot Actuator가 활성화되어 있어 추가 메트릭 엔드포인트를 제공합니다.

#### `GET /api/actuator/health`
상세 헬스 정보 조회

**응답 예시**:
```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "PostgreSQL",
        "validationQuery": "isValid()"
      }
    },
    "diskSpace": {
      "status": "UP",
      "details": {
        "total": 499963174912,
        "free": 100000000000,
        "threshold": 10485760,
        "exists": true
      }
    },
    "ping": {
      "status": "UP"
    }
  }
}
```

#### `GET /api/actuator/info`
애플리케이션 정보 조회

**응답 예시**:
```json
{
  "app": {
    "name": "gahyeonbot",
    "version": "1.0.0",
    "description": "Discord Bot for Reservation & Music"
  }
}
```

#### `GET /api/actuator/metrics`
사용 가능한 메트릭 목록

**응답 예시**:
```json
{
  "names": [
    "jvm.memory.used",
    "jvm.threads.live",
    "http.server.requests",
    "process.uptime",
    "system.cpu.usage"
  ]
}
```

#### `GET /api/actuator/metrics/{metricName}`
특정 메트릭 상세 정보

**예시**: `GET /api/actuator/metrics/jvm.memory.used`

```json
{
  "name": "jvm.memory.used",
  "description": "The amount of used memory",
  "baseUnit": "bytes",
  "measurements": [
    {
      "statistic": "VALUE",
      "value": 234567890
    }
  ]
}
```

---

## Discord Adapter 슬래시 명령어

아래 명령은 Gahyeon의 여러 Client 중 Discord Adapter가 제공하는 호환 인터페이스입니다. Core Conversation은 Discord 객체를 받지 않으며 Desktop과 Headless Client도 동일한 대화 유스케이스를 사용합니다.

### 일반 명령어 (General)

#### `/가현아`
OpenAI GPT-4o-mini를 사용한 AI 대화 기능

**파라미터**:
- `질문` (필수): AI에게 물어볼 질문 (최대 1000자)

**예시**:
```
/가현아 질문:자바스프링부트에 대해 설명해줘
```

**응답**:
- Discord Embed 메시지로 질문과 답변 표시
- 최대 응답 길이: 2000자 (초과 시 자동 절단)

**Rate Limiting**:
- 시간당 3회 (개인)
- 일일 10회 (개인)
- 일일 1000회 (전체)
- 월간 20,000회 (전체)

**에러 메시지**:
- `⚠️ 시간당 한도 초과`: 1시간 후 재시도
- `⚠️ 하루 한도 초과`: 24시간 후 재시도
- `🚫 적대적 프롬프트 감지`: 질문 내용 수정 필요
- `OpenAI 서비스 비활성화`: 관리자 문의

---

#### `/info`
봇 정보 및 서버 통계 조회

**파라미터**: 없음

**응답**:
- 봇 버전
- 서버 멤버 수
- 활성 음성 채널 수
- 업타임

---

#### `/clean`
채널의 최근 메시지 삭제

**파라미터**:
- `개수` (선택): 삭제할 메시지 개수 (기본값: 10, 최대: 100)

**권한**: 메시지 관리 권한 필요

**예시**:
```
/clean 개수:50
```

---

#### `/allhere`
음성 채널의 모든 사용자에게 알림 메시지 전송

**파라미터**: 없음

**동작**:
- 명령어 실행자가 속한 음성 채널의 모든 멤버 멘션

---

### 음악 명령어 (Music)

#### `/add`
재생 목록에 음악 추가

**파라미터**:
- `url` (필수): YouTube URL, Spotify URL, 또는 검색어

**지원 형식**:
- YouTube 동영상: `https://www.youtube.com/watch?v=...`
- YouTube 플레이리스트: `https://www.youtube.com/playlist?list=...`
- Spotify 트랙: `https://open.spotify.com/track/...`
- 검색어: `never gonna give you up`

**예시**:
```
/add url:https://www.youtube.com/watch?v=dQw4w9WgXcQ
/add url:베토벤 교향곡 5번
```

**응답**:
- 재생 목록에 추가 확인 메시지
- 현재 재생 중이 아니면 즉시 재생

---

#### `/skip`
현재 재생 중인 곡 건너뛰기

**파라미터**: 없음

**동작**:
- 현재 트랙 중단
- 다음 트랙 재생 (큐에 있는 경우)

---

#### `/pause`
재생 일시 정지

**파라미터**: 없음

---

#### `/resume`
재생 재개

**파라미터**: 없음

---

#### `/queue`
현재 재생 목록 조회

**파라미터**: 없음

**응답**:
- 현재 재생 중인 곡
- 대기 중인 곡 목록

---

#### `/clear`
재생 목록 전체 삭제

**파라미터**: 없음

---

### 관리 명령어 (Moderation)

#### `/kickuser`
특정 사용자를 음성 채널에서 추방

**파라미터**:
- `user` (필수): 추방할 사용자
- `reason` (선택): 추방 사유

**권한**: 멤버 추방 권한 필요

---

#### `/kickallbots`
모든 봇을 음성 채널에서 추방

**파라미터**: 없음

**권한**: 멤버 추방 권한 필요

---

#### `/kickallusers`
모든 사용자를 음성 채널에서 추방

**파라미터**: 없음

**권한**: 관리자 권한 필요

---

#### `/listkicks`
예약된 추방 목록 조회

**파라미터**: 없음

---

#### `/cancelkick`
예약된 추방 취소

**파라미터**:
- `kickId` (필수): 취소할 추방 ID

---

## 에러 코드

### HTTP 에러
| 코드 | 의미 | 설명 |
|------|------|------|
| 200 | OK | 정상 처리 |
| 503 | Service Unavailable | 서비스 비정상 상태 |

### Discord 명령어 에러
| 메시지 | 원인 | 해결 방법 |
|--------|------|-----------|
| `음성 채널에 먼저 입장하세요` | 사용자가 음성 채널에 없음 | 음성 채널 입장 후 재시도 |
| `권한이 없습니다` | 명령어 실행 권한 부족 | 서버 관리자에게 권한 요청 |
| `OpenAI 서비스 비활성화` | OPENAI_API_KEY 미설정 | 관리자에게 문의 |
| `재생 중인 곡이 없습니다` | 음악 재생 상태 아님 | `/add`로 음악 추가 |

---

## Rate Limiting

### OpenAI API (`/가현아`)
| 제한 유형 | 한도 | 윈도우 |
|-----------|------|--------|
| 개인 시간당 | 3회 | 1시간 |
| 개인 일일 | 10회 | 24시간 |
| 전체 일일 | 1000회 | 24시간 |
| 전체 월간 | 20,000회 | 30일 |

**초과 시**:
- 에러 메시지 표시
- 남은 시간 안내
- 요청 거부

### 기타 명령어
현재 Rate Limiting 없음. 향후 Discord Rate Limit 준수 예정.

---

## Webhook (향후 확장)

### 계획 중인 Webhook 엔드포인트
- `POST /api/webhooks/github`: GitHub 이벤트 수신
- `POST /api/webhooks/stripe`: 결제 이벤트 수신 (후원 기능)

---

## 모니터링 연동

### Prometheus Metrics (향후 확장)
```
# 예상 메트릭
openai_requests_total{status="success|failure"}
openai_rate_limit_exceeded_total
music_tracks_played_total
commands_executed_total{command="gahyeona|add|skip"}
```

### Grafana Dashboard (향후 확장)
- OpenAI 사용량 추이
- 음악 재생 통계
- 명령어 실행 빈도
- 에러율

---

## 변경 이력

### v1.0.0 (2025-11-14)
- 초기 API 문서 작성
- Health Check 엔드포인트 문서화
- Discord 슬래시 명령어 전체 목록

---

## 참고 문서
- [ARCHITECTURE.md](./ARCHITECTURE.md) - 시스템 아키텍처
- [DEPLOYMENT.md](./DEPLOYMENT.md) - 배포 가이드
- [Discord Developer Portal](https://discord.com/developers/docs/interactions/application-commands)
