package com.gahyeonbot.application.behavior;

import com.gahyeonbot.application.event.GahyeonEventPublisher;
import com.gahyeonbot.core.behavior.BehaviorDecision;
import com.gahyeonbot.core.event.GahyeonEventDraft;
import com.gahyeonbot.core.world.WorldId;
import com.gahyeonbot.core.world.WorldPosition;
import com.gahyeonbot.core.world.WorldStateConflictException;
import com.gahyeonbot.core.world.WorldStateSnapshot;
import com.gahyeonbot.core.world.WorldStateUseCase;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates Backend target -> renderer execution -> authoritative World commit. */
@Service
public class WorldActionCoordinator {
    private static final Duration ACTION_TIMEOUT = Duration.ofSeconds(60);
    private static final double HEADLESS_SPEED_UNITS_PER_SECOND = 1.5;
    private static final long INTERACTION_SETTLE_MILLIS = 1_000;
    private static final long MAX_HEADLESS_EXECUTION_MILLIS = 30_000;
    private static final double MAX_COMPLETION_DISTANCE = 0.5;

    private final WorldStateUseCase worlds;
    private final GahyeonEventPublisher events;
    private final WorldActionLedger ledger;
    private final Clock clock;

    @Autowired
    public WorldActionCoordinator(
            WorldStateUseCase worlds,
            GahyeonEventPublisher events,
            WorldActionLedger ledger) {
        this(worlds, events, ledger, Clock.systemUTC());
    }

    WorldActionCoordinator(
            WorldStateUseCase worlds,
            GahyeonEventPublisher events,
            WorldActionLedger ledger,
            Clock clock) {
        this.worlds = worlds;
        this.events = events;
        this.ledger = ledger;
        this.clock = clock;
    }

    @Transactional
    public synchronized RequestResult request(
            WorldStateSnapshot current,
            BehaviorDecision decision) {
        expireTimedOut();
        var existing = ledger.findPending(current.worldId());
        if (existing.isPresent()) return new RequestResult(
                RequestStatus.ALREADY_PENDING, existing.get().action().actionId());
        String actionId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        var pending = new WorldActionLedger.PendingAction(
                actionId, current.worldId(), current.revision(), current.position(),
                decision.target().room(),
                decision.target().position(), decision.activity(), decision.target().id(),
                now, now.plusMillis(estimatedExecutionMillis(
                        current.position(), decision.target().position())),
                now.plus(ACTION_TIMEOUT));
        if (!ledger.create(pending)) {
            var raced = ledger.findPending(current.worldId());
            return new RequestResult(RequestStatus.ALREADY_PENDING,
                    raced.map(record -> record.action().actionId()).orElse(actionId));
        }
        events.publish(GahyeonEventDraft.world(
                "world.transition.target",
                current.worldId().value(),
                "world-action:" + actionId,
                targetPayload(pending)));
        return new RequestResult(RequestStatus.REQUESTED, actionId);
    }

    @Transactional
    public synchronized CompletionResult complete(ActionCompletion completion) {
        var stored = ledger.find(completion.actionId());
        if (stored.isEmpty()) return CompletionResult.STALE;
        if (stored.get().status() != WorldActionLedger.ActionStatus.PENDING) {
            return CompletionResult.DUPLICATE;
        }
        var pending = stored.get().action();
        if (!completion.worldId().equals(pending.worldId())
                || completion.expectedRevision() != pending.expectedRevision()
                || !validPosition(completion.finalPosition())
                || !Set.of("completed", "failed", "cancelled").contains(completion.outcome())
                || ("completed".equals(completion.outcome())
                    && distance(completion.finalPosition(), pending.position())
                        > MAX_COMPLETION_DISTANCE)) {
            return CompletionResult.INVALID;
        }
        if (!ledger.claim(completion.actionId())) return CompletionResult.DUPLICATE;
        CompletionResult result;
        if (!"completed".equals(completion.outcome())) {
            result = CompletionResult.RECORDED_FAILURE;
        } else {
            try {
                worlds.transition(
                        pending.worldId(),
                        pending.expectedRevision(),
                        pending.room(), pending.position(), pending.activity(),
                        pending.interactionTarget());
                result = CompletionResult.COMMITTED;
            } catch (WorldStateConflictException conflict) {
                result = CompletionResult.CONFLICT;
            }
        }
        if (!ledger.finishClaimed(completion.actionId(), terminalStatus(completion, result),
                result.name().toLowerCase(), clock.instant())) {
            return CompletionResult.DUPLICATE;
        }
        publishOutcome(pending, completion, result);
        return result;
    }

