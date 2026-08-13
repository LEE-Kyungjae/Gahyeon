package com.gahyeonbot.core.world;

public interface WorldStateUseCase {
    WorldStateSnapshot current(WorldId worldId);

    /** Reconciles process-local activities that cannot survive a backend restart. */
    WorldStateSnapshot recoverAfterRestart(WorldId worldId);

    WorldStateSnapshot move(
            WorldId worldId,
            long expectedRevision,
            String room,
            WorldPosition position);

    WorldStateSnapshot changeActivity(
            WorldId worldId,
            long expectedRevision,
            WorldActivity activity,
            String interactionTarget);

    WorldStateSnapshot transition(
            WorldId worldId,
            long expectedRevision,
            String room,
            WorldPosition position,
            WorldActivity activity,
            String interactionTarget);

    WorldStateSnapshot changeEmotion(
            WorldId worldId,
            long expectedRevision,
            String emotion,
            double intensity);
}
