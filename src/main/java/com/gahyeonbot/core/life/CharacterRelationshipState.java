package com.gahyeonbot.core.life;

import com.gahyeonbot.core.world.WorldId;
import java.time.Instant;

public record CharacterRelationshipState(
        CharacterId characterId,
        WorldId worldId,
        String subjectId,
        long revision,
        double familiarity,
        double trust,
        double affinity,
        double tension,
        Instant lastInteractionAt,
        Instant updatedAt
) {
    public CharacterRelationshipState {
        if (characterId == null || worldId == null) throw new IllegalArgumentException("relationship namespace is required");
        if (subjectId == null || subjectId.isBlank()) throw new IllegalArgumentException("subjectId is required");
        if (revision < 0) throw new IllegalArgumentException("revision is invalid");
        unit(familiarity, "familiarity"); unit(trust, "trust"); unit(affinity, "affinity"); unit(tension, "tension");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }

    public static CharacterRelationshipState initial(CharacterId characterId, WorldId worldId,
            String subjectId, Instant now) {
        return new CharacterRelationshipState(characterId, worldId, subjectId, 0,
                0.05, 0.50, 0.35, 0, null, now);
    }

    private static void unit(double value, String field) {
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException(field + " is invalid");
    }
}
