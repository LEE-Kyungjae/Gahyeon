package com.gahyeonbot.adapters.unreal.protocol;

import java.util.OptionalLong;

public final class UnrealCorrelationId {
    private static final String PREFIX = "unreal:g";

    private UnrealCorrelationId() {}

    public static String command(long generation, String messageId) {
        if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
        if (messageId == null || messageId.isBlank()) throw new IllegalArgumentException("messageId is required");
        return PREFIX + generation + ":" + messageId;
    }

    public static OptionalLong generation(String correlationId) {
        if (correlationId == null || !correlationId.startsWith(PREFIX)) return OptionalLong.empty();
        int separator = correlationId.indexOf(':', PREFIX.length());
        if (separator < 0) return OptionalLong.empty();
        try {
            long value = Long.parseLong(correlationId.substring(PREFIX.length(), separator));
            return value < 0 ? OptionalLong.empty() : OptionalLong.of(value);
        } catch (NumberFormatException ignored) {
            return OptionalLong.empty();
        }
    }
}
