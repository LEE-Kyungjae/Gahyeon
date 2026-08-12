package com.gahyeonbot.adapters.unreal;

import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Volatile latest-value store. Perception events are never written to the durable event store. */
public final class LatestUnrealPerceptionStore implements UnrealPerceptionSink {
    private final ConcurrentHashMap<Key, UnrealPerceptionEvent> latest = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> generationBySession = new ConcurrentHashMap<>();
    private final java.util.Set<String> activeSessions = ConcurrentHashMap.newKeySet();
    private final Clock clock;
    private final Duration ttl;

    public LatestUnrealPerceptionStore(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
    }

    @Override
    public synchronized void accept(UnrealPerceptionEvent event) {
        if (!activeSessions.contains(event.sessionId())) return;
        generationBySession.merge(event.sessionId(), event.generation(), Math::max);
        long currentGeneration = generationBySession.get(event.sessionId());
        if (event.generation() < currentGeneration) return;
        latest.compute(new Key(event.sessionId(), event.type()), (key, current) ->
                current == null
                        || event.generation() > current.generation()
                        || event.generation() == current.generation()
                        && !event.receivedAt().isBefore(current.receivedAt())
                        ? event
                        : current);
    }

    public synchronized Optional<UnrealPerceptionEvent> latest(String sessionId, String type) {
        UnrealPerceptionEvent event = latest.get(new Key(sessionId, type));
        Long currentGeneration = generationBySession.get(sessionId);
        if (event == null
                || currentGeneration != null && event.generation() < currentGeneration
                || expired(event, clock.instant())) return Optional.empty();
        return Optional.of(event);
    }

    @Override
    public synchronized void activateSession(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) activeSessions.add(sessionId);
    }

    @Override
    public synchronized void releaseSession(String sessionId) {
        if (sessionId == null) return;
        activeSessions.remove(sessionId);
        latest.keySet().removeIf(key -> key.sessionId().equals(sessionId));
        generationBySession.remove(sessionId);
    }

    @Scheduled(fixedDelayString = "${gahyeon.unreal.perception-cleanup-millis:5000}")
    public synchronized void evictExpired() {
        Instant now = clock.instant();
        latest.entrySet().removeIf(entry -> expired(entry.getValue(), now));
        java.util.Set<String> sessionsWithValues = new java.util.HashSet<>();
        latest.keySet().forEach(key -> sessionsWithValues.add(key.sessionId()));
        generationBySession.keySet().removeIf(sessionId -> !sessionsWithValues.contains(sessionId));
    }

    synchronized int size() {
        return latest.size();
    }

    synchronized int sessionCount() {
        return generationBySession.size();
    }

    private boolean expired(UnrealPerceptionEvent event, Instant now) {
        return !now.isBefore(event.receivedAt().plus(ttl));
    }

    private record Key(String sessionId, String type) {}
}
