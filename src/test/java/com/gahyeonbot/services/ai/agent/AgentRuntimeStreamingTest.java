package com.gahyeonbot.services.ai.agent;

import com.gahyeonbot.core.identity.ActorId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRuntimeStreamingTest {
    @Test
    void defaultCapabilityPublishesOnlyTheCommittedFinalResult() {
        AgentRuntime runtime = new AgentRuntime() {
            @Override
            public AgentResult execute(AgentRequest request) {
                // Any internal tool/model-step text remains private to execute().
                return new AgentResult(
                        "run-1", "도구 결과를 반영한 최종 답변.",
                        List.of("weather"), Duration.ofMillis(10));
            }

            @Override
            public AgentResult resume(String runId, ActorId actorId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public AgentResult resumeBackground(String runId, String backgroundResult) {
                throw new UnsupportedOperationException();
            }
        };
        var deltas = new ArrayList<String>();
        var request = new AgentRequest(
                "request-1", "session-1", AgentModality.VOICE,
                null, new com.gahyeonbot.core.identity.ActorId(42L), "tester", "날씨 알려줘", 8);

        AgentResult result = runtime.executeStreaming(request, deltas::add);

        assertThat(deltas).containsExactly("도구 결과를 반영한 최종 답변.");
        assertThat(result.tools()).containsExactly("weather");
    }

    @Test
    void defaultCapabilityDoesNotStartWorkForAnAlreadyCancelledObserver() {
        var executed = new java.util.concurrent.atomic.AtomicBoolean();
        AgentRuntime runtime = new AgentRuntime() {
            @Override
            public AgentResult execute(AgentRequest request) {
                executed.set(true);
                return new AgentResult("run", "응답", List.of(), Duration.ZERO);
            }

            @Override
            public AgentResult resume(String runId, ActorId actorId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public AgentResult resumeBackground(String runId, String backgroundResult) {
                throw new UnsupportedOperationException();
            }
        };
        var request = new AgentRequest(
                "request-2", "session-1", AgentModality.VOICE,
                null, new com.gahyeonbot.core.identity.ActorId(42L), "tester", "취소", 8);

        assertThatThrownBy(() -> runtime.executeStreaming(request, new AgentStreamObserver() {
            @Override
            public void onTextDelta(String delta) {}

            @Override
            public boolean isCancelled() {
                return true;
            }
        })).isInstanceOf(AgentStreamCancelledException.class);
        assertThat(executed).isFalse();
    }
}
