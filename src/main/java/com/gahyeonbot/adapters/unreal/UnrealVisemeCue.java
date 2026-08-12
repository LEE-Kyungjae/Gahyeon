package com.gahyeonbot.adapters.unreal;

public record UnrealVisemeCue(
        String semantic,
        long atMs,
        long durationMs,
        double weight
) {
    public UnrealVisemeCue {
        if (semantic == null || semantic.isBlank()) {
            throw new IllegalArgumentException("viseme semantic is required");
        }
        semantic = semantic.trim();
        if (atMs < 0 || durationMs <= 0) {
            throw new IllegalArgumentException("viseme timing is invalid");
        }
        if (!Double.isFinite(weight) || weight <= 0 || weight > 1) {
            throw new IllegalArgumentException("viseme weight must be in (0, 1]");
        }
    }
}
