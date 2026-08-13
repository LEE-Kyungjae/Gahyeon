# Gahyeonbot 배포 가이드

## 개요

Gahyeon은 GitHub Actions를 통한 자동화된 CI/CD 파이프라인과 Blue/Green 무중단 배포 전략을 사용합니다.

**배포 환경**:
- **Container Registry**: GitHub Container Registry (GHCR)
- **CI/CD**: GitHub Actions
- **배포 전략**: Blue/Green Deployment
- **프록시**: Traefik 또는 Nginx (권장)
- **운영 서버**: SSH 접근 가능한 Linux 서버

---

## 배포 아키텍처

### Blue/Green 배포 전략

```
┌──────────────────────────────────────┐
│          Reverse Proxy               │
│        (Traefik/Nginx)               │
│      Port 80/443 → 8080 or 8081      │
└──────────┬──────────────┬────────────┘
           │              │
      Blue │              │ Green
    (Port 8080)      (Port 8081)
           │              │
    ┌──────▼──────┐ ┌────▼───────┐
    │  Container  │ │ Container  │
    │  (Inactive) │ │  (Active)  │
    └─────────────┘ └────────────┘

배포 흐름:
1. Green 활성 → Blue 배포
2. Blue Health Check 성공
3. Traffic을 Blue로 전환
4. Green 컨테이너 정리
```

### 배포 프로세스

```mermaid
graph TD
    A[Git Push to main] --> B[GitHub Actions Trigger]
    B --> C[Build & Test]
    C --> D[Generate Version Tag]
    D --> E[Build Docker Image]
    E --> F[Push to GHCR]
    F --> G[SSH to Production Server]
    G --> H[Determine Target Blue/Green]
    H --> I[Pull New Image]
    I --> J[Start New Container]
    J --> K[Health Check]
    K --> L{Health OK?}
    L -->|Yes| M[Stop Old Container]
    L -->|No| N[Rollback: Stop New]
    M --> O[Cleanup Old Images]
    O --> P[Create GitHub Release]
    N --> Q[Exit with Error]
```

---

## 사전 요구사항

### 1. GitHub Secrets 설정

GitHub 저장소의 **Settings > Secrets and variables > Actions**에서 다음 시크릿을 등록:

| Secret Name | 설명 | 예시 |
|-------------|------|------|
| `TOKEN` | Discord 봇 토큰 | `MTIzNDU2Nzg5MDEyMzQ1Njc4.GaBcDe.FgHiJkLmNoPqRsTuVwXyZ` |
| `APPLICATION_ID` | Discord 애플리케이션 ID | `1220338955082399845` |
| `SPOTIFY_CLIENT_ID` | Spotify Client ID | `abc123def456` |
| `SPOTIFY_CLIENT_SECRET` | Spotify Client Secret | `xyz789uvw012` |
| `OPENAI_API_KEY` | OpenAI API 키 | `sk-proj-...` |
| `POSTGRES_PROD_PASSWORD` | 운영 DB 비밀번호 | `your-secure-password` |
| `POSTGRES_PROD_HOST` | 운영 DB 호스트 | `postgres.internal` 또는 `localhost` |
| `POSTGRES_PROD_PORT` | 운영 DB 포트 | `5432` |
| `POSTGRES_PROD_USERNAME` | 운영 DB 사용자명 | `gahyeonbot_app` |
| `SSH_HOST` | 배포 서버 호스트 | `your-server.com` 또는 `192.168.1.100` |
| `SSH_PORT` | SSH 포트 | `22` |
| `SSH_USER` | SSH 사용자명 | `deploy` |
| `SSH_KEY` | SSH Private Key | `-----BEGIN OPENSSH PRIVATE KEY-----...` |

**SSH 키 생성 방법**:
```bash
# 배포용 SSH 키 생성
ssh-keygen -t ed25519 -C "github-actions-deploy" -f ~/.ssh/gahyeonbot_deploy

# 공개 키를 서버에 등록
ssh-copy-id -i ~/.ssh/gahyeonbot_deploy.pub deploy@your-server.com

# Private Key를 GitHub Secrets에 등록
cat ~/.ssh/gahyeonbot_deploy
```

### 2. 운영 서버 설정

#### Docker 설치
```bash
# Ubuntu/Debian
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh

# 사용자를 docker 그룹에 추가
sudo usermod -aG docker $USER
newgrp docker

# Docker Compose 설치 (선택)
sudo apt-get install docker-compose-plugin
```

