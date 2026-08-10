package com.gahyeonbot.application.conversation;

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
                GahyeonEventTypes.CONVERSATION_COMPLETED);
        assertThat(events.getLast().payload()).containsEntry("content", "안녕하세요.");
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
                GahyeonEventTypes.CONVERSATION_FAILED);
    }
}
