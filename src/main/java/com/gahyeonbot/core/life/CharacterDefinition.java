package com.gahyeonbot.core.life;

import java.time.Duration;

public record CharacterDefinition(
        CharacterId id,
        String displayName,
        boolean primary,
        String personaPrompt,
        String voiceProfile,
        String expressionProfile,
        boolean autonomousEnabled,
        double initiativeThreshold,
        Duration initiativeCooldown,
        double socialDriftPerHour,
        double curiosityDriftPerHour,
        double restDriftPerHour
) {
    public CharacterDefinition(
            CharacterId id, String displayName, boolean primary, String personaPrompt,
            String voiceProfile, String expressionProfile, double initiativeThreshold,
            Duration initiativeCooldown, double socialDriftPerHour,
            double curiosityDriftPerHour, double restDriftPerHour) {
        this(id, displayName, primary, personaPrompt, voiceProfile, expressionProfile, primary,
                initiativeThreshold, initiativeCooldown, socialDriftPerHour, curiosityDriftPerHour,
                restDriftPerHour);
    }

    public CharacterDefinition {
        if (id == null) throw new IllegalArgumentException("character id is required");
        if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("displayName is required");
        if (personaPrompt == null || personaPrompt.isBlank()) throw new IllegalArgumentException("personaPrompt is required");
        if (voiceProfile == null || voiceProfile.isBlank()) throw new IllegalArgumentException("voiceProfile is required");
        if (expressionProfile == null || expressionProfile.isBlank()) throw new IllegalArgumentException("expressionProfile is required");
        requireUnit(initiativeThreshold, "initiativeThreshold");
        if (initiativeCooldown == null || initiativeCooldown.isNegative()) throw new IllegalArgumentException("initiativeCooldown is invalid");
        requireFiniteNonNegative(socialDriftPerHour, "socialDriftPerHour");
        requireFiniteNonNegative(curiosityDriftPerHour, "curiosityDriftPerHour");
        requireFiniteNonNegative(restDriftPerHour, "restDriftPerHour");
    }

    private static void requireUnit(double value, String field) {
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException(field + " must be between 0 and 1");
    }

    private static void requireFiniteNonNegative(double value, String field) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException(field + " must be finite and non-negative");
    }
}
