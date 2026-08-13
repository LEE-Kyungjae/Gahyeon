package com.gahyeonbot.services.ai.agent;

/** A concurrent request was rejected while the provider circuit was open/half-open. */
final class AgentProviderUnavailableException extends RuntimeException {
    AgentProviderUnavailableException() {
        super("model provider recovery probe is already running or cooling down");
    }
}
