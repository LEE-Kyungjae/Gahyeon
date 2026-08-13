# ADR-0011: 중립 도메인 이름은 V24 물리 스키마에 매핑한다

## 상태

Accepted — 2026-08-12

## 배경

최신 검증 GitOps 이미지 SHA `12ebae2`에는 migration이 V24까지만 포함되어 있고,
V30~V35는 Git에 없는 로컬 untracked 파일이었다. 이 증거만으로 라이브 DB의
`flyway_schema_history`를 단정하지는 않는다. 다만 이 rename migration과 새 JPA 매핑을 다음
이미지에 함께 포함하면, 아직 적용되지 않았다는 전제에서 여러 운영 테이블·인덱스·제약을 한
번에 이름 변경하고 V24 이름을 기대하는 이전 이미지로의 애플리케이션 롤백을 깨뜨린다.

## 결정

Java와 Core 도메인은 `actorId`, `actorDisplayName`, `toolScopeId`, `modality`, `ModelUsage`를
유지한다. Hibernate와 native SQL의 물리 이름만 기존 V24 스키마에 명시적으로 매핑한다.

| 중립 Java 이름 | V24 물리 이름 |
|---|---|
| `AgentSession.modality`, `AgentRun.modality` | `gateway` |
| `toolScopeId` | `guild_id` |
| `actorId` | `user_id` |
| `AgentRun.actorDisplayName` | `username` |
| `ModelUsage` | `openai_usage` |
| `ModelUsage.requestId` | `interaction_id` |
| `ModelUsage.actorDisplayName` | `username` |

아직 적용되지 않은 V30~V35 rename 파일은 제거한다. V36의 supersession 인덱스는 기능상
필요한 비파괴 migration이므로 유지하되 V24의 `agent_runs.user_id`를 대상으로 한다.
이 선택은 두 물리 스키마를 동시에 지원하지 않는다. 현재 단일 정본은 V24 이름이다.

## 결과와 후속 작업

- 새 코드가 V24 운영 스키마에서 rename 선행 없이 시작할 수 있다.
- 기존 V24 코드 이미지로의 애플리케이션 롤백이 물리 이름 때문에 차단되지 않는다.
- 중립 물리 스키마가 실제로 필요해지면 운영 백업·dry-run·확장/전환/축소 절차를 갖춘 새
  Flyway 버전으로 별도 설계한다. 삭제한 V30~V35 버전 번호나 SQL을 재사용하지 않는다.
- 계약 테스트는 JPA annotations, native SQL, V36 대상 column, V30~V35 부재를 함께 검증한다.

이 ADR은 라이브 PostgreSQL 검사나 migration 적용 증거가 아니다. 실제 배포 전에는 V24
schema snapshot에 대한 Flyway dry-run 또는 임시 복제 DB 검증과, 라이브
`flyway_schema_history`에 V30~V35가 적용된 적 없음을 확인하는 read-only release gate가
필요하다. 그 확인이 실패하면 이 호환성 pivot을 배포하지 않고 별도 복구 계획을 세운다.
