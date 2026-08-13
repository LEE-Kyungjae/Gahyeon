# ADR-0004: 정책은 C++, 연출은 Blueprint/DataAsset에 둔다

- 상태: Accepted
- 날짜: 2026-08-11

## 결정

Protocol, 검증, 상태 전이, 우선순위, interruption, controller interface와 테스트는
C++로 구현한다. MetaHuman 조립, animation graph, Control Rig tuning, 표현 profile은
Blueprint/Anim Blueprint/DataAsset으로 제작한다.

## 이유

핵심 규칙을 자동 테스트하고 source review할 수 있으면서, 캐릭터 연출은 Unreal
Editor에서 빠르게 반복할 수 있다.

## 결과

단일 거대 Blueprint와 Tick 기반 도메인 로직을 금지한다. Tick은 보간, IK, look-at,
procedural secondary motion에만 사용한다.
