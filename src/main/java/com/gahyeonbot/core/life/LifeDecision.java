package com.gahyeonbot.core.life;

public record LifeDecision(
        LifeDisposition disposition,
        String reason,
        CharacterLifeState nextState,
        ExpressionPlan expressionPlan
) {
    public LifeDecision {
        if (disposition == null) throw new IllegalArgumentException("disposition is required");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason is required");
        if (nextState == null) throw new IllegalArgumentException("nextState is required");
        if (disposition == LifeDisposition.COGNITION && expressionPlan == null) {
            throw new IllegalArgumentException("cognition requires an expression plan");
        }
    }
}
