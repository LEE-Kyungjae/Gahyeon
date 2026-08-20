package com.gahyeonbot.application.speech;

/** Compact context only: expression planning must not receive conversation history or raw memories. */
public record ConversationExpressionModelRequest(
        String characterId,
        String expressionProfile,
        boolean primary,
        String utterance,
        String activity,
        double valence,
        double arousal,
        double familiarity,
        double trust,
        double affinity,
        double tension,
        String fallbackStyle,
        double fallbackIntensity,
        String fallbackIntent
) {}
