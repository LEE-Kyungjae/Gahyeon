package com.gahyeonbot.adapters.unreal;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LatestUnrealPerceptionStoreTest {
    @Test
    void keepsOnlyTheLatestGenerationAndExpiresVolatileData() {
        var clock = new MutableClock(Instant.EPOCH);
        var store = new LatestUnrealPerceptionStore(clock, Duration.ofSeconds(10));
        store.activateSession("session-1");
        store.accept(event(2, Instant.EPOCH.plusSeconds(2), "새 부분문장"));
        store.accept(event(1, Instant.EPOCH.plusSeconds(3), "늦게 온 이전 문장"));

        assertThat(store.latest("session-1", "perception.transcript.partial"))
                .get().extracting(event -> event.payload().get("text"))
                .isEqualTo("새 부분문장");

        clock.instant = Instant.EPOCH.plusSeconds(13);
        store.evictExpired();
        assertThat(store.latest("session-1", "perception.transcript.partial")).isEmpty();
        assertThat(store.size()).isZero();
    }

    @Test
    void hidesAnOldPartialAsSoonAsAnyNewerSessionEventArrives() {
        var store = new LatestUnrealPerceptionStore(
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), Duration.ofSeconds(10));
        store.activateSession("session-1");
        store.accept(event(4, Instant.EPOCH, "이전 부분문장"));
        store.accept(new UnrealPerceptionEvent(
                "perception.voice.started", "session-1", "gahyeon-home",
                5, Instant.EPOCH, Map.of()));

        assertThat(store.latest("session-1", "perception.transcript.partial")).isEmpty();
        assertThat(store.latest("session-1", "perception.voice.started")).isPresent();
    }

    @Test
    void releaseRemovesLatestValuesAndGenerationWatermarkImmediately() {
        var store = new LatestUnrealPerceptionStore(
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), Duration.ofSeconds(10));
        store.activateSession("session-1");
        store.accept(event(4, Instant.EPOCH, "부분문장"));
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.sessionCount()).isEqualTo(1);

        store.releaseSession("session-1");

        assertThat(store.size()).isZero();
        assertThat(store.sessionCount()).isZero();
        assertThat(store.latest("session-1", "perception.transcript.partial")).isEmpty();

        store.accept(event(5, Instant.EPOCH, "늦은 callback"));
        assertThat(store.size()).isZero();
    }

    private UnrealPerceptionEvent event(long generation, Instant receivedAt, String text) {
        return new UnrealPerceptionEvent(
                "perception.transcript.partial", "session-1", "gahyeon-home",
                generation, receivedAt, Map.of("text", text));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