#### PostgreSQL 설정 (운영 DB)
```bash
# Docker로 PostgreSQL 실행 (권장)
docker run -d \
  --name postgres-prod \
  --restart unless-stopped \
  -e POSTGRES_PASSWORD=your-secure-password \
  -e POSTGRES_USER=gahyeonbot_app \
  -e POSTGRES_DB=gahyeonbot \
  -v /var/lib/postgresql/data:/var/lib/postgresql/data \
  -p 5432:5432 \
  postgres:16

# 또는 호스트에 직접 설치
sudo apt-get install postgresql-16
```

#### GHCR 인증 (서버에서)
```bash
# GitHub Personal Access Token 생성 (read:packages 권한)
# https://github.com/settings/tokens

# GHCR 로그인
echo $GITHUB_TOKEN | docker login ghcr.io -u YOUR_GITHUB_USERNAME --password-stdin
```

---

## 배포 방법

### 자동 배포 (추천)

#### main 브랜치 푸시로 배포
```bash
git checkout main
git merge feature/your-feature
git push origin main
```

**자동 실행 단계**:
1. ✅ 테스트 실행 (`./gradlew clean test`)
2. 🏷️ 버전 태그 생성 (예: `v1.2.3`)
3. 🐳 Docker 이미지 빌드 (multi-arch: amd64, arm64)
4. 📦 GHCR에 이미지 푸시
5. 🚀 운영 서버에 Blue/Green 배포
6. 📝 GitHub Release 생성

### Blue/Green 드레인 TTL
트래픽 전환 후 기존 환경은 즉시 종료하지 않고 `DRAIN_TTL_SECONDS`(기본 3600초) 동안 드레인 상태로 유지됩니다.
장애로 롤백되는 경우, 드레인 대기 중인 종료 예약은 즉시 취소됩니다.

#### 수동 워크플로 실행
GitHub 저장소의 **Actions** 탭에서:
1. **CI/CD** 워크플로 선택
2. **Run workflow** 클릭
3. 배포 대상 선택:
   - `auto`: 자동 감지 (Green 활성 → Blue 배포)
   - `blue`: Blue 환경에 강제 배포
   - `green`: Green 환경에 강제 배포
4. **Run workflow** 실행

---

### 수동 배포

#### 1. 로컬에서 Docker 이미지 빌드
```bash
# JAR 빌드
./gradlew clean bootJar

# Docker 이미지 빌드
docker build -t gahyeonbot:latest .

# GHCR로 푸시 (선택)
docker tag gahyeonbot:latest ghcr.io/lee-kyungjae/gahyeonbot:v1.0.0
docker push ghcr.io/lee-kyungjae/gahyeonbot:v1.0.0
```

#### 2. 서버에서 직접 배포
```bash
# 서버 SSH 접속
ssh deploy@your-server.com

# 배포 스크립트 실행
./remote-deploy.sh auto ghcr.io/lee-kyungjae/gahyeonbot v1.0.0
```

---

## 배포 스크립트 상세 (`remote-deploy.sh`)

### 사용법
```bash
./remote-deploy.sh <target-env> <image-repository> <image-tag>
```

**파라미터**:
- `<target-env>`: 배포 환경
  - `auto`: 현재 비활성 환경 자동 선택 (권장)
  - `blue`: Blue 환경에 강제 배포
  - `green`: Green 환경에 강제 배포
- `<image-repository>`: Docker 이미지 저장소 (예: `ghcr.io/lee-kyungjae/gahyeonbot`)
- `<image-tag>`: 이미지 태그 (예: `v1.2.3` 또는 `latest`)

**예시**:
```bash
# 자동 환경 선택
./remote-deploy.sh auto ghcr.io/lee-kyungjae/gahyeonbot v1.2.3

# Blue 환경에 강제 배포
./remote-deploy.sh blue ghcr.io/lee-kyungjae/gahyeonbot latest
```

### 배포 과정 단계별 설명

#### 1. 환경 변수 검증
필수 환경 변수가 모두 설정되어 있는지 확인:
- `TOKEN`, `APPLICATION_ID`
- `SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET`
- `OPENAI_API_KEY`
- `POSTGRES_PROD_*` (HOST, PORT, USERNAME, PASSWORD)

#### 2. 타겟 환경 결정
- `auto`: 현재 실행 중인 컨테이너 확인
  - Green 실행 중 → Blue에 배포
  - Blue 실행 중 또는 없음 → Green에 배포
- `blue` / `green`: 지정된 환경에 배포

#### 3. Docker 이미지 Pull
```bash
docker pull ghcr.io/lee-kyungjae/gahyeonbot:v1.2.3
```

