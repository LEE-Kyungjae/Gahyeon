package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.application.speech.StreamingExpressiveSpeechSynthesisPort;
import com.gahyeonbot.core.speech.ExpressiveSpeechRequest;
import com.gahyeonbot.core.speech.PcmAudioFormat;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Bounded replayable stream buffer so multiple renderers can consume one synthesis. */
public final class UnrealPcmStreamCache {
    static final int DEFAULT_MAX_ENTRIES = 32;
    static final int DEFAULT_MAX_BYTES = 16 * 1024 * 1024;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final StreamingExpressiveSpeechSynthesisPort synthesis;
    private final Executor executor;
    private final Clock clock;
    private final Duration ttl;
    private final int maxEntries;
    private final int maxBytes;

    public UnrealPcmStreamCache(
            StreamingExpressiveSpeechSynthesisPort synthesis,
            Executor executor,
            Clock clock,
            Duration ttl) {
        this(synthesis, executor, clock, ttl, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_BYTES);
    }

    UnrealPcmStreamCache(
            StreamingExpressiveSpeechSynthesisPort synthesis,
            Executor executor,
            Clock clock,
            Duration ttl,
            int maxEntries,
            int maxBytes) {
        if (synthesis == null || executor == null || clock == null || ttl == null
                || ttl.isZero() || ttl.isNegative() || maxEntries < 1 || maxBytes < 4_800) {
            throw new IllegalArgumentException("invalid PCM stream cache configuration");
        }
        this.synthesis = synthesis;
        this.executor = executor;
        this.clock = clock;
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
    }

    public String start(ExpressiveSpeechRequest request, BooleanSupplier current) {
        return start(request, current, () -> {}, ignored -> {});
    }

    public String start(
            ExpressiveSpeechRequest request,
            BooleanSupplier current,
            Runnable completed,
            Consumer<RuntimeException> failed) {
        cleanup();
        if (entries.size() >= maxEntries) throw new IllegalStateException("PCM stream cache is full");
        String id = UUID.randomUUID().toString();
        Entry entry = new Entry(clock.instant().plus(ttl), current, maxBytes, completed, failed);
        if (entries.putIfAbsent(id, entry) != null) throw new IllegalStateException("PCM stream ID collision");
        try {
            executor.execute(() -> produce(id, entry, request));
        } catch (RuntimeException error) {
            entries.remove(id, entry);
            throw error;
        }
        return id;
    }

    public boolean contains(String id) {
        cleanup();
        Entry entry = valid(id);
        return entry != null && !entry.failed();
    }

    public Optional<PcmAudioFormat> format(String id) {
        Entry entry = valid(id);
        return entry == null ? Optional.empty() : entry.format();
    }

    public void writeTo(String id, OutputStream output) throws IOException {
        Entry entry = valid(id);
        if (entry == null) throw new IOException("PCM stream is unavailable");
        entry.writeTo(output);
    }

    public void discard(String id) {
        Entry removed = id == null ? null : entries.remove(id);
        if (removed != null) removed.cancel();
    }

    int entryCount() {
        cleanup();
        return entries.size();
    }

    private void produce(String id, Entry entry, ExpressiveSpeechRequest request) {
        try {
            synthesis.streamPcm(request, entry::current, entry);
        } catch (RuntimeException error) {
            entry.fail(error);
        }
        if (entry.failed() && entry.readerCount() == 0) entries.remove(id, entry);
    }

    private Entry valid(String id) {
        if (id == null || id.isBlank()) return null;
        Entry entry = entries.get(id);
        if (entry != null && entry.expired(clock.instant())) {
            entries.remove(id, entry);
            entry.cancel();
            return null;
        }
        return entry;
    }

    private void cleanup() {
        Instant now = clock.instant();
        entries.forEach((id, entry) -> {
            if (entry.expired(now) && entries.remove(id, entry)) entry.cancel();
        });
    }

    private static final class Entry implements StreamingExpressiveSpeechSynthesisPort.PcmSink {
        private final Instant expiresAt;
        private final BooleanSupplier generationCurrent;
        private final int maxBytes;
        private final Runnable completionCallback;
        private final Consumer<RuntimeException> failureCallback;
        private byte[] audio = new byte[0];
        private PcmAudioFormat format;
        private RuntimeException failure;
        private boolean completed;
        private boolean cancelled;
        private int readers;

        private Entry(
                Instant expiresAt,
                BooleanSupplier generationCurrent,
                int maxBytes,
                Runnable completionCallback,
                Consumer<RuntimeException> failureCallback) {
            this.expiresAt = expiresAt;
            this.generationCurrent = generationCurrent;
            this.maxBytes = maxBytes;
            this.completionCallback = completionCallback;
            this.failureCallback = failureCallback;
        }

        @Override public synchronized void started(PcmAudioFormat value) {
            if (format != null || value == null || cancelled) throw new IllegalStateException("invalid PCM start");
            format = value;
            notifyAll();
        }

        @Override public synchronized void chunk(byte[] pcm) {
            if (format == null || completed || cancelled || pcm == null || pcm.length == 0
                    || pcm.length % format.bytesPerFrame() != 0
                    || audio.length + pcm.length > maxBytes) {
                throw new IllegalStateException("invalid PCM chunk");
            }
            int offset = audio.length;
            audio = Arrays.copyOf(audio, offset + pcm.length);
            System.arraycopy(pcm, 0, audio, offset, pcm.length);
            notifyAll();
        }

        @Override public synchronized void completed(long pcmBytes) {
            if (completed || cancelled || failure != null || pcmBytes != audio.length || pcmBytes == 0) {
                throw new IllegalStateException("invalid PCM completion");
            }
            completed = true;
            notifyAll();
            completionCallback.run();
        }

        private void writeTo(OutputStream output) throws IOException {
            int offset = 0;
            synchronized (this) { readers++; }
            try {
                while (true) {
                    byte[] next;
                    synchronized (this) {
                        while (offset >= audio.length && !completed && failure == null && current()) {
                            try { wait(500); }
                            catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                throw new IOException("PCM stream interrupted", error);
                            }
                        }
                        if (failure != null) throw new IOException("PCM synthesis failed", failure);
                        if (!current()) throw new IOException("PCM stream cancelled");
                        next = Arrays.copyOfRange(audio, offset, audio.length);
                        offset = audio.length;
                        if (next.length == 0 && completed) return;
                    }
                    output.write(next);
                    output.flush();
                }
            } finally {
                synchronized (this) { readers--; }
            }
        }

        private synchronized void fail(RuntimeException error) {
            failure = error;
            notifyAll();
            failureCallback.accept(error);
        }

        private synchronized void cancel() {
            cancelled = true;
            notifyAll();
        }

        private synchronized boolean current() {
            return !cancelled && generationCurrent != null && generationCurrent.getAsBoolean();
        }

        private synchronized boolean failed() { return failure != null; }
        private synchronized int readerCount() { return readers; }
        private synchronized Optional<PcmAudioFormat> format() { return Optional.ofNullable(format); }
        private boolean expired(Instant now) { return !expiresAt.isAfter(now); }
    }

    public boolean isReady(com.gahyeonbot.core.speech.VoiceProfileId voiceProfile) {
        return synthesis.isStreamingReady(voiceProfile);
    }
}
