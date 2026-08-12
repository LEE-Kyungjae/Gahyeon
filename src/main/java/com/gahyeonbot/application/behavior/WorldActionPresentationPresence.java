package com.gahyeonbot.application.behavior;

import com.gahyeonbot.core.world.WorldId;

/** Core-facing presence port; Presentation adapters report whether they own live execution. */
@FunctionalInterface
public interface WorldActionPresentationPresence {
    boolean hasRenderer(WorldId worldId);
}
