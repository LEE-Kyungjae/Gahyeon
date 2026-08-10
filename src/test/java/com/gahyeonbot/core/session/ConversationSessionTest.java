package com.gahyeonbot.core.session;

import com.gahyeonbot.core.identity.ActorId;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationSessionTest {
    @Test
    void copiesClientContextAtTheCoreBoundary() {
        Map<String, String> context = new HashMap<>();
        context.put("discord.guildId", "10");

        var session = new ConversationSession(
                new ConversationSessionId("session-1"),
                new ActorId(20),
                ClientSource.DISCORD,
                ConversationModality.TEXT,
                context);
        context.put("discord.guildId", "changed");

        assertThat(session.clientContext()).containsEntry("discord.guildId", "10");
        assertThatThrownBy(() -> session.clientContext().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInvalidInternalIdentityAndSessionId() {
        assertThatThrownBy(() -> new ActorId(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConversationSessionId(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
