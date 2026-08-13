package com.gahyeonbot.services.ai.agent;

/** Identifies a model transport/inference failure for Runtime readiness accounting. */
final class ModelProviderException extends RuntimeException {
    ModelProviderException(Throwable cause) {
        super("model provider call failed", cause);
    }
}
