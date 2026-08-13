package com.gahyeonbot.adapters.desktop;

import com.gahyeonbot.core.world.WorldId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesktopWorldActionPresentationPresenceTest {
    @Test
    void wiresTheProductionConstructorWithSafeDefaultsWhenHeadlessIsEnabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(DesktopWorldActionPresentationPresence.class)
                .withPropertyValues("gahyeon.headless.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .hasSingleBean(DesktopWorldActionPresentationPresence.class);
                });
    }

    @Test
    void refreshesAWorldScopedLeaseAndExpiresItAfterTheTtl() {
        var clock = new MutableClock(Instant.parse("2026-08-13T00:00:00Z"));
        var presence = new DesktopWorldActionPresentationPresence(
                clock, Duration.ofSeconds(15), 4);
        WorldId worldId = new WorldId("gahyeon-home");

        presence.heartbeat(worldId, "install-1", "renderer-a");
        clock.advance(Duration.ofSeconds(10));
        assertThat(presence.hasRenderer(worldId)).isTrue();

        presence.heartbeat(worldId, "install-1", "renderer-a");
        clock.advance(Duration.ofSeconds(14));
        assertThat(presence.hasRenderer(worldId)).isTrue();

        clock.advance(Duration.ofSeconds(1));
        assertThat(presence.hasRenderer(worldId)).isFalse();
        assertThat(presence.activeLeases()).isZero();
    }

    @Test
    void releaseOnlyRemovesTheExactRendererInstallationAndWorldLease() {
        var presence = new DesktopWorldActionPresentationPresence(
                Clock.systemUTC(), Duration.ofSeconds(15), 4);
        WorldId worldId = new WorldId("gahyeon-home");
        presence.heartbeat(worldId, "install-1", "renderer-a");
        presence.heartbeat(worldId, "install-1", "renderer-b");
        presence.heartbeat(worldId, "install-2", "renderer-a");
        presence.heartbeat(new WorldId("other-world"), "install-1", "renderer-a");

        presence.release(worldId, "install-1", "renderer-a");

        assertThat(presence.hasRenderer(worldId)).isTrue();
        presence.release(worldId, "install-1", "renderer-b");
        assertThat(presence.hasRenderer(worldId)).isTrue();
        presence.release(worldId, "install-2", "renderer-a");
        assertThat(presence.hasRenderer(worldId)).isFalse();
        assertThat(presence.hasRenderer(new WorldId("other-world"))).isTrue();
    }

    @Test
    void boundsActiveLeasesButAllowsRefreshAndReclaimsExpiredCapacity() {
        var clock = new MutableClock(Instant.parse("2026-08-13T00:00:00Z"));
        var presence = new DesktopWorldActionPresentationPresence(
                clock, Duration.ofSeconds(15), 2);
        presence.heartbeat(new WorldId("world-1"), "install-1", "renderer-a");
        presence.heartbeat(new WorldId("world-2"), "install-2", "renderer-b");

        presence.heartbeat(new WorldId("world-1"), "install-1", "renderer-a");
        assertThatThrownBy(() -> presence.heartbeat(
                new WorldId("world-3"), "install-3", "renderer-c"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("429 TOO_MANY_REQUESTS");

        clock.advance(Duration.ofSeconds(15));
        presence.heartbeat(new WorldId("world-3"), "install-3", "renderer-c");
        assertThat(presence.hasRenderer(new WorldId("world-3"))).isTrue();
        assertThat(presence.activeLeases()).isOne();
    }

    @Test
    void rejectsUnboundedIdsAndUnsafeConfiguration() {
        var clock = Clock.systemUTC();
        assertThatThrownBy(() -> new DesktopWorldActionPresentationPresence(
                clock, Duration.ofMillis(999), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DesktopWorldActionPresentationPresence(
                clock, Duration.ofMinutes(6), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DesktopWorldActionPresentationPresence(
                clock, Duration.ofSeconds(15), 0))
                .isInstanceOf(IllegalArgumentException.class);

        var presence = new DesktopWorldActionPresentationPresence(
                clock, Duration.ofSeconds(15), 1);
        assertThatThrownBy(() -> presence.heartbeat(
                new WorldId("w".repeat(181)), "install-1", "renderer-a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> presence.heartbeat(
                new WorldId("world-1"), "i".repeat(201), "renderer-a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> presence.heartbeat(
                new WorldId("world-1"), "install-1", "r".repeat(121)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
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
