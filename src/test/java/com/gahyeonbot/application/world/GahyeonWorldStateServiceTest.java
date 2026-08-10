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
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("character.moved");
            assertThat(event.scope().type()).isEqualTo(EventScopeType.WORLD);
            assertThat(event.scope().id()).isEqualTo("gahyeon-home");
            assertThat(event.sessionId()).isNull();
        });
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
                .containsExactly("character.moved", "behavior.activity.changed");
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
