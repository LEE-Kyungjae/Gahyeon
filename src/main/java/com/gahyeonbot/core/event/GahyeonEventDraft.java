package com.gahyeonbot.core.event;

import com.gahyeonbot.core.session.ConversationSessionId;

import java.util.Map;

public record GahyeonEventDraft(
        String type,
        ConversationSessionId sessionId,
        String correlationId,
        Map<String, Object> payload
) {
    public GahyeonEventDraft {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("event type이 필요합니다.");
        if (sessionId == null) throw new IllegalArgumentException("sessionId가 필요합니다.");
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId가 필요합니다.");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
