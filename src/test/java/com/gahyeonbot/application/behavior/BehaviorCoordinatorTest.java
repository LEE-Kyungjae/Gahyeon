package com.gahyeonbot.application.behavior;

import com.gahyeonbot.application.world.WorldRuntimeReadiness;
import com.gahyeonbot.core.behavior.BehaviorDecision;
import com.gahyeonbot.core.behavior.DeterministicBehaviorPolicy;
import com.gahyeonbot.core.behavior.GahyeonHomeWorld;
import com.gahyeonbot.core.world.WorldActivity;
import com.gahyeonbot.core.world.WorldId;
import com.gahyeonbot.core.world.WorldStateSnapshot;
import com.gahyeonbot.core.world.WorldStateUseCase;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BehaviorCoordinatorTest {
    @Test
    void doesNotReadOrDecideWorldBeforeRecoveryCompletes() {
        WorldStateUseCase worlds = mock(WorldStateUseCase.class);
        DeterministicBehaviorPolicy policy = mock(DeterministicBehaviorPolicy.class);
        WorldActionCoordinator actions = mock(WorldActionCoordinator.class);
        var coordinator = new BehaviorCoordinator(
                worlds, policy, actions, new WorldRuntimeReadiness());

        coordinator.tick();

        verify(worlds, never()).current(new WorldId("gahyeon-home"));
        org.mockito.Mockito.verifyNoInteractions(policy, actions);
    }

    @Test
    void requestsPolicyDecisionOnlyAfterRecoveryCompletes() {
        WorldStateUseCase worlds = mock(WorldStateUseCase.class);
        DeterministicBehaviorPolicy policy = mock(DeterministicBehaviorPolicy.class);
        WorldActionCoordinator actions = mock(WorldActionCoordinator.class);
        WorldStateSnapshot current = mock(WorldStateSnapshot.class);
        var decision = new BehaviorDecision(
                WorldActivity.WORK, GahyeonHomeWorld.definition().requirePoint("desk"));
        when(worlds.current(new WorldId("gahyeon-home"))).thenReturn(current);
        when(policy.decide(current, GahyeonHomeWorld.definition()))
                .thenReturn(Optional.of(decision));
        var readiness = new WorldRuntimeReadiness();
        readiness.markReady();
        var coordinator = new BehaviorCoordinator(worlds, policy, actions, readiness);

        coordinator.tick();

        verify(actions).request(current, decision);
    }
}
