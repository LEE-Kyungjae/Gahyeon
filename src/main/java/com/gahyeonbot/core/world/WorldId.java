package com.gahyeonbot.core.world;

public record WorldId(String value) {
    public WorldId {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("worldId가 필요합니다.");
    }
}
