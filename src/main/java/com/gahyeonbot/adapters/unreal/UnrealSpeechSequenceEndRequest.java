package com.gahyeonbot.adapters.unreal;

public record UnrealSpeechSequenceEndRequest(
        String sessionId,
        String correlationId,
        long generation,
        int utteranceCount,
        String outcome
) {
    public UnrealSpeechSequenceEndRequest {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        if (correlationId == null || correlationId.isBlank()) throw new IllegalArgumentException("correlationId is required");
        if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
        if (utteranceCount < 0) throw new IllegalArgumentException("utteranceCount must be non-negative");
        if (!"completed".equals(outcome) && !"failed".equals(outcome)) {
            throw new IllegalArgumentException("outcome must be completed or failed");
        }
    }
}
