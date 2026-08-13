package com.gahyeonbot.adapters.unreal.protocol;

import com.gahyeonbot.core.event.GahyeonEvent;

import java.util.LinkedHashMap;
import java.util.Map;

public final class UnrealEventMapper {
    public UnrealEnvelope map(GahyeonEvent event) {
        if (event == null) throw new IllegalArgumentException("event is required");
        return new UnrealEnvelope(
                UnrealEnvelope.PROTOCOL,
                UnrealEnvelope.SCHEMA_VERSION,
                event.eventId(),
                targetType(event.type()),
                event.occurredAt(),
                event.sessionId() == null ? null : event.sessionId().value(),
                event.correlationId(),
                UnrealDelivery.DURABLE.wireValue(),
                event.sequence(),
                targetPayload(event));
    }

    private String targetType(String sourceType) {
        return switch (sourceType) {
            case "conversation.started" -> "cognition.request.started";
            case "conversation.completed" -> "cognition.response.completed";
            case "conversation.failed" -> "cognition.response.failed";
            case "conversation.cancelled" -> "cognition.request.cancelled";
            case "avatar.expression" -> "emotion.target";
            case "character.moved" -> "world.position.changed";
            case "behavior.activity.changed" -> "world.activity.changed";
            case "world.state.restored" -> "world.snapshot";
            case "world.state.changed" -> "world.snapshot";
            default -> sourceType;
        };
    }

    private Map<String, Object> targetPayload(GahyeonEvent event) {
        Map<String, Object> mapped;
        if (!"avatar.expression".equals(event.type())) {
            mapped = event.payload();
        } else {
            Object expression = event.payload().get("expression");
            Object intensity = event.payload().get("intensity");
            if (!(expression instanceof String name) || name.isBlank()
                    || !(intensity instanceof Number number)) {
                mapped = withSourceType(event);
            } else {
                var payload = new LinkedHashMap<String, Object>();
                copyIfPresent(event.payload(), payload, "revision");
                payload.put("dimensions", Map.of(name, clamp(number.doubleValue())));
                payload.put("blendSeconds", 0.25);
                mapped = Map.copyOf(payload);
            }
        }
        var generation = UnrealCorrelationId.generation(event.correlationId());
        if (generation.isEmpty()) return mapped;
        var enriched = new LinkedHashMap<>(mapped);
        enriched.put("generation", generation.getAsLong());
        return Map.copyOf(enriched);
    }

    private Map<String, Object> withSourceType(GahyeonEvent event) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("sourceType", event.type());
        payload.put("sourcePayload", event.payload());
        return Map.copyOf(payload);
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key) && source.get(key) != null) target.put(key, source.get(key));
    }

    private double clamp(double value) {
        if (!Double.isFinite(value)) return 0;
        return Math.max(0, Math.min(1, value));
    }
}
