package com.gahyeonbot.adapters.desktop;

import com.gahyeonbot.application.behavior.WorldActionPresentationPresence;
import com.gahyeonbot.core.world.WorldId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** A short, bounded lease proving that a Desktop renderer owns a world's presentation. */
@Component
@ConditionalOnProperty(name = "gahyeon.headless.enabled", havingValue = "true")
public final class DesktopWorldActionPresentationPresence
        implements WorldActionPresentationPresence {
    static final int MAXIMUM_INSTALLATION_ID_CHARACTERS = 200;
    static final int MAXIMUM_RENDERER_ID_CHARACTERS = 120;
    private static final Duration MINIMUM_TTL = Duration.ofSeconds(1);
    private static final Duration MAXIMUM_TTL = Duration.ofMinutes(5);
    private static final int MAXIMUM_CONFIGURED_LEASES = 4_096;

    private final Clock clock;
    private final Duration ttl;
    private final int maximumLeases;
    private final Map<LeaseKey, Instant> leases = new HashMap<>();

    @Autowired
    public DesktopWorldActionPresentationPresence(
            @Value("${gahyeon.desktop.world-presence-ttl-millis:15000}") long ttlMillis,
            @Value("${gahyeon.desktop.maximum-world-presence-leases:256}") int maximumLeases) {
        this(Clock.systemUTC(), Duration.ofMillis(ttlMillis), maximumLeases);
    }

    DesktopWorldActionPresentationPresence(
            Clock clock,
            Duration ttl,
            int maximumLeases) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.compareTo(MINIMUM_TTL) < 0 || ttl.compareTo(MAXIMUM_TTL) > 0) {
            throw new IllegalArgumentException(
                    "Desktop world presence TTL must be between 1 second and 5 minutes");
        }
        if (maximumLeases < 1 || maximumLeases > MAXIMUM_CONFIGURED_LEASES) {
            throw new IllegalArgumentException(
                    "Desktop world presence capacity must be between 1 and 4096");
        }
        this.maximumLeases = maximumLeases;
    }

    public synchronized void heartbeat(
            WorldId worldId,
            String installationId,
            String rendererId) {
        LeaseKey key = key(worldId, installationId, rendererId);
        Instant now = clock.instant();
        removeExpired(now);
        if (!leases.containsKey(key) && leases.size() >= maximumLeases) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Desktop world presence capacity exceeded");
        }
        leases.put(key, now.plus(ttl));
    }

    public synchronized void release(
            WorldId worldId,
            String installationId,
            String rendererId) {
        leases.remove(key(worldId, installationId, rendererId));
    }

    @Override
    public synchronized boolean hasRenderer(WorldId worldId) {
        String world = requireWorldId(worldId);
        Instant now = clock.instant();
        removeExpired(now);
        return leases.entrySet().stream()
                .anyMatch(entry -> entry.getKey().worldId.equals(world));
    }

    synchronized int activeLeases() {
        removeExpired(clock.instant());
        return leases.size();
    }

    private void removeExpired(Instant now) {
        leases.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    }

    private static LeaseKey key(
            WorldId worldId,
            String installationId,
            String rendererId) {
        String world = requireWorldId(worldId);
        if (installationId == null || installationId.isBlank()
                || installationId.length() > MAXIMUM_INSTALLATION_ID_CHARACTERS) {
            throw new IllegalArgumentException(
                    "installationId가 필요하며 200자 이하여야 합니다.");
        }
        if (rendererId == null || rendererId.isBlank()
                || rendererId.length() > MAXIMUM_RENDERER_ID_CHARACTERS) {
            throw new IllegalArgumentException(
                    "rendererId가 필요하며 120자 이하여야 합니다.");
        }
        return new LeaseKey(world, installationId, rendererId);
    }

    private static String requireWorldId(WorldId worldId) {
        if (worldId == null || worldId.value().length()
                > DesktopEventStreamService.MAXIMUM_WORLD_ID_CHARACTERS) {
            throw new IllegalArgumentException("worldId가 필요하며 180자 이하여야 합니다.");
        }
        return worldId.value();
    }

    private record LeaseKey(String worldId, String installationId, String rendererId) {}
}
