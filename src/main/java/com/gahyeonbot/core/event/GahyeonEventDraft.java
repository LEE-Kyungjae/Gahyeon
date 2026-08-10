package com.gahyeonbot.core.event;

import com.gahyeonbot.core.session.ConversationSessionId;

import java.util.Map;

public record GahyeonEventDraft(
        String type,
        EventScope scope,
        ConversationSessionId sessionId,
        String correlationId,
        Map<String, Object> payload
) {
    public GahyeonEventDraft {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("event type이 필요합니다.");
        if (scope == null) throw new IllegalArgumentException("event scope가 필요합니다.");
        if (scope.type() == EventScopeType.SESSION && sessionId == null) {
            throw new IllegalArgumentException("SESSION scope에는 sessionId가 필요합니다.");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId가 필요합니다.");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public GahyeonEventDraft(
            String type,
            ConversationSessionId sessionId,
            String correlationId,
            Map<String, Object> payload) {
        this(type, EventScope.session(sessionId.value()), sessionId, correlationId, payload);
    }

    public static GahyeonEventDraft world(
            String type,
            String worldId,
            String correlationId,
            Map<String, Object> payload) {
        return new GahyeonEventDraft(
                type, EventScope.world(worldId), null, correlationId, payload);
    }
}
