package com.gahyeonbot.adapters.unreal;

public record UnrealSpeechPreparationRequest(
        String sessionId,
        String correlationId,
        long generation,
        int utteranceIndex,
        String text
) {
    public UnrealSpeechPreparationRequest(
            String sessionId,
            String correlationId,
            long generation,
            String text) {
        this(sessionId, correlationId, generation, 0, text);
    }

    public UnrealSpeechPreparationRequest {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        if (correlationId == null || correlationId.isBlank()) throw new IllegalArgumentException("correlationId is required");
        if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
        if (utteranceIndex < 0) throw new IllegalArgumentException("utteranceIndex must be non-negative");
        if (text == null || text.isBlank()) throw new IllegalArgumentException("text is required");
    }
}
