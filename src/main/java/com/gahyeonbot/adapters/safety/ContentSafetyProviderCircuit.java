package com.gahyeonbot.adapters.safety;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Independent safety-provider circuit; one half-open probe avoids request-wide retry storms. */
final class ContentSafetyProviderCircuit {
    private static final long CLOSED = 0;
    private static final long HALF_OPEN = Long.MAX_VALUE;
    private static final long MINIMUM_COOLDOWN_MILLIS = 1_000;
    private static final long MAXIMUM_COOLDOWN_MILLIS = 300_000;

    private final long cooldownNanos;
    private final LongSupplier nanoTime;
    private final AtomicLong unavailableUntil = new AtomicLong(CLOSED);

    ContentSafetyProviderCircuit(long cooldownMillis) {
        this(cooldownMillis, System::nanoTime);
    }

    ContentSafetyProviderCircuit(long cooldownMillis, LongSupplier nanoTime) {
        long bounded = Math.max(MINIMUM_COOLDOWN_MILLIS,
                Math.min(MAXIMUM_COOLDOWN_MILLIS, cooldownMillis));
        this.cooldownNanos = TimeUnit.MILLISECONDS.toNanos(bounded);
        this.nanoTime = nanoTime;
    }

    boolean tryAcquire() {
        while (true) {
            long state = unavailableUntil.get();
            if (state == CLOSED) return true;
            if (state == HALF_OPEN || nanoTime.getAsLong() < state) return false;
            if (unavailableUntil.compareAndSet(state, HALF_OPEN)) return true;
        }
    }

    void success() {
        unavailableUntil.set(CLOSED);
    }

    void failure() {
        long now = nanoTime.getAsLong();
        long until = now > Long.MAX_VALUE - cooldownNanos
                ? Long.MAX_VALUE - 1 : now + cooldownNanos;
        while (true) {
            long state = unavailableUntil.get();
            long replacement = state == CLOSED || state == HALF_OPEN
                    ? until : Math.max(state, until);
            if (unavailableUntil.compareAndSet(state, replacement)) return;
        }
    }
}
