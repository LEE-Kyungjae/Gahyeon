package com.gahyeonbot.application.behavior;

import com.gahyeonbot.core.world.WorldId;
import com.gahyeonbot.application.world.WorldRuntimeReadiness;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorldActionExecutionSchedulerTest {
    @Test
    @SuppressWarnings("unchecked")
    void delegatesWorldScopedRendererOwnershipToHeadlessAdvance() {
        WorldActionCoordinator actions = mock(WorldActionCoordinator.class);
        WorldActionPresentationPresence presence =
                worldId -> worldId.value().equals("rendered-world");
        var readiness = ready();
        var scheduler = new WorldActionExecutionScheduler(
                actions, List.of(presence), readiness);

        scheduler.advance();

        var predicate = org.mockito.ArgumentCaptor.forClass(Predicate.class);
        verify(actions).advanceReadyActions(predicate.capture());
        assertThat(predicate.getValue().test(new WorldId("rendered-world"))).isTrue();
        assertThat(predicate.getValue().test(new WorldId("headless-world"))).isFalse();
    }

    @Test
    void defaultsToHeadlessExecutionWhenNoPresentationAdapterExists() {
        WorldActionCoordinator actions = mock(WorldActionCoordinator.class);
        var scheduler = new WorldActionExecutionScheduler(
                actions, List.of(), ready());

        scheduler.advance();

        var predicate = org.mockito.ArgumentCaptor.forClass(Predicate.class);
        verify(actions).advanceReadyActions(predicate.capture());
        assertThat(predicate.getValue().test(new WorldId("headless-world"))).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void treatsTheWorldAsRendererOwnedWhenAnyPresentationAdapterIsPresent() {
        WorldActionCoordinator actions = mock(WorldActionCoordinator.class);
        WorldActionPresentationPresence unreal = worldId -> false;
        WorldActionPresentationPresence desktop =
                worldId -> worldId.value().equals("desktop-world");
        var scheduler = new WorldActionExecutionScheduler(
                actions, List.of(unreal, desktop), ready());

        scheduler.advance();

        var predicate = org.mockito.ArgumentCaptor.forClass(Predicate.class);
        verify(actions).advanceReadyActions(predicate.capture());
        assertThat(predicate.getValue().test(new WorldId("desktop-world"))).isTrue();
        assertThat(predicate.getValue().test(new WorldId("headless-world"))).isFalse();
    }

    @Test
    void doesNotAdvanceOrExpireBeforeRestartRecoveryCompletes() {
        WorldActionCoordinator actions = mock(WorldActionCoordinator.class);
        var scheduler = new WorldActionExecutionScheduler(
                actions, List.of(), new WorldRuntimeReadiness());
        scheduler.advance();
        scheduler.expire();
        org.mockito.Mockito.verifyNoInteractions(actions);
    }

    private WorldRuntimeReadiness ready() {
        var readiness = new WorldRuntimeReadiness();
        readiness.markReady();
        return readiness;
    }
}
