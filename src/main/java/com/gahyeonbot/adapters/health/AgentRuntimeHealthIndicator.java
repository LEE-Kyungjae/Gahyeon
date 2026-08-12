package com.gahyeonbot.adapters.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Actuator visibility for optional versus required conversation deployments. */
@Component
public final class AgentRuntimeHealthIndicator implements HealthIndicator {
    private final AgentRuntimeReadiness readiness;

    public AgentRuntimeHealthIndicator(AgentRuntimeReadiness readiness) {
        this.readiness = readiness;
    }

    @Override
    public Health health() {
        AgentRuntimeReadiness.Snapshot state = readiness.snapshot();
        Health.Builder health = state.deploymentReady() ? Health.up() : Health.outOfService();
        return health
                .withDetail("required", state.required())
                .withDetail("runtimeReady", state.ready())
                .build();
    }
}
