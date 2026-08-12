package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.core.speech.AudioOutput;
import org.springframework.core.task.AsyncTaskExecutor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Isolates exact alignment from TTS workers and bounds how long audio publication may wait. */
public final class DeadlineUnrealVisemeTimelineProvider implements UnrealVisemeTimelinePort {
    private final UnrealVisemeTimelinePort delegate;
    private final AsyncTaskExecutor executor;
    private final long deadlineMillis;

    public DeadlineUnrealVisemeTimelineProvider(
            UnrealVisemeTimelinePort delegate,
            AsyncTaskExecutor executor,
            Duration deadline) {
        if (delegate == null || executor == null || deadline == null
                || deadline.isNegative() || deadline.isZero() || deadline.toMillis() > 1_000) {
            throw new IllegalArgumentException("valid aligner delegate, executor and deadline are required");
        }
        this.delegate = delegate;
        this.executor = executor;
        this.deadlineMillis = deadline.toMillis();
    }

    @Override
    public List<UnrealVisemeCue> align(String text, AudioOutput audio) {
        Future<List<UnrealVisemeCue>> future;
        try {
            future = executor.submit(() -> delegate.align(text, audio));
        } catch (RuntimeException rejected) {
            throw new IllegalStateException("forced-aligner executor rejected request", rejected);
        }
        try {
            return future.get(deadlineMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new IllegalStateException("forced-aligner playback deadline exceeded", timeout);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("forced-aligner wait interrupted", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("forced-aligner request failed", cause);
        }
    }

    @Override
    public String source() {
        return delegate.source();
    }
}
