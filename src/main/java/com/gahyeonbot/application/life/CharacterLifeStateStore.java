package com.gahyeonbot.application.life;

import com.gahyeonbot.core.life.CharacterId;
import com.gahyeonbot.core.life.CharacterLifeState;
import com.gahyeonbot.core.world.WorldId;

import java.util.Optional;

public interface CharacterLifeStateStore {
    Optional<CharacterLifeState> find(CharacterId characterId, WorldId worldId);
    CharacterLifeState save(CharacterLifeState state);
}
