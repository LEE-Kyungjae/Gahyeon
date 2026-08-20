package com.gahyeonbot.adapters.world;

import com.gahyeonbot.application.world.WorldRuntimeReadiness;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/** Surfaces fail-closed restart reconciliation to deployment readiness. */
@Component
@ConditionalOnExpression("${gahyeon.behavior.enabled:false} || ${gahyeon.life.enabled:false}")
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
