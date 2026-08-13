# ADR-0001: Unreal은 Gahyeon의 Presentation Client다

- 상태: Accepted
- 날짜: 2026-08-11

## 결정

Unreal Stage는 독립 AI Backend가 아니라 Gahyeon Core에 연결되는 Client/Adapter로
구현한다. STT/TTS/LLM/Memory의 provider와 World의 정본은 Backend에 둔다. Unreal은
입력 수집, 로컬 Reflex, 행동 표현, animation, audio playback, rendering을 맡는다.

## 이유

Discord, 현재 Desktop, Unreal이 동일한 AI와 기억을 공유할 수 있고 renderer 교체가
Core에 영향을 주지 않는다. Backend 단절 시에도 로컬 idle/reflex는 유지한다.

## 결과

Unreal C++에는 provider 구현 대신 transport와 presentation controller interface가
들어간다. Engine 전용 타입은 Core Java domain으로 유입하지 않는다.
