package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.application.behavior.WorldActionCoordinator;

@FunctionalInterface
public interface UnrealActionCompletionPort {
    WorldActionCoordinator.CompletionResult complete(
            WorldActionCoordinator.ActionCompletion completion);

    static UnrealActionCompletionPort unavailable() {
        return completion -> WorldActionCoordinator.CompletionResult.STALE;
    }
}
