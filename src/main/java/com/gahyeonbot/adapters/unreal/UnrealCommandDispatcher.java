package com.gahyeonbot.adapters.unreal;

@FunctionalInterface
public interface UnrealCommandDispatcher {
    DispatchResult dispatch(UnrealConversationCommand command);

    default void advanceGeneration(String sessionId, long generation) {
    }

    default void releaseSession(String sessionId) {
    }

    enum DispatchResult {
        ACCEPTED,
        DUPLICATE,
        STALE,
        BACKPRESSURE
    }
}
