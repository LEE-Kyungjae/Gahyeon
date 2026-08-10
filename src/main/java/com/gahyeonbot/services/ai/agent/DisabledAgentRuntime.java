package com.gahyeonbot.services.ai.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Keeps non-conversation Core capabilities available when no LLM provider is configured. */
@Component
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "none", matchIfMissing = true)
public class DisabledAgentRuntime implements AgentRuntime {
    @Override
    public AgentResult execute(AgentRequest request) {
        throw unavailable();
    }

    @Override
    public AgentResult resume(String runId, long actorUserId) {
        throw unavailable();
    }

    @Override
    public AgentResult resumeBackground(String runId, String backgroundResult) {
        throw unavailable();
    }

    private IllegalStateException unavailable() {
        return new IllegalStateException("Gahyeon agent provider is disabled");
    }
}