#### 4. 기존 컨테이너 정리
타겟 환경의 기존 컨테이너 중지 및 삭제:
```bash
docker stop gahyeonbot-blue
docker rm gahyeonbot-blue
```

#### 5. 새 컨테이너 시작
```bash
docker run -d \
  --name gahyeonbot-blue \
  --restart unless-stopped \
  -p 8080:8080 \
  -e TOKEN="..." \
  -e APPLICATION_ID="..." \
  -e SPOTIFY_CLIENT_ID="..." \
  -e SPOTIFY_CLIENT_SECRET="..." \
  -e OPENAI_API_KEY="..." \
  -e POSTGRES_PROD_HOST="..." \
  -e POSTGRES_PROD_PORT="5432" \
  -e POSTGRES_PROD_USERNAME="..." \
  -e POSTGRES_PROD_PASSWORD="..." \
  -e SPRING_PROFILES_ACTIVE="prod" \
  ghcr.io/lee-kyungjae/gahyeonbot:v1.2.3
```

#### 6. Health Check
30초 동안 `/api/health` 엔드포인트 폴링:
```bash
# 매 초마다 HTTP 200 응답 확인
curl -fsS http://127.0.0.1:8080/api/health

# 10초마다 두 포트(8080, 8081) 상태 출력
```

**성공 조건**:
- HTTP 200 응답 수신
- Headless 또는 Unreal을 켠 배포에서는 `conversationRequired=true`, `conversation=UP`
- `BOT_ENABLED=false`이면 `botState=DISABLED`, `bot=DISABLED`
- Discord 활성 leader이면 `botState=READY`, `bot=UP`; advisory-lock follower이면
  `botState=STANDBY`, `bot=STANDBY`
- 30초 이내 성공 시 다음 단계 진행

**실패 시**:
- 새 컨테이너 로그 출력
- 컨테이너 중지 및 삭제 (롤백)
- 배포 실패 종료

Discord가 활성화된 상태에서 token 누락·거부, leader-lock DB 오류, ShardManager 초기화 실패는
프로세스를 바로 종료하지 않지만 `botState=FAILED`, `bot=DOWN`과 HTTP 503을 반환합니다.
정상 Blue/Green follower의 `STANDBY`와 실제 activation 장애의 `FAILED`를 혼동하지 마세요.

> 배포 전 미완료 gate: GitHub의 image smoke는 현재 runner-native architecture와 `dev`/H2를
> 검증합니다. 별도의 disposable PostgreSQL에서 운영 DB의 현재 Flyway version부터 최신까지
> upgrade하는 release gate는 아직 필요합니다. 현재 image publish는 libdave의
> `natives-linux-x86-64` 제약 때문에 `linux/amd64`만 지원합니다. `linux/arm64`는 arm64 native
> artifact와 `BOT_ENABLED=true` Discord activation smoke를 갖추기 전까지 publish하지 않습니다.
> 이 문서는 운영 PostgreSQL 또는 arm64 검증을 실행했다고 주장하지 않습니다.

V30–V35 rename은 첫 GitOps 반영 전 제거하고 중립 Java 이름을 V24 물리 스키마에
매핑하는 방식으로 source 호환성을 해결했습니다. 다만 이것은 live DB 상태의 증거가
아닙니다. [migration compatibility preflight](DEPLOYMENT_MIGRATION_BLOCKER.md)에 정의한
read-only `flyway_schema_history` 확인과 disposable PostgreSQL upgrade/empty-DB gate가
통과하기 전에는 배포하지 마세요.

```bash
./scripts/preflight_postgres_migrations.sh
```

이 명령은 pgvector가 포함된 일회성 PostgreSQL 16에서 empty PostgreSQL 이력과
authoritative V24 fixture→current 이력을 독립적으로 검증합니다. 제한된 migration role,
V7 pgvector, V29 backfill/`NOT NULL`, V36 `user_id,status,created_at` index, application
schema validation을 한꺼번에 fail-closed로 확인합니다. 이 결과는 **not live-production
evidence**이며, 운영 `flyway_schema_history` read-only 확인과 운영 규모 lock 실측을
대체하지 않습니다.

`Build Image`는 main push 또는 main에서 `push_image=true`로 수동 실행할 때만 image/infra를
변경합니다. main 수동 실행의 기본값(`false`)과 develop·feature ref의 모든 수동 실행은
validation image와 smoke까지만 수행하고 GHCR의 `latest`나 infra repository를 변경하지
않습니다. 성공한 main Build Image만 `Verify Production`의 자동 rollout 검증 대상으로
이어집니다.

