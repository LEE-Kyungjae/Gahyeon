# ADR-0005: 세 계층은 비차단·동시 실행한다

- 상태: Accepted
- 날짜: 2026-08-11

## 결정

Reflex, Behavior, Cognition을 직렬 pipeline이나 하나의 거대 상태머신으로 구현하지
않는다. Reflex와 Behavior는 Unreal 로컬에서 계속 실행하고 Cognition은 Backend의
비동기 작업으로 실행한다. 결과는 timestamp, priority, expiry, correlation,
generation을 가진 intent로 교환하고 Presentation의 arbiter가 합성한다.

## 이유

LLM, Memory, STT, TTS와 네트워크 지연은 가변적이다. 이를 Game Thread나 캐릭터
상태 전이에 직접 연결하면 캐릭터가 멈추고 오래된 응답이 새 상호작용을 덮는다.

## 결과

- Game Thread에서는 network/LLM future를 기다리지 않는다.
- 사용자의 새 turn과 barge-in은 cancellation generation을 증가시킨다.
- 늦게 도착한 이전 generation 결과는 폐기한다.
- Backend 단절 중에도 ambient behavior와 reflex가 유지된다.
- 그래픽 자산 작업 전에 지연·실패·순서 역전 harness를 통과해야 한다.
