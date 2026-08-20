package com.gahyeonbot.application.life;

import com.gahyeonbot.core.life.ExpressionPlan;

public record CharacterCognitionResult(
        boolean speak,
        String utterance,
        String memoryNote,
        double memoryImportance,
        ExpressionPlan expressionPlan
) {
    public CharacterCognitionResult {
        utterance = normalize(utterance);
        memoryNote = normalize(memoryNote);
        if (speak && utterance == null) throw new IllegalArgumentException("spoken cognition requires an utterance");
        if (!Double.isFinite(memoryImportance) || memoryImportance < 0 || memoryImportance > 1) throw new IllegalArgumentException("memoryImportance is invalid");
        if (expressionPlan == null) throw new IllegalArgumentException("expressionPlan is required");
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
