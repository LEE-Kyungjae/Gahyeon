package com.gahyeonbot.adapters.world;

import com.gahyeonbot.application.world.WorldRuntimeReadiness;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Surfaces fail-closed restart reconciliation to deployment readiness. */
@Component
@ConditionalOnProperty(name = "gahyeon.behavior.enabled", havingValue = "true")
public final class WorldRuntimeHealthIndicator implements HealthIndicator {
    private final WorldRuntimeReadiness readiness;

    public WorldRuntimeHealthIndicator(WorldRuntimeReadiness readiness) {
        this.readiness = readiness;
    }

    @Override
    public Health health() {
        boolean ready = readiness.isReady();
        return (ready ? Health.up() : Health.outOfService())
                .withDetail("worldRuntimeReady", ready)
                .withDetail("autonomousExecution", ready ? "enabled" : "recovery_pending")
                .build();
    }
}
