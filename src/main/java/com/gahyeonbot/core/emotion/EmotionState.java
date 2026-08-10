package com.gahyeonbot.core.emotion;

public record EmotionState(String name, double intensity) {
    public static final EmotionState NEUTRAL = new EmotionState("neutral", 0);

    public EmotionState {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("emotion name is required");
        if (!Double.isFinite(intensity) || intensity < 0 || intensity > 1) {
            throw new IllegalArgumentException("emotion intensity must be between 0 and 1");
        }
        name = name.trim();
    }
}
