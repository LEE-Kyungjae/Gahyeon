package com.gahyeonbot.application.world;

import com.gahyeonbot.core.world.WorldId;
import com.gahyeonbot.core.world.WorldStateSnapshot;

import java.util.Optional;

public interface WorldStateStore {
    Optional<WorldStateSnapshot> find(WorldId worldId);
    WorldStateSnapshot save(WorldStateSnapshot snapshot);
}
