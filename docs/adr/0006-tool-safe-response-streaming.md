# ADR-0006: Tool-safe response streaming

- Status: Accepted
- Date: 2026-08-11

## Context

낮은 음성 응답 지연을 위해 모델 토큰을 문장 단위로 TTS에 넘겨야 한다. 하지만 현재
AgentRuntime은 같은 모델 step에서 일반 텍스트와 tool call을 함께 받을 수 있다.
그 step의 텍스트를 도착 즉시 읽으면, 뒤이어 tool call이 확인됐을 때 사용자에게
중간 추론이나 폐기될 답변을 이미 말한 상태가 될 수 있다.

## Decision

Application에는 `StreamingConversationAgentPort`와 `ConversationStreamObserver`를 두고,
Unreal 경로는 완성 문장을 즉시 TTS 준비 큐로 넘긴다. 스트리밍을 지원하지 않는
provider는 최종 응답을 단일 delta로 변환한다.

Provider 구현은 다음 규칙을 지켜야 한다.

1. tool call 가능성이 남아 있는 model step의 텍스트는 speakable delta로 확정하지 않는다.
2. tool call step은 실행·승인·원장 기록을 마친 후 다음 step으로 넘어간다.
3. 최종 answer step임이 보장된 경우에만 token delta를 외부 observer에 공개한다.
4. 이 보장이 없는 provider는 동기 fallback을 유지한다.
5. observer/TTS/renderer 실패는 성공한 cognition 결과를 실패로 바꾸지 않는다.
6. generation이 바뀐 뒤 도착한 delta와 TTS 결과는 폐기한다.

## Consequences

- `GAHYEON_AGENT_TOOL_SAFE_STREAMING_ENABLED=true`는 검증된 provider base URL/model
  조합에서만 활성화한다. 둘 중 하나라도 현재 설정과 다르면 동기 fallback하며 기본값은
  `false`다.
- 활성화 시 AgentRuntime은 `ChatModel.stream()`의 text delta를 즉시 전달하고 tool-call
  step은 전달하지 않는다. 선행 `<think>...</think>` 블록도 증분 필터에서 제거한다.
- 선언된 배타 계약을 provider가 위반하면 해당 요청은 실패시키고 metric을 기록한 뒤,
  프로세스 수명 동안 이후 요청을 동기 fallback으로 자동 전환한다.
- 단순히 `ChatModel.call`을 `ChatModel.stream`으로 치환하지 않는다.
- provider/model별 활성화 전에는 direct-answer, tool-call, multi-tool, reasoning-tag fixture를
  실제 endpoint에 대조해야 한다.
