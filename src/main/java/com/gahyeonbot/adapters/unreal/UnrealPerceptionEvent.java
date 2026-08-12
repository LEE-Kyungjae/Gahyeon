package com.gahyeonbot.adapters.unreal;

import java.time.Instant;
import java.util.Map;

public record UnrealPerceptionEvent(
        String type,
        String sessionId,
        String worldId,
        long generation,
        Instant receivedAt,
        Map<String, Object> payload
) {
    public UnrealPerceptionEvent {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type is required");
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        if (worldId == null || worldId.isBlank()) throw new IllegalArgumentException("worldId is required");
        if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
        if (receivedAt == null) throw new IllegalArgumentException("receivedAt is required");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
