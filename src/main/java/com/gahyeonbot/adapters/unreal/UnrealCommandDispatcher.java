package com.gahyeonbot.adapters.unreal;

@FunctionalInterface
public interface UnrealCommandDispatcher {
    DispatchResult dispatch(UnrealConversationCommand command);

    default void advanceGeneration(String sessionId, long generation) {
    }

    default void releaseSession(String sessionId) {
    }

    /** Current renderer-owned generation, or -1 before the first interaction. */
    default long currentGeneration(String sessionId) {
        return -1;
    }

    /** True only when no user cognition task owns this renderer session. */
    default boolean acceptsAutonomousSpeech(String sessionId) {
        return false;
    }

    enum DispatchResult {
        ACCEPTED,
        DUPLICATE,
        STALE,
        BACKPRESSURE
    }
}
