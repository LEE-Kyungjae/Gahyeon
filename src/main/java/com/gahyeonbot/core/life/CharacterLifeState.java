package com.gahyeonbot.core.life;

import com.gahyeonbot.core.world.WorldId;
import java.time.Instant;

public record CharacterLifeState(
        CharacterId characterId,
        WorldId worldId,
        long revision,
        String activity,
        double valence,
        double arousal,
        double socialNeed,
        double curiosityNeed,
        double restNeed,
        String attentionTarget,
        String currentGoal,
        String prospectiveIntention,
        Instant lastInteractionAt,
        Instant lastInitiativeAt,
        Instant updatedAt
) {
    public CharacterLifeState {
        if (characterId == null) throw new IllegalArgumentException("characterId is required");
        if (worldId == null) throw new IllegalArgumentException("worldId is required");
        if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
        if (activity == null || activity.isBlank()) throw new IllegalArgumentException("activity is required");
        requireRange(valence, -1, 1, "valence");
        requireRange(arousal, 0, 1, "arousal");
        requireRange(socialNeed, 0, 1, "socialNeed");
        requireRange(curiosityNeed, 0, 1, "curiosityNeed");
        requireRange(restNeed, 0, 1, "restNeed");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
    }

    public static CharacterLifeState initial(CharacterId characterId, WorldId worldId, Instant now) {
        return new CharacterLifeState(characterId, worldId, 0, "idle", 0, 0.15,
                0.20, 0.25, 0.10, null, "live_well_today", null, null, null, now);
    }

    private static void requireRange(double value, double minimum, double maximum, String field) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " is out of range");
        }
    }
}
