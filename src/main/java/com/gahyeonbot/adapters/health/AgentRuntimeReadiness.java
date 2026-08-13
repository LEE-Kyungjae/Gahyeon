package com.gahyeonbot.adapters.health;

import com.gahyeonbot.services.ai.agent.AgentRuntime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Shared deployment policy for deciding when conversation is a required capability. */
@Component
public final class AgentRuntimeReadiness {
    private final AgentRuntime runtime;
    private final boolean required;

    public AgentRuntimeReadiness(
            AgentRuntime runtime,
            @Value("${gahyeon.headless.enabled:false}") boolean headlessEnabled,
            @Value("${gahyeon.unreal.websocket.enabled:false}") boolean unrealEnabled) {
        this.runtime = runtime;
        this.required = headlessEnabled || unrealEnabled;
    }

    public Snapshot snapshot() {
        try {
            return new Snapshot(required, runtime.isReady());
        } catch (RuntimeException unavailable) {
            return new Snapshot(required, false);
        }
    }

    public record Snapshot(boolean required, boolean ready) {
        public boolean deploymentReady() {
            return !required || ready;
        }
    }
}
