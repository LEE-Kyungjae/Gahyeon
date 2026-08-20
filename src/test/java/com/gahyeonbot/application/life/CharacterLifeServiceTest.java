package com.gahyeonbot.application.life;

import com.gahyeonbot.application.event.GahyeonEventPublisher;
import com.gahyeonbot.core.life.*;
import com.gahyeonbot.core.world.WorldId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CharacterLifeServiceTest {
    @Test
    void twentyFourHourSimulationKeepsEveryPersonaStateAndIntentIsolated() {
        Instant start = Instant.parse("2026-08-19T00:00:00Z");
        var clock = new MutableClock(start);
        var store = new InMemoryStore();
        var registry = new CharacterDefinitionRegistry(CharacterCatalogProperties.standard());
        var service = new CharacterLifeService(registry, store, mock(GahyeonEventPublisher.class),
                new DeterministicLifePolicy(), clock);
        var world = new WorldId("home");

        for (int tick = 0; tick <= 24 * 12; tick++) {
            clock.advance(Duration.ofMinutes(tick == 0 ? 0 : 5));
            for (var character : registry.all()) service.tick(character.id(), world);
            if (tick == 24) {
                service.observe(new CharacterId("gahyeon"), world,
                        new LifeStimulus("prospective.intention", 0.8, "ask_about_umbrella", false, clock.instant()));
            }
            if (tick == 60) {
                service.observe(new CharacterId("gahyeon"), world,
                        new LifeStimulus("user.returned", 0.9, "user", true, clock.instant()));
            }
            if (tick % 48 == 12) {
                service.observe(new CharacterId("diana"), world,
                        new LifeStimulus("surprising.event", 0.7, "window", true, clock.instant()));
            }
        }

        var states = registry.all().stream()
                .map(character -> service.current(character.id(), world)).toList();
        assertThat(states).extracting(state -> state.characterId().value())
                .containsExactlyInAnyOrderElementsOf(registry.all().stream()
                        .map(character -> character.id().value()).toList());
        assertThat(states).allSatisfy(state -> {
            assertThat(state.revision()).isGreaterThanOrEqualTo(289);
            assertThat(state.updatedAt()).isEqualTo(clock.instant());
            assertThat(state.socialNeed()).isBetween(0.0, 1.0);
            assertThat(state.curiosityNeed()).isBetween(0.0, 1.0);
            assertThat(state.restNeed()).isBetween(0.0, 1.0);
        });
        CharacterLifeState gahyeon = service.current(new CharacterId("gahyeon"), world);
        CharacterLifeState diana = service.current(new CharacterId("diana"), world);
        assertThat(gahyeon.prospectiveIntention()).isNull();
        assertThat(gahyeon.lastInitiativeAt()).isNotNull();
        assertThat(diana.attentionTarget()).isEqualTo("window");
        assertThat(states.stream().filter(state -> state.characterId().equals(new CharacterId("diana")))
                .noneMatch(state -> "ask_about_umbrella".equals(state.prospectiveIntention()))).isTrue();
        assertThat(store.states).hasSize(registry.all().size());
    }

    @Test
    void gahyeonAndDianaPersistIndependentLivesInTheSameWorld() {
        Instant now = Instant.parse("2026-08-19T12:00:00Z");
        var store = new InMemoryStore();
        var service = new CharacterLifeService(new CharacterDefinitionRegistry(CharacterCatalogProperties.standard()), store,
                mock(GahyeonEventPublisher.class), new DeterministicLifePolicy(), Clock.fixed(now, ZoneOffset.UTC));
        var world = new WorldId("home");

        service.observe(new CharacterId("gahyeon"), world, new LifeStimulus("playful.event", 1, "user", true, now));

        CharacterLifeState gahyeon = service.current(new CharacterId("gahyeon"), world);
        CharacterLifeState diana = service.current(new CharacterId("diana"), world);
        assertThat(gahyeon.revision()).isEqualTo(1);
        assertThat(gahyeon.lastInitiativeAt()).isEqualTo(now);
        assertThat(diana.revision()).isZero();
        assertThat(diana.lastInitiativeAt()).isNull();
        assertThat(store.states).hasSize(2);
    }

    private static final class InMemoryStore implements CharacterLifeStateStore {
        private final Map<String, CharacterLifeState> states = new HashMap<>();

        @Override
        public Optional<CharacterLifeState> find(CharacterId characterId, WorldId worldId) {
            return Optional.ofNullable(states.get(characterId.value() + "@" + worldId.value()));
        }

        @Override
        public CharacterLifeState save(CharacterLifeState state) {
            states.put(state.characterId().value() + "@" + state.worldId().value(), state);
            return state;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
