package com.gahyeonbot.core.conversation;

import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.core.session.ClientSource;
import com.gahyeonbot.core.session.ConversationModality;
import com.gahyeonbot.core.session.ConversationSession;
import com.gahyeonbot.core.session.ConversationSessionId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationRequestTest {
    @Test
    void acceptsExactLimitsAndNormalizesBoundaryWhitespace() {
        var request = new ConversationRequest(
                " " + "r".repeat(ConversationRequest.MAXIMUM_REQUEST_ID_CHARACTERS) + " ",
                session(),
                " " + "d".repeat(ConversationRequest.MAXIMUM_DISPLAY_NAME_CHARACTERS) + " ",
                " " + "가".repeat(ConversationRequest.MAXIMUM_MESSAGE_CHARACTERS) + " ");

        assertThat(request.requestId()).hasSize(ConversationRequest.MAXIMUM_REQUEST_ID_CHARACTERS);
        assertThat(request.displayName()).hasSize(ConversationRequest.MAXIMUM_DISPLAY_NAME_CHARACTERS);
        assertThat(request.message()).hasSize(ConversationRequest.MAXIMUM_MESSAGE_CHARACTERS);
    }

    @Test
    void rejectsEveryUnboundedCallerControlledTextField() {
        assertThatThrownBy(() -> new ConversationRequest(
                "r".repeat(ConversationRequest.MAXIMUM_REQUEST_ID_CHARACTERS + 1),
                session(), "tester", "hello"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestId");
        assertThatThrownBy(() -> new ConversationRequest(
                "request", session(),
                "d".repeat(ConversationRequest.MAXIMUM_DISPLAY_NAME_CHARACTERS + 1), "hello"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("displayName");
        assertThatThrownBy(() -> new ConversationRequest(
                "request", session(), "tester",
                "m".repeat(ConversationRequest.MAXIMUM_MESSAGE_CHARACTERS + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message");
    }

    @Test
    void preservesTheExistingUnknownDisplayNameFallback() {
        assertThat(new ConversationRequest("request", session(), "  ", "hello").displayName())
                .isEqualTo("unknown");
    }

    private ConversationSession session() {
        return new ConversationSession(
                new ConversationSessionId("desktop:test"),
                new ActorId(42),
                ClientSource.DESKTOP,
                ConversationModality.TEXT,
                Map.of());
    }
}
