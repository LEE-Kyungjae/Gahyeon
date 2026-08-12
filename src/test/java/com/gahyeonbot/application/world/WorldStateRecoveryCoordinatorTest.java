package com.gahyeonbot.application.world;

import com.gahyeonbot.core.world.WorldId;
import com.gahyeonbot.core.world.WorldStateUseCase;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldStateRecoveryCoordinatorTest {
    @Test
    void recoversTheCanonicalHomeWorldOnApplicationReady() {
        WorldStateUseCase worlds = mock(WorldStateUseCase.class);
        var readiness = new WorldRuntimeReadiness();
        new WorldStateRecoveryCoordinator(worlds, readiness).recover();
        verify(worlds).recoverAfterRestart(new WorldId("gahyeon-home"));
        assertThat(readiness.isReady()).isTrue();
    }

    @Test
    void recoveryFailureLeavesAutonomousRuntimeClosed() {
        WorldStateUseCase worlds = mock(WorldStateUseCase.class);
        var readiness = new WorldRuntimeReadiness();
        org.mockito.Mockito.when(worlds.recoverAfterRestart(new WorldId("gahyeon-home")))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> new WorldStateRecoveryCoordinator(worlds, readiness).recover())
                .isInstanceOf(IllegalStateException.class);
        assertThat(readiness.isReady()).isFalse();
    }

    @Test
    void duplicateReadyEventDoesNotRepeatRecovery() {
        WorldStateUseCase worlds = mock(WorldStateUseCase.class);
        var readiness = new WorldRuntimeReadiness();
        var recovery = new WorldStateRecoveryCoordinator(worlds, readiness);
        recovery.recover();
        recovery.recover();
        verify(worlds).recoverAfterRestart(new WorldId("gahyeon-home"));
    }
}
