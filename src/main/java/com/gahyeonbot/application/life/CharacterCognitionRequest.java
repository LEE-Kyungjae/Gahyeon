package com.gahyeonbot.application.life;

import com.gahyeonbot.core.life.*;
import java.util.List;

public record CharacterCognitionRequest(
        CharacterDefinition character,
        CharacterLifeState state,
        String reason,
        ExpressionPlan proposedExpression,
        List<CharacterMemory> recentMemories
) {
    public CharacterCognitionRequest {
        if (character == null || state == null || proposedExpression == null) throw new IllegalArgumentException("cognition context is required");
        if (!character.id().equals(state.characterId())) throw new IllegalArgumentException("character and state do not match");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason is required");
        recentMemories = recentMemories == null ? List.of() : List.copyOf(recentMemories);
    }
}
