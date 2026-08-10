package com.gahyeonbot.core.event;

import com.gahyeonbot.core.session.ConversationSessionId;

import java.time.Instant;
import java.util.Map;

public record GahyeonEvent(
        int schemaVersion,
        String eventId,
        long sequence,
        String type,
        ConversationSessionId sessionId,
        String correlationId,
        Instant occurredAt,
        Map<String, Object> payload
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public GahyeonEvent {
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion은 1 이상이어야 합니다.");
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("eventId가 필요합니다.");
        if (sequence < 1) throw new IllegalArgumentException("sequence는 1 이상이어야 합니다.");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("event type이 필요합니다.");
        if (sessionId == null) throw new IllegalArgumentException("sessionId가 필요합니다.");
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId가 필요합니다.");
        }
        if (occurredAt == null) throw new IllegalArgumentException("occurredAt이 필요합니다.");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
