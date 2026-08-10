package com.gahyeonbot.core.session;

import com.gahyeonbot.core.identity.ActorId;

import java.util.Map;

public record ConversationSession(
        ConversationSessionId id,
        ActorId actorId,
        ClientSource source,
        ConversationModality modality,
        Map<String, String> clientContext
) {
    public ConversationSession {
        if (id == null) throw new IllegalArgumentException("sessionId가 필요합니다.");
        if (actorId == null) throw new IllegalArgumentException("actorId가 필요합니다.");
        if (source == null) throw new IllegalArgumentException("source가 필요합니다.");
        if (modality == null) throw new IllegalArgumentException("modality가 필요합니다.");
        clientContext = clientContext == null ? Map.of() : Map.copyOf(clientContext);
    }
}
