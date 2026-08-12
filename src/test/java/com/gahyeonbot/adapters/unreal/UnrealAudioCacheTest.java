package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.core.speech.AudioOutput;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnrealAudioCacheTest {
    @Test
    void evictsOldestAudioToKeepEntryAndByteBounds() {
        var cache = new UnrealAudioCache(
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                Duration.ofMinutes(5), 2, 4, 6);

        String first = cache.put(audio(3));
        String second = cache.put(audio(3));
        String third = cache.put(audio(3));

        assertThat(cache.get(first)).isEmpty();
        assertThat(cache.get(second)).isPresent();
        assertThat(cache.get(third)).isPresent();
        assertThat(cache.entryCount()).isEqualTo(2);
        assertThat(cache.totalBytes()).isEqualTo(6);
    }

    @Test
    void rejectsOneAudioEntryThatAClientCannotSafelyDownload() {
        var cache = new UnrealAudioCache(
                Clock.systemUTC(), Duration.ofMinutes(5), 2, 4, 8);

        assertThatThrownBy(() -> cache.put(audio(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entry limit");
        assertThat(cache.entryCount()).isZero();
        assertThat(cache.totalBytes()).isZero();
    }

    @Test
    void expirationAndExplicitDiscardReleaseByteAccounting() {
        var clock = new MutableClock();
        var cache = new UnrealAudioCache(clock, Duration.ofSeconds(5), 4, 8, 16);
        String expired = cache.put(audio(4));
        String discarded = cache.put(audio(3));

        cache.discard(discarded);
        assertThat(cache.totalBytes()).isEqualTo(4);
        clock.advance(Duration.ofSeconds(5));
        cache.evictExpired();

        assertThat(cache.get(expired)).isEmpty();
        assertThat(cache.entryCount()).isZero();
        assertThat(cache.totalBytes()).isZero();
    }

    @Test
    void publishesBoundedCacheGaugesAndExceptionalCounters() {
        var registry = new SimpleMeterRegistry();
        var metrics = new UnrealRuntimeMetrics(registry);
        var cache = new UnrealAudioCache(
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), Duration.ofMinutes(5),
                1, 4, 4, metrics);

        cache.put(audio(4));
        cache.put(audio(3));
        assertThatThrownBy(() -> cache.put(audio(5)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(registry.get("gahyeon.unreal.audio.cache.entries").gauge().value())
                .isEqualTo(1);
        assertThat(registry.get("gahyeon.unreal.audio.cache.bytes").gauge().value())
                .isEqualTo(3);
        assertThat(registry.counter(
                "gahyeon.unreal.audio.cache.evicted", "reason", "capacity").count())
                .isEqualTo(1);
        assertThat(registry.counter(
                "gahyeon.unreal.audio.cache.rejected", "reason", "entry_too_large").count())
                .isEqualTo(1);
    }

    private static AudioOutput audio(int bytes) {
        return new AudioOutput(new byte[bytes], "audio/wav", "wav");
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.EPOCH;

        void advance(Duration duration) {
            now = now.plus(duration);
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
            return now;
        }
    }
}
