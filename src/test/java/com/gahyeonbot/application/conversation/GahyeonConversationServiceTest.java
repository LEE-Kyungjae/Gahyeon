package com.gahyeonbot.application.conversation;

import com.gahyeonbot.application.behavior.ConversationPresencePort;
import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.conversation.ConversationResponse;
import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.core.event.GahyeonEvent;
import com.gahyeonbot.core.event.GahyeonEventDraft;
import com.gahyeonbot.core.event.GahyeonEventTypes;
import com.gahyeonbot.core.session.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GahyeonConversationServiceTest {
    @Test
    void delegatesOnlyThePlatformNeutralRequestToTheAgentPort() {
        AtomicReference<ConversationRequest> captured = new AtomicReference<>();
        ConversationAgentPort port = request -> {
            captured.set(request);
            return new ConversationResponse("run-1", "안녕하세요.", List.of(), Duration.ofMillis(5));
        };
        List<GahyeonEventDraft> events = new ArrayList<>();
        var service = new GahyeonConversationService(port, event -> {
            events.add(event);
            return new GahyeonEvent(1, "event-" + events.size(), events.size(),
                    event.type(), event.sessionId(), event.correlationId(), Instant.now(), event.payload());
        });
        var request = new ConversationRequest(
                "request-1",
                new ConversationSession(
                        new ConversationSessionId("desktop-session"),
                        new ActorId(42),
                        ClientSource.DESKTOP,
                        ConversationModality.TEXT,
                        Map.of()),
                "tester",
                "안녕");

        ConversationResponse response = service.converse(request);

        assertThat(captured.get()).isSameAs(request);
        assertThat(response.content()).isEqualTo("안녕하세요.");
        assertThat(events).extracting(GahyeonEventDraft::type).containsExactly(
                GahyeonEventTypes.CONVERSATION_STARTED,
                GahyeonEventTypes.CHARACTER_STATE_TARGET,
                GahyeonEventTypes.CONVERSATION_COMPLETED,
                GahyeonEventTypes.CHARACTER_STATE_TARGET);
        assertThat(events.get(1).payload()).containsEntry("state", "thinking");
        assertThat(events.get(2).payload()).containsEntry("content", "안녕하세요.");
        assertThat(events.getLast().payload()).containsEntry("state", "idle");
    }

    @Test
    void publishesFailureWithoutSwallowingTheOriginalException() {
        List<GahyeonEventDraft> events = new ArrayList<>();
        RuntimeException expected = new IllegalStateException("provider unavailable");
        var service = new GahyeonConversationService(request -> { throw expected; }, event -> {
            events.add(event);
            return new GahyeonEvent(1, "event-" + events.size(), events.size(),
                    event.type(), event.sessionId(), event.correlationId(), Instant.now(), event.payload());
        });
        var request = new ConversationRequest(
                "request-failed",
                new ConversationSession(
                        new ConversationSessionId("headless-session"),
                        new ActorId(7),
                        ClientSource.HEADLESS,
                        ConversationModality.TEXT,
                        Map.of()),
                "tester",
                "실패 테스트");

        assertThatThrownBy(() -> service.converse(request)).isSameAs(expected);
        assertThat(events).extracting(GahyeonEventDraft::type).containsExactly(
                GahyeonEventTypes.CONVERSATION_STARTED,
                GahyeonEventTypes.CHARACTER_STATE_TARGET,
                GahyeonEventTypes.CONVERSATION_FAILED,
                GahyeonEventTypes.CHARACTER_STATE_TARGET);
        assertThat(events.getLast().payload()).containsEntry("state", "idle");
    }

    @Test
    void forwardsStreamingDeltasWithoutRepeatingTheFinalResponse() {
        List<String> deltas = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean();
        StreamingConversationAgentPort port = new StreamingConversationAgentPort() {
            @Override
            public ConversationResponse executeStreaming(
                    ConversationRequest request,
                    ConversationStreamObserver observer) {
                observer.onTextDelta("첫 문장.");
                observer.onTextDelta("둘째 문장.");
                return new ConversationResponse(
                        "run-stream", "첫 문장.둘째 문장.", List.of(), Duration.ofMillis(4));
            }

            @Override
            public ConversationResponse execute(ConversationRequest request) {
                throw new AssertionError("streaming capability should be preferred");
            }
        };
        var service = new GahyeonConversationService(port, event -> new GahyeonEvent(
                2, "event", 1, event.type(), event.sessionId(),
                event.correlationId(), Instant.EPOCH, event.payload()));

        service.converseStreaming(request("stream-request"), new ConversationStreamObserver() {
            @Override
            public void onTextDelta(String delta) {
                deltas.add(delta);
            }

            @Override
            public void onCompleted(ConversationResponse response) {
                completed.set(true);
            }
        });

        assertThat(deltas).containsExactly("첫 문장.", "둘째 문장.");
        assertThat(completed).isTrue();
    }

    @Test
    void adaptsSynchronousAgentResponseIntoOneStreamingDelta() {
        var service = new GahyeonConversationService(
                request -> new ConversationResponse("run-sync", "동기 응답", List.of(), Duration.ZERO),
                event -> new GahyeonEvent(2, "event", 1, event.type(), event.sessionId(),
                        event.correlationId(), Instant.EPOCH, event.payload()));
        List<String> deltas = new ArrayList<>();

        service.converseStreaming(request("sync-request"), deltas::add);

        assertThat(deltas).containsExactly("동기 응답");
    }

    @Test
    void observerFailureDoesNotTurnASuccessfulConversationIntoFailure() {
        List<GahyeonEventDraft> events = new ArrayList<>();
        var service = new GahyeonConversationService(
                request -> new ConversationResponse("run-ok", "정상 응답", List.of(), Duration.ZERO),
                event -> {
                    events.add(event);
                    return new GahyeonEvent(3, "event-" + events.size(), events.size(), event.type(),
                            event.sessionId(), event.correlationId(), Instant.EPOCH, event.payload());
                });

        ConversationResponse response = service.converseStreaming(
                request("observer-failure"),
                delta -> { throw new IllegalStateException("renderer disconnected"); });

        assertThat(response.content()).isEqualTo("정상 응답");
        assertThat(events).extracting(GahyeonEventDraft::type).containsExactly(
                GahyeonEventTypes.CONVERSATION_STARTED,
                GahyeonEventTypes.CHARACTER_STATE_TARGET,
                GahyeonEventTypes.CONVERSATION_COMPLETED,
                GahyeonEventTypes.CHARACTER_STATE_TARGET);
    }

    @Test
    void recordsSupersededStreamingWorkAsCancelledInsteadOfFailed() {
        List<GahyeonEventDraft> events = new ArrayList<>();
        RuntimeException cancellation = new RuntimeException("interrupted");
        var service = new GahyeonConversationService(request -> { throw cancellation; }, event -> {
            events.add(event);
            return new GahyeonEvent(4, "event-" + events.size(), events.size(), event.type(),
                    event.sessionId(), event.correlationId(), Instant.EPOCH, event.payload());
        });

        assertThatThrownBy(() -> service.converseStreaming(
                request("cancelled-request"), new ConversationStreamObserver() {
                    @Override
                    public void onTextDelta(String delta) {}

                    @Override
                    public boolean isCancelled() {
                        return true;
                    }
                })).isSameAs(cancellation);

        assertThat(events).extracting(GahyeonEventDraft::type).containsExactly(
                GahyeonEventTypes.CONVERSATION_STARTED,
                GahyeonEventTypes.CHARACTER_STATE_TARGET,
                GahyeonEventTypes.CONVERSATION_CANCELLED,
                GahyeonEventTypes.CHARACTER_STATE_TARGET);
    }

    @Test
    void acquiresPresenceBeforeStartedEventAndCognitionThenClosesLease() {
        List<String> order = new ArrayList<>();
        ConversationPresencePort presence = session -> {
            order.add("presence.enter");
            return () -> order.add("presence.close");
        };
        ConversationAgentPort agent = request -> {
            order.add("agent.execute");
            return new ConversationResponse(
                    "run-order", "응답", List.of(), Duration.ZERO);
        };
        var service = new GahyeonConversationService(agent, event -> {
            order.add("event." + event.type());
            return new GahyeonEvent(5, "event-" + order.size(), order.size(), event.type(),
                    event.sessionId(), event.correlationId(), Instant.EPOCH, event.payload());
        }, presence);

        service.converseStreaming(request("presence-order"), delta -> {});

        assertThat(order).containsExactly(
                "presence.enter",
                "event." + GahyeonEventTypes.CONVERSATION_STARTED,
                "event." + GahyeonEventTypes.CHARACTER_STATE_TARGET,
                "agent.execute",
                "event." + GahyeonEventTypes.CONVERSATION_COMPLETED,
                "event." + GahyeonEventTypes.CHARACTER_STATE_TARGET,
                "presence.close");
    }

    @Test
    void cancellationStillClosesPresenceLeaseExactlyOnce() {
        AtomicBoolean closed = new AtomicBoolean();
        ConversationPresencePort presence = session -> () -> {
            if (!closed.compareAndSet(false, true)) {
                throw new AssertionError("presence lease closed twice");
            }
        };
        var service = new GahyeonConversationService(
                request -> { throw new RuntimeException("superseded"); },
                event -> new GahyeonEvent(6, "event", 1, event.type(), event.sessionId(),
                        event.correlationId(), Instant.EPOCH, event.payload()),
                presence);

        assertThatThrownBy(() -> service.converseStreaming(
                request("presence-cancel"), new ConversationStreamObserver() {
                    @Override
                    public void onTextDelta(String delta) {}

                    @Override
                    public boolean isCancelled() { return true; }
                })).isInstanceOf(RuntimeException.class);

        assertThat(closed).isTrue();
    }

    private ConversationRequest request(String requestId) {
        return new ConversationRequest(
                requestId,
                new ConversationSession(
                        new ConversationSessionId("desktop-session"),
                        new ActorId(42),
                        ClientSource.DESKTOP,
                        ConversationModality.TEXT,
                        Map.of()),
                "tester",
                "안녕");
    }
}
