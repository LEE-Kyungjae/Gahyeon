package com.gahyeonbot.adapters.world;

import com.gahyeonbot.application.world.WorldRuntimeReadiness;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

class WorldRuntimeHealthIndicatorTest {
    @Test
    void remainsOutOfServiceUntilRestartRecoveryCompletes() {
        var readiness = new WorldRuntimeReadiness();
        var indicator = new WorldRuntimeHealthIndicator(readiness);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(indicator.health().getDetails())
                .containsEntry("worldRuntimeReady", false)
                .containsEntry("autonomousExecution", "recovery_pending");

        readiness.markReady();

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getDetails())
                .containsEntry("worldRuntimeReady", true)
                .containsEntry("autonomousExecution", "enabled");
    }
}
