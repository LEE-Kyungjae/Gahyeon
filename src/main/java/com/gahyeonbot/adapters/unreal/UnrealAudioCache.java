package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.core.speech.AudioOutput;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class UnrealAudioCache {
    static final int DEFAULT_MAX_ENTRIES = 256;
    static final int DEFAULT_MAX_ENTRY_BYTES = 32 * 1024 * 1024;
    static final long DEFAULT_MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    private final Clock clock;
    private final Duration ttl;
    private final int maxEntries;
    private final int maxEntryBytes;
    private final long maxTotalBytes;
    private final UnrealRuntimeMetrics metrics;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private long totalBytes;

    public UnrealAudioCache(Clock clock, Duration ttl) {
        this(clock, ttl, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_ENTRY_BYTES,
                DEFAULT_MAX_TOTAL_BYTES, null);
    }

    UnrealAudioCache(
            Clock clock,
            Duration ttl,
            int maxEntries,
            int maxEntryBytes,
            long maxTotalBytes) {
        this(clock, ttl, maxEntries, maxEntryBytes, maxTotalBytes, null);
    }

    UnrealAudioCache(
            Clock clock,
            Duration ttl,
            int maxEntries,
            int maxEntryBytes,
            long maxTotalBytes,
            UnrealRuntimeMetrics metrics) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("ttl must be positive");
        if (clock == null) throw new IllegalArgumentException("clock is required");
        if (maxEntries <= 0 || maxEntryBytes <= 0 || maxTotalBytes <= 0
                || maxEntryBytes > maxTotalBytes) {
            throw new IllegalArgumentException("audio cache bounds are invalid");
        }
        this.clock = clock;
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.maxEntryBytes = maxEntryBytes;
        this.maxTotalBytes = maxTotalBytes;
        this.metrics = metrics;
        if (metrics != null) metrics.bindAudioCache(this);
    }

    public synchronized String put(AudioOutput output) {
        if (output == null) throw new IllegalArgumentException("audio output is required");
        int size = output.data().length;
        if (size > maxEntryBytes) {
            if (metrics != null) metrics.audioCacheRejected("entry_too_large");
            throw new IllegalArgumentException("audio output exceeds cache entry limit");
        }
        evictExpiredAt(clock.instant());
        String id = UUID.randomUUID().toString();
        entries.put(id, new Entry(output, clock.instant().plus(ttl), size));
        totalBytes += size;
        evictToBounds();
        return id;
    }

    public synchronized Optional<AudioOutput> get(String id) {
        Entry entry = entries.get(id);
        if (entry == null) return Optional.empty();
        if (!clock.instant().isBefore(entry.expiresAt)) {
            remove(id);
            return Optional.empty();
        }
        return Optional.of(entry.output);
    }

    public synchronized void discard(String id) {
        if (id != null) remove(id);
    }

    synchronized int entryCount() {
        return entries.size();
    }

    synchronized long totalBytes() {
        return totalBytes;
    }

    @Scheduled(fixedDelayString = "${gahyeon.unreal.audio-cleanup-millis:30000}")
    public synchronized void evictExpired() {
        evictExpiredAt(clock.instant());
    }

    private void evictExpiredAt(Instant now) {
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (!now.isBefore(entry.expiresAt)) {
                iterator.remove();
                totalBytes -= entry.size;
                if (metrics != null) metrics.audioCacheEvicted("expired");
            }
        }
    }

    private void evictToBounds() {
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while ((entries.size() > maxEntries || totalBytes > maxTotalBytes)
                && iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            iterator.remove();
            totalBytes -= entry.size;
            if (metrics != null) metrics.audioCacheEvicted("capacity");
        }
    }

    private void remove(String id) {
        Entry removed = entries.remove(id);
        if (removed != null) totalBytes -= removed.size;
    }

    private record Entry(AudioOutput output, Instant expiresAt, int size) {}
}
