package com.gahyeonbot.adapters.unreal;

@FunctionalInterface
public interface UnrealPerceptionSink {
    void accept(UnrealPerceptionEvent event);

    default void activateSession(String sessionId) {
    }

    default void releaseSession(String sessionId) {
    }
}
