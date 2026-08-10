package com.gahyeonbot.application.conversation;

import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.conversation.ConversationResponse;
import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.core.session.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GahyeonConversationServiceTest {
    @Test
    void delegatesOnlyThePlatformNeutralRequestToTheAgentPort() {
        AtomicReference<ConversationRequest> captured = new AtomicReference<>();
        ConversationAgentPort port = request -> {
            captured.set(request);
            return new ConversationResponse("run-1", "안녕하세요.", List.of(), Duration.ofMillis(5));
        };
        var service = new GahyeonConversationService(port);
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
    }
}
