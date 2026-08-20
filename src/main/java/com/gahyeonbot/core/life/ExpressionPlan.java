package com.gahyeonbot.core.life;

public record ExpressionPlan(
        String communicativeIntent,
        String voiceStyle,
        double intensity,
        String facialExpression,
        String gazeTarget,
        String gesture,
        boolean resumePreviousActivity
) {
    public ExpressionPlan {
        if (communicativeIntent == null || communicativeIntent.isBlank()) throw new IllegalArgumentException("communicativeIntent is required");
        if (voiceStyle == null || voiceStyle.isBlank()) throw new IllegalArgumentException("voiceStyle is required");
        if (!Double.isFinite(intensity) || intensity < 0 || intensity > 1) throw new IllegalArgumentException("intensity is invalid");
    }
}
