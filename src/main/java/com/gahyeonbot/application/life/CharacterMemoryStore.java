package com.gahyeonbot.application.life;

import com.gahyeonbot.core.life.CharacterId;
import com.gahyeonbot.core.life.CharacterMemory;
import com.gahyeonbot.core.life.CharacterMemoryMergeResult;
import com.gahyeonbot.core.world.WorldId;
import java.util.List;

public interface CharacterMemoryStore {
    CharacterMemory append(CharacterMemory memory);

    default boolean appendIfAbsent(CharacterMemory memory) {
        append(memory);
        return true;
    }

    default CharacterMemoryMergeResult merge(CharacterMemory memory) {
        return appendIfAbsent(memory)
                ? CharacterMemoryMergeResult.INSERTED
                : CharacterMemoryMergeResult.DUPLICATE;
    }
    List<CharacterMemory> recent(CharacterId characterId, WorldId worldId, int limit);

    default List<CharacterMemory> recent(CharacterId characterId, WorldId worldId, String subjectId, int limit) {
        return recent(characterId, worldId, limit).stream()
                .filter(memory -> memory.subjectId() == null || memory.subjectId().equals(subjectId))
                .limit(limit)
                .toList();
    }
}
