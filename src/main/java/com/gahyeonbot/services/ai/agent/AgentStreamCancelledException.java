package com.gahyeonbot.services.ai.agent;

public final class AgentStreamCancelledException extends RuntimeException {
    public AgentStreamCancelledException() {
        super("agent stream was cancelled by a newer client generation");
    }

    public AgentStreamCancelledException(Throwable cause) {
        super("agent stream was cancelled by a newer client generation", cause);
    }
}
