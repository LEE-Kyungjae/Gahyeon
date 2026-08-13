package com.gahyeonbot.services.ai.agent;

@FunctionalInterface
public interface AgentStreamObserver {
    void onTextDelta(String delta);

    default boolean isCancelled() { return false; }
}
