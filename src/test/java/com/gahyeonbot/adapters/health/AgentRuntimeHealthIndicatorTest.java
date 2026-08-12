package com.gahyeonbot.adapters.health;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRuntimeHealthIndicatorTest {
    @Test
    void reportsOutOfServiceWhenRequiredConversationRuntimeIsUnavailable() {
        AgentRuntimeReadiness readiness = mock(AgentRuntimeReadiness.class);
        when(readiness.snapshot()).thenReturn(new AgentRuntimeReadiness.Snapshot(true, false));

        var health = new AgentRuntimeHealthIndicator(readiness).health();

        assertThat(health.getStatus().getCode()).isEqualTo("OUT_OF_SERVICE");
        assertThat(health.getDetails())
                .containsEntry("required", true)
                .containsEntry("runtimeReady", false);
    }

    @Test
    void keepsOptionalNonAiDeploymentsHealthyButVisible() {
        AgentRuntimeReadiness readiness = mock(AgentRuntimeReadiness.class);
        when(readiness.snapshot()).thenReturn(new AgentRuntimeReadiness.Snapshot(false, false));

        var health = new AgentRuntimeHealthIndicator(readiness).health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("runtimeReady", false);
    }
}
