package com.gahyeonbot.services.ai.agent;

/** Keeps a presentation observer failure distinct from a model provider failure. */
final class AgentStreamObserverDeliveryException extends RuntimeException {
    AgentStreamObserverDeliveryException(Throwable cause) {
        super("agent stream observer delivery failed", cause);
    }
}
