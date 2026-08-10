package com.gahyeonbot.application.world;

import com.gahyeonbot.application.event.GahyeonEventPublisher;
import com.gahyeonbot.core.event.GahyeonEventDraft;
import com.gahyeonbot.core.world.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class GahyeonWorldStateService implements WorldStateUseCase {
    private final WorldStateStore store;
    private final GahyeonEventPublisher events;
    private final Clock clock;

    public GahyeonWorldStateService(WorldStateStore store, GahyeonEventPublisher events) {
        this(store, events, Clock.systemUTC());
    }

    GahyeonWorldStateService(WorldStateStore store, GahyeonEventPublisher events, Clock clock) {
        this.store = store;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public WorldStateSnapshot current(WorldId worldId) {
        return store.find(worldId).orElseGet(() -> store.save(WorldStateSnapshot.initial(
                worldId, clock.instant())));
    }

    @Override
    @Transactional
    public WorldStateSnapshot move(
            WorldId worldId,
            long expectedRevision,
            String room,
            WorldPosition position) {
        if (room == null || room.isBlank()) throw new IllegalArgumentException("room이 필요합니다.");
        if (position == null) throw new IllegalArgumentException("position이 필요합니다.");
        WorldStateSnapshot before = requireRevision(current(worldId), expectedRevision);
        Instant now = clock.instant();
        WorldStateSnapshot changed = new WorldStateSnapshot(
                before.worldId(), before.revision() + 1, room.trim(), position,
                before.activity(), before.activityStartedAt(), before.outfit(), now,
                before.emotion(), before.emotionIntensity(), before.interactionTarget(), now);
        WorldStateSnapshot saved = store.save(changed);
        publish(saved, "character.moved", Map.of(
                "revision", saved.revision(),
                "room", saved.currentRoom(),
                "position", positionPayload(saved.position())));
        return saved;
    }

    @Override
    @Transactional
    public WorldStateSnapshot changeActivity(
            WorldId worldId,
            long expectedRevision,
            WorldActivity activity,
            String interactionTarget) {
        if (activity == null) throw new IllegalArgumentException("activity가 필요합니다.");
        WorldStateSnapshot before = requireRevision(current(worldId), expectedRevision);
        Instant now = clock.instant();
        WorldStateSnapshot changed = new WorldStateSnapshot(
                before.worldId(), before.revision() + 1, before.currentRoom(), before.position(),
                activity, now, before.outfit(), now, before.emotion(), before.emotionIntensity(), interactionTarget, now);
        WorldStateSnapshot saved = store.save(changed);
        publish(saved, "behavior.activity.changed", nullableMap(
                "revision", saved.revision(),
                "activity", saved.activity().name().toLowerCase(),
                "interactionTarget", saved.interactionTarget()));
        return saved;
    }

    @Override
    @Transactional
    public WorldStateSnapshot transition(
            WorldId worldId,
            long expectedRevision,
            String room,
            WorldPosition position,
            WorldActivity activity,
            String interactionTarget) {
        if (room == null || room.isBlank()) throw new IllegalArgumentException("room이 필요합니다.");
        if (position == null) throw new IllegalArgumentException("position이 필요합니다.");
        if (activity == null) throw new IllegalArgumentException("activity가 필요합니다.");
        WorldStateSnapshot before = requireRevision(current(worldId), expectedRevision);
        Instant now = clock.instant();
        WorldStateSnapshot changed = new WorldStateSnapshot(
                before.worldId(), before.revision() + 1, room.trim(), position,
                activity, now, before.outfit(), now, before.emotion(), before.emotionIntensity(), interactionTarget, now);
        WorldStateSnapshot saved = store.save(changed);
        publish(saved, "character.moved", Map.of(
                "revision", saved.revision(),
                "room", saved.currentRoom(),
                "position", positionPayload(saved.position())));
        publish(saved, "behavior.activity.changed", nullableMap(
                "revision", saved.revision(),
                "activity", saved.activity().name().toLowerCase(),
                "interactionTarget", saved.interactionTarget()));
        return saved;
    }

    @Override
    @Transactional
    public WorldStateSnapshot changeEmotion(
            WorldId worldId,
            long expectedRevision,
            String emotion,
            double intensity) {
        if (emotion == null || emotion.isBlank()) throw new IllegalArgumentException("emotion이 필요합니다.");
        if (!Double.isFinite(intensity) || intensity < 0 || intensity > 1) {
            throw new IllegalArgumentException("emotion intensity는 0~1이어야 합니다.");
        }
        WorldStateSnapshot before = requireRevision(current(worldId), expectedRevision);
        Instant now = clock.instant();
        WorldStateSnapshot changed = new WorldStateSnapshot(
                before.worldId(), before.revision() + 1, before.currentRoom(), before.position(),
                before.activity(), before.activityStartedAt(), before.outfit(), now,
                emotion.trim(), intensity, before.interactionTarget(), now);
        WorldStateSnapshot saved = store.save(changed);
        publish(saved, "avatar.expression", Map.of(
                "revision", saved.revision(),
                "expression", saved.emotion(),
                "intensity", saved.emotionIntensity()));
        return saved;
    }

    private WorldStateSnapshot requireRevision(WorldStateSnapshot state, long expectedRevision) {
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision은 0 이상이어야 합니다.");
        if (state.revision() != expectedRevision) {
            throw new WorldStateConflictException(expectedRevision, state.revision());
        }
        return state;
    }

    private void publish(WorldStateSnapshot state, String type, Map<String, Object> payload) {
        events.publish(GahyeonEventDraft.world(
                type,
                state.worldId().value(),
                "world:" + state.worldId().value() + ":" + state.revision() + ":" + UUID.randomUUID(),
                payload));
    }

    private Map<String, Object> positionPayload(WorldPosition position) {
        return Map.of("x", position.x(), "y", position.y(), "z", position.z());
    }

    private Map<String, Object> nullableMap(
            String key1, Object value1,
            String key2, Object value2,
            String key3, Object value3) {
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put(key1, value1);
        result.put(key2, value2);
        if (value3 != null) result.put(key3, value3);
        return Map.copyOf(result);
    }
}
