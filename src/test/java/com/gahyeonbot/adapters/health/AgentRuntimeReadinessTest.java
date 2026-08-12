package com.gahyeonbot.adapters.health;

import com.gahyeonbot.services.ai.agent.AgentRuntime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRuntimeReadinessTest {
    @Test
    void disabledRuntimeBlocksAHeadlessOrUnrealDeployment() {
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.isReady()).thenReturn(false);

        var state = new AgentRuntimeReadiness(runtime, true, false).snapshot();

        assertThat(state.required()).isTrue();
        assertThat(state.ready()).isFalse();
        assertThat(state.deploymentReady()).isFalse();
    }

    @Test
    void disabledRuntimeDoesNotFailAPlatformDeploymentThatDoesNotRequireConversation() {
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.isReady()).thenReturn(false);

        var state = new AgentRuntimeReadiness(runtime, false, false).snapshot();

        assertThat(state.required()).isFalse();
        assertThat(state.deploymentReady()).isTrue();
    }

    @Test
    void providerProbeExceptionsFailClosedOnlyWhenConversationIsRequired() {
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.isReady()).thenThrow(new IllegalStateException("provider unavailable"));

        assertThat(new AgentRuntimeReadiness(runtime, false, true)
                .snapshot().deploymentReady()).isFalse();
        assertThat(new AgentRuntimeReadiness(runtime, false, false)
                .snapshot().deploymentReady()).isTrue();
    }
}