#### 7. 이전 환경 정리
새 컨테이너 Health Check 성공 후:
```bash
# 이전 컨테이너 정리
docker stop gahyeonbot-green
docker rm gahyeonbot-green

# 오래된 이미지 정리 (7일 이상)
docker image prune -af --filter "until=168h"
```

#### 8. 배포 완료 요약
```
========================================
✓ Deployment Summary
========================================
Environment: gahyeonbot-blue (port 8080)
Version:     v1.2.2 → v1.2.3
Image:       ghcr.io/lee-kyungjae/gahyeonbot:v1.2.3
Status:      Running and healthy ✓
========================================
```

---

## 포트 설정 및 Reverse Proxy 구성

### Traefik 설정 예시

**docker-compose.yml**:
```yaml
version: '3.8'

services:
  traefik:
    image: traefik:v2.10
    command:
      - "--api.insecure=true"
      - "--providers.docker=true"
      - "--entrypoints.web.address=:80"
      - "--entrypoints.websecure.address=:443"
    ports:
      - "80:80"
      - "443:443"
      - "8090:8080"  # Traefik Dashboard
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
    restart: unless-stopped

  gahyeonbot-blue:
    image: ghcr.io/lee-kyungjae/gahyeonbot:latest
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.gahyeonbot.rule=Host(`bot.yourdomain.com`)"
      - "traefik.http.services.gahyeonbot.loadbalancer.server.port=8080"
    environment:
      - TOKEN=${TOKEN}
      - SPRING_PROFILES_ACTIVE=prod
    restart: unless-stopped
```

### Nginx 설정 예시 (Blue/Green 전환)

**/etc/nginx/conf.d/gahyeonbot-upstream.conf** (배포 스크립트가 이 파일을 갱신):
```nginx
upstream gahyeonbot {
    server 127.0.0.1:8080;
}
```
배포 계정이 이 파일을 수정할 수 있어야 합니다. 필요하면 `ACTIVE_UPSTREAM_CONF`를 쓰기 가능한 경로로 바꾸고, Nginx에서 해당 파일을 include 하세요.

**/etc/nginx/sites-available/gahyeonbot**:
```nginx
server {
    listen 80;
    server_name bot.yourdomain.com;

    location /api/health {
        proxy_pass http://gahyeonbot;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    location / {
        return 200 "Gahyeonbot API";
        add_header Content-Type text/plain;
    }
}
```

**활성화**:
```bash
sudo ln -s /etc/nginx/sites-available/gahyeonbot /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl reload nginx
```

---

## 롤백 절차

### 자동 롤백
전환 직후 헬스 체크가 실패하면 자동으로 롤백됩니다:
1. 트래픽을 이전 환경으로 되돌림 (Nginx reload)
2. 1시간 드레인 예약 취소
3. 실패한 컨테이너 중지/삭제

### 수동 롤백
배포 후 문제 발견 시:

```bash
# 1. GitHub Actions Rollback 워크플로 실행 (권장)
#  - target: blue, green, auto

# 2. 서버에서 직접 롤백
./remote-rollback.sh auto
```

---

## 모니터링 및 로그

### 컨테이너 상태 확인
```bash
# 실행 중인 컨테이너 확인
docker ps | grep gahyeonbot

# 로그 확인
docker logs -f gahyeonbot-blue

# 최근 100줄 로그
docker logs --tail 100 gahyeonbot-blue

# 특정 시간 이후 로그
docker logs --since 10m gahyeonbot-blue
```

### Health Check
```bash
# Blue 환경
curl http://localhost:8080/api/health

# Green 환경
curl http://localhost:8081/api/health

# Actuator Health (상세)
curl http://localhost:8080/api/actuator/health
```

### 메트릭 확인
```bash
# JVM 메모리 사용량
curl http://localhost:8080/api/actuator/metrics/jvm.memory.used

# CPU 사용량
curl http://localhost:8080/api/actuator/metrics/system.cpu.usage

# HTTP 요청 수
curl http://localhost:8080/api/actuator/metrics/http.server.requests
```

---

## 트러블슈팅

### 1. Health Check 타임아웃
**증상**: 30초 동안 `/api/health`가 200을 반환하지 않음

**해결 방법**:
```bash
# 1. 컨테이너 로그 확인
docker logs gahyeonbot-blue

# 2. 일반적인 원인
# - DB 연결 실패: POSTGRES_PROD_* 환경 변수 확인
# - Discord 토큰 오류: TOKEN, APPLICATION_ID 확인
# - 포트 충돌: 8080/8081 포트 사용 여부 확인

# 3. 수동으로 컨테이너 재시작
docker restart gahyeonbot-blue
```

