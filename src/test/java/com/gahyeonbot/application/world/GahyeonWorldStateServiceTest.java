package com.gahyeonbot.application.world;

import com.gahyeonbot.core.event.GahyeonEventDraft;
import com.gahyeonbot.core.event.EventScopeType;
import com.gahyeonbot.core.world.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GahyeonWorldStateServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-10T07:00:00Z");

    @Test
    void createsMovesAndPublishesWorldScopedSemanticEvent() {
        var store = new InMemoryWorldStore();
        List<GahyeonEventDraft> events = new ArrayList<>();
        var service = new GahyeonWorldStateService(
                store,
                draft -> {
                    events.add(draft);
                    return null;
                },
                Clock.fixed(NOW, ZoneOffset.UTC));
        var worldId = new WorldId("gahyeon-home");

        WorldStateSnapshot initial = service.current(worldId);
        WorldStateSnapshot moved = service.move(
                worldId, initial.revision(), "workspace", new WorldPosition(3, 0, -2));

        assertThat(moved.revision()).isEqualTo(1);
        assertThat(moved.currentRoom()).isEqualTo("workspace");
        assertThat(store.find(worldId)).contains(moved);
        assertThat(events).first().satisfies(event -> {
            assertThat(event.type()).isEqualTo("character.moved");
            assertThat(event.scope().type()).isEqualTo(EventScopeType.WORLD);
            assertThat(event.scope().id()).isEqualTo("gahyeon-home");
            assertThat(event.sessionId()).isNull();
        });
        assertThat(events).extracting(GahyeonEventDraft::type)
                .containsExactly("character.moved", "world.state.changed");
        assertThat(events.getLast().payload())
                .containsEntry("revision", 1L)
                .containsEntry("currentRoom", "workspace");
    }

    @Test
    void rejectsStaleWriterWithoutChangingState() {
        var store = new InMemoryWorldStore();
        var service = new GahyeonWorldStateService(
                store,
                draft -> null,
                Clock.fixed(NOW, ZoneOffset.UTC));
        var worldId = new WorldId("gahyeon-home");
        service.current(worldId);
        service.changeActivity(worldId, 0, WorldActivity.READ, "bookshelf");

        assertThatThrownBy(() -> service.move(
                worldId, 0, "kitchen", new WorldPosition(1, 0, 1)))
                .isInstanceOf(WorldStateConflictException.class);
        assertThat(store.find(worldId).orElseThrow().activity()).isEqualTo(WorldActivity.READ);
        assertThat(store.find(worldId).orElseThrow().revision()).isEqualTo(1);
    }

    @Test
    void transitionsMovementAndActivityInOneRevision() {
        var store = new InMemoryWorldStore();
        List<GahyeonEventDraft> events = new ArrayList<>();
        var service = new GahyeonWorldStateService(
                store,
                draft -> {
                    events.add(draft);
                    return null;
                },
                Clock.fixed(NOW, ZoneOffset.UTC));
        var worldId = new WorldId("gahyeon-home");
        service.current(worldId);

        var changed = service.transition(
                worldId, 0, "workspace", new WorldPosition(7, 0, -2),
                WorldActivity.WORK, "desk");

        assertThat(changed.revision()).isOne();
        assertThat(changed.activity()).isEqualTo(WorldActivity.WORK);
        assertThat(changed.interactionTarget()).isEqualTo("desk");
        assertThat(events).extracting(GahyeonEventDraft::type)
                .containsExactly("character.moved", "behavior.activity.changed", "world.state.changed");
    }

    @Test
    void persistsEmotionIntensityAndPublishesThePersistedValue() {
        var store = new InMemoryWorldStore();
        List<GahyeonEventDraft> events = new ArrayList<>();
        var service = new GahyeonWorldStateService(
                store,
                draft -> {
                    events.add(draft);
                    return null;
                },
                Clock.fixed(NOW, ZoneOffset.UTC));
        var worldId = new WorldId("gahyeon-home");
        service.current(worldId);

        var changed = service.changeEmotion(worldId, 0, "happy", 0.7);

        assertThat(changed.emotionState().name()).isEqualTo("happy");
        assertThat(changed.emotionIntensity()).isEqualTo(0.7);
        assertThat(store.find(worldId).orElseThrow().emotionIntensity()).isEqualTo(0.7);
        assertThat(events).first().satisfies(event -> {
            assertThat(event.type()).isEqualTo("avatar.expression");
            assertThat(event.payload()).containsEntry("intensity", 0.7);
        });
        assertThat(events).extracting(GahyeonEventDraft::type)
                .containsExactly("avatar.expression", "world.state.changed");
        assertThat(events.getLast().payload()).containsEntry(
                "emotion", Map.of("name", "happy", "intensity", 0.7));
    }

    @Test
    void restartRecoversOrphanedConversationToIdleAndPublishesRestoredSnapshot() {
        var store = new InMemoryWorldStore();
        var worldId = new WorldId("gahyeon-home");
        Instant beforeRestart = NOW.minusSeconds(30);
        store.save(new WorldStateSnapshot(
                worldId, 8, "workspace", new WorldPosition(7, 0, -2),
                WorldActivity.CONVERSATION, beforeRestart, "default", beforeRestart,
                "neutral", 0, "user:42", beforeRestart));
        List<GahyeonEventDraft> events = new ArrayList<>();
        var service = new GahyeonWorldStateService(
                store, draft -> {
                    events.add(draft);
                    return null;
                }, Clock.fixed(NOW, ZoneOffset.UTC));

        WorldStateSnapshot recovered = service.recoverAfterRestart(worldId);

        assertThat(recovered.revision()).isEqualTo(9);
        assertThat(recovered.activity()).isEqualTo(WorldActivity.IDLE);
        assertThat(recovered.interactionTarget()).isNull();
        assertThat(recovered.activityStartedAt()).isEqualTo(NOW);
        assertThat(events).extracting(GahyeonEventDraft::type)
                .containsExactly("behavior.activity.changed", "world.state.restored");
        assertThat(events.getFirst().payload())
                .containsEntry("recoveryReason", "backend_restart_transient_activity");
        assertThat(events.getLast().payload()).containsEntry("revision", 9L);
    }

    @Test
    void restartPreservesDurableActivityWithoutRevisionOrEvents() {
        var store = new InMemoryWorldStore();
        var worldId = new WorldId("gahyeon-home");
        WorldStateSnapshot durable = new WorldStateSnapshot(
                worldId, 12, "workspace", new WorldPosition(7, 0, -2),
                WorldActivity.WORK, NOW.minusSeconds(300), "default", NOW,
                "focused", 0.4, "desk", NOW);
        store.save(durable);
        List<GahyeonEventDraft> events = new ArrayList<>();
        var service = new GahyeonWorldStateService(
                store, draft -> {
                    events.add(draft);
                    return null;
                }, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.recoverAfterRestart(worldId)).isEqualTo(durable);
        assertThat(events).isEmpty();
        assertThat(store.find(worldId)).contains(durable);
    }

    @Test
    void restartAlsoRecoversOrphanedAttention() {
        var store = new InMemoryWorldStore();
        var worldId = new WorldId("gahyeon-home");
        store.save(new WorldStateSnapshot(
                worldId, 4, "living_room", WorldPosition.origin(),
                WorldActivity.ATTENTION, NOW.minusSeconds(5), "default", NOW,
                "curious", 0.5, "user:camera", NOW));
        var service = new GahyeonWorldStateService(
                store, draft -> null, Clock.fixed(NOW, ZoneOffset.UTC));

        WorldStateSnapshot recovered = service.recoverAfterRestart(worldId);

        assertThat(recovered.activity()).isEqualTo(WorldActivity.IDLE);
        assertThat(recovered.revision()).isEqualTo(5);
        assertThat(recovered.interactionTarget()).isNull();
    }

    private static final class InMemoryWorldStore implements WorldStateStore {
        private final Map<WorldId, WorldStateSnapshot> states = new HashMap<>();

        @Override
        public Optional<WorldStateSnapshot> find(WorldId worldId) {
            return Optional.ofNullable(states.get(worldId));
        }

        @Override
        public WorldStateSnapshot save(WorldStateSnapshot snapshot) {
            states.put(snapshot.worldId(), snapshot);
            return snapshot;
        }
    }
}
