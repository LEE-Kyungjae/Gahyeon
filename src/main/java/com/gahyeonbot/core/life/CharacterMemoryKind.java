package com.gahyeonbot.core.life;

public enum CharacterMemoryKind {
    WORKING,
    EPISODIC,
    SEMANTIC,
    RELATIONSHIP,
    PROSPECTIVE,
    REFLECTION,
    UTTERANCE;

    public static CharacterMemoryKind parse(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("memory kind is required");
        return valueOf(value.trim().toUpperCase());
    }
}
