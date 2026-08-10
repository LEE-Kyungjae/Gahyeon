package com.gahyeonbot.core.memory;

import com.gahyeonbot.core.identity.ActorId;

public interface MemoryUseCase {
    MemorySnapshot recall(ActorId actorId);

    void remember(ActorId actorId, String userMessage, String assistantResponse);

    void clear(ActorId actorId);
}
