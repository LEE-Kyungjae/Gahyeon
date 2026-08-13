package com.gahyeonbot.adapters.unreal;

import java.util.function.BooleanSupplier;

@FunctionalInterface
public interface UnrealSpeechPreparationPort {
    UnrealSpeechPreparationPort NOOP = (request, currentGeneration) -> {};

    void prepare(UnrealSpeechPreparationRequest request, BooleanSupplier currentGeneration);

    /** Cancels queued/running synthesis owned by older generations when supported. */
    default void advanceGeneration(String sessionId, long generation) {
    }

    default void releaseSession(String sessionId) {
    }

    default void finishSequence(
            UnrealSpeechSequenceEndRequest request,
            BooleanSupplier currentGeneration) {
    }
}
