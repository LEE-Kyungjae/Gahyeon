package com.gahyeonbot.application.life;

import com.gahyeonbot.core.life.*;
import com.gahyeonbot.core.world.WorldId;
import java.util.Optional;

public interface CharacterRelationshipStore {
    Optional<CharacterRelationshipState> find(CharacterId characterId, WorldId worldId, String subjectId);
    CharacterRelationshipState save(CharacterRelationshipState state);
}
