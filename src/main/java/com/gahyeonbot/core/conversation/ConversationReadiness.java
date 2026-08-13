package com.gahyeonbot.core.conversation;

/**
 * Provider-neutral availability boundary for conversation clients.
 *
 * Clients must not infer availability from a specific provider's credentials.
 */
@FunctionalInterface
public interface ConversationReadiness {
    boolean isReady();
}
