package com.gahyeonbot.application.behavior;

import com.gahyeonbot.application.event.GahyeonEventPublisher;
import com.gahyeonbot.core.behavior.BehaviorDecision;
import com.gahyeonbot.core.behavior.GahyeonHomeWorld;
import com.gahyeonbot.core.event.GahyeonEventDraft;
import com.gahyeonbot.core.world.WorldActivity;
import com.gahyeonbot.core.world.WorldId;
import com.gahyeonbot.core.world.WorldPosition;
import com.gahyeonbot.core.world.WorldStateSnapshot;
import com.gahyeonbot.core.world.WorldStateUseCase;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WorldActionCoordinatorTest {
    @Test
    void publishesTargetWithoutCommittingWorldUntilRendererCompletes() {
        WorldStateUseCase worlds = mock(WorldStateUseCase.class);
        var drafts = new ArrayList<GahyeonEventDraft>();
        GahyeonEventPublisher events = draft -> {
            drafts.add(draft);
            return null;
        };
        var coordinator = new WorldActionCoordinator(
                worlds, events, new TestWorldActionLedger(), Clock.systemUTC());
        WorldStateSnapshot current = current(7);
        var decision = new BehaviorDecision(
                WorldActivity.WORK,
                GahyeonHomeWorld.definition().requirePoint("desk"));

        var requested = coordinator.request(current, decision);

        assertThat(requested.status()).isEqualTo(WorldActionCoordinator.RequestStatus.REQUESTED);
        assertThat(drafts).singleElement().satisfies(draft -> {
            assertThat(draft.type()).isEqualTo("world.transition.target");
            assertThat(draft.payload()).containsEntry("expectedRevision", 7L);
            assertThat(draft.payload()).containsEntry("interactionTarget", "desk");
        });
        verify(worlds, never()).transition(any(), anyLong(), any(), any(), any(), any());

        var result = coordinator.complete(new WorldActionCoordinator.ActionCompletion(
                new WorldId("gahyeon-home"), requested.actionId(), 7, "completed", "",
                new WorldPosition(7, 0, -2)));

        assertThat(result).isEqualTo(WorldActionCoordinator.CompletionResult.COMMITTED);
        verify(worlds).transition(
                new WorldId("gahyeon-home"), 7, "workspace",
                new WorldPosition(7, 0, -2), WorldActivity.WORK, "desk");
        assertThat(coordinator.complete(new WorldActionCoordinator.ActionCompletion(
                new WorldId("gahyeon-home"), requested.actionId(), 7, "completed", "",
                new WorldPosition(7, 0, -2))))
                .isEqualTo(WorldActionCoordinator.CompletionResult.DUPLICATE);
    }

    @Test
    void failureDoesNotMoveWorldAndTimedOutPendingActionIsReleased() {
        WorldStateUseCase worlds = mock(WorldStateUseCase.class);
        var drafts = new ArrayList<GahyeonEventDraft>();
        Instant started = Instant.parse("2026-08-11T03:00:00Z");
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(started, started, started.plusSeconds(61));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        var coordinator = new WorldActionCoordinator(worlds, draft -> {
            drafts.add(draft);
            return null;
        }, new TestWorldActionLedger(), clock);
        var decision = new BehaviorDecision(
                WorldActivity.READ,
                GahyeonHomeWorld.definition().requirePoint("bookshelf"));
        coordinator.request(current(3), decision);

        assertThat(coordinator.pendingCount()).isZero();
        verify(worlds, never()).transition(any(), anyLong(), any(), any(), any(), any());
        assertThat(drafts).extracting(GahyeonEventDraft::type)
                .containsExactly("world.transition.target", "character.action.result");
        assertThat(drafts.getLast().payload())
                .containsEntry("outcome", "failed")
                .containsEntry("reason", "renderer_timeout");
    }

    @Test
    void rejectsCompletionFarFromAuthoritativeInteractionPoint() {
        WorldStateUseCase worlds = mock(WorldStateUseCase.class);
        var coordinator = new WorldActionCoordinator(
                worlds, draft -> null, new TestWorldActionLedger(), Clock.systemUTC());
        var requested = coordinator.request(current(5), new BehaviorDecision(
                WorldActivity.RELAX,
                GahyeonHomeWorld.definition().requirePoint("chair")));

        assertThat(coordinator.complete(new WorldActionCoordinator.ActionCompletion(
                new WorldId("gahyeon-home"), requested.actionId(), 5, "completed", "",
                new WorldPosition(100, 0, 100))))
                .isEqualTo(WorldActionCoordinator.CompletionResult.INVALID);
        verify(worlds, never()).transition(any(), anyLong(), any(), any(), any(), any());
        assertThat(coordinator.pendingCount()).isEqualTo(1);
    }

    @Test
    void rejectsCompletionFromAnotherWorldScope() {
        WorldStateUseCase worlds = mock(WorldStateUseCase.class);
        var coordinator = new WorldActionCoordinator(
                worlds, draft -> null, new TestWorldActionLedger(), Clock.systemUTC());
        var requested = coordinator.request(current(5), new BehaviorDecision(
                WorldActivity.RELAX,
                GahyeonHomeWorld.definition().requirePoint("chair")));

        assertThat(coordinator.complete(new WorldActionCoordinator.ActionCompletion(
                new WorldId("another-world"), requested.actionId(), 5, "completed", "",
                new WorldPosition(2, 0, 1))))
                .isEqualTo(WorldActionCoordinator.CompletionResult.INVALID);
        verify(worlds, never()).transition(any(), anyLong(), any(), any(), any(), any());
        assertThat(coordinator.pendingCount()).isEqualTo(1);
    }

    @Test
    void restartRestoresPendingActionAndCompletionIdempotencyFromLedger() {
        WorldStateUseCase worlds = mock(WorldStateUseCase.class);
        var ledger = new TestWorldActionLedger();
        var first = new WorldActionCoordinator(
                worlds, draft -> null, ledger, Clock.systemUTC());
        var requested = first.request(current(9), new BehaviorDecision(
                WorldActivity.WORK,
                GahyeonHomeWorld.definition().requirePoint("desk")));

        var restarted = new WorldActionCoordinator(
                worlds, draft -> null, ledger, Clock.systemUTC());
        var recovered = restarted.request(current(9), new BehaviorDecision(
                WorldActivity.READ,
                GahyeonHomeWorld.definition().requirePoint("bookshelf")));

        assertThat(recovered.status())
                .isEqualTo(WorldActionCoordinator.RequestStatus.ALREADY_PENDING);
        assertThat(recovered.actionId()).isEqualTo(requested.actionId());
        var completion = new WorldActionCoordinator.ActionCompletion(
                new WorldId("gahyeon-home"), requested.actionId(), 9, "completed", "",
                new WorldPosition(7, 0, -2));
        assertThat(restarted.complete(completion))
                .isEqualTo(WorldActionCoordinator.CompletionResult.COMMITTED);

        var secondRestart = new WorldActionCoordinator(
                worlds, draft -> null, ledger, Clock.systemUTC());
        assertThat(secondRestart.complete(completion))
                .isEqualTo(WorldActionCoordinator.CompletionResult.DUPLICATE);
        verify(worlds, times(1)).transition(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void restartReconcilesClaimedCompletionAfterWorldCommit() {
        WorldStateUseCase worlds = mock(WorldStateUseCase.class);
        var ledger = new TestWorldActionLedger();
        Instant started = Instant.parse("2026-08-11T03:00:00Z");
        Clock requestClock = Clock.fixed(started, ZoneOffset.UTC);
        var first = new WorldActionCoordinator(worlds, draft -> null, ledger, requestClock);
        var requested = first.request(current(11), new BehaviorDecision(
                WorldActivity.WORK,
                GahyeonHomeWorld.definition().requirePoint("desk")));
        assertThat(ledger.claim(requested.actionId())).isTrue();

        Instant recoveredAt = started.plusSeconds(61);
        when(worlds.current(new WorldId("gahyeon-home"))).thenReturn(new WorldStateSnapshot(
                new WorldId("gahyeon-home"), 12, "workspace", new WorldPosition(7, 0, -2),
                WorldActivity.WORK, recoveredAt, "default", recoveredAt,
                "neutral", 0, "desk", recoveredAt));
        var restarted = new WorldActionCoordinator(
                worlds, draft -> null, ledger, Clock.fixed(recoveredAt, ZoneOffset.UTC));

        restarted.expireTimedOutActions();

        assertThat(ledger.find(requested.actionId())).get().satisfies(record -> {
            assertThat(record.status()).isEqualTo(WorldActionLedger.ActionStatus.COMPLETED);
            assertThat(record.result()).isEqualTo("committed");
        });
        verify(worlds, never()).transition(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void concurrentCompletionAcrossInstancesCommitsExactlyOnce() throws Exception {
        WorldStateUseCase worlds = mock(WorldStateUseCase.class);
        var ledger = new TestWorldActionLedger();
        var first = new WorldActionCoordinator(
                worlds, draft -> null, ledger, Clock.systemUTC());
        var second = new WorldActionCoordinator(
                worlds, draft -> null, ledger, Clock.systemUTC());
        var requested = first.request(current(13), new BehaviorDecision(
                WorldActivity.WORK,
                GahyeonHomeWorld.definition().requirePoint("desk")));
        var completion = new WorldActionCoordinator.ActionCompletion(
                new WorldId("gahyeon-home"), requested.actionId(), 13, "completed", "",
                new WorldPosition(7, 0, -2));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> {
                start.await();
                return first.complete(completion);
            });
            var right = executor.submit(() -> {
                start.await();
                return second.complete(completion);
            });
            start.countDown();
            assertThat(List.of(left.get(5, TimeUnit.SECONDS), right.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(
                            WorldActionCoordinator.CompletionResult.COMMITTED,
                            WorldActionCoordinator.CompletionResult.DUPLICATE);
        }
        verify(worlds, times(1)).transition(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void coreCompletesWorldActionWithoutAnyRendererConnected() {
        WorldStateUseCase worlds = mock(WorldStateUseCase.class);
        var ledger = new TestWorldActionLedger();
        var drafts = new ArrayList<GahyeonEventDraft>();
        Instant started = Instant.parse("2026-08-11T03:00:00Z");
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(
                started, started, started.plusSeconds(10), started.plusSeconds(10),
                started.plusSeconds(10));
        var coordinator = new WorldActionCoordinator(worlds, draft -> {
            drafts.add(draft);
            return null;
        }, ledger, clock);
        var requested = coordinator.request(current(21), new BehaviorDecision(
                WorldActivity.WORK,
                GahyeonHomeWorld.definition().requirePoint("desk")));

        coordinator.advanceReadyActions();

        verify(worlds).transition(
                new WorldId("gahyeon-home"), 21, "workspace",
                new WorldPosition(7, 0, -2), WorldActivity.WORK, "desk");
        assertThat(ledger.find(requested.actionId())).get().satisfies(record ->
                assertThat(record.status()).isEqualTo(WorldActionLedger.ActionStatus.COMPLETED));
        assertThat(drafts).extracting(GahyeonEventDraft::type)
                .containsExactly("world.transition.target", "character.action.result");
        assertThat(drafts.getLast().payload()).containsEntry("reason", "core_headless_execution");

        assertThat(coordinator.complete(new WorldActionCoordinator.ActionCompletion(
                new WorldId("gahyeon-home"), requested.actionId(), 21, "completed", "",
                new WorldPosition(7, 0, -2))))
                .isEqualTo(WorldActionCoordinator.CompletionResult.DUPLICATE);
        verify(worlds, times(1)).transition(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void connectedRendererRetainsExecutionOwnershipUntilItCompletes() {
        WorldStateUseCase worlds = mock(WorldStateUseCase.class);
        var ledger = new TestWorldActionLedger();
        Instant started = Instant.parse("2026-08-11T03:00:00Z");
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(started, started, started.plusSeconds(10));
        var coordinator = new WorldActionCoordinator(
                worlds, draft -> null, ledger, clock);
        var requested = coordinator.request(current(22), new BehaviorDecision(
                WorldActivity.WORK,
                GahyeonHomeWorld.definition().requirePoint("desk")));

        coordinator.advanceReadyActions(worldId -> worldId.value().equals("gahyeon-home"));

        verify(worlds, never()).transition(any(), anyLong(), any(), any(), any(), any());
        assertThat(ledger.find(requested.actionId())).get().satisfies(record ->
                assertThat(record.status()).isEqualTo(WorldActionLedger.ActionStatus.PENDING));
    }

    @Test
    void headlessTakesOverReadyActionAfterRendererDisconnects() {
        WorldStateUseCase worlds = mock(WorldStateUseCase.class);
        var ledger = new TestWorldActionLedger();
        Instant started = Instant.parse("2026-08-11T03:00:00Z");
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(
                started, started, started.plusSeconds(10), started.plusSeconds(10));
        var coordinator = new WorldActionCoordinator(
                worlds, draft -> null, ledger, clock);
        coordinator.request(current(23), new BehaviorDecision(
                WorldActivity.WORK,
                GahyeonHomeWorld.definition().requirePoint("desk")));

        coordinator.advanceReadyActions(worldId -> true);
        coordinator.advanceReadyActions(worldId -> false);

        verify(worlds).transition(
                new WorldId("gahyeon-home"), 23, "workspace",
                new WorldPosition(7, 0, -2), WorldActivity.WORK, "desk");
    }

    @Test
    void conversationCancellationTerminatesPendingMovementWithoutMovingWorld() {
        WorldStateUseCase worlds = mock(WorldStateUseCase.class);
        var ledger = new TestWorldActionLedger();
        var drafts = new ArrayList<GahyeonEventDraft>();
        var coordinator = new WorldActionCoordinator(
                worlds, draft -> {
                    drafts.add(draft);
                    return null;
                }, ledger, Clock.systemUTC());
        var requested = coordinator.request(current(24), new BehaviorDecision(
                WorldActivity.WORK,
                GahyeonHomeWorld.definition().requirePoint("desk")));

        assertThat(coordinator.cancelPending(
                new WorldId("gahyeon-home"), "conversation_started"))
                .contains(WorldActionCoordinator.CompletionResult.RECORDED_FAILURE);

        verify(worlds, never()).transition(any(), anyLong(), any(), any(), any(), any());
        assertThat(ledger.find(requested.actionId())).get().satisfies(record ->
                assertThat(record.status()).isEqualTo(WorldActionLedger.ActionStatus.CANCELLED));
        assertThat(drafts.getLast().payload())
                .containsEntry("outcome", "cancelled")
                .containsEntry("reason", "conversation_started");
    }

    private WorldStateSnapshot current(long revision) {
        Instant now = Instant.parse("2026-08-11T03:00:00Z");
        return new WorldStateSnapshot(
                new WorldId("gahyeon-home"), revision, "bedroom", WorldPosition.origin(),
                WorldActivity.IDLE, now, "default", now, "neutral", 0, null, now);
    }

    private static final class TestWorldActionLedger implements WorldActionLedger {
        private final Map<String, ActionRecord> records = new LinkedHashMap<>();

        @Override
        public synchronized boolean create(PendingAction action) {
            if (records.containsKey(action.actionId()) || findPending(action.worldId()).isPresent()) {
                return false;
            }
            records.put(action.actionId(), new ActionRecord(
                    action, ActionStatus.PENDING, null, null));
            return true;
        }

        @Override
        public synchronized Optional<ActionRecord> find(String actionId) {
            return Optional.ofNullable(records.get(actionId));
        }

        @Override
        public synchronized Optional<ActionRecord> findPending(WorldId worldId) {
            return records.values().stream().filter(record ->
                    (record.status() == ActionStatus.PENDING
                            || record.status() == ActionStatus.PROCESSING)
                            && record.action().worldId().equals(worldId)).findFirst();
        }

        @Override
        public synchronized List<ActionRecord> findExpired(Instant now, int limit) {
            return records.values().stream()
                    .filter(record -> (record.status() == ActionStatus.PENDING
                            || record.status() == ActionStatus.PROCESSING)
                            && record.action().expiresAt().isBefore(now))
                    .limit(limit).toList();
        }

        @Override
        public synchronized List<ActionRecord> findReady(Instant now, int limit) {
            return records.values().stream()
                    .filter(record -> record.status() == ActionStatus.PENDING
                            && !record.action().executeAfter().isAfter(now))
                    .limit(limit).toList();
        }

        @Override
        public synchronized int countPending() {
            return (int) records.values().stream()
                    .filter(record -> record.status() == ActionStatus.PENDING
                            || record.status() == ActionStatus.PROCESSING).count();
        }

        @Override
        public synchronized boolean claim(String actionId) {
            ActionRecord current = records.get(actionId);
            if (current == null || current.status() != ActionStatus.PENDING) return false;
            records.put(actionId, new ActionRecord(
                    current.action(), ActionStatus.PROCESSING, null, null));
            return true;
        }

        @Override
        public synchronized boolean finishClaimed(
                String actionId, ActionStatus status, String result, Instant completedAt) {
            ActionRecord current = records.get(actionId);
            if (current == null || current.status() != ActionStatus.PROCESSING) return false;
            records.put(actionId, new ActionRecord(current.action(), status, result, completedAt));
            return true;
        }

        @Override
        public synchronized boolean expirePending(
                String actionId, String result, Instant completedAt) {
            ActionRecord current = records.get(actionId);
            if (current == null || current.status() != ActionStatus.PENDING
                    || !current.action().expiresAt().isBefore(completedAt)) return false;
            records.put(actionId, new ActionRecord(
                    current.action(), ActionStatus.FAILED, result, completedAt));
            return true;
        }
    }
}
