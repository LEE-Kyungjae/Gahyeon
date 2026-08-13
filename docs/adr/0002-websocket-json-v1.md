# ADR-0002: Unreal 연결은 WebSocket JSON v1으로 시작한다

- 상태: Accepted
- 날짜: 2026-08-11

## 결정

기존 Desktop HTTP/SSE를 유지하면서 Unreal 전용 WebSocket Adapter를 추가한다.
Versioned JSON envelope, durable sequence resume, snapshot, correlation ID를 사용한다.
Audio는 초기에는 URL로 전달한다.

## 이유

양방향 VAD/관측/ack가 필요하며 초기 디버깅과 event replay가 쉽다. 현 단계에서
Protobuf/gRPC의 복잡성은 이득보다 크다.

## 결과

고빈도 ephemeral 값과 durable domain event를 구분해야 한다. 성능 측정으로 JSON이
병목임이 확인될 때만 binary/Protobuf를 별도 ADR로 검토한다.
