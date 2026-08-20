package com.gahyeonbot.application.life;

import com.gahyeonbot.core.life.CharacterDefinition;
import com.gahyeonbot.core.life.CharacterMemory;
import com.gahyeonbot.core.world.WorldId;
import java.util.List;

public record CharacterMemoryConsolidationRequest(
        CharacterDefinition character,
        WorldId worldId,
        String subjectId,
        String userMessage,
        String assistantMessage,
        List<CharacterMemory> existingMemories
) {
    public CharacterMemoryConsolidationRequest {
        if (character == null || worldId == null) throw new IllegalArgumentException("memory namespace is required");
        if (subjectId == null || subjectId.isBlank()) throw new IllegalArgumentException("subjectId is required");
        if (userMessage == null || userMessage.isBlank() || assistantMessage == null || assistantMessage.isBlank()) {
            throw new IllegalArgumentException("conversation turns are required");
        }
        existingMemories = existingMemories == null ? List.of() : List.copyOf(existingMemories);
    }
}
