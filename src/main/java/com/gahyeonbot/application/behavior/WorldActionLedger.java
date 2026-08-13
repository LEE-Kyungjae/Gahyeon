package com.gahyeonbot.application.behavior;

import com.gahyeonbot.core.world.WorldActivity;
import com.gahyeonbot.core.world.WorldId;
import com.gahyeonbot.core.world.WorldPosition;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable idempotency and recovery boundary for renderer-owned World actions. */
public interface WorldActionLedger {
    boolean create(PendingAction action);

    Optional<ActionRecord> find(String actionId);

    Optional<ActionRecord> findPending(WorldId worldId);

    List<ActionRecord> findExpired(Instant now, int limit);

    List<ActionRecord> findReady(Instant now, int limit);

    int countPending();

    boolean claim(String actionId);

    boolean finishClaimed(String actionId, ActionStatus status, String result, Instant completedAt);

    boolean expirePending(String actionId, String result, Instant completedAt);

    record PendingAction(
            String actionId,
            WorldId worldId,
            long expectedRevision,
            WorldPosition sourcePosition,
            String room,
            WorldPosition position,
            WorldActivity activity,
            String interactionTarget,
            Instant requestedAt,
            Instant executeAfter,
            Instant expiresAt) {}

    record ActionRecord(
            PendingAction action,
            ActionStatus status,
            String result,
            Instant completedAt) {}

    enum ActionStatus { PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED, CONFLICT }
}
