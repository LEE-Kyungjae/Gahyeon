package com.gahyeonbot.core.life;

public record CharacterId(String value) {
    public CharacterId {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("characterId is required");
        value = value.trim().toLowerCase();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("characterId must be a safe slug");
        }
    }
}