    @Transactional
    public synchronized int pendingCount() {
        expireTimedOut();
        return ledger.countPending();
    }

    @Transactional
    public synchronized void expireTimedOutActions() {
        expireTimedOut();
    }

    /** Cancels autonomous movement before user attention/conversation takes ownership. */
    @Transactional
    public synchronized Optional<CompletionResult> cancelPending(
            WorldId worldId, String reason) {
        if (worldId == null) throw new IllegalArgumentException("worldId is required");
        String cancellationReason = reason == null || reason.isBlank()
                ? "core_cancelled" : reason.trim();
        return ledger.findPending(worldId).map(record -> {
            var action = record.action();
            return complete(new ActionCompletion(
                    worldId, action.actionId(), action.expectedRevision(), "cancelled",
                    cancellationReason, action.sourcePosition()));
        });
    }

    @Transactional
    public synchronized void advanceReadyActions() {
        advanceReadyActions(worldId -> false);
    }

    @Transactional
    public synchronized void advanceReadyActions(Predicate<WorldId> rendererPresent) {
        if (rendererPresent == null) {
            throw new IllegalArgumentException("rendererPresent is required");
        }
        Instant now = clock.instant();
        ledger.findReady(now, 100).forEach(record -> {
            var action = record.action();
            if (rendererPresent.test(action.worldId())) return;
            complete(new ActionCompletion(
                    action.worldId(), action.actionId(), action.expectedRevision(),
                    "completed", "core_headless_execution", action.position()));
        });
    }

    private void expireTimedOut() {
        Instant now = clock.instant();
        ledger.findExpired(now, 100).forEach(actionRecord -> {
            var action = actionRecord.action();
            if (actionRecord.status() == WorldActionLedger.ActionStatus.PROCESSING) {
                recoverClaimed(action, now);
            } else if (ledger.expirePending(action.actionId(),
                    "recorded_failure", now)) {
                publishOutcome(action, new ActionCompletion(
                        action.worldId(), action.actionId(), action.expectedRevision(), "failed",
                        "renderer_timeout", action.position()),
                        CompletionResult.RECORDED_FAILURE);
            }
        });
    }

    private void recoverClaimed(WorldActionLedger.PendingAction action, Instant now) {
        WorldStateSnapshot state = worlds.current(action.worldId());
        CompletionResult result;
        if (matchesCommittedTarget(state, action)) {
            result = CompletionResult.COMMITTED;
        } else if (state.revision() == action.expectedRevision()) {
            try {
                worlds.transition(action.worldId(), action.expectedRevision(), action.room(),
                        action.position(), action.activity(), action.interactionTarget());
                result = CompletionResult.COMMITTED;
            } catch (WorldStateConflictException conflict) {
                result = CompletionResult.CONFLICT;
            }
        } else {
            result = CompletionResult.CONFLICT;
        }
        if (ledger.finishClaimed(action.actionId(), terminalStatus(
                new ActionCompletion(action.worldId(), action.actionId(),
                        action.expectedRevision(), "completed",
                        "recovered_after_backend_restart", action.position()), result),
                result.name().toLowerCase(), now)) {
            publishOutcome(action, new ActionCompletion(
                    action.worldId(), action.actionId(), action.expectedRevision(), "completed",
                    "recovered_after_backend_restart", action.position()), result);
        }
    }

