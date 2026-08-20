package com.gahyeonbot.application.life;

import com.gahyeonbot.core.life.CharacterMemoryKind;
import java.time.Instant;

public record CharacterMemoryCandidate(
        CharacterMemoryKind kind,
        String memoryKey,
        String content,
        double importance,
        double confidence,
        double emotionalWeight,
        Instant expiresAt
) {
    public CharacterMemoryCandidate {
        if (kind != CharacterMemoryKind.EPISODIC && kind != CharacterMemoryKind.SEMANTIC
                && kind != CharacterMemoryKind.RELATIONSHIP && kind != CharacterMemoryKind.PROSPECTIVE) {
            throw new IllegalArgumentException("unsupported consolidated memory kind");
        }
        if (content == null || content.isBlank() || content.length() > 500) throw new IllegalArgumentException("memory content is invalid");
        if (memoryKey != null && !memoryKey.matches("[a-z0-9][a-z0-9._-]{0,159}")) throw new IllegalArgumentException("memoryKey is invalid");
        if (!Double.isFinite(importance) || importance < 0 || importance > 1) throw new IllegalArgumentException("importance is invalid");
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) throw new IllegalArgumentException("confidence is invalid");
        if (!Double.isFinite(emotionalWeight) || emotionalWeight < -1 || emotionalWeight > 1) throw new IllegalArgumentException("emotionalWeight is invalid");
        content = content.trim();
    }

    public CharacterMemoryCandidate(CharacterMemoryKind kind, String content, double importance,
            double confidence, double emotionalWeight, Instant expiresAt) {
        this(kind, null, content, importance, confidence, emotionalWeight, expiresAt);
    }
}