### 2. Discord 봇이 오프라인 상태
**증상**: Discord에서 봇이 오프라인으로 표시

**해결 방법**:
```bash
# 1. 컨테이너 실행 확인
docker ps | grep gahyeonbot

# 2. 로그에서 JDA 연결 오류 확인
docker logs gahyeonbot-blue | grep "JDA"

# 3. TOKEN 확인
# - Discord Developer Portal에서 토큰 재생성
# - GitHub Secrets 업데이트
# - 재배포
```

### 3. OpenAI 기능 작동 안 함
**증상**: `/가현아` 명령어가 "서비스 비활성화" 응답

**해결 방법**:
```bash
# 1. OPENAI_API_KEY 환경 변수 확인
docker exec gahyeonbot-blue env | grep OPENAI

# 2. GitHub Secrets에 OPENAI_API_KEY 등록 확인
# 3. 재배포
```

### 4. 음악 재생 안 됨
**증상**: `/add` 명령어 실행 후 음악이 재생되지 않음

**해결 방법**:
```bash
# 1. Lavaplayer 로그 확인
docker logs gahyeonbot-blue | grep "Lavaplayer"

# 2. YouTube/Spotify API 제한 확인
# - YouTube: 일일 할당량 확인
# - Spotify: SPOTIFY_CLIENT_ID/SECRET 확인
```

### 5. 데이터베이스 연결 오류
**증상**: 애플리케이션 시작 실패, "Connection refused" 로그

**해결 방법**:
```bash
# 1. PostgreSQL 컨테이너 실행 확인
docker ps | grep postgres

# 2. 연결 테스트
docker exec -it gahyeonbot-blue \
  psql -h $POSTGRES_PROD_HOST -p 5432 -U gahyeonbot_app -d gahyeonbot

# 3. 네트워크 확인
# - Docker 네트워크: 같은 네트워크에 있는지 확인
# - 방화벽: 5432 포트 개방 확인
```

---

## 성능 최적화

### 1. JVM 메모리 설정
`Dockerfile`에 JVM 옵션 추가:
```dockerfile
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC"
CMD ["java", "-jar", "gahyeonbot.jar"]
```

### 2. Docker 리소스 제한
```bash
docker run -d \
  --name gahyeonbot-blue \
  --memory="1g" \
  --cpus="1.0" \
  ...
```

### 3. 데이터베이스 연결 풀
`application-prod.yml`:
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 5
      connection-timeout: 30000
```

---

## 백업 및 복구

### 데이터베이스 백업
```bash
# 백업
docker exec postgres-prod pg_dump -U gahyeonbot_app gahyeonbot > backup_$(date +%Y%m%d).sql

# 복구
docker exec -i postgres-prod psql -U gahyeonbot_app gahyeonbot < backup_20251114.sql
```

### 자동 백업 스크립트
```bash
#!/bin/bash
# /usr/local/bin/backup-gahyeonbot.sh

BACKUP_DIR="/var/backups/gahyeonbot"
DATE=$(date +%Y%m%d_%H%M%S)

docker exec postgres-prod pg_dump -U gahyeonbot_app gahyeonbot | \
  gzip > ${BACKUP_DIR}/gahyeonbot_${DATE}.sql.gz

# 30일 이상 오래된 백업 삭제
find ${BACKUP_DIR} -name "*.sql.gz" -mtime +30 -delete
```

**Cron 등록**:
```bash
# 매일 새벽 2시 백업
0 2 * * * /usr/local/bin/backup-gahyeonbot.sh
```

---

## 보안 권장사항

1. **SSH 키 관리**:
   - 배포용 SSH 키는 읽기 전용 권한만 부여
   - 정기적으로 키 로테이션

2. **환경 변수 보호**:
   - GitHub Secrets 사용 (절대 코드에 하드코딩 금지)
   - 운영 서버에서 `.env` 파일 권한 제한 (`chmod 600`)

3. **네트워크 보안**:
   - 8080/8081 포트를 외부에 직접 노출하지 말 것
   - Traefik/Nginx 등 Reverse Proxy 사용

4. **Docker 보안**:
   - 최신 이미지 사용
   - 불필요한 권한 부여 금지
   - `--security-opt` 활용

---

## 참고 문서
- [ARCHITECTURE.md](./ARCHITECTURE.md) - 시스템 아키텍처
- [API.md](./API.md) - API 엔드포인트
- [GitHub Actions 문서](https://docs.github.com/en/actions)
- [Docker 문서](https://docs.docker.com/)
