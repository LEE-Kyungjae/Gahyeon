package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.adapters.unreal.protocol.UnrealEnvelope;
import com.gahyeonbot.adapters.unreal.protocol.UnrealEventMapper;
import com.gahyeonbot.application.event.GahyeonEventQuery;
import com.gahyeonbot.core.event.EventScopeType;
import com.gahyeonbot.core.event.GahyeonEvent;
import com.gahyeonbot.core.session.ClientSource;
import com.gahyeonbot.core.session.ConversationSessionId;

import java.util.List;

/** Transport-independent replay boundary. A future WebSocket handler delegates here. */
public final class UnrealEventReplayService {
    public static final int MAX_BATCH_SIZE = 500;

    private final GahyeonEventQuery events;
    private final UnrealEventMapper mapper;

    public UnrealEventReplayService(GahyeonEventQuery events, UnrealEventMapper mapper) {
        this.events = events;
        this.mapper = mapper;
    }

    public ReplayBatch replay(UnrealSubscription subscription, long afterSequence, int limit) {
        if (subscription == null) throw new IllegalArgumentException("subscription is required");
        if (afterSequence < 0) throw new IllegalArgumentException("afterSequence must be non-negative");
        int safeLimit = Math.max(1, Math.min(limit, MAX_BATCH_SIZE));
        List<GahyeonEvent> queried = events.after(afterSequence, safeLimit);
        List<UnrealEnvelope> visible = queried.stream()
                .filter(event -> visibleTo(event, subscription))
                .map(mapper::map)
                .toList();
        long scannedThrough = queried.isEmpty()
                ? afterSequence
                : queried.getLast().sequence();
        return new ReplayBatch(afterSequence, scannedThrough, visible);
    }

    private boolean visibleTo(GahyeonEvent event, UnrealSubscription subscription) {
        return switch (event.scope().type()) {
            case SYSTEM -> true;
            case SESSION -> event.scope().id().equals(subscription.sessionId())
                    || event.scope().id().equals(ConversationSessionId
                            .fromExternal(ClientSource.UNREAL, subscription.sessionId()).value());
            case WORLD -> event.scope().id().equals(subscription.worldId());
        };
    }

    public record UnrealSubscription(String sessionId, String worldId) {
        public UnrealSubscription {
            requireText(sessionId, "sessionId");
            requireText(worldId, "worldId");
        }

        private static void requireText(String value, String name) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        }
    }

    public record ReplayBatch(long requestedAfter, long scannedThrough, List<UnrealEnvelope> messages) {
        public ReplayBatch {
            messages = List.copyOf(messages);
        }
    }
}
