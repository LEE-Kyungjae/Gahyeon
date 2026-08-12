package com.gahyeonbot.application.behavior;

import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.core.session.*;
import com.gahyeonbot.core.world.*;
import org.mockito.InOrder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.mockito.Mockito.*;

class WorldConversationPresenceTest {
    @Test
    void entersConversationAndRestoresPreviousActivityAfterLastLeaseCloses() {
        WorldStateUseCase worlds = mock(WorldStateUseCase.class);
        WorldId worldId = new WorldId("gahyeon-home");
        Instant now = Instant.parse("2026-08-10T07:00:00Z");
        WorldStateSnapshot reading = snapshot(worldId, 2, WorldActivity.READ, "bookshelf", now);
        WorldStateSnapshot talking = snapshot(worldId, 3, WorldActivity.CONVERSATION, "user:42", now);
        when(worlds.current(worldId)).thenReturn(reading, talking);
        when(worlds.changeActivity(worldId, 2, WorldActivity.CONVERSATION, "user:42"))
                .thenReturn(talking);
        WorldActionCoordinator actions = mock(WorldActionCoordinator.class);
        var presence = new WorldConversationPresence(worlds, actions);
        var session = new ConversationSession(
                new ConversationSessionId("desktop-session"),
                new ActorId(42),
                ClientSource.DESKTOP,
                ConversationModality.TEXT,
                Map.of());

        var first = presence.enter(session);
        var second = presence.enter(session);
        second.close();
        verify(worlds, never()).changeActivity(worldId, 3, WorldActivity.READ, "bookshelf");
        first.close();

        verify(worlds).changeActivity(worldId, 2, WorldActivity.CONVERSATION, "user:42");
        verify(worlds).changeActivity(worldId, 3, WorldActivity.READ, "bookshelf");
        verify(actions).cancelPending(worldId, "conversation_started");
        InOrder entryOrder = inOrder(actions, worlds);
        entryOrder.verify(actions).cancelPending(worldId, "conversation_started");
        entryOrder.verify(worlds).current(worldId);
    }

    private WorldStateSnapshot snapshot(
            WorldId worldId,
            long revision,
            WorldActivity activity,
            String target,
            Instant now) {
        return new WorldStateSnapshot(
                worldId, revision, "living_room", WorldPosition.origin(), activity, now,
                "default", now, "neutral", 0, target, now);
    }
}
