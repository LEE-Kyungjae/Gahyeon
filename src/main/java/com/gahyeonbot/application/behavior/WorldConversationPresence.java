package com.gahyeonbot.application.behavior;

import com.gahyeonbot.core.behavior.GahyeonHomeWorld;
import com.gahyeonbot.core.session.ConversationSession;
import com.gahyeonbot.core.world.WorldActivity;
import com.gahyeonbot.core.world.WorldId;
import com.gahyeonbot.core.world.WorldStateSnapshot;
import com.gahyeonbot.core.world.WorldStateUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class WorldConversationPresence implements ConversationPresencePort {
    private static final Logger log = LoggerFactory.getLogger(WorldConversationPresence.class);

    private final WorldStateUseCase worlds;
    private final WorldActionCoordinator actions;
    private final ConcurrentHashMap<String, PresenceState> presence = new ConcurrentHashMap<>();

    public WorldConversationPresence(
            WorldStateUseCase worlds,
            WorldActionCoordinator actions) {
        this.worlds = worlds;
        this.actions = actions;
    }

    @Override
    public PresenceLease enter(ConversationSession session) {
        String worldKey = GahyeonHomeWorld.WORLD_ID;
        PresenceState state = presence.computeIfAbsent(worldKey, ignored -> new PresenceState());
        synchronized (state) {
            state.activeConversations++;
            if (state.activeConversations > 1) return () -> leave(worldKey, state);
            try {
                WorldId worldId = new WorldId(worldKey);
                actions.cancelPending(worldId, "conversation_started");
                WorldStateSnapshot current = worlds.current(worldId);
                state.previousActivity = current.activity();
                state.previousTarget = current.interactionTarget();
                if (current.activity() != WorldActivity.CONVERSATION) {
                    worlds.changeActivity(
                            worldId,
                            current.revision(),
                            WorldActivity.CONVERSATION,
                            "user:" + session.actorId().value());
                }
                return () -> leave(worldKey, state);
            } catch (RuntimeException error) {
                state.activeConversations--;
                if (state.activeConversations == 0) presence.remove(worldKey, state);
                log.warn("Conversation presence를 World State에 반영하지 못했습니다: {}", error.getMessage());
                return PresenceLease.NOOP;
            }
        }
    }

    private void leave(String worldKey, PresenceState state) {
        synchronized (state) {
            if (state.activeConversations == 0) return;
            state.activeConversations--;
            if (state.activeConversations > 0) return;
            try {
                WorldId worldId = new WorldId(worldKey);
                WorldStateSnapshot current = worlds.current(worldId);
                if (current.activity() == WorldActivity.CONVERSATION) {
                    worlds.changeActivity(
                            worldId,
                            current.revision(),
                            state.previousActivity == null ? WorldActivity.IDLE : state.previousActivity,
                            state.previousTarget);
                }
            } catch (RuntimeException error) {
                log.warn("Conversation 종료 후 이전 World activity를 복원하지 못했습니다: {}", error.getMessage());
            } finally {
                presence.remove(worldKey, state);
            }
        }
    }

    private static final class PresenceState {
        private int activeConversations;
        private WorldActivity previousActivity;
        private String previousTarget;
    }
}
