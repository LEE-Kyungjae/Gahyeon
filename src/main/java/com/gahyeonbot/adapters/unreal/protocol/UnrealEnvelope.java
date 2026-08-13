package com.gahyeonbot.adapters.unreal.protocol;

import java.time.Instant;
import java.util.Map;

public record UnrealEnvelope(
        String protocol,
        int schemaVersion,
        String messageId,
        String type,
        Instant sentAt,
        String sessionId,
        String correlationId,
        String delivery,
        Long sequence,
        Map<String, Object> payload
) {
    public static final String PROTOCOL = "gahyeon.unreal.v1";
    public static final int SCHEMA_VERSION = 1;

    public UnrealEnvelope {
        if (!PROTOCOL.equals(protocol)) throw new IllegalArgumentException("unsupported protocol");
        if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("unsupported schema version");
        requireText(messageId, "messageId");
        requireText(type, "type");
        if (sentAt == null) throw new IllegalArgumentException("sentAt is required");
        requireText(correlationId, "correlationId");
        requireText(delivery, "delivery");
        if (UnrealDelivery.DURABLE.wireValue().equals(delivery)) {
            if (sequence == null || sequence < 1) {
                throw new IllegalArgumentException("durable message requires a positive sequence");
            }
        } else if (sequence != null) {
            throw new IllegalArgumentException("only durable messages may carry a sequence");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
