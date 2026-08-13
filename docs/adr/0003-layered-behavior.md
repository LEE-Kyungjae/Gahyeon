# ADR-0003: Reflex, Behavior, Cognition을 분리한다

- 상태: Accepted
- 날짜: 2026-08-11

## 결정

Reflex는 Unreal 로컬 실시간 overlay, Behavior는 결정론적 상태/gesture 합성,
Cognition은 Backend의 LLM·Memory 기반 의미 결정으로 분리한다. 최상위 phase와
Emotion/Attention/Gesture를 병렬 layer로 둔다.

## 이유

LLM 지연과 장애가 캐릭터 정지로 이어지는 것을 막고, 프레임 단위 결과를 재현·조정할
수 있다.

## 결과

LLM은 각도, bone, animation asset ID를 출력하지 않는다. DataAsset profile과 로컬
controller가 같은 의미에서도 상황에 맞는 표현 변형을 선택한다.
