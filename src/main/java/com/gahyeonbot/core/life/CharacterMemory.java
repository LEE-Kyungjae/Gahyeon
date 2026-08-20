package com.gahyeonbot.core.life;

import com.gahyeonbot.core.world.WorldId;
import java.time.Instant;

public record CharacterMemory(
        long id,
        CharacterId characterId,
        WorldId worldId,
        String subjectId,
        CharacterMemoryKind kind,
        String memoryKey,
        String content,
        double importance,
        double confidence,
        double emotionalWeight,
        Instant expiresAt,
        Instant lastAccessedAt,
        Instant createdAt
) {
    public CharacterMemory {
        if (characterId == null || worldId == null) throw new IllegalArgumentException("character and world are required");
        if (subjectId != null && (subjectId.isBlank() || subjectId.length() > 160)) throw new IllegalArgumentException("subjectId is invalid");
        if (kind == null) throw new IllegalArgumentException("kind is required");
        if (memoryKey != null && !memoryKey.matches("[a-z0-9][a-z0-9._-]{0,159}")) throw new IllegalArgumentException("memoryKey is invalid");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("content is required");
        if (!Double.isFinite(importance) || importance < 0 || importance > 1) throw new IllegalArgumentException("importance is invalid");
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) throw new IllegalArgumentException("confidence is invalid");
        if (!Double.isFinite(emotionalWeight) || emotionalWeight < -1 || emotionalWeight > 1) throw new IllegalArgumentException("emotionalWeight is invalid");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (lastAccessedAt == null) lastAccessedAt = createdAt;
    }

    public CharacterMemory(long id, CharacterId characterId, WorldId worldId, String kind,
            String content, double importance, Instant createdAt) {
        this(id, characterId, worldId, null, CharacterMemoryKind.parse(kind), null, content,
                importance, 0.75, 0, null, createdAt, createdAt);
    }

    public CharacterMemory(long id, CharacterId characterId, WorldId worldId, String subjectId,
            CharacterMemoryKind kind, String content, double importance, double confidence,
            double emotionalWeight, Instant expiresAt, Instant lastAccessedAt, Instant createdAt) {
        this(id, characterId, worldId, subjectId, kind, null, content, importance, confidence,
                emotionalWeight, expiresAt, lastAccessedAt, createdAt);
    }

    public boolean expiredAt(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}