    private boolean matchesCommittedTarget(
            WorldStateSnapshot state, WorldActionLedger.PendingAction action) {
        return state.revision() == action.expectedRevision() + 1
                && state.currentRoom().equals(action.room())
                && state.activity() == action.activity()
                && java.util.Objects.equals(state.interactionTarget(), action.interactionTarget())
                && distance(state.position(), action.position()) <= MAX_COMPLETION_DISTANCE;
    }

    private Map<String, Object> targetPayload(WorldActionLedger.PendingAction action) {
        return Map.of(
                "actionId", action.actionId(),
                "worldId", action.worldId().value(),
                "expectedRevision", action.expectedRevision(),
                "room", action.room(),
                "position", positionPayload(action.position()),
                "activity", action.activity().name().toLowerCase(),
                "interactionTarget", action.interactionTarget(),
                "timeoutMs", ACTION_TIMEOUT.toMillis());
    }

    private void publishOutcome(
            WorldActionLedger.PendingAction pending,
            ActionCompletion completion,
            CompletionResult result) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("actionId", completion.actionId());
        payload.put("expectedRevision", completion.expectedRevision());
        payload.put("outcome", completion.outcome());
        payload.put("result", result.name().toLowerCase());
        payload.put("finalPosition", positionPayload(completion.finalPosition()));
        if (completion.reason() != null && !completion.reason().isBlank()) {
            payload.put("reason", completion.reason());
        }
        events.publish(GahyeonEventDraft.world(
                "character.action.result",
                pending.worldId().value(),
                "world-action-result:" + completion.actionId(),
                payload));
    }

    private WorldActionLedger.ActionStatus terminalStatus(
            ActionCompletion completion, CompletionResult result) {
        if (result == CompletionResult.CONFLICT) return WorldActionLedger.ActionStatus.CONFLICT;
        return switch (completion.outcome()) {
            case "completed" -> WorldActionLedger.ActionStatus.COMPLETED;
            case "cancelled" -> WorldActionLedger.ActionStatus.CANCELLED;
            default -> WorldActionLedger.ActionStatus.FAILED;
        };
    }

    private Map<String, Object> positionPayload(WorldPosition position) {
        return Map.of("x", position.x(), "y", position.y(), "z", position.z());
    }

    private boolean validPosition(WorldPosition position) {
        return position != null && Double.isFinite(position.x())
                && Double.isFinite(position.y()) && Double.isFinite(position.z());
    }

    private double distance(WorldPosition left, WorldPosition right) {
        double x = left.x() - right.x();
        double y = left.y() - right.y();
        double z = left.z() - right.z();
        return Math.sqrt(x * x + y * y + z * z);
    }

    private long estimatedExecutionMillis(WorldPosition source, WorldPosition target) {
        long travel = (long) Math.ceil(
                distance(source, target) / HEADLESS_SPEED_UNITS_PER_SECOND * 1_000.0);
        return Math.max(500, Math.min(MAX_HEADLESS_EXECUTION_MILLIS,
                travel + INTERACTION_SETTLE_MILLIS));
    }

    public enum RequestStatus { REQUESTED, ALREADY_PENDING }
    public enum CompletionResult { COMMITTED, RECORDED_FAILURE, DUPLICATE, STALE, CONFLICT, INVALID }

    public record RequestResult(RequestStatus status, String actionId) {}

    public record ActionCompletion(
            WorldId worldId,
            String actionId,
            long expectedRevision,
            String outcome,
            String reason,
            WorldPosition finalPosition
    ) {
        public ActionCompletion {
            if (worldId == null) throw new IllegalArgumentException("worldId is required");
            if (actionId == null || actionId.isBlank()) {
                throw new IllegalArgumentException("actionId is required");
            }
            if (expectedRevision < 0) {
                throw new IllegalArgumentException("expectedRevision must be non-negative");
            }
        }
    }

}
